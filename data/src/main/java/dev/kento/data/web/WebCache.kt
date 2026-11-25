package dev.kento.data.web

import dev.kento.core.text.tr

import dev.kento.data.db.WebCacheEntry
import dev.kento.data.db.WebDao
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private const val DAY = 86_400_000L
private val cacheJson = Json { ignoreUnknownKeys = true }

class WebCache(private val dao: WebDao? = null, private val trace: ((String) -> Unit)? = null) {
    private val locks = ConcurrentHashMap<String, Array<Mutex>>()

    internal suspend inline fun <reified T> cached(kind: String, key: String, limit: Int, ttl: Long = DAY,
        crossinline load: suspend () -> T): T = entry(kind, key, limit, ttl, {
        cacheJson.decodeFromString<T>(it)
    }, { cacheJson.encodeToString(it) }, { load() })

    internal suspend fun <T> entry(kind: String, rawKey: String, limit: Int, ttl: Long,
        decode: (String) -> T, encode: (T) -> String, load: suspend () -> T): T {
        val store = dao ?: return load()
        val key = hash(rawKey)
        // Prevent identical in-flight lookups from billing twice.
        val stripes = locks.getOrPut(kind) { Array(64) { Mutex() } }
        return stripes[(key.hashCode() and Int.MAX_VALUE) % stripes.size].withLock {
            val saved = store.get(kind, key, System.currentTimeMillis())
            if (saved != null) {
                try {
                    val value = decode(saved)
                    trace?.invoke(tr("Кэш {0}: hit", kind))
                    return@withLock value
                } catch (_: IllegalArgumentException) { /* Refresh stale format. */ }
            }
            val value = load()
            val now = System.currentTimeMillis()
            store.save(WebCacheEntry(kind, key, encode(value), now + ttl), now, limit)
            value
        }
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
