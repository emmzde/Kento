package dev.kento.data.backup

import dev.kento.core.text.tr

import dev.kento.data.db.*
import dev.kento.data.media.LocalMedia
import dev.kento.data.memory.MemorySnapshot
import dev.kento.data.settings.ProfileBook
import dev.kento.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
internal data class BackupChat(val id: String, val title: String, val updatedAt: Long, val pinned: Boolean,
    val messages: List<BackupMessage>)

@Serializable
internal data class BackupMessage(val id: Long, val role: String, val text: String, val thoughts: String, val state: String,
    val model: String, val issue: String, val imageGeneration: Boolean, val createdAt: Long, val sources: String,
    val attachments: List<BackupFile> = emptyList())

@Serializable
internal data class BackupFile(val id: String, val name: String, val mime: String, val bytes: Long, val hash: String,
    val includeOcr: Boolean)

@Serializable
internal data class BackupManifest(val format: Int, val created: Long, val chats: Int, val messages: Int, val files: Int)

/** What a restore added, for the message the user sees. */
data class Restored(val chats: Int, val messages: Int, val files: Int, val skipped: Int)

/**
 * One ZIP with everything a new phone needs: chats with messages, thoughts, sources and attachments, the dossier
 * and the settings. API keys of connections, search engines and the image API never enter the file, so a copy
 * sent to the cloud or a friend leaks no account. Restoring merges: chats already on the device are kept as they
 * are, new ones are added with their files, and settings are taken over without touching the keys already here.
 */
class Backup(private val db: KentoDb, private val media: LocalMedia, private val settings: SettingsStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun save(openOutput: () -> OutputStream) = withContext(Dispatchers.IO) {
        val chats = db.backup().chats()
        val files = mutableListOf<Attachment>()
        val payload = chats.map { chat ->
            val attachments = db.backup().attachments(chat.id).groupBy { it.messageId }
            BackupChat(chat.id, chat.title, chat.updatedAt, chat.pinned, db.backup().messages(chat.id).map { m ->
                val own = attachments[m.id].orEmpty().also { files += it }
                BackupMessage(m.id, m.role, m.text, m.thoughts, m.state, m.model, m.issue, m.imageGeneration, m.createdAt, m.sources,
                    own.map { BackupFile(it.id, it.name, it.mime, it.bytes, it.hash, it.includeOcr) })
            })
        }
        val book = settings.profiles.firstOrNullSafe()?.let(::withoutSecrets)
        ZipOutputStream(openOutput().buffered()).use { zip ->
            fun text(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
            text("manifest.json", json.encodeToString(BackupManifest(1, System.currentTimeMillis(), payload.size,
                payload.sumOf { it.messages.size }, files.size)))
            text("chats.json", json.encodeToString(payload))
            text("memory.json", json.encodeToString(db.memory().snapshot()))
            if (book != null) text("settings.json", json.encodeToString(book))
            for (file in files) {
                val source = media.file(file)
                if (!source.isFile) continue
                zip.putNextEntry(ZipEntry("media/" + file.id))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    suspend fun restore(openInput: () -> InputStream): Restored = withContext(Dispatchers.IO) {
        // Files go to a staging folder first; nothing touches the database until the archive has been read whole.
        val staging = File(media.directory.parentFile, "restore-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            var manifest: BackupManifest? = null
            var chats: List<BackupChat> = emptyList()
            var memory: MemorySnapshot? = null
            var book: ProfileBook? = null
            ZipInputStream(openInput().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == "manifest.json" -> manifest = json.decodeFromString(zip.readBytes().decodeToString())
                        name == "chats.json" -> chats = json.decodeFromString(zip.readBytes().decodeToString())
                        name == "memory.json" -> memory = json.decodeFromString(zip.readBytes().decodeToString())
                        // Copies made before 0.6 hold several connections: the same migration as stored settings applies.
                        name == "settings.json" -> book = runCatching { dev.kento.data.settings.decodeBook(zip.readBytes().decodeToString()) }.getOrNull()
                        name.startsWith("media/") -> {
                            // Only plain UUID names: a crafted "media/../x" must not escape the staging folder.
                            val id = runCatching { UUID.fromString(name.removePrefix("media/")).toString() }.getOrNull() ?: continue
                            File(staging, id).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            require(manifest?.format == 1) { tr("Это не резервная копия Kento") }
            val result = db.backup().restore(chats) { file ->
                val staged = File(staging, file.id)
                val target = File(media.directory, file.id)
                staged.isFile && (target.isFile || staged.renameTo(target) || runCatching { staged.copyTo(target, true) }.isSuccess)
            }
            // Dossier links follow messages whose IDs had to change.
            memory?.let { snapshot ->
                db.memory().restore(snapshot.copy(sources = snapshot.sources.map { it.copy(messageId = result.messageIds[it.messageId] ?: it.messageId) }))
            }
            book?.let { incoming -> settings.updateBook { current -> merge(current, incoming) } }
            media.recover()
            result.restored
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun kotlinx.coroutines.flow.Flow<ProfileBook>.firstOrNullSafe() =
        runCatching { this.first() }.getOrNull()

    internal companion object {
        fun withoutSecrets(book: ProfileBook) = book.copy(
            profiles = book.profiles.map { it.copy(apiKey = "") },
            web = book.web.copy(tavilyKey = "", braveKey = "", serperKey = "", exaKey = ""),
            images = book.images.copy(apiKey = ""), speech = book.speech.copy(apiKey = ""))

        /**
         * A device that is already set up keeps its connection (key, models, prompts); a fresh one takes the copy's
         * prompts, models and sampling, still without a key. The rest of the settings come from the copy, again
         * keeping any key typed on this device.
         */
        fun merge(current: ProfileBook, incoming: ProfileBook): ProfileBook {
            val here = current.active
            val fresh = here.apiKey.isBlank() && here.model.isBlank()
            val chat = if (fresh) incoming.active.copy(apiKey = here.apiKey, catalog = here.catalog, catalogLoaded = here.catalogLoaded,
                catalogVersion = here.catalogVersion, catalogAt = here.catalogAt) else here
            val web = incoming.web.copy(tavilyKey = current.web.tavilyKey, braveKey = current.web.braveKey,
                serperKey = current.web.serperKey, exaKey = current.web.exaKey)
            val images = if (current.images.configured) current.images else incoming.images.copy(apiKey = current.images.apiKey)
            return current.copy(profiles = listOf(chat), activeId = chat.id, web = web, memory = incoming.memory, bubble = incoming.bubble,
                images = images, speech = if (current.speech.configured) current.speech else incoming.speech)
        }
    }
}
