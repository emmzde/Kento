package dev.kento.data.memory

import dev.kento.data.db.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class MemoryArchiveEntry(val identity: String, val kind: String, val text: String, val edited: Boolean, val deleted: Boolean,
    val topic: String = "", val importance: Int = 2, val createdAt: Long = 0,
    /** See [dev.kento.data.db.MemoryEntry.fingerprint]; absent in archives of older versions. */
    val fingerprint: String = "")

@Serializable
data class MemoryArchiveSource(val identity: String, val chatId: String, val messageId: Long)

@Serializable
data class MemorySnapshot(val version: Int, val entries: List<MemoryArchiveEntry>, val sources: List<MemoryArchiveSource>)

@OptIn(ExperimentalSerializationApi::class)
class MemoryArchive(private val memory: MemoryDao) {
    suspend fun save(openOutput: () -> OutputStream) = withContext(Dispatchers.IO) {
        val snapshot = memory.snapshot()
        openOutput().buffered().use { Json.encodeToStream(snapshot, it) }
    }

    suspend fun restore(openInput: () -> InputStream) = withContext(Dispatchers.IO) {
        val snapshot = openInput().buffered().use { Json.decodeFromStream<MemorySnapshot>(it) }
        val keys = snapshot.entries.map { it.identity }.toSet()
        require(snapshot.version == 1 && keys.size == snapshot.entries.size)
        require(snapshot.entries.all { entry ->
            entry.identity.isNotBlank() && entry.kind in setOf("fact", "outdated", "summary") && (entry.deleted || entry.text.isNotBlank())
        })
        require(snapshot.sources.all { it.identity in keys && it.chatId.isNotBlank() && it.messageId > 0 })
        memory.restore(snapshot)
    }
}
