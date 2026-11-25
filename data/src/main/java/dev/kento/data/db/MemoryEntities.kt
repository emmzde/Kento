package dev.kento.data.db

import androidx.room.*

/**
 * One note of the dossier. [kind] is `fact`; `outdated` for a fact the background reader marked as no longer true
 * (kept, out of every request and search); `summary` for the chat retellings older versions wrote, kept as they are.
 */
@Entity(tableName = "memory_entries", indices = [Index("kind"), Index("identity", unique = true)])
data class MemoryEntry(@PrimaryKey(autoGenerate = true) val id: Long = 0, val kind: String,
    val text: String, val identity: String, val edited: Boolean = false, val deleted: Boolean = false,
    /** Short subject such as "работа" or "стек"; empty for older entries. */
    @ColumnInfo(defaultValue = "") val topic: String = "",
    /** 1 — detail, 2 — useful, 3 — core identity or a standing preference. Decides what reaches the context first. */
    @ColumnInfo(defaultValue = "2") val importance: Int = 2,
    /** Epoch milliseconds of the last write; shown to the model as the fact's date. */
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    /**
     * Of a fact the user deleted: hashes of its word roots ([dev.kento.data.memory.FactSimilarity.print]). The text
     * is wiped, yet the same fact in other words is still recognised and not saved again.
     */
    @ColumnInfo(defaultValue = "") val fingerprint: String = "")

@Entity(tableName = "memory_sources", primaryKeys = ["entryId", "messageId"], foreignKeys = [
    ForeignKey(entity = MemoryEntry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Message::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("messageId")])
data class MemorySource(val entryId: Long, val messageId: Long)

@Entity(tableName = "memory_cursor", foreignKeys = [
    ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)
])
data class MemoryCursor(@PrimaryKey val chatId: String, val lastId: Long)

@Fts4(contentEntity = MemoryEntry::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "memory_search")
data class MemoryIndex(val text: String)

@Fts4(contentEntity = Message::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "message_search")
data class MessageIndex(val text: String)

/** Chat summaries ([ChatSummary]) for memory_search: earlier chats are found by their summary as well. */
@Fts4(contentEntity = ChatSummary::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "summary_search")
data class SummaryIndex(val text: String)
