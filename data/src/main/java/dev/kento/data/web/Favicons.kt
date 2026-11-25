package dev.kento.data.web

import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Site icons for the search line and the sources sheet. Each is fetched from the site itself (`/favicon.ico`, the
 * site the research has already visited), through the same public-only client as pages, so no third-party icon
 * service learns what was searched. Misses are remembered until restart; the interface then draws a letter.
 */
object Favicons {
    private val icons = ConcurrentHashMap<String, ByteArray>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    suspend fun bytes(host: String): ByteArray? {
        icons[host]?.let { return it }
        if (host in missing || host.isBlank()) return null
        val found = withTimeoutOrNull(6_000) { fetch("https://$host/favicon.ico") }
        if (found == null) missing += host else icons[host] = found
        return found
    }

    private suspend fun fetch(start: String): ByteArray? {
        var url = start.toHttpUrlOrNull() ?: return null
        repeat(3) {
            val body = try { webFetch(publicPages, Request.Builder().url(url).header("Accept", "image/*").build(), 131_072) }
                catch (_: IOException) { return null }
            if (!body.redirect) return body.bytes.takeIf { it.size > 64 && body.type?.type != "text" }
            url = body.location?.let { url.resolve(it) }?.takeIf { it.isHttps } ?: return null
        }
        return null
    }
}
