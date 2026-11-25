package dev.kento.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.kento.data.backup.Restored

/** Reads everything for a backup and writes a restored one back in a single transaction. */
@Dao
abstract class BackupDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    internal abstract suspend fun chats(): List<Conversation>
    @Query("SELECT * FROM messages WHERE chatId = :chat ORDER BY id")
    internal abstract suspend fun messages(chat: String): List<Message>
    @Query("SELECT * FROM attachments WHERE chatId = :chat AND messageId IS NOT NULL ORDER BY rowid")
    internal abstract suspend fun attachments(chat: String): List<Attachment>

    @Query("SELECT EXISTS(SELECT 1 FROM chats WHERE id = :id)") protected abstract suspend fun chatExists(id: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)") protected abstract suspend fun messageExists(id: Long): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM attachments WHERE id = :id)") protected abstract suspend fun fileExists(id: String): Boolean
    @Insert protected abstract suspend fun insert(chat: Conversation)
    @Insert protected abstract suspend fun insert(message: Message): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insert(file: Attachment)

    internal class Result(val restored: Restored, val messageIds: Map<Long, Long>)

    /**
     * Adds the chats that are not on the device yet. Message IDs are kept when free, so dossier links from the
     * same backup still point at the right messages; taken IDs get new ones and [Result.messageIds] maps them.
     * [place] moves an attachment's file into the media folder and says whether it is there.
     */
    @Transaction
    internal open suspend fun restore(chats: List<dev.kento.data.backup.BackupChat>,
        place: (dev.kento.data.backup.BackupFile) -> Boolean): Result {
        val ids = mutableMapOf<Long, Long>()
        var added = 0; var messages = 0; var files = 0; var skipped = 0
        for (chat in chats) {
            if (chat.id.isBlank() || chatExists(chat.id)) { skipped++; continue }
            insert(Conversation(chat.id, chat.title, chat.updatedAt, chat.pinned))
            added++
            for (m in chat.messages) {
                if (m.role !in setOf("user", "assistant")) continue
                val keep = m.id > 0 && !messageExists(m.id)
                val id = insert(Message(id = if (keep) m.id else 0, chatId = chat.id, role = m.role, text = m.text, thoughts = m.thoughts,
                    state = if (m.state == "active") "stopped" else m.state, model = m.model, issue = m.issue,
                    imageGeneration = m.imageGeneration, createdAt = m.createdAt, sources = m.sources))
                ids[m.id] = id
                messages++
                for (f in m.attachments) {
                    if (fileExists(f.id) || !place(f)) continue
                    insert(Attachment(f.id, chat.id, id, f.name, f.mime, f.bytes, f.hash, f.includeOcr))
                    files++
                }
            }
        }
        return Result(Restored(added, messages, files, skipped), ids)
    }
}
