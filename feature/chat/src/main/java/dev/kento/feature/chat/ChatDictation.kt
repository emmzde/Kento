package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow

interface DictationHost {
    val dictation: ChatDictation
}

/** One user-controlled dictation, made up of short, main-thread Android recognition sessions. */
class ChatDictation(private val context: Context) {
    internal val input = MutableStateFlow("")
    internal var issue by mutableStateOf<String?>(null)
    /** Set when offline dictation was asked for before its Whisper model was downloaded. */
    internal var needsModel by mutableStateOf(false)
    var listening by mutableStateOf(false)
        private set
    /**
     * Cloud transcription set up in the speech settings; when present it replaces the phone's recognizer. The app keeps
     * it current from the settings store.
     */
    @Volatile var cloud: dev.kento.data.settings.SpeechEndpoint? = null
    /** Set while the last piece of a cloud dictation is being transcribed after the user pressed stop. */
    internal var finishing by mutableStateOf(false)
    private val speechApi by lazy { dev.kento.data.media.SpeechApi() }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var service: DictationService? = null
    private var failures = 0
    private var fallback: WhisperDictation? = null
    private var useWhisper = false
    private val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (java.util.Locale.getDefault().language == "en") "en-US" else "ru-RU")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf("ru-RU", "en-US"))
        }
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    fun start() {
        if (listening) return
        releasePhrase()
        issue = null
        needsModel = false
        useWhisper = android.os.Build.VERSION.SDK_INT < 34 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        failures = 0
        listening = true
        try {
            context.startForegroundService(Intent(context, DictationService::class.java))
        } catch (_: RuntimeException) {
            fail(tr("Android не разрешил включить микрофон. Открой чат и попробуй снова."))
        }
    }

    internal fun attach(host: DictationService) {
        service = host
        if (listening) listen() else close()
    }

    internal fun detach(host: DictationService) {
        if (service === host) {
            service = null
            close()
        }
    }

    private fun append(words: String) {
        input.value = listOf(input.value.trimEnd(), words.trim()).filter(String::isNotEmpty).joinToString(" ")
    }

    fun stop() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        fallback?.let { if (cloud?.configured == true) finishing = true; it.finish(); return }
        val current = recognizer ?: return close()
        // Give engines without partial results a chance to return the last spoken words.
        handler.postDelayed(::close, 4_000)
        try {
            current.stopListening()
        } catch (_: RuntimeException) {
            close()
        }
    }

    fun close() {
        listening = false
        finishing = false
        releasePhrase()
        service?.stopSelf()
        service = null
    }

    /** Keep the visible text and invalidate any callbacks for it before a manual edit or send. */
    fun newPhrase() = restart(200)

    private fun releasePhrase() {
        handler.removeCallbacksAndMessages(null)
        val old = recognizer
        recognizer = null
        old?.destroy()
        fallback?.close()
        fallback = null
    }

    private fun restart(delayMs: Long) {
        releasePhrase()
        if (!listening) close()
        else if (service != null) handler.postDelayed(::listen, delayMs)
    }

    private fun fail(message: String) {
        close()
        issue = message
    }

    @SuppressLint("MissingPermission") // The composer checks RECORD_AUDIO immediately before starting.
    private fun listen() {
        if (!listening || service == null || recognizer != null || fallback != null) return
        try {
            cloud?.takeIf { it.configured }?.let { endpoint ->
                // Pieces of 10–45 s cut at a pause: the model hears whole thoughts and punctuates them; each piece
                // carries the end of the text before it, so names and style stay consistent across pieces.
                val russian = java.util.Locale.getDefault().language == "ru"
                val engine = WhisperDictation(context, ::append, { error -> finishing = false; if (error == null) close() else fail(error) },
                    longest = 45, shortest = 10.0, pause = .8) { pcm, said ->
                    speechApi.transcribe(endpoint, pcm, prompt = said.takeLast(300).ifBlank {
                        if (russian) "Привет. Вот что я хочу сказать, с правильной пунктуацией." else null
                    })
                }
                fallback = engine
                engine.start()
                return
            }
            if (useWhisper || android.os.Build.VERSION.SDK_INT < 31) {
                if (!dev.kento.data.media.SpeechModel.ready(context)) {
                    close()
                    needsModel = true
                    return
                }
                val engine = WhisperDictation(context, ::append, { error -> if (error == null) close() else fail(error) })
                fallback = engine
                engine.start()
                return
            }
            val current = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = current
            current.setRecognitionListener(Phrase(current, input.value))
            current.startListening(request)
        } catch (_: SecurityException) {
            fail(tr("Android запретил доступ к микрофону. Проверь разрешения Kento."))
        } catch (_: RuntimeException) {
            fail(tr("Не удалось запустить службу распознавания речи Android."))
        }
    }

    private inner class Phrase(private val engine: SpeechRecognizer, private val prefix: String) : RecognitionListener {
        private val current get() = recognizer === engine
        private var lastDraft = prefix

        private fun update(results: Bundle?) {
            if (!current || input.value != lastDraft) return
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (words.isEmpty()) return
            val separator = if (prefix.isEmpty() || prefix.last().isWhitespace() || words.first() in ".,!?:;…") "" else " "
            lastDraft = prefix + separator + words
            input.value = lastDraft
        }

        override fun onPartialResults(partialResults: Bundle?) = update(partialResults)

        override fun onResults(results: Bundle?) {
            if (!current) return
            update(results)
            failures = 0
            restart(150)
        }

        override fun onEndOfSpeech() {
            // Some engines signal the end of audio but never deliver a terminal callback.
            if (current && listening) handler.postDelayed({ if (current) restart(200) }, 8_000)
        }

        override fun onError(error: Int) {
            if (!current) return
            if (!listening) {
                close()
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    failures = 0
                    restart(250)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    fail(tr("Android запретил доступ к микрофону. Проверь разрешения Kento."))
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    { useWhisper = true; restart(100) }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, SpeechRecognizer.ERROR_CLIENT -> {
                    if (failures >= 2) {
                        useWhisper = true
                        restart(100)
                    } else {
                        val delayMs = if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) 30_000L else 500L shl failures
                        failures = (failures + 1).coerceAtMost(5)
                        restart(delayMs)
                    }
                }
                else -> fail(tr("Служба распознавания речи Android вернула ошибку ({0}).", error))
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
