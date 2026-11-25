package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.kento.data.media.OfflineSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Capture and inference run independently so recognition doesn't interrupt microphone recording. The recording is cut
 * at pauses into pieces of [shortest]…[longest] seconds; a pause of [pause] seconds ends a piece once it is long
 * enough. The offline model works best with short phrases; a cloud model with long ones, where it hears the context
 * and punctuates like a person would. [recognize] gets each piece and the text said so far.
 */
internal class WhisperDictation(private val context: Context, val words: (String) -> Unit, val complete: (String?) -> Unit,
    private val longest: Int = 18, private val shortest: Double = .2, private val pause: Double = .6,
    private val recognize: suspend (FloatArray, String) -> String = { pcm, _ -> OfflineSpeech.recognize(context, pcm) }) {
    private val ending = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun start() {
        scope.launch {
            try {
                coroutineScope {
                    val chunks = Channel<FloatArray>(6)
                    launch(Dispatchers.IO) {
                        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
                        try {
                            mic.startRecording()
                            val frame = ShortArray(320)
                            val phrase = FloatArray(16000 * longest)
                            val lead = ArrayDeque<FloatArray>()
                            var length = 0
                            var silence = 0
                            suspend fun flush() {
                                if (length >= 3200 && !chunks.trySend(phrase.copyOf(length)).isSuccess)
                                    error(tr("Распознавание не успевает за диктовкой. Сделайте паузу."))
                                length = 0
                                silence = 0
                            }
                            while (isActive && !ending.get()) {
                                val n = mic.read(frame, 0, frame.size)
                                if (n <= 0) error(tr("Не удалось получить звук с микрофона"))
                                val samples = FloatArray(n) { frame[it] / 32768f }
                                val speech = sqrt(samples.sumOf { (it * it).toDouble() } / n) > .008
                                if (length == 0 && !speech) {
                                    lead.addLast(samples)
                                    if (lead.size > 12) lead.removeFirst()
                                    continue
                                }
                                if (length == 0) while (lead.isNotEmpty()) {
                                    val prior = lead.removeFirst()
                                    prior.copyInto(phrase, length)
                                    length += prior.size
                                }
                                if (length + n > phrase.size) flush()
                                samples.copyInto(phrase, length)
                                length += n
                                silence = if (speech) 0 else silence + n
                                if (silence >= pause * 16000 && (length >= shortest * 16000 || silence >= 32000)) flush()
                            }
                            flush()
                        } finally {
                            runCatching { mic.stop() }
                            mic.release()
                            chunks.close()
                        }
                    }
                    val said = StringBuilder()
                    for (chunk in chunks) {
                        val text = recognize(chunk, said.toString()).trim()
                        if (text.isEmpty()) continue
                        said.append(' ').append(text)
                        words(text)
                    }
                }
                complete(null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { complete(e.message ?: tr("Не удалось распознать речь")) }
        }
    }
    fun finish() { ending.set(true) }
    fun close() { ending.set(true); scope.cancel() }
}
