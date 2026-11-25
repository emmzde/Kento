package dev.kento.data.net

import dev.kento.core.text.tr

import dev.kento.data.settings.ModelOption
import dev.kento.data.settings.REFUSELESS_URL
import dev.kento.data.settings.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An address Kento may call: HTTPS, or plain HTTP to the phone's own network (SearXNG, a local Whisper server). */
fun validBase(url: String): Boolean {
    val parsed = url.trim().toHttpUrlOrNull() ?: return false
    return (parsed.isHttps || localAddress(parsed.host)) && parsed.username.isEmpty() && parsed.password.isEmpty() &&
        parsed.query == null && parsed.fragment == null
}

private fun localAddress(host: String): Boolean {
    if (host == "localhost" || host == "::1") return true
    val octets = host.split('.').map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
    if (octets.size != 4) return false
    return octets[0] in listOf(10, 127) || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31
}

/**
 * Failures worth a quiet second try: the model is busy or warming up, or the gateway is restarting (429, 500, 502,
 * 503, 504, 529), or the connection broke before anything arrived. Timeouts are not repeated: the user has already
 * waited for them. Money (402) and keys (401, 403) are never retried.
 */
internal fun retryable(e: Throwable): Boolean = when (e) {
    is StreamCut -> true
    is ProviderIssue -> e.status in setOf(429, 500, 502, 503, 504, 529)
    is java.net.SocketTimeoutException -> false
    is java.net.UnknownHostException -> false
    is IOException -> true
    else -> false
}

/** Longest pause Kento honours from Retry-After: Refuseless asks 15 s for a busy model and 30 s for a cold one. */
internal const val MAX_RETRY_WAIT_MS = 30_000L

/**
 * Lets the screen say why a reply is late ("the model is starting"), without threading a callback through every
 * caller of [Provider.stream]. The chat installs it around a reply; background work has none.
 */
class ProviderNotice(val show: (String) -> Unit) : AbstractCoroutineContextElement(ProviderNotice) {
    companion object Key : CoroutineContext.Key<ProviderNotice>
}

/**
 * The application's [Provider], so work that Android starts on its own (WorkManager) uses the same client and
 * addresses as the chat. Instrumented tests point those addresses at a mock server.
 */
interface ProviderHost {
    val api: Provider
}

/** Chat Completions and the catalogue of Refuseless, the only text provider. */
class Provider(private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build(),
    /** Pauses before the second and third attempt; tests pass zeros. */
    private val backoff: List<Long> = listOf(1_500L, 4_000L),
    /** The API roots; instrumented tests point them at a local server. */
    private val base: () -> String = { REFUSELESS_URL },
    imageBase: () -> String = { dev.kento.data.settings.OPENUNCENSORED_URL }) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    val images = ImageApi(client, imageBase)
    /** What is left on the keys of both providers (see [BalanceProbe]). */
    val balances = BalanceProbe(client, base, imageBase)

    private fun request(s: Settings, route: String): Request.Builder {
        val url = requireNotNull("${base()}/$route".toHttpUrlOrNull())
        return Request.Builder().url(url).apply { authorize(this, s.apiKey) }
    }

    /** The catalogue; public, and with the key it carries this account's prices and private models. */
    suspend fun models(s: Settings): List<ModelOption> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request(s, "models").build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                try {
                    val items = response.use {
                        checkStatus(it)
                        modelCatalog(it.body!!.string())
                    }
                    cont.resume(items)
                } catch (e: Exception) { cont.resumeWithException(e) }
            }
        })
    }

    /**
     * The chat stream with up to two automatic retries when the provider fails before sending a single event.
     * Once text has arrived nothing is repeated, so the user never sees an answer start over or get billed twice.
     * A busy or warming model is waited for as Refuseless asks, at most [MAX_RETRY_WAIT_MS].
     */
    fun stream(s: Settings, messages: List<Turn>, tools: kotlinx.serialization.json.JsonArray? = null,
        temperature: Double? = null, toolChoice: String? = null, maxTokens: Int? = null, chat: Boolean = false,
        effort: String? = null): Flow<Delta> = flow {
        var attempt = 0
        val meter = meter()
        while (true) {
            var received = false
            var reported: Reported? = null
            var written = 0L
            try {
                emitAll(once(s, messages, tools, temperature, toolChoice, maxTokens, chat, effort).onEach {
                    received = true
                    if (it.usage != null) reported = it.usage
                    written += it.text.length + (it.tool?.arguments?.length ?: 0)
                })
                if (chat) reported?.let { currentCoroutineContext()[PromptProbe]?.offer(it.input) }
                // Every finished request is counted: by the provider's figures, or by Kento's own estimate of the text.
                meter?.add(priced(reported, s.option(), dev.kento.data.memory.estimatedTokens(messages).toLong(), (written + 3) / 4))
                return@flow
            } catch (e: Throwable) {
                if (received && e !is kotlinx.coroutines.CancellationException) meter?.add(priced(reported, s.option(),
                    dev.kento.data.memory.estimatedTokens(messages).toLong(), (written + 3) / 4))
                if (e is kotlinx.coroutines.CancellationException || received || attempt >= backoff.size || !retryable(e)) throw e
                val issue = e as? ProviderIssue
                val wait = issue?.retryAfterMs?.coerceAtMost(MAX_RETRY_WAIT_MS) ?: backoff[attempt]
                if (issue?.code == "model_warming" || issue?.code == "model_busy" || wait > 5_000)
                    currentCoroutineContext()[ProviderNotice]?.show(waitReason(issue?.code))
                dev.kento.data.log.Journal.note("retry", tr("Повтор {0} после: ", attempt + 1) + friendlyError(e))
                kotlinx.coroutines.delay(wait)
                attempt++
            }
        }
    }

    private fun once(s: Settings, messages: List<Turn>, tools: kotlinx.serialization.json.JsonArray?,
        temperature: Double?, toolChoice: String?, maxTokens: Int?, chat: Boolean, effort: String?): Flow<Delta> = callbackFlow {
        val payload = payload(s, messages, tools, temperature, toolChoice, maxTokens, chat, effort)
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        // No compression for the stream: gateways that gzip SSE flush it in blocks, and the reply arrives in lumps.
        val call = client.newCall(request(s, "chat/completions").header("Accept", "text/event-stream")
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache").post(body).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        checkStatus(it)
                        SseReader(json).read(it.body!!.source()) { delta ->
                            if (trySendBlocking(delta).isFailure) throw IOException(tr("Поток закрыт"))
                        }
                    }
                    close()
                } catch (e: Exception) { close(e) }
            }
        })
        awaitClose { call.cancel() }
    }

    private fun checkStatus(response: Response) {
        if (response.isSuccessful) return
        val raw = runCatching { response.peekBody(16_384).string() }.getOrDefault("")
        val code = errorCode(raw)
        val reason = codeReason(code) ?: when (response.code) {
            401 -> tr("Проверь API-ключ")
            403 -> tr("Провайдер отклонил доступ. Проверь права ключа")
            402 -> tr("Недостаточно средств на счёте API")
            404 -> tr("Проверь ID модели")
            429 -> tr("Превышен лимит запросов")
            413 -> tr("Запрос с вложениями превышает лимит провайдера. Разделите сообщение")
            400, 422 -> {
                val detail = raw.lowercase()
                when {
                    "context_length" in detail || "context window" in detail || "maximum context" in detail ->
                        tr("Полный запрос превышает контекст модели. Выберите большее окно или разделите сообщение")
                    "image" in detail -> tr("Провайдер отклонил изображение. В настройках модели можно выбрать локальный OCR")
                    "reasoning" in detail -> tr("Модель не принимает этот уровень рассуждений")
                    else -> tr("Провайдер отклонил запрос")
                }
            }
            else -> tr("Ошибка подключения к провайдеру")
        }
        val after = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
        val detail = runCatching { errorDetail(raw, response.sentKey()) }.getOrNull()
        throw ProviderIssue("$reason (HTTP ${response.code})" + (detail?.let { ": $it" } ?: ""), response.code, after, code)
    }
}

/** The key goes as a Bearer token, the way Refuseless and OpenUncensored read it. */
internal fun authorize(request: Request.Builder, key: String) {
    val token = key.trim().takeIf(String::isNotEmpty) ?: return
    request.header("Authorization", "Bearer $token")
}

fun friendlyError(e: Throwable): String = when (e) {
    is ProviderIssue -> e.message.orEmpty()
    is java.net.SocketTimeoutException -> tr("Провайдер не ответил вовремя")
    is IOException -> tr("Не удалось подключиться. Проверь сеть")
    else -> tr("Не удалось обработать данные. Проверь настройки подключения")
}
