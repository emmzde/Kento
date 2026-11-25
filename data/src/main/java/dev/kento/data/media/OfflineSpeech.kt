package dev.kento.data.media

import dev.kento.core.text.tr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.Keep
import dev.kento.data.net.ProviderIssue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Keep
object OfflineSpeech {
    private val lock = Mutex()
    private const val RATE = 16000
    private const val MAX_SECONDS = 300
    private external fun recognize(path: String, pcm: FloatArray): ByteArray?
    private external fun release()
    /** The x86_64 build uses AVX2, FMA and F16C; ARM builds have no such requirement. */
    private val vectorReady by lazy {
        if (System.getProperty("os.arch") != "x86_64") return@lazy true
        val flags = runCatching { File("/proc/cpuinfo").useLines { l -> l.firstOrNull { it.startsWith("flags") }.orEmpty() } }.getOrDefault("")
            .split(' ', '\t').toSet()
        listOf("avx2", "fma", "f16c").all { it in flags }
    }
    private val idle = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var unload: Job? = null

    /** The native side keeps the model loaded between phrases and frees it after a minute and a half of silence. */
    private suspend fun pass(context: Context, samples: FloatArray): String? = lock.withLock {
        unload?.cancel()
        if (!vectorReady) throw ProviderIssue(tr("Процессор устройства не поддерживает AVX2, нужный локальному распознаванию речи"))
        if (!SpeechModel.ready(context)) throw SpeechModelMissing()
        System.loadLibrary("kento_speech")
        val model = SpeechModel.file(context)
        try { runInterruptible { recognize(model.path, samples) }?.toString(Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty) }
        finally { unload = idle.launch { delay(90_000); lock.withLock { release() } } }
    }

    suspend fun recognize(context: Context, samples: FloatArray): String = withContext(Dispatchers.Default) {
        pass(context, samples) ?: throw ProviderIssue(tr("В записи не удалось распознать речь"))
    }

    /**
     * Whisper picks one language per window, so a Russian opening would swallow later English speech.
     * Cut at pauses and recognize each phrase on its own; very short phrases join a neighbour.
     */
    internal fun phrases(samples: FloatArray): List<FloatArray> {
        val frame = RATE / 50
        val count = samples.size / frame
        if (count < 2) return listOf(samples)
        val rms = FloatArray(count) { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += samples[i] * samples[i]
            kotlin.math.sqrt(sum / frame).toFloat()
        }
        // Three times the quietest tenth of the recording, bounded for silent rooms and for speech without pauses.
        val threshold = (rms.sorted()[count / 10] * 3).coerceIn(0.005f, 0.05f)
        val pause = 20 // 0.4 s
        val regions = mutableListOf<IntRange>()
        var start = -1; var quiet = 0
        for (f in 0 until count) {
            if (rms[f] > threshold) { if (start < 0) start = f; quiet = 0 }
            else if (start >= 0 && ++quiet >= pause) { regions += start..(f - quiet); start = -1; quiet = 0 }
        }
        if (start >= 0) regions += start..(count - 1 - quiet)
        if (regions.isEmpty()) regions += 0 until count
        val merged = mutableListOf<IntRange>()
        for (r in regions) {
            val last = merged.lastOrNull()
            if (last != null && (last.last - last.first < 75 || r.last - r.first < 75) && r.last - last.first < 1400)
                merged[merged.lastIndex] = last.first..r.last
            else merged += r
        }
        return merged.flatMap { r ->
            val from = maxOf(0, (r.first - 10) * frame)
            val to = minOf(samples.size, (r.last + 11) * frame)
            (from until to step RATE * 28).map { samples.copyOfRange(it, minOf(to, it + RATE * 28)) }
        }
    }

    private inline fun <T> audio(file: File, block: (MediaExtractor, MediaFormat) -> T): T {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw ProviderIssue(tr("Файл не содержит поддерживаемой звуковой дорожки"))
            ex.selectTrack(track)
            return block(ex, ex.getTrackFormat(track))
        } catch (e: CancellationException) { throw e }
        catch (e: ProviderIssue) { throw e }
        catch (e: Exception) { throw ProviderIssue(tr("Повреждённый или неподдерживаемый аудиофайл")) }
        finally { ex.release() }
    }
    fun audioType(file: File) = audio(file) { _, f -> f.getString(MediaFormat.KEY_MIME)!! }

    suspend fun transcribe(context: Context, file: File): String = withContext(Dispatchers.IO) {
        val samples = audio(file) { ex, format ->
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > MAX_SECONDS * 1_000_000L)
                throw ProviderIssue(tr("Максимальная длительность аудио — 5 минут"))
            val output = Samples()
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (mime == "audio/raw") {
                val buf = ByteBuffer.allocateDirect(1024 * 1024)
                while (true) {
                    ensureActive()
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    buf.position(0).limit(n)
                    output.add(buf, format)
                    ex.advance()
                }
            } else {
                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val info = MediaCodec.BufferInfo()
                    var ended = false
                    var lastProgress = android.os.SystemClock.elapsedRealtime()
                    while (true) {
                        ensureActive()
                        if (android.os.SystemClock.elapsedRealtime() - lastProgress > 15000)
                            throw ProviderIssue(tr("Декодер аудио не отвечает"))
                        if (!ended) {
                            val index = codec.dequeueInputBuffer(10000)
                            if (index >= 0) {
                                val buf = codec.getInputBuffer(index)!!
                                buf.clear()
                                val n = ex.readSampleData(buf, 0)
                                ended = n < 0
                                codec.queueInputBuffer(index, 0, n.coerceAtLeast(0), ex.sampleTime.coerceAtLeast(0),
                                    if (ended) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                if (!ended) ex.advance()
                                lastProgress = android.os.SystemClock.elapsedRealtime()
                            }
                        }
                        val index = codec.dequeueOutputBuffer(info, 10000)
                        if (index >= 0) {
                            val buf = codec.getOutputBuffer(index)!!
                            buf.position(info.offset).limit(info.offset + info.size)
                            output.add(buf, codec.outputFormat)
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            lastProgress = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                } finally { codec.release() }
            }
            output.finish()
        }
        withContext(Dispatchers.Default) { phrases(samples).mapNotNull { pass(context, it) } }.joinToString(" ")
            .ifEmpty { throw ProviderIssue(tr("В записи не удалось распознать речь")) }
    }

    private class Samples {
        private var array = FloatArray(RATE * 4)
        private var count = 0
        private var phase = 0
        fun add(buffer: ByteBuffer, format: MediaFormat) {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (channels !in 1..32 || rate !in 4000..384000) throw ProviderIssue(tr("Неизвестный формат аудио"))
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                else AudioFormat.ENCODING_PCM_16BIT
            val width = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> throw ProviderIssue(tr("Сохраните аудио как WAV PCM 16 бит или M4A"))
            }
            while (buffer.remaining() >= width * channels) {
                var mono = 0f
                repeat(channels) { mono += if (width == 2) buffer.short / 32768f else buffer.float }
                phase += RATE
                while (phase >= rate) {
                    if (count == RATE * MAX_SECONDS) throw ProviderIssue(tr("Максимальная длительность аудио — 5 минут"))
                    if (count == array.size) array = array.copyOf(minOf(array.size * 2, RATE * MAX_SECONDS))
                    array[count++] = mono / channels
                    phase -= rate
                }
            }
        }
        fun finish(): FloatArray {
            if (count < RATE / 4) throw ProviderIssue(tr("Запись слишком короткая для распознавания"))
            return array.copyOf(count)
        }
    }
}
