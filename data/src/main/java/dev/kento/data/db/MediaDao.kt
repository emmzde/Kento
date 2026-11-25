package dev.kento.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "attachments", foreignKeys = [
    ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Message::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("chatId"), Index("messageId")])
data class Attachment(@PrimaryKey val id: String, val chatId: String, val messageId: Long? = null,
    val name: String, val mime: String, val bytes: Long, val hash: String, @ColumnInfo(defaultValue = "0") val includeOcr: Boolean = false)

@Entity(tableName = "ocr_cache")
data class OcrEntry(@PrimaryKey val hash: String, val text: String, val expires: Long)

@Dao
interface MediaDao {
    @Query("SELECT * FROM attachments WHERE chatId = :chat ORDER BY rowid")
    fun observe(chat: String): Flow<List<Attachment>>
    @Query("SELECT * FROM attachments WHERE messageId = :message ORDER BY rowid")
    suspend fun sent(message: Long): List<Attachment>
    @Query("SELECT DISTINCT chatId FROM attachments WHERE messageId IS NULL ORDER BY rowid DESC LIMIT 1")
    suspend fun latestDraft(): String?
    @Query("SELECT id FROM attachments")
    suspend fun files(): List<String>
    @Insert suspend fun add(rows: List<Attachment>)
    @Query("UPDATE attachments SET id = :next, name = :name, mime = :mime, bytes = :bytes, hash = :hash WHERE id = :id AND messageId IS NULL")
    suspend fun replaceDraft(id: String, next: String, name: String, mime: String, bytes: Long, hash: String): Int
    @Query("DELETE FROM attachments WHERE id = :id AND messageId IS NULL")
    suspend fun remove(id: String): Int
    @Query("UPDATE attachments SET includeOcr = :enabled WHERE chatId = :chat AND messageId IS NULL AND mime LIKE 'image/%'")
    suspend fun setDraftOcr(chat: String, enabled: Boolean)
    @Query("SELECT COUNT(*) FROM attachments WHERE chatId = :chat AND messageId IS NULL")
    suspend fun countDraft(chat: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensureChat(chat: Conversation)
    @Query("SELECT text FROM ocr_cache WHERE hash = :hash AND expires > :now")
    suspend fun cached(hash: String, now: Long): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cache(row: OcrEntry)
    @Query("DELETE FROM ocr_cache WHERE expires <= :now")
    suspend fun expire(now: Long)
}
