package dev.kento.data.db

import dev.kento.core.text.tr

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val contextRows = "FROM messages WHERE chatId = :chat AND (role = 'user' OR (state IN ('done','stopped') AND text != ''))"

@Dao
abstract class ChatDao {
    @Query("SELECT * FROM chats ORDER BY pinned DESC, updatedAt DESC")
    abstract fun history(): Flow<List<Conversation>>
    @Query("UPDATE chats SET pinned = :pinned WHERE id = :chat")
    abstract suspend fun pin(chat: String, pinned: Boolean)
    @Query("UPDATE chats SET title = :title WHERE id = :chat")
    abstract suspend fun rename(chat: String, title: String)
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chat AND role = 'user'")
    abstract suspend fun questions(chat: String): Int

    @Query("SELECT id FROM chats ORDER BY updatedAt DESC LIMIT 1")
    abstract suspend fun latest(): String?

    @Query("SELECT * FROM messages WHERE chatId = :chat ORDER BY id DESC LIMIT :limit")
    abstract fun recent(chat: String, limit: Int): Flow<List<Message>>

    @Query("SELECT * " + contextRows + " ORDER BY id DESC LIMIT :limit")
    abstract suspend fun context(chat: String, limit: Int = 20): List<Message>
    @Query("SELECT COUNT(*) " + contextRows)
    abstract suspend fun contextSize(chat: String): Int
    /** Every message a request may carry after message [after] (the end of the chat's summary), newest first. */
    @Query("SELECT * " + contextRows + " AND id > :after ORDER BY id DESC")
    abstract suspend fun contextSince(chat: String, after: Long): List<Message>

    @Query("SELECT * FROM chat_summaries WHERE chatId = :chat")
    abstract suspend fun summary(chat: String): ChatSummary?
    /** An upsert, not a replace: a replace deletes the old row without telling its full-text index. */
    @androidx.room.Upsert
    abstract suspend fun saveSummary(summary: ChatSummary)
    /** A summary that covers message [id] no longer matches the chat once that message changes. */
    @Query("DELETE FROM chat_summaries WHERE chatId = :chat AND upToMessageId >= :id")
    protected abstract suspend fun dropSummaryFrom(chat: String, id: Long)
    /** See [Message.promptTokens]. */
    @Query("UPDATE messages SET promptTokens = :tokens, promptEstimate = :estimate WHERE id = :id")
    abstract suspend fun setPrompt(id: Long, tokens: Int, estimate: Int)
    /** The newest reply of the chat whose first request the provider measured. */
    @Query("SELECT promptTokens AS tokens, promptEstimate AS estimate FROM messages WHERE chatId = :chat AND promptTokens > 0 AND promptEstimate > 0 ORDER BY id DESC LIMIT 1")
    abstract suspend fun lastPrompt(chat: String): PromptSize?

    @Insert protected abstract suspend fun addConversation(chat: Conversation)
    @Insert protected abstract suspend fun insert(message: Message): Long
    @Query("UPDATE chats SET updatedAt = :now WHERE id = :chat")
    protected abstract suspend fun touch(chat: String, now: Long)
    @Query("SELECT EXISTS(SELECT 1 FROM chats WHERE id = :chat)")
    protected abstract suspend fun exists(chat: String): Boolean
    @Query("UPDATE messages SET text = :text, thoughts = :thoughts, state = :state, issue = :issue WHERE id = :id")
    abstract suspend fun checkpoint(id: Long, text: String, thoughts: String, state: String, issue: String = "")
    @Query("UPDATE messages SET sources = :sources WHERE id = :id")
    abstract suspend fun setSources(id: Long, sources: String)
    @Query("UPDATE messages SET research = :research WHERE id = :id")
    abstract suspend fun setResearch(id: Long, research: String)
    @Query("SELECT usage FROM messages WHERE id = :id")
    protected abstract suspend fun usageOf(id: Long): String?
    @Query("UPDATE messages SET usage = :usage WHERE id = :id")
    protected abstract suspend fun setUsage(id: Long, usage: String)
    /** Adds what a request cost to the reply that caused it (a retried reply keeps what its earlier tries cost). */
    @Transaction
    open suspend fun addUsage(id: Long, usage: dev.kento.data.net.Usage) {
        if (usage.requests == 0) return
        setUsage(id, (dev.kento.data.net.Usage.decode(usageOf(id).orEmpty()) + usage).encode())
    }
    @Query("SELECT * FROM messages WHERE chatId = :chat ORDER BY id DESC LIMIT 2")
    protected abstract suspend fun latestPair(chat: String): List<Message>
    @Query("SELECT * FROM messages WHERE chatId = :chat AND id < :reply AND role = 'user' ORDER BY id DESC LIMIT 1")
    abstract suspend fun promptFor(chat: String, reply: Long): Message
    @Query("UPDATE messages SET state=:state, issue=:issue WHERE id=:reply AND state='active'")
    abstract suspend fun endActive(reply: Long, state: String, issue: String = "")
    @Query("UPDATE messages SET text='', thoughts='', state='active', issue='', sources='', research='', promptTokens=0, promptEstimate=0, model=:model WHERE id=:reply")
    protected abstract suspend fun restartReply(reply: Long, model: String)
    @Query("UPDATE messages SET state = 'stopped' WHERE state = 'active'")
    abstract suspend fun recoverInterrupted()
    @Query("DELETE FROM chats WHERE id = :id")
    protected abstract suspend fun deleteChat(id: String)
    // Facts the user deleted stay as text-less tombstones, so they are not saved again from another chat.
    @Query("""DELETE FROM memory_entries WHERE edited=0 AND deleted=0 AND EXISTS (
        SELECT 1 FROM memory_sources s JOIN messages m ON m.id=s.messageId WHERE s.entryId=memory_entries.id AND m.chatId=:chat
    ) AND NOT EXISTS (
        SELECT 1 FROM memory_sources s JOIN messages m ON m.id=s.messageId WHERE s.entryId=memory_entries.id AND m.chatId!=:chat
    )""")
    protected abstract suspend fun purgeMemoriesFrom(chat: String)
    @Transaction open suspend fun delete(id: String) { purgeMemoriesFrom(id); deleteChat(id) }
    /** Several chats in one transaction: either all of them go, or none. */
    @Transaction open suspend fun deleteAll(ids: Collection<String>) { ids.forEach { purgeMemoriesFrom(it); deleteChat(it) } }

    @Transaction
    open suspend fun retry(chat: String, reply: Long, model: String): Boolean {
        val rows = latestPair(chat)
        val answer = rows.firstOrNull() ?: return false
        if (answer.id != reply || answer.role != "assistant" || answer.state == "active" || rows.getOrNull(1)?.role != "user") return false
        restartReply(reply, model)
        // The question is always newer than the summary; should a summary cover it anyway, it is built again.
        dropSummaryFrom(chat, rows[1].id)
        touch(chat, System.currentTimeMillis())
        return true
    }

    @Query("UPDATE messages SET text = :text WHERE id = :id AND chatId = :chat AND role = 'user'")
    protected abstract suspend fun rewrite(chat: String, id: Long, text: String): Int
    @Query("DELETE FROM messages WHERE chatId = :chat AND id > :id")
    protected abstract suspend fun dropAfter(chat: String, id: Long)
    @Query("UPDATE memory_cursor SET lastId = MIN(lastId, :id - 1) WHERE chatId = :chat")
    protected abstract suspend fun rewindMemory(chat: String, id: Long)
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE chatId = :chat AND state = 'active')")
    protected abstract suspend fun busy(chat: String): Boolean

    /**
     * Edits one of the user's messages the way ChatGPT does: the new text replaces it, everything after it goes
     * (answers, their attachments and memory links cascade), and an empty reply waits for [retry]. Returns that
     * reply's ID, or null when the chat is answering right now or the message is not the user's.
     */
    @Transaction
    open suspend fun edit(chat: String, id: Long, text: String, model: String): Long? {
        if (busy(chat) || rewrite(chat, id, text) == 0) return null
        dropAfter(chat, id)
        rewindMemory(chat, id)
        // An edit inside the summarized part: the summary describes messages that are gone, so it is built again.
        dropSummaryFrom(chat, id)
        touch(chat, System.currentTimeMillis())
        return insert(Message(chatId = chat, role = "assistant", state = "stopped", model = model))
    }

    /** Messages whose text matches an FTS query, newest first: the chat list searches inside conversations. */
    @Query("""SELECT m.chatId AS chatId, m.text AS text FROM message_search f JOIN messages m ON m.id = f.rowid
        WHERE message_search MATCH :query AND m.text != '' ORDER BY m.id DESC LIMIT 400""")
    abstract suspend fun searchText(query: String): List<ChatHit>

    @Query("UPDATE attachments SET messageId = :message WHERE chatId = :chat AND messageId IS NULL")
    protected abstract suspend fun bindDraft(chat: String, message: Long)
    @Query("UPDATE chats SET title = :title WHERE id = :chat AND NOT EXISTS (SELECT 1 FROM messages WHERE chatId = :chat)")
    protected abstract suspend fun firstTitle(chat: String, title: String)

    @Transaction
    open suspend fun start(chatId: String, input: String, model: String, imageGeneration: Boolean = false): Long {
        val now = System.currentTimeMillis()
        if (!exists(chatId)) {
            addConversation(Conversation(chatId, draftTitle(input), now))
        } else {
            touch(chatId, now)
            firstTitle(chatId, draftTitle(input).ifBlank { tr("Вложения") })
        }
        val user = insert(Message(chatId = chatId, role = "user", text = input))
        bindDraft(chatId, user)
        return insert(Message(chatId = chatId, role = "assistant", state = "active", model = model, imageGeneration = imageGeneration))
    }
}

data class ChatHit(val chatId: String, val text: String)
data class PromptSize(val tokens: Int, val estimate: Int)

/** Placeholder until the model names the chat: the first line, cut at a word boundary. */
internal fun draftTitle(input: String): String {
    val line = input.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    if (line.length <= 48) return line
    val cut = line.take(48)
    val space = cut.lastIndexOf(' ')
    return (if (space >= 24) cut.take(space) else cut).trimEnd(',', ';', ':', '-', ' ') + "…"
}
