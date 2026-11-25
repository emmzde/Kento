package dev.kento.data.web

import dev.kento.core.text.tr

import dev.kento.data.net.ProviderIssue
import dev.kento.data.net.wireJson
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal val webHttp = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS)
    .followRedirects(false).retryOnConnectionFailure(false)
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", "Kento/0.2 (Android)").build())
    }.build()

internal val publicPages = webHttp.newBuilder().proxy(Proxy.NO_PROXY)
    .dns(object : Dns {
        override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).also { it.forEach(::checkPageAddress) }
    })
    .addInterceptor { chain ->
        val host = chain.request().url.host
        // OkHttp can route numeric hosts without calling its Dns implementation.
        if (':' in host || host.all { it in '0'..'9' || it == '.' }) checkPageAddress(InetAddress.getByName(host))
        chain.proceed(chain.request())
    }.build()

private fun checkPageAddress(ip: InetAddress) {
    val bytes = ip.address
    val first = bytes[0].toInt() and 255
    val privateRange = if (bytes.size == 16) (first and 254) == 252
        else first == 0 || first >= 240 || first == 100 && (bytes[1].toInt() and 192) == 64
    if (privateRange || ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress ||
        ip.isSiteLocalAddress || ip.isMulticastAddress) throw UnknownHostException(tr("Недоступен публичный адрес страницы"))
}

internal data class WebBody(val bytes: ByteArray, val type: MediaType?, val location: String?, val status: Int) {
    val redirect get() = status in 300..399
    fun text() = bytes.toString(type?.charset() ?: Charsets.UTF_8)
}

internal suspend fun webFetch(client: OkHttpClient, request: Request, maxBytes: Long) =
    suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val data = response.use {
                        if (!it.isSuccessful && !it.isRedirect)
                            throw ProviderIssue(tr("{0} вернул HTTP {1}", request.url.host, it.code), it.code)
                        val body = it.body ?: throw ProviderIssue(tr("Сервер вернул пустой ответ"))
                        if (body.contentLength() > maxBytes || body.source().request(maxBytes + 1))
                            throw ProviderIssue(tr("Страница превышает лимит загрузки"))
                        WebBody(body.bytes(), body.contentType(), it.header("Location"), it.code)
                    }
                    cont.resume(data)
                } catch (e: Exception) { cont.resumeWithException(e) }
            }
        })
    }

internal class HostPace {
    private class Slot(val lock: Mutex = Mutex(), var last: Long? = null)
    // Fixed storage. Colliding hosts share an interval, so the per-host limit still holds.
    private val slots = Array(256) { Slot() }

    suspend fun wait(host: String) {
        val slot = slots[(host.hashCode() and Int.MAX_VALUE) % slots.size]
        slot.lock.withLock {
            slot.last?.let { delay(((1_000_000_000L - (System.nanoTime() - it)) / 1_000_000L + 1).coerceAtLeast(0)) }
            slot.last = System.nanoTime()
        }
    }
}

internal fun webObject(text: String) = try {
    wireJson.parseToJsonElement(text) as? JsonObject
} catch (_: IllegalArgumentException) { null }

internal fun cleanUrl(raw: String): HttpUrl? {
    if (raw.length > 4096) return null
    val url = raw.trim().toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() } ?: return null
    return url.newBuilder().fragment(null).apply {
        for (name in url.queryParameterNames) {
            if (name.lowercase().let { it.startsWith("utm_") || it in setOf("fbclid", "gclid", "msclkid", "ref") })
                removeAllQueryParameters(name)
        }
    }.build()
}

internal fun urlIdentity(url: HttpUrl) = url.newBuilder().scheme("https").host(url.host.removePrefix("www.")).build().toString()

internal fun belongsTo(host: String, domain: String) = host == domain || host.endsWith(".$domain")
internal fun compact(text: String, limit: Int) =
    text.trim().replace(Regex("\\s+"), " ").take(limit).trimEnd().trimEnd(Char::isHighSurrogate)
internal fun hasCyrillic(text: String) = text.any { it in '\u0400'..'\u04ff' }
