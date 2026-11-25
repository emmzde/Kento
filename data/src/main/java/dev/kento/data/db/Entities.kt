package dev.kento.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class Conversation(@PrimaryKey val id: String, val title: String, val updatedAt: Long,
    /** Pinned chats stay at the top of every list. */
    @androidx.room.ColumnInfo(defaultValue = "0") val pinned: Boolean = false)

@Entity(tableName = "messages", foreignKeys = [
    ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("chatId")])
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val role: String,
    val text: String = "",
    val thoughts: String = "",
    val state: String = "done",
    val model: String = "",
    val issue: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val imageGeneration: Boolean = false,
    /** Epoch milliseconds; recalled quotes carry their date. Older rows get their chat's last activity. */
    @androidx.room.ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    /** Web pages behind a searched answer, as a JSON list of [Source]; empty for every other message. */
    @androidx.room.ColumnInfo(defaultValue = "") val sources: String = "",
    /** What producing this reply cost, as JSON of [dev.kento.data.net.Usage]; empty for user messages and old rows. */
    @androidx.room.ColumnInfo(defaultValue = "") val usage: String = "",
    /** The web research behind the reply (queries, pages, where it began), as JSON of [dev.kento.data.web.Trail]. */
    @androidx.room.ColumnInfo(defaultValue = "") val research: String = "",
    /**
     * `usage.prompt_tokens` of the first request of this reply (the one that carried the bare history), and Kento's
     * own estimate of that same request. Their ratio turns the estimate of this chat's text into the provider's
     * count (see [dev.kento.data.memory.TokenScale]); 0 when the provider reported nothing.
     */
    @androidx.room.ColumnInfo(defaultValue = "0") val promptTokens: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val promptEstimate: Int = 0
) {
    fun sourceList(): List<Source> = if (sources.isBlank()) emptyList()
        else runCatching { sourceJson.decodeFromString<List<Source>>(sources) }.getOrDefault(emptyList())
}

/** A page an answer was built on: shown under the answer and kept with it in the history sent to the model. */
@kotlinx.serialization.Serializable
data class Source(val title: String, val url: String)

private val sourceJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
fun encodeSources(list: List<Source>): String = if (list.isEmpty()) "" else sourceJson.encodeToString(list)


/**
 * "Earlier in this chat": the background model's summary of every message up to [upToMessageId], sent as the second
 * system message instead of those messages once the chat outgrew its history budget. One per chat; it goes with the
 * chat, and an edit at or before [upToMessageId] drops it so the next request builds it again.
 */
@Entity(tableName = "chat_summaries", foreignKeys = [
    ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)
])
data class ChatSummary(@PrimaryKey val chatId: String, val upToMessageId: Long, val text: String,
    val createdAt: Long = System.currentTimeMillis())
