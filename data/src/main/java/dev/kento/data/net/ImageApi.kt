package dev.kento.data.net

import dev.kento.core.text.tr

import dev.kento.data.settings.ImageEndpoint
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val IMAGE_BYTES = 32L * 1024 * 1024
private const val JSON_BYTES = IMAGE_BYTES * 4 / 3 + 1024 * 1024
data class ImageReference(val file: File, val mime: String)

@Serializable private data class ImageReply(val data: List<ImageItem>? = null, val ou: ImageBill? = null)
@Serializable private data class ImageItem(val b64_json: String? = null, val url: String? = null)
/** OpenUncensored reports what the call cost and how many pictures failed. */
@Serializable private data class ImageBill(val cost_usd: Double? = null, val images_failed: Int? = null)

private fun ResponseBody.limited(limit: Long): InputStream = object : ForwardingSource(source()) {
    private var total = 0L
    override fun read(sink: Buffer, byteCount: Long): Long {
        val count = super.read(sink, byteCount)
        if (count > 0) total += count
        if (total > limit) throw ProviderIssue(tr("Ответ провайдера превышает допустимый размер"))
        return count
    }
}.buffer().inputStream()

/**
 * Pictures through OpenUncensored: `images/models` for the catalogue, `images/generations` for a prompt alone and
 * `images/edits` with the photos as data URLs. Results come back as Base64 (a `url` is a data URL too, nothing is
 * hosted). A billable POST is sent exactly once. Docs: https://openuncensored.com/docs.md
 */
class ImageApi internal constructor(client: OkHttpClient, private val base: () -> String = { dev.kento.data.settings.OPENUNCENSORED_URL }) {
    // Image calls may take up to four minutes on the provider's side.
    private val http = client.newBuilder().readTimeout(5, TimeUnit.MINUTES).callTimeout(6, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false).followRedirects(false).build()

    private fun request(endpoint: ImageEndpoint, route: String): Request.Builder =
        Request.Builder().url((base() + "/" + route).toHttpUrl()).apply {
            endpoint.apiKey.trim().takeIf(String::isNotEmpty)?.let { header("Authorization", "Bearer $it") }
        }

    private suspend fun <T> execute(req: Request, read: (Response) -> T): T = coroutineScope {
        val call = http.newCall(req)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                try { call.execute().use(read) }
                catch (e: java.io.IOException) { currentCoroutineContext().ensureActive(); throw e }
            }
        } finally { cancellation.cancel() }
    }

    private fun Response.checked(): ResponseBody {
        if (isSuccessful) return body ?: throw ProviderIssue(tr("Провайдер вернул пустой ответ"))
        val raw = runCatching { peekBody(16_384).string() }.getOrDefault("")
        val reason = codeReason(errorCode(raw)) ?: when (code) {
            401, 403 -> tr("Проверьте ключ OpenUncensored")
            402 -> tr("Недостаточно средств на счёте API")
            404, 405 -> tr("Модель изображений недоступна")
            400, 413, 422 -> tr("Провайдер отклонил параметры, промпт или формат референса")
            429 -> tr("Достигнут лимит запросов провайдера")
            else -> tr("Провайдер не смог обработать запрос изображения")
        }
        // The provider's own explanation says what exactly is wrong (model, size, balance, format).
        val detail = runCatching { errorDetail(raw, sentKey()) }.getOrNull()
        throw ProviderIssue("$reason (HTTP $code)" + (detail?.let { ": $it" } ?: ""), code)
    }

    /** The catalogue is public; the key is sent when there is one. */
    suspend fun models(endpoint: ImageEndpoint): List<ImageModel> = execute(request(endpoint, "images/models").build()) {
        imageCatalog(it.checked().limited(12L * 1024 * 1024).bufferedReader().readText())
    }

    /**
     * Creates one picture into [target]. Returns what OpenUncensored billed for it, or null when the reply did not
     * say (the caller then uses the catalogue price).
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun create(endpoint: ImageEndpoint, model: ImageModel, prompt: String, refs: List<ImageReference>, target: File): Double? {
        if (prompt.isBlank() || !model.accepts(refs.size)) throw ProviderIssue(tr("Проверьте описание и число референсов для выбранной модели"))
        val body = imageBody(model, prompt, refs)
        // Never replay a billable POST, including after an HTTP follow-up from OkHttp.
        val once = object : RequestBody() {
            override fun isOneShot() = true
            override fun contentType() = body.contentType()
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }
        return execute(request(endpoint, if (refs.isEmpty()) "images/generations" else "images/edits").post(once).build()) { response ->
            val reply = wireJson.decodeFromStream<ImageReply>(response.checked().limited(JSON_BYTES))
            val item = reply.data?.firstOrNull()
            val encoded = item?.b64_json
            val url = item?.url
            when {
                !encoded.isNullOrBlank() -> decode(encoded, 0, target)
                url?.startsWith("data:image/") == true && ";base64," in url.take(100) -> decode(url, url.indexOf(',') + 1, target)
                else -> throw ProviderIssue(tr("Модель не вернула изображение"))
            }
            reply.ou?.cost_usd
        }
    }

    private fun decode(value: String, start: Int, target: File) {
        // Decode in aligned blocks so a large result never needs a second full-size byte array.
        try {
            target.outputStream().buffered().use { out ->
                val block = StringBuilder(8192)
                var size = 0L
                fun flushBlock() {
                    val bytes = Base64.getDecoder().decode(block.toString())
                    size += bytes.size
                    if (size > IMAGE_BYTES) throw ProviderIssue(tr("Изображение больше 32 МиБ"))
                    out.write(bytes)
                    block.setLength(0)
                }
                for (i in start until value.length) {
                    val c = value[i]
                    if (!c.isWhitespace()) block.append(c)
                    if (block.length == 8192) flushBlock()
                }
                if (block.isNotEmpty()) flushBlock()
            }
        } catch (_: IllegalArgumentException) { throw ProviderIssue(tr("Провайдер вернул повреждённый Base64")) }
    }
}

/** `{"model", "n": 1, "prompt", "images": [data URLs]}`, streamed so a large reference is never held as one string. */
internal fun imageBody(model: ImageModel, prompt: String, refs: List<ImageReference>): RequestBody = object : RequestBody() {
    override fun contentType() = "application/json".toMediaType()
    override fun writeTo(sink: BufferedSink) {
        fun reference(ref: ImageReference) {
            sink.writeUtf8("\"data:${ref.mime};base64,")
            val stream = object : FilterOutputStream(sink.outputStream()) {
                override fun close() = flush()
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            }
            Base64.getEncoder().wrap(stream).use { encoded -> ref.file.inputStream().use { it.copyTo(encoded) } }
            sink.writeUtf8("\"")
        }
        sink.writeUtf8("{\"model\":${JsonPrimitive(model.id)},\"n\":1,\"prompt\":${JsonPrimitive(prompt)}")
        if (refs.isNotEmpty()) {
            sink.writeUtf8(",\"images\":[")
            refs.forEachIndexed { i, ref -> if (i > 0) sink.writeUtf8(","); reference(ref) }
            sink.writeUtf8("]")
        }
        sink.writeUtf8("}")
    }
}
