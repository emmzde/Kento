package dev.kento.data.memory

import dev.kento.core.text.tr

import dev.kento.data.db.MemoryDao
import dev.kento.data.tools.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * memory_search, the chat model's only memory tool: saved facts, summaries of earlier chats and their messages.
 * Saving is the background reader's work ([MemoryAnalysis]): a memory_write call of the chat model cost one more
 * full round at the chat's reasoning level.
 */
class MemoryTools(private val memory: MemoryDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * The chat and the oldest of its messages the request carries word for word, read when the tool runs: that part
     * is already in front of the model, the summarized part before it is not, so only the latter is quoted. This
     * chat's own summary is in the request too.
     */
    private val shown: () -> Pair<String, Long>? = { null }) {

    companion object {
        /** The declaration never depends on the chat or the user, so the request prefix stays cached. */
        val SEARCH: JsonObject = functionSchema(ToolNames.RECALL,
            "Search saved facts, summaries of the user's earlier chats and their messages, including older parts of this chat. Returns dated excerpts.",
            required = listOf("query")) {
            putJsonObject("query") { put("type", "string"); put("description", "Keywords in the language the user used then") }
        }
        val SCHEMAS: List<JsonObject> get() = listOf(SEARCH)
    }

    fun all(): List<ChatTool> = listOf(search)

    private val search = object : ChatTool {
        override val name = ToolNames.RECALL
        override val schema = SEARCH
        override fun status(args: JsonObject) = tr("Вспоминаю")
        override suspend fun run(args: JsonObject): ToolOutput {
            val query = args.text("query") ?: throw ToolRefusal("query is empty")
            val terms = Recall.terms(query)
            if (terms.isEmpty()) throw ToolRefusal("query has no searchable words")
            val match = Recall.match(terms)
            val now = System.currentTimeMillis()
            val day = 86_400_000.0
            val visible = shown()
            // Facts (and retellings older versions saved): the best BM25 matches first, not the newest ones.
            val entryRanks = memory.entryMatches(match).map { it.id to Recall.rank(it.info, terms.size, 0.0) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(24)
            val entryOrder = entryRanks.withIndex().associate { it.value.first to it.index }
            val notes = (if (entryRanks.isEmpty()) emptyList() else memory.entries(entryRanks.map { it.first }))
                .sortedBy { entryOrder[it.id] }.take(5).map { entry ->
                    "[Date: ${date(entry.createdAt)}] Topic: ${if (entry.kind == "fact") entry.topic.ifBlank { "general" } else "summary of an earlier chat"} " +
                        "Content: ${excerpt(entry.text, terms, 600)}"
                }
            // Summaries of other chats: the one of this chat is already in the request.
            val summaryRanks = memory.summaryMatches(match).map { it.id to Recall.rank(it.info, terms.size, 0.0) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(10)
            val summaryOrder = summaryRanks.withIndex().associate { it.value.first to it.index }
            val summaries = (if (summaryRanks.isEmpty()) emptyList() else memory.summaries(summaryRanks.map { it.first }))
                .filterNot { visible != null && it.chatId == visible.first }
                .sortedBy { summaryOrder[it.id] }.take(2).map { row ->
                    "[Date: ${date(row.createdAt)}] Topic: ${row.title} Content (summary of that chat): ${excerpt(row.text, terms, 1200)}"
                }
            // Earlier messages: every match is scored (a year of chats is a few thousand matches, a few milliseconds),
            // then the best one of each chat is quoted. The user's own words carry their facts, so they weigh a bit more.
            val matches = memory.messageMatches(match)
            val times = memory.times(matches.map { it.id })
            val scored = matches.map { m -> m.id to Recall.rank(m.info, terms.size, (now - (times[m.id] ?: now)).coerceAtLeast(0) / day) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(60)
            val score = scored.toMap()
            val quotes = (if (scored.isEmpty()) emptyList() else memory.recalled(scored.map { it.first }))
                .filterNot { visible != null && it.chatId == visible.first && it.id >= visible.second }
                .sortedByDescending { (score[it.id] ?: 0.0) * (if (it.role == "user") 1.25 else 1.0) }
                .distinctBy { it.chatId }.take(5).map { row ->
                    val who = if (row.role == "user") "user" else "assistant"
                    "[Date: ${date(row.createdAt)}] Topic: ${row.title} Content ($who): ${excerpt(row.text, terms, 800)}"
                }
            val found = notes + summaries + quotes
            return ToolOutput(if (found.isEmpty()) "Nothing found for \"$query\"." else found.joinToString("\n"))
        }
    }

    private fun date(millis: Long) = if (millis <= 0) "unknown" else day.format(Instant.ofEpochMilli(millis).atZone(zone))
}
