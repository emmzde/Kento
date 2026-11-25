package dev.kento.feature.chat

import dev.kento.core.text.tr

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kento.data.db.ChatDao
import dev.kento.data.db.Message
import dev.kento.data.net.Provider
import dev.kento.data.net.ImageModel
import dev.kento.data.net.friendlyError
import dev.kento.data.settings.Settings
import dev.kento.data.settings.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.OutputStream
import java.util.UUID

/** The spending sheet: the chat in total and per model. */
data class Spending(val total: dev.kento.data.net.Usage, val byModel: Map<String, dev.kento.data.net.Usage>)
/** What is left on the Refuseless key and, when pictures are set up, on the OpenUncensored key. */
data class Balances(val text: dev.kento.data.net.Balance?, val images: dev.kento.data.net.Balance?, val imagesConfigured: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val db: ChatDao,
    private val store: SettingsStore,
    private val api: Provider,
    private val saved: SavedStateHandle,
    val voice: ChatDictation, val media: dev.kento.data.media.LocalMedia,
    recovery: Deferred<Unit>, context: dev.kento.data.memory.ChatContext, research: dev.kento.data.web.Research, scheduleMemory: () -> Unit,
    /** Names a new chat from its first message; null keeps the first-line title. */
    nameChat: suspend (Settings, String) -> String? = dev.kento.data.memory.ChatTitles(api)::suggest,
    /** The app-wide engine: replies keep running after this screen goes away. Null keeps them in this ViewModel. */
    private val engine: ChatEngine? = null
): ViewModel() {
    private val generation = engine?.generation ?: Generation(db, api, context, research, media, scheduleMemory, viewModelScope, nameChat)
    val live: StateFlow<DraftReply?> = generation.live
    val problem = MutableStateFlow<String?>(null)
    val settings = store.settings.catch { problem.value = tr("Не удалось открыть защищённые настройки") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** The saved settings exist but cannot be decrypted; the chat offers a reset (see [resetSettings]). */
    val settingsBroken = store.unreadable
    fun resetSettings(done: () -> Unit) {
        viewModelScope.launch {
            try { store.reset(); done() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem.value = tr("Не удалось сбросить настройки") }
        }
    }
    val bubble = store.profiles.map { it.bubble }.catch { emit("blue") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "blue")
    /** The OpenUncensored key, catalogue and chosen picture models. */
    val images = store.profiles.map { it.images }.catch { emit(dev.kento.data.settings.ImageEndpoint()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, dev.kento.data.settings.ImageEndpoint())
    val history = db.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeId = saved.getStateFlow("activeId", "")
    val attachments = activeId.flatMapLatest { id -> media.dao.observe(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val importing = MutableStateFlow(false)
    fun changeMedia(action: suspend () -> Unit) {
        if (sending.value || importing.value) return
        importing.value = true
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem.value = (e as? dev.kento.data.net.ProviderIssue)?.message ?: tr("Не удалось прочитать файл. Проверьте формат и доступ.") }
            finally { importing.value = false }
        }
    }
    val search = saved.getStateFlow("webSearch", false)
    val imageMode = saved.getStateFlow("imageMode", false)
    val imageLoading = MutableStateFlow(false)
    val imageCatalogIssue = MutableStateFlow<String?>(null)
    private var imageRefresh: Job? = null

    fun setImageMode(active: Boolean) {
        if (!sending.value) saved["imageMode"] = active
    }

    fun refreshImages() {
        imageRefresh?.cancel()
        imageRefresh = viewModelScope.launch {
            imageLoading.value = true
            imageCatalogIssue.value = null
            try {
                val endpoint = store.profiles.first().images
                val items = api.images.models(endpoint)
                store.updateBook { book -> if (book.images.apiKey == endpoint.apiKey) book.copy(images = book.images.copy(catalog = items)) else book }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { imageCatalogIssue.value = friendlyError(e) }
            finally { imageLoading.value = false }
        }
    }

    fun chooseImage(model: String, editing: Boolean) {
        viewModelScope.launch {
            try { store.updateBook { book -> book.copy(images = if (editing) book.images.copy(editModel = model) else book.images.copy(generationModel = model)) } }
            catch (e: Exception) { problem.value = tr("Не удалось сохранить модель изображений") }
        }
    }
    val progress = generation.progress
    /** Queries and pages of the reply being written. */
    val trail = generation.trail
    /** "Skip" on the search line: stop looking and answer with what was found. */
    fun skipSearch() { generation.skip.value = true }

    /**
     * What the chat has cost: every reply with its tool rounds, query rewrites and title, by model. Deleted messages
     * no longer count.
     */
    suspend fun spending(chat: String): Spending = withContext(Dispatchers.IO) {
        val rows = db.recent(chat, Int.MAX_VALUE).first().filter { it.usage.isNotBlank() }
        val byModel = rows.groupBy { it.model.ifBlank { "—" } }
            .mapValues { (_, list) -> list.fold(dev.kento.data.net.Usage()) { sum, m -> sum + dev.kento.data.net.Usage.decode(m.usage) } }
        Spending(byModel.values.fold(dev.kento.data.net.Usage()) { a, b -> a + b }, byModel)
    }

    /** What is left on both keys; a balance that cannot be read is null. */
    suspend fun balance(): Balances = withContext(Dispatchers.IO) {
        val book = runCatching { store.profiles.first() }.getOrNull() ?: return@withContext Balances(null, null, false)
        coroutineScope {
            val text = async { runCatching { api.balances.text(book.active) }.getOrNull() }
            val pictures = async { runCatching { api.balances.images(book.images) }.getOrNull() }
            Balances(text.await(), pictures.await(), book.images.configured)
        }
    }

    fun toggleSearch() { if (!sending.value) saved["webSearch"] = !search.value }
    val input = voice.input
    private val limit = MutableStateFlow(60)
    val ready = MutableStateFlow(false)
    val sending = engine?.sending ?: MutableStateFlow(false)
    private var ownTask: Job? = null
    private var task: Job?
        get() = engine?.task ?: ownTask
        set(value) { if (engine != null) engine.task = value else ownTask = value }

    data class Page(val id: String, val rows: List<Message>, val hasMore: Boolean)
    val page = combine(activeId, limit) { id, n -> id to n }.flatMapLatest { (id, n) ->
        db.recent(id, n + 1).map { Page(id, it.take(n), it.size > n) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Page("", emptyList(), false))

    init {
        viewModelScope.launch {
            recovery.await()
            // Restore the most recent pending attachment queue on a cold start.
            if (activeId.value.isEmpty()) saved["activeId"] = media.dao.latestDraft() ?: newId()
            ready.value = true
            settings.first { it != null }
            refreshModels(silent = true)
        }
    }

    /** The ID of the user's message being edited in the composer, 0 when none. */
    val editing = saved.getStateFlow("editing", 0L)
    fun startEdit(message: Message) {
        if (sending.value || message.role != "user") return
        saved["editing"] = message.id
        input.value = message.text
    }
    fun cancelEdit() {
        if (editing.value == 0L) return
        saved["editing"] = 0L
        input.value = ""
    }

    /**
     * Finds chats by what was said in them, not only by title: an FTS prefix search over every message.
     * Returns chat ID to a short excerpt around the first match.
     */
    suspend fun findInChats(query: String): Map<String, String> {
        val terms = Regex("[\\p{L}\\p{N}]{2,}").findAll(query.lowercase()).map { it.value.take(40) }.distinct().take(6).toList()
        if (terms.isEmpty()) return emptyMap()
        val rows = runCatching { db.searchText(terms.joinToString(" ") { "$it*" }) }.getOrDefault(emptyList())
        return rows.groupBy { it.chatId }.mapValues { (_, hits) -> snippet(hits.first().text, terms) }
    }

    private fun snippet(text: String, terms: List<String>): String {
        // Markdown marks (table pipes, emphasis, headings, code ticks) only clutter a one-line excerpt.
        val flat = text.replace(Regex("[|*#`>~]+|\\$\\$|-{3,}"), " ").replace(Regex("\\s+"), " ").trim()
        val at = terms.mapNotNull { term -> flat.lowercase().indexOf(term).takeIf { it >= 0 } }.minOrNull() ?: 0
        val start = (at - 30).coerceAtLeast(0).let { s -> flat.lastIndexOf(' ', s).takeIf { it in 0 until at } ?: s }
        val cut = flat.substring(start).take(110).trim()
        return (if (start > 0) "…" else "") + cut + if (start + 110 < flat.length) "…" else ""
    }

    fun select(id: String) {
        if (sending.value || importing.value || id == activeId.value) return
        voice.close()
        input.value = ""
        saved["editing"] = 0L
        limit.value = 60
        saved["imageMode"] = false
        saved["activeId"] = id
    }
    fun newChat() = select(newId())
    fun older() { limit.value += 60 }

    fun delete(id: String) {
        if (sending.value || importing.value) return
        viewModelScope.launch {
            db.delete(id)
            media.recover()
            if (activeId.value == id) select(newId())
        }
    }

    /** Deletes a selection from the chat list at once; the open chat is replaced by a new one if it was among them. */
    fun deleteMany(ids: Set<String>) {
        if (ids.isEmpty() || sending.value || importing.value) return
        viewModelScope.launch {
            try {
                db.deleteAll(ids)
                media.recover()
                if (activeId.value in ids) select(newId())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem.value = tr("Не удалось удалить чаты") }
        }
    }

    /** Pinned chats stay at the top of the drawer and of the full list. */
    fun pin(id: String, pinned: Boolean) {
        viewModelScope.launch {
            try { db.pin(id, pinned) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem.value = tr("Не удалось закрепить чат") }
        }
    }

    fun exportChat(id: String, openOutput: () -> OutputStream) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val messages = db.recent(id, Int.MAX_VALUE).first().asReversed()
                    check(messages.isNotEmpty())
                    val book = store.profiles.first()
                    val web = book.web
                    val keys = (listOf(book.active.apiKey, book.images.apiKey, web.tavilyKey, web.braveKey, web.serperKey, web.exaKey))
                        .map(String::trim).filter(String::isNotEmpty).distinct().sortedByDescending(String::length)
                    openOutput().bufferedWriter(Charsets.UTF_8).use { writer ->
                        messages.filter { it.text.isNotBlank() }.forEach { message ->
                            val role = if (message.role == "user") tr("Пользователь") else "Kento"
                            val text = keys.fold("$role\n${message.text}") { current, key -> current.replace(key, tr("[скрыто]")) }
                            writer.appendLine(text)
                            writer.newLine()
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem.value = tr("Не удалось экспортировать чат") }
        }
    }

    fun send() {
        val s = settings.value ?: return
        val text = input.value.trim()
        if (!ready.value || sending.value || importing.value) return
        val edited = editing.value
        if (edited != 0L && !imageMode.value) {
            if (text.isEmpty() || s.model.isBlank()) return
            sending.value = true
            viewModelScope.launch {
                val reply = try { db.edit(activeId.value, edited, text, s.model) } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { null }
                sending.value = false
                if (reply == null) { problem.value = tr("Не удалось изменить сообщение"); return@launch }
                saved["editing"] = 0L
                voice.close()
                input.value = ""
                launchGeneration(text, s, retry = reply)
            }
            return
        }
        val draft = attachments.value.filter { it.chatId == activeId.value && it.messageId == null }
        val image = if (imageMode.value) {
            if (text.isEmpty()) { problem.value = tr("Опишите изображение или нужные изменения"); return }
            if (draft.any { !it.mime.startsWith("image/") }) { problem.value = tr("Уберите файлы, которые не являются изображениями"); return }
            images.value.option(draft.size) ?: run { problem.value = if (images.value.configured) tr("Нет подходящей модели для этого запроса")
                else tr("Добавьте ключ OpenUncensored в настройках изображений"); return }
        } else null
        if (image == null && ((text.isEmpty() && draft.isEmpty()) || s.model.isBlank())) return
        voice.close()
        input.value = ""
        launchGeneration(text, s, image = image)
    }

    fun regenerate(reply: Long) {
        val s = settings.value ?: return
        val current = page.value
        if (!ready.value || sending.value || current.id != activeId.value) return
        val answer = current.rows.firstOrNull() ?: return
        val prompt = current.rows.getOrNull(1)?.takeIf { it.role == "user" } ?: return
        if (answer.id != reply || answer.role != "assistant" || answer.state == "active") return
        val image = if (answer.imageGeneration) {
            if (answer.state == "done") return
            images.value.catalog.find { it.id == answer.model } ?: run { problem.value = tr("Модель этого изображения недоступна в текущем каталоге"); return }
        } else null
        if (image == null && s.model.isBlank()) return
        launchGeneration(prompt.text, s, retry = reply, image = image)
    }

    private fun launchGeneration(text: String, s: Settings, retry: Long? = null, image: ImageModel? = null) {
        val id = activeId.value
        val useSearch = search.value
        sending.value = true
        engine?.begin()
        task = (engine?.scope ?: viewModelScope).launch {
            try {
                withContext(Dispatchers.IO) {
                    val book = store.profiles.first()
                    if (image != null) generation.image(id, text, book.images, image, retry)
                    else generation.run(id, text, s, book, useSearch, retry)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                problem.value = if (retry == null) tr("Не удалось сохранить переписку") else tr("Не удалось повторить ответ")
                if (retry == null && input.value.isEmpty()) input.value = text
            } finally {
                sending.value = false
                engine?.end(id)
            }
        }
    }
    fun stop() { task?.cancel() }

    private var refresh: Job? = null
    /**
     * Reads the catalogue again when it is missing, saved by an older parser or a day old (Refuseless prices move);
     * [silent] skips the error message, for the refresh at start.
     */
    fun refreshModels(silent: Boolean = false) {
        val s = settings.value ?: return
        if (!s.catalogStale() || refresh?.isActive == true) return
        refresh = viewModelScope.launch {
            try {
                val catalog = api.models(s)
                store.update { current ->
                    if (current.apiKey == s.apiKey) current.copy(catalog = catalog, catalogLoaded = true,
                        catalogVersion = dev.kento.data.settings.CATALOG_VERSION, catalogAt = System.currentTimeMillis())
                    else current
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!silent) problem.value = tr("Каталог моделей недоступен: ") + friendlyError(e) }
        }
    }
    fun choose(model: String) {
        viewModelScope.launch {
            try { store.update { it.select(model) } }
            catch (e: Exception) { problem.value = tr("Не удалось сохранить выбор модели") }
        }
    }
    /** The reasoning level of the selected model; each model remembers its own. */
    fun chooseEffort(level: String) {
        viewModelScope.launch {
            try { store.update { it.withEffort(level) } }
            catch (e: Exception) { problem.value = tr("Не удалось сохранить уровень рассуждений") }
        }
    }
    override fun onCleared() {
        voice.close()
        input.value = ""
    }
    private fun newId(): String = UUID.randomUUID().toString()
}
