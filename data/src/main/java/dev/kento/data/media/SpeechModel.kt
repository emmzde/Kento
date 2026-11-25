package dev.kento.data.media

import dev.kento.core.text.tr

import android.content.Context
import dev.kento.data.net.ProviderIssue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Thrown when offline speech is used before the Whisper model has been downloaded. */
class SpeechModelMissing : ProviderIssue(tr("Для офлайн-распознавания речи нужна модель Whisper ({0} МБ)", SpeechModel.SIZE_MB))

/**
 * The multilingual Whisper small-q5_1 model used to be bundled in the APK (190 of its 234 MB) and was then copied
 * into filesDir, so it occupied the phone twice. It is now fetched once, on demand, resumed after interruptions
 * and verified by SHA-256 before use. A copy installed by an older build is picked up as is.
 */
object SpeechModel {
    const val NAME = "ggml-small-q5_1.bin"
    private const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$NAME"
    private const val SHA256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    private const val BYTES = 190_085_487L
    const val SIZE_MB = BYTES / 1_000_000

    sealed interface State {
        data object Missing : State
        data class Loading(val fraction: Float) : State
        data class Failed(val message: String) : State
        data object Ready : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    fun file(context: Context) = File(context.filesDir, "speech/$NAME")
    private fun part(context: Context) = File(context.filesDir, "speech/$NAME.part")

    fun ready(context: Context): Boolean = file(context).length() == BYTES

    /** Refreshes [state] from disk; call when a screen that shows it opens. */
    fun refresh(context: Context) {
        if (job?.isActive == true) return
        _state.value = if (ready(context)) State.Ready else State.Missing
    }

    /** Starts or resumes the download in the background; repeated calls join the running one. */
    @Synchronized
    fun download(context: Context) {
        val app = context.applicationContext
        if (job?.isActive == true || ready(app)) { refresh(app); return }
        job = scope.launch {
            try {
                fetch(app)
                _state.value = State.Ready
            } catch (e: CancellationException) {
                _state.value = State.Missing
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed((e as? ProviderIssue)?.message ?: tr("Не удалось скачать модель. Проверьте сеть и повторите"))
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun delete(context: Context) {
        cancel()
        file(context).delete(); part(context).delete()
        _state.value = State.Missing
    }

    private suspend fun fetch(context: Context) {
        val dest = file(context).apply { parentFile!!.mkdirs() }
        val part = part(context)
        if (part.length() > BYTES) part.delete()
        var have = part.length()
        _state.value = State.Loading(have.toFloat() / BYTES)
        val request = Request.Builder().url(URL).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ProviderIssue(tr("Сервер модели ответил HTTP {0}", response.code))
            // A server that ignores Range sends the whole file again.
            if (have > 0 && response.code != 206) have = 0
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                val buffer = ByteArray(256 * 1024)
                val input = response.body!!.byteStream()
                var shown = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    have += n
                    val percent = (have * 100 / BYTES).toInt()
                    if (percent != shown) { shown = percent; _state.value = State.Loading(have.toFloat() / BYTES) }
                }
            }
        }
        if (part.length() != BYTES || digest(part) != SHA256) {
            part.delete()
            throw ProviderIssue(tr("Скачанная модель повреждена. Повторите загрузку"))
        }
        if (!part.renameTo(dest)) throw ProviderIssue(tr("Не удалось сохранить модель распознавания"))
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
