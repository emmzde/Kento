package dev.kento.data.db

import androidx.room.*
import dev.kento.data.memory.FactSimilarity
import dev.kento.data.memory.MemoryArchiveEntry
import dev.kento.data.memory.MemoryArchiveSource
import dev.kento.data.memory.MemorySnapshot
import dev.kento.data.memory.Recall
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** A past message found by memory_search, with the date and title of its chat. */
data class RecalledMessage(val id: Long, val chatId: String, val role: String, val text: String, val createdAt: Long, val title: String)

/** A chat summary found by memory_search, with the title of its chat. */
data class RecalledSummary(val id: Long, val chatId: String, val text: String, val createdAt: Long, val title: String)

data class IdTime(val id: Long, val info: Long)

/** One change of the dossier the background reader asked for with save_facts, already checked against its batch. */
data class FactChange(val op: Op, val sources: List<Long>, val text: String = "", val topic: String = "",
    val importance: Int = 2, val target: Long? = null) {
    enum class Op { ADD, UPDATE, OUTDATE }
}

/** Result of one guarded write ([MemoryDao.remember]). */
sealed interface Remembered {
    /** [merged]: the fact restated a saved one, which now holds it, instead of becoming a second note. */
    data class Saved(val id: Long, val updated: Boolean, val merged: Boolean = false) : Remembered
    /** The user deleted this fact earlier, in these or other words: it is not saved again. */
    data object Declined : Remembered
}

private val spaces = Regex("\\s+")
internal fun factKey(text: String): String {
    val normalized = text.trim().lowercase().replace(spaces, " ")
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
    return "fact:" + hash.joinToString("") { "%02x".format(it) }
}

/** One full-text match with its FTS4 `matchinfo(…, 'pcnalx')` blob, ranked in Kotlin (see dev.kento.data.memory.Recall). */
data class FtsMatch(val id: Long, val info: ByteArray)

@Dao
abstract class MemoryDao {
    @Query("SELECT rowid AS id, matchinfo(message_search, 'pcnalx') AS info FROM message_search WHERE message_search MATCH :query")
    abstract suspend fun messageMatches(query: String): List<FtsMatch>
    @Query("SELECT rowid AS id, matchinfo(memory_search, 'pcnalx') AS info FROM memory_search WHERE memory_search MATCH :query")
    abstract suspend fun entryMatches(query: String): List<FtsMatch>
    @Query("SELECT rowid AS id, matchinfo(summary_search, 'pcnalx') AS info FROM summary_search WHERE summary_search MATCH :query")
    abstract suspend fun summaryMatches(query: String): List<FtsMatch>
    /**
     * The messages behind ranked matches of [messageMatches]: finished ones with text, except those a deleted fact
     * was taken from.
     */
    @Query("""SELECT m.id, m.chatId, m.role, m.text, m.createdAt, c.title FROM messages m JOIN chats c ON c.id=m.chatId
        WHERE m.id IN (:ids) AND m.state IN ('done','stopped') AND m.text!=''
        AND NOT EXISTS (SELECT 1 FROM memory_sources s JOIN memory_entries e ON e.id=s.entryId WHERE s.messageId=m.id AND e.deleted)""")
    abstract suspend fun recalled(ids: List<Long>): List<RecalledMessage>
    /** Chat summaries behind ranked matches of [summaryMatches]. */
    @Query("""SELECT s.rowid AS id, s.chatId, s.text, s.createdAt, c.title FROM chat_summaries s JOIN chats c ON c.id=s.chatId
        WHERE s.rowid IN (:ids)""")
    abstract suspend fun summaries(ids: List<Long>): List<RecalledSummary>
    /** Creation time of each message, for the recency part of the ranking. */
    @Query("SELECT id, createdAt AS info FROM messages WHERE id IN (:ids)")
    protected abstract suspend fun messageTimes(ids: List<Long>): List<IdTime>
    /**
     * Entries behind ranked matches of [entryMatches]: facts, and the chat retellings of older
     * versions unless a fact of the same messages was deleted or edited. Outdated facts are left out.
     */
    @Query("""SELECT e.* FROM memory_entries e WHERE e.id IN (:ids) AND e.deleted=0 AND (e.kind='fact' OR e.kind='summary' AND NOT EXISTS
        (SELECT 1 FROM memory_sources own JOIN memory_sources other ON own.messageId=other.messageId JOIN memory_entries fact
        ON fact.id=other.entryId WHERE own.entryId=e.id AND (fact.deleted OR fact.edited)))""")
    abstract suspend fun entries(ids: List<Long>): List<MemoryEntry>
    @Query("SELECT * FROM memory_entries WHERE kind='fact' AND deleted=0 AND topic=:topic AND topic!='' ORDER BY createdAt DESC LIMIT 60")
    protected abstract suspend fun sameTopic(topic: String): List<MemoryEntry>
    @Query("SELECT DISTINCT topic FROM memory_entries WHERE kind='fact' AND deleted=0 AND topic!=''")
    protected abstract suspend fun topics(): List<String>

    suspend fun times(ids: List<Long>): Map<Long, Long> =
        ids.chunked(500).flatMap { messageTimes(it) }.associate { it.id to it.info }

    /** Facts among [ids] (FTS matches) in their order of [Recall.rank] for [terms]. */
    private suspend fun ranked(terms: List<String>, take: Int): List<MemoryEntry> {
        if (terms.isEmpty()) return emptyList()
        val ranks = entryMatches(Recall.match(terms)).map { it.id to Recall.rank(it.info, terms.size, 0.0) }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(take)
        if (ranks.isEmpty()) return emptyList()
        val order = ranks.withIndex().associate { it.value.first to it.index }
        return entries(ranks.map { it.first }).filter { it.kind == "fact" }.sortedBy { order[it.id] }
    }

    /**
     * Facts that may say the same as [text]: notes of the same topic and the best full-text matches. A year of
     * background extraction restates the same facts many times; [remember] merges those instead of stacking copies.
     */
    open suspend fun neighbours(text: String, topic: String): List<MemoryEntry> =
        (sameTopic(topic.trim()) + ranked(Recall.terms(text, limit = 12), 30)).distinctBy { it.id }

    /**
     * Saved facts the background reader compares the new messages [text] with, at most [limit]: the best full-text
     * matches, facts of a topic the text names, then the core of the profile, so "moved to Y" meets "works at X"
     * even without a word in common.
     */
    open suspend fun related(text: String, limit: Int = 15): List<MemoryEntry> {
        val terms = Recall.terms(text, limit = 24)
        val matched = ranked(terms, 30)
        val roots = terms.toSet()
        val named = topics().filter { topic -> Recall.terms(topic).any { it in roots } }.flatMap { sameTopic(it) }
        return (matched.take(limit * 2 / 3) + named + matched + core()).distinctBy { it.id }.take(limit)
    }

    @Query("SELECT * FROM memory_entries WHERE kind='fact' AND deleted=0 ORDER BY edited DESC, id DESC")
    abstract fun facts(): Flow<List<MemoryEntry>>
    @Query("SELECT * FROM memory_entries WHERE kind='fact' AND deleted=0 ORDER BY edited DESC, id DESC LIMIT 80")
    abstract suspend fun dossier(): List<MemoryEntry>
    /** Candidates for the context profile; ranking happens in [dev.kento.data.memory.MemoryProfile]. */
    @Query("SELECT * FROM memory_entries WHERE kind='fact' AND deleted=0 ORDER BY importance DESC, createdAt DESC LIMIT 400")
    abstract suspend fun activeFacts(): List<MemoryEntry>
    /** Candidates for the core of the profile ([dev.kento.data.memory.MemoryProfile.core]): the user's own notes, then importance 3. */
    @Query("""SELECT * FROM memory_entries WHERE kind='fact' AND deleted=0 AND (edited=1 OR importance=3)
        ORDER BY edited DESC, createdAt DESC LIMIT 60""")
    abstract suspend fun core(): List<MemoryEntry>
    @Query("SELECT m.* FROM messages m JOIN memory_sources s ON m.id=s.messageId WHERE s.entryId=:id ORDER BY m.id")
    abstract suspend fun sources(id: Long): List<Message>
    @Query("SELECT lastId FROM memory_cursor WHERE chatId=:chat")
    abstract suspend fun cursor(chat: String): Long?
    @Query("""SELECT * FROM messages WHERE chatId=:chat AND id>:after AND state IN ('done','stopped') AND text!=''
        AND id < COALESCE((SELECT MIN(id) FROM messages WHERE chatId=:chat AND state='active'), 9223372036854775807)
        ORDER BY id LIMIT 16""")
    abstract suspend fun batch(chat: String, after: Long): List<Message>
    @Query("""SELECT DISTINCT m.chatId FROM messages m LEFT JOIN memory_cursor c ON c.chatId=m.chatId
        WHERE m.id>COALESCE(c.lastId,0) AND m.state IN ('done','stopped') AND m.text!='' ORDER BY m.chatId""")
    abstract suspend fun pending(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(entry: MemoryEntry): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun link(links: List<MemorySource>)
    @Upsert protected abstract suspend fun advance(cursor: MemoryCursor)
    @Query("SELECT id FROM memory_entries WHERE identity=:key")
    protected abstract suspend fun existing(key: String): Long
    @Query("SELECT * FROM memory_entries WHERE id=:id")
    abstract suspend fun entry(id: Long): MemoryEntry?
    @Query("SELECT COUNT(*) FROM messages WHERE chatId=:chat AND id IN (:ids)")
    protected abstract suspend fun count(chat: String, ids: List<Long>): Int
    @Query("UPDATE memory_entries SET text=:text, edited=1, createdAt=:now WHERE id=:id AND kind='fact' AND deleted=0")
    abstract suspend fun amend(id: Long, text: String, now: Long = System.currentTimeMillis())
    /** The background reader's new wording of a fact; the user's own notes are never rewritten. */
    @Query("""UPDATE memory_entries SET text=:text, topic=:topic, importance=:importance, createdAt=:now
        WHERE id=:id AND kind='fact' AND deleted=0 AND edited=0""")
    protected abstract suspend fun rewrite(id: Long, text: String, topic: String, importance: Int, now: Long): Int
    /** A fact marked outdated that is told again in the same words is true again. */
    @Query("""UPDATE memory_entries SET kind='fact', text=:text, topic=:topic, importance=:importance, createdAt=:now
        WHERE id=:id AND kind='outdated' AND deleted=0""")
    protected abstract suspend fun revive(id: Long, text: String, topic: String, importance: Int, now: Long): Int
    @Query("UPDATE memory_entries SET kind='outdated', createdAt=:now WHERE id=:id AND kind='fact' AND deleted=0 AND edited=0")
    protected abstract suspend fun markOutdated(id: Long, now: Long): Int
    @Query("SELECT deleted FROM memory_entries WHERE identity=:key")
    protected abstract suspend fun deletedFlag(key: String): Boolean?
    @Query("SELECT fingerprint FROM memory_entries WHERE deleted=1 AND fingerprint!=''")
    protected abstract suspend fun fingerprints(): List<String>
    @Query("UPDATE memory_entries SET topic=:topic, importance=MAX(importance, :importance), createdAt=:now WHERE id=:id")
    protected abstract suspend fun refresh(id: Long, topic: String, importance: Int, now: Long)
    @Query("UPDATE memory_entries SET deleted=1, text='', fingerprint=:fingerprint WHERE id=:id")
    protected abstract suspend fun erase(id: Long, fingerprint: String)
    @Query("SELECT * FROM memory_entries WHERE deleted=0")
    protected abstract suspend fun undeleted(): List<MemoryEntry>

    /** The user deletes a fact: its text goes, its [MemoryEntry.fingerprint] stays, so it is not saved again. */
    @Transaction
    open suspend fun forget(id: Long) {
        val fact = entry(id)?.takeIf { it.kind == "fact" && !it.deleted } ?: return
        erase(id, FactSimilarity.print(fact.text))
    }

    /** Everything goes, as if each fact was deleted one by one. */
    @Transaction
    open suspend fun clear() {
        for (entry in undeleted()) erase(entry.id, if (entry.kind == "summary") "" else FactSimilarity.print(entry.text))
    }

    @Query("SELECT identity, kind, text, edited, deleted, topic, importance, createdAt, fingerprint FROM memory_entries ORDER BY id")
    protected abstract suspend fun archiveEntries(): List<MemoryArchiveEntry>
    @Query("""SELECT e.identity, m.chatId, s.messageId FROM memory_sources s JOIN memory_entries e ON e.id=s.entryId
        JOIN messages m ON m.id=s.messageId ORDER BY s.entryId, s.messageId""")
    protected abstract suspend fun archiveSources(): List<MemoryArchiveSource>
    @Query("""UPDATE memory_entries SET kind=:kind, text=:text, edited=:edited, deleted=:deleted, topic=:topic,
        importance=:importance, createdAt=:createdAt, fingerprint=:fingerprint WHERE identity=:identity""")
    protected abstract suspend fun restoreEntry(identity: String, kind: String, text: String, edited: Boolean, deleted: Boolean,
        topic: String, importance: Int, createdAt: Long, fingerprint: String)
    @Query("""INSERT OR IGNORE INTO memory_sources (entryId, messageId) SELECT :entryId, id FROM messages
        WHERE id=:messageId AND chatId=:chatId""")
    protected abstract suspend fun restoreSource(entryId: Long, chatId: String, messageId: Long)

    @Transaction
    open suspend fun snapshot(): MemorySnapshot = MemorySnapshot(1, archiveEntries(), archiveSources())

    @Transaction
    open suspend fun restore(snapshot: MemorySnapshot) {
        val entryIds = snapshot.entries.associate { entry ->
            val text = if (entry.deleted) "" else entry.text
            val created = entry.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            val inserted = insert(MemoryEntry(kind = entry.kind, text = text, identity = entry.identity, edited = entry.edited,
                deleted = entry.deleted, topic = entry.topic, importance = entry.importance, createdAt = created,
                fingerprint = entry.fingerprint))
            val id = if (inserted != -1L) inserted else {
                restoreEntry(entry.identity, entry.kind, text, entry.edited, entry.deleted, entry.topic, entry.importance, created,
                    entry.fingerprint)
                existing(entry.identity)
            }
            entry.identity to id
        }
        for (source in snapshot.sources) restoreSource(entryIds.getValue(source.identity), source.chatId, source.messageId)
    }

    suspend fun addFact(text: String) { if (text.isNotBlank()) insert(MemoryEntry(kind="fact", text=text.trim(),
        identity="manual:" + UUID.randomUUID(), edited=true, importance=3)) }

    /**
     * Every write of a fact goes through here: one standalone fact, optionally the new wording of fact [replaces],
     * linked to the user's messages [sources].
     * - A fact the user deleted, in the same or other words (word roots alike at [FactSimilarity.SAME]), is declined.
     * - A restatement of another saved fact merges into it, so the dossier holds one line per fact, not a dozen copies.
     * - The user's own notes keep their text; [replaces] pointing at one is a new fact instead.
     */
    @Transaction
    open suspend fun remember(text: String, topic: String, importance: Int, replaces: Long?, sources: List<Long> = emptyList()): Remembered {
        val clean = text.trim()
        val now = System.currentTimeMillis()
        val level = importance.coerceIn(1, 3)
        val key = factKey(clean)
        val stems = FactSimilarity.stems(clean)
        val hashed = FactSimilarity.hashed(stems)
        if (clean.isEmpty() || deletedFlag(key) == true ||
            fingerprints().any { FactSimilarity.jaccard(hashed, it.split(' ').toSet()) >= FactSimilarity.SAME }) return Remembered.Declined
        val target = replaces?.let { entry(it) }?.takeIf { it.kind == "fact" && !it.deleted && !it.edited }
        val twin = neighbours(clean, topic).filter { it.id != target?.id }
            .map { it to FactSimilarity.jaccard(stems, FactSimilarity.stems(it.text)) }.filter { it.second >= FactSimilarity.SAME }
            .maxByOrNull { it.second }?.first
        val id = when {
            twin != null -> {
                // The new wording restates another saved fact: that one holds it, and the replaced one is outdated.
                target?.let { markOutdated(it.id, now) }
                if (rewrite(twin.id, clean, topic.ifBlank { twin.topic }, maxOf(level, twin.importance), now) == 0)
                    refresh(twin.id, twin.topic, level, now)
                twin.id
            }
            target != null -> target.id.also { rewrite(it, clean, topic.ifBlank { target.topic }, level, now) }
            else -> {
                val created = insert(MemoryEntry(kind = "fact", text = clean, identity = key, topic = topic, importance = level, createdAt = now))
                if (created != -1L) created else existing(key).also {
                    if (revive(it, clean, topic, level, now) == 0) refresh(it, topic, level, now)
                }
            }
        }
        link(sources.distinct().map { MemorySource(id, it) })
        return Remembered.Saved(id, updated = target != null || twin != null, merged = twin != null)
    }

    /**
     * The background reader's changes for messages [rows] of [chat], in one transaction with the cursor: a second
     * analysis of the same messages finds the cursor moved and writes nothing. Every change cites messages of [rows]
     * and goes through [remember]'s guard; outdating never touches the user's own notes.
     */
    @Transaction
    open suspend fun commit(chat: String, after: Long, rows: List<Long>, changes: List<FactChange>): Boolean {
        if (rows.isEmpty() || (cursor(chat) ?: 0L) != after || count(chat, rows) != rows.size) return false
        val now = System.currentTimeMillis()
        for (change in changes) {
            val sources = change.sources.distinct()
            if (sources.isEmpty() || !rows.containsAll(sources)) continue
            when (change.op) {
                FactChange.Op.ADD -> remember(change.text, change.topic.take(40), change.importance, null, sources)
                FactChange.Op.UPDATE -> remember(change.text, change.topic.take(40), change.importance, change.target, sources)
                FactChange.Op.OUTDATE -> change.target?.let { id ->
                    if (markOutdated(id, now) == 1) link(sources.map { MemorySource(id, it) })
                }
            }
        }
        advance(MemoryCursor(chat, rows.last()))
        return true
    }
}
