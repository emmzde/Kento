package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kento.data.net.*
import dev.kento.data.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SettingsEditor(private val store: SettingsStore, private val api: Provider, val memory: dev.kento.data.db.MemoryDao,
    private val webAccess: dev.kento.data.web.SearchAccess, private val scheduleMemory: () -> Unit,
    private val backup: dev.kento.data.backup.Backup? = null): ViewModel() {
    /** Backup progress for its page: "…" prefix while running, "!" for a failure, plain text for a result. */
    val backupState = MutableStateFlow("")
    fun saveBackup(open: () -> java.io.OutputStream) {
        val work = backup ?: return
        backupState.value = tr("…Сохраняем копию")
        viewModelScope.launch {
            backupState.value = try { work.save(open); tr("Копия сохранена") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                dev.kento.data.log.Journal.note("backup", tr("Копия не сохранена"), e)
                tr("!Не удалось сохранить копию")
            }
        }
    }
    fun restoreBackup(open: () -> java.io.InputStream) {
        val work = backup ?: return
        backupState.value = tr("…Восстанавливаем")
        viewModelScope.launch {
            backupState.value = try {
                val done = work.restore(open)
                // The draft follows the restored settings, so the next "save" does not write the old ones back.
                book.value = store.profiles.first()
                tr("Восстановлено: чатов {0}, сообщений {1}, файлов {2}", done.chats, done.messages, done.files) +
                    if (done.skipped > 0) tr(". Уже были на телефоне: {0}", done.skipped) else ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                dev.kento.data.log.Journal.note("backup", tr("Копия не восстановлена"), e)
                tr("!Файл не похож на копию Kento или повреждён")
            }
        }
    }
    val book = MutableStateFlow<ProfileBook?>(null)
    val message = MutableStateFlow<String?>(null)
    val checking = MutableStateFlow(false)
    val saving = MutableStateFlow(false)
    private var checkJob: Job? = null
    val probe = MutableStateFlow("")
    private var probeJob: Job? = null
    fun testInstance() {
        val web = book.value?.web ?: return
        probeJob?.cancel()
        probe.value = tr("Проверяем JSON…")
        probeJob = viewModelScope.launch {
            try {
                val hits = webAccess.search(web.copy(engine = "SearXNG"), "Android")
                probe.value = tr("JSON доступен · результатов: ") + hits.size
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { probe.value = friendlyError(e) }
        }
    }
    private var load: Job? = null

    fun open() {
        if (book.value != null) return
        load = viewModelScope.launch {
            try { book.value = store.profiles.first() }
            catch (e: Exception) { message.value = tr("Не удалось открыть защищённые настройки") }
        }
    }
    fun close() {
        load?.cancel()
        probeJob?.cancel()
        probe.value = ""
        resetCheck()
        book.value = null
    }
    private fun resetCheck() {
        checkJob?.cancel()
        checking.value = false
        message.value = null
    }
    fun edit(change: (Settings) -> Settings) {
        val b = book.value ?: return
        val old = b.active
        var next = change(old)
        // Another key may see other prices and private models: the catalogue is read again with it.
        if (old.apiKey != next.apiKey) {
            resetCheck()
            next = next.copy(catalogLoaded = false, catalogAt = 0)
        }
        book.value = b.replace(next)
    }
    private fun origin(url: String) = url.trim().toHttpUrlOrNull()?.let { Triple(it.scheme, it.host, it.port) }
    private fun usd(value: Double) = "$" + java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    fun editWeb(change: (WebSettings) -> WebSettings) {
        probeJob?.cancel()
        probe.value = ""
        book.update { it?.copy(web = change(it.web)) }
    }
    fun editBubble(id: String) { book.update { it?.copy(bubble = id) } }

    /** The OpenUncensored key; another key drops the old catalogue. */
    fun editImages(change: (ImageEndpoint) -> ImageEndpoint) {
        val b = book.value ?: return
        val old = b.images
        var next = change(old)
        if (old.apiKey != next.apiKey) {
            imageCheck?.cancel()
            checkingImages.value = false
            next = next.copy(catalog = emptyList())
        }
        book.value = b.copy(images = next)
    }
    val checkingImages = MutableStateFlow(false)
    private var imageCheck: Job? = null
    fun checkImages() {
        val endpoint = book.value?.images ?: return
        if (checkingImages.value) return
        checkingImages.value = true
        imageCheck = viewModelScope.launch {
            try {
                // The catalogue is public; the balance proves the key, so a typo shows here and not at the first picture.
                val models = api.images.models(endpoint)
                val balance = api.balances.images(endpoint)
                val b = book.value ?: return@launch
                if (b.images.apiKey != endpoint.apiKey) return@launch
                book.value = b.copy(images = b.images.copy(catalog = models))
                message.value = if (balance == null) tr("Ключ не подошёл: OpenUncensored не отдал баланс")
                    else tr("Каталог доступен · моделей изображений: {0} · баланс {1}", models.size, usd(balance.amount))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = tr("API изображений: ") + friendlyError(e) }
            finally { checkingImages.value = false }
        }
    }
    /** Cloud dictation. Another server drops the key, as for the image API. */
    fun editSpeech(change: (dev.kento.data.settings.SpeechEndpoint) -> dev.kento.data.settings.SpeechEndpoint) {
        val b = book.value ?: return
        val old = b.speech
        var next = change(old)
        if (origin(old.baseUrl) != origin(next.baseUrl) && old.baseUrl.isNotBlank()) next = next.copy(apiKey = "")
        book.value = b.copy(speech = next)
    }
    val checkingSpeech = MutableStateFlow(false)
    fun checkSpeech() {
        val endpoint = book.value?.speech ?: return
        if (!validBase(endpoint.baseUrl.trim())) { message.value = tr("Введи HTTPS или локальный HTTP-адрес"); return }
        if (checkingSpeech.value) return
        checkingSpeech.value = true
        viewModelScope.launch {
            try {
                // Half a second of quiet: proves address, key and model at the cost of a fraction of a cent.
                dev.kento.data.media.SpeechApi().transcribe(endpoint, FloatArray(8_000))
                message.value = tr("Распознавание речи работает")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = friendlyError(e) }
            finally { checkingSpeech.value = false }
        }
    }
    fun editMemory(change: (MemorySettings) -> MemorySettings) { book.update { it?.copy(memory = change(it.memory)) } }
    /** Reads the catalogue (public) and the balance (needs the key), so a wrong key is seen at once. */
    fun check() {
        val s = book.value?.active ?: return
        if (checking.value) return
        checking.value = true
        checkJob = viewModelScope.launch {
            try {
                val models = api.models(s)
                val balance = if (s.apiKey.isBlank()) null else api.balances.text(s)
                val b = book.value ?: return@launch
                if (b.active.apiKey != s.apiKey) return@launch
                book.value = b.replace(b.active.copy(catalog = models, catalogLoaded = true, catalogVersion = CATALOG_VERSION,
                    catalogAt = System.currentTimeMillis()))
                message.value = tr("Каталог доступен · моделей: ") + models.size + when {
                    s.apiKey.isBlank() -> tr(" · без ключа чат не ответит")
                    balance == null -> tr(" · ключ не подошёл")
                    else -> tr(" · доступно {0}", usd(balance.amount))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = friendlyError(e) }
            finally { checking.value = false }
        }
    }
    fun save(done: () -> Unit) {
        val b = book.value ?: return
        val s = b.active
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            try {
                val clean = b.replace(s.copy(apiKey = s.apiKey.trim())).let { it.copy(images = it.images.copy(apiKey = it.images.apiKey.trim())) }
                store.updateBook { clean }
                if (clean.memory.enabled && clean.memory.background) scheduleMemory()
                done()
            } catch (e: Exception) { message.value = tr("Не удалось сохранить настройки") }
            finally { saving.value = false }
        }
    }
}
