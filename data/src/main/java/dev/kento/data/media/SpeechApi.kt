package dev.kento.data.media

import dev.kento.core.text.tr
import dev.kento.data.net.ProviderIssue
import dev.kento.data.settings.SpeechEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Dictation through an OpenAI-compatible `audio/transcriptions` endpoint (Groq Whisper large-v3-turbo, OpenAI
 * gpt-4o-transcribe, a local whisper server). This is how ChatGPT and Gemini dictate: the whole phrase goes to a
 * large model at once, which hears context and punctuates, instead of a small on-device model guessing word by word.
 * It adds nothing to the APK; audio is sent as 16 kHz mono WAV (32 KB per second).
 */
class SpeechApi(private val client: OkHttpClient = defaultClient) {
    companion object {
        private val defaultClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * Text of [pcm] (16 kHz mono). [prompt] carries the end of what was already said, so a long dictation sent in pieces
     * keeps its spelling and punctuation; [language] is an ISO code or null for detection.
     */
    suspend fun transcribe(endpoint: SpeechEndpoint, pcm: FloatArray, prompt: String? = null, language: String? = null): String =
        withContext(Dispatchers.IO) {
            val base = endpoint.baseUrl.trim().trimEnd('/').removeSuffix("/audio/transcriptions")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", endpoint.model.trim())
                .addFormDataPart("response_format", "json")
                .addFormDataPart("temperature", "0")
                .apply {
                    language?.let { addFormDataPart("language", it) }
                    prompt?.takeIf(String::isNotBlank)?.let { addFormDataPart("prompt", it.takeLast(400)) }
                }
                .addFormDataPart("file", "speech.wav", wav(pcm).toRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder().url("$base/audio/transcriptions").post(body)
                .apply { if (endpoint.apiKey.isNotBlank()) header("Authorization", "Bearer " + endpoint.apiKey.trim()) }
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ProviderIssue(when (response.code) {
                    401, 403 -> tr("Распознавание речи: проверьте ключ API")
                    404 -> tr("Распознавание речи: сервис не поддерживает audio/transcriptions")
                    429 -> tr("Распознавание речи: превышен лимит запросов")
                    else -> tr("Распознавание речи: ошибка HTTP {0}", response.code)
                } + detail(text), response.code)
                // response_format=json gives {"text": …}; some servers ignore it and answer with plain text.
                runCatching { (json.parseToJsonElement(text) as? JsonObject)?.get("text") as? JsonPrimitive }.getOrNull()
                    ?.contentOrNull?.trim() ?: text.trim().takeUnless { it.startsWith("{") }.orEmpty()
            }
        }

    private fun detail(body: String): String {
        val message = runCatching {
            val o = json.parseToJsonElement(body) as? JsonObject
            ((o?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: (o?.get("error") as? JsonPrimitive)?.contentOrNull ?: (o?.get("message") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()?.take(200)
        return if (message.isNullOrBlank()) "" else ": $message"
    }
}

/** 16-bit PCM WAV of 16 kHz mono samples in −1…1. */
fun wav(pcm: FloatArray, rate: Int = 16_000): ByteArray {
    val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    pcm.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size * 2)
    }
    return ByteArrayOutputStream(44 + pcm.size * 2).apply { write(header.array()); write(data.array()) }.toByteArray()
}

/** Known services; anything else OpenAI-compatible works with its own address and model. */
object SpeechPresets {
    data class Preset(val name: String, val baseUrl: String, val model: String, val keyPage: String)
    val all = listOf(
        Preset("Groq", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", "https://console.groq.com/keys"),
        Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini-transcribe", "https://platform.openai.com/api-keys")
    )
}
