package dev.kento.data.memory

import dev.kento.core.text.tr

import dev.kento.data.db.*
import dev.kento.data.net.*
import dev.kento.data.settings.MemorySettings
import dev.kento.data.settings.Settings
import java.time.ZonedDateTime

/** Bytes give a conservative approximation for byte-level tokenizers, including mixed Cyrillic and code. */
internal fun cost(text: String) = text.toByteArray().size + 8
internal fun fit(text: String, bytes: Int): String {
    if (bytes < 12) return ""
    if (cost(text) <= bytes) return text
    var end = 0
    var used = 11
    while (end < text.length) {
        val cp = text.codePointAt(end)
        val n = when { cp < 128 -> 1; cp < 2048 -> 2; cp < 65536 -> 3; else -> 4 }
        if (used + n > bytes) break
        used += n
        end += Character.charCount(cp)
    }
    return text.take(end) + "…"
}
/**
 * What one chat request may carry, in [cost] units (bytes, a conservative bound on tokens): the model's whole window
 * (a million tokens on Refuseless) less room for the answer, its output cap at this reasoning level. This only keeps a
 * request inside the window; how much history goes is [Settings.historyTokens], counted in tokens.
 */
internal fun inputBudget(s: Settings): Int {
    val window = (s.option()?.contextLength ?: 16384).coerceAtLeast(128)
    return window - minOf(window / 4, s.maxTokens())
}
/**
 * The window of background work (fact extraction): at most 128K however large the model's window grew, so a batch
 * of the memory reader does not grow with it.
 */
internal fun backgroundBudget(s: Settings, cap: Int = 131072): Int {
    val window = (s.option()?.contextLength ?: 16384).coerceIn(128, cap)
    return window - minOf(window / 4, 8192)
}

/**
 * The text of a history message as the model sees it. A searched answer keeps its sources as one short line, so a
 * follow-up ("tell me more about the second one") can open the page again instead of searching from scratch.
 * Links already cited in the answer are not repeated; at most five, titles cut short, to stay cheap.
 */
internal fun historyText(message: Message): String {
    val extra = message.sourceList().filter { it.url !in message.text }.take(5)
    if (message.role != "assistant" || extra.isEmpty()) return message.text
    return message.text + "\n\nSources: " + extra.joinToString("; ") { "[${it.title.take(60).ifBlank { "page" }}](${it.url})" }
}

data class ContextUsage(val estimated: Int?, val measured: Int? = null, val omitted: Int, val shortened: Boolean)
/** [estimate] is Kento's count of the whole request, tool schemas included; see [dev.kento.data.db.Message.promptTokens]. */
data class PreparedContext(val turns: List<Turn>, val usage: ContextUsage, val estimate: Int = 0)

internal fun textTokens(text: String): Int {
    val ascii = text.count { it.code < 128 }
    val remainingBytes = text.toByteArray().size - ascii
    return (ascii + 3) / 4 + (remainingBytes + 2) / 3
}
internal fun estimatedTokens(turns: List<Turn>): Int = 3 + turns.sumOf { 6 + textTokens(it.content) + it.images.size * 512 }

/**
 * Builds one request: the system prompt (the user's blocks and the tool rules, the same in every request), the
 * chat's summary as a second system message once the chat outgrew [Settings.historyTokens], the rest of the chat word
 * for word, then the newest message with its [RuntimeContext] in front: clock, the saved facts chosen for this
 * message (the core of the dossier and the notes that match it), the chat's attachments and the web_search flag.
 * Other chats and the summarized part are reached with memory_search. Without [api] (tests, the bench) nothing is summarized and a long chat is cut to the budget.
 */
class ChatContext(private val chats: ChatDao, private val memory: MemoryDao, api: Provider? = null) {
    private val store = object : SummaryStore {
        override suspend fun summary(chat: String) = chats.summary(chat)
        override suspend fun save(summary: ChatSummary) = chats.saveSummary(summary)
        override suspend fun since(chat: String, after: Long) = chats.contextSince(chat, after)
    }
    private val history = ChatHistory(store, api?.let { ChatSummaries(it)::summarize })
    /** The oldest message each chat's last request carried word for word; memory_search skips that part. */
    private val sentFrom = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** memory_search for this chat. Facts are saved by the background reader ([MemoryAnalysis]), not by the chat model. */
    fun memoryTools(chat: String): List<dev.kento.data.tools.ChatTool> =
        MemoryTools(memory, shown = { sentFrom[chat]?.let { chat to it } }).all()

    /**
     * Facts the profile may choose from: the most important and newest few hundred, plus the best full-text matches
     * of [focus], so an old but relevant note still reaches the prompt after a year of chats.
     */
    private suspend fun candidates(focus: String): List<MemoryEntry> {
        val base = memory.activeFacts()
        val terms = Recall.terms(focus, limit = 16)
        if (terms.isEmpty()) return base
        val ranked = runCatching { memory.entryMatches(Recall.match(terms)) }.getOrDefault(emptyList())
            .map { it.id to Recall.rank(it.info, terms.size, 0.0) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(40).map { it.first }
        val matched = if (ranked.isEmpty()) emptyList() else memory.entries(ranked).filter { it.kind == "fact" }
        return (base + matched).distinctBy { it.id }
    }

    /**
     * Right after a reply: when the chat with that reply outgrew its history budget, the summary is folded now, so
     * the next message does not wait for it. A failure is journaled; the next request then goes without it.
     */
    suspend fun compressAhead(chat: String, s: Settings) {
        history.load(chat, s, TokenScale.of(chats.lastPrompt(chat)), ahead = true)
    }

    /**
     * [tools] are the tool names this request carries and [toolBytes] the size of their schemas, which the
     * provider counts as input too. [web] is the web_search flag (null when no web tool is declared) and [files] the
     * chat's sent attachments the attachment tools may open. [compressing] is called before a summary is folded.
     */
    suspend fun prepare(chat: String, s: Settings, research: Boolean = false, media: dev.kento.data.media.MediaInput? = null,
        previousMedia: Pair<Long, dev.kento.data.media.MediaInput>? = null, memorySettings: MemorySettings = MemorySettings(),
        tools: Set<String> = emptySet(), toolBytes: Int = 0, now: ZonedDateTime = ZonedDateTime.now(),
        web: WebSearch? = null, files: List<Attachment> = emptyList(), compressing: () -> Unit = {}): PreparedContext {
        val scale = TokenScale.of(chats.lastPrompt(chat))
        val loaded = history.load(chat, s, scale, compressing = compressing)
        val recent = loaded.rows
        val newest = recent.first()
        // The core of the dossier goes with every message, then the notes that match this very one. Both travel in
        // its runtime context, so a new topic brings its own notes without touching the cached prefix.
        val facts = if (!memorySettings.enabled) emptyList() else {
            val core = MemoryProfile.core(memory.core())
            core + MemoryProfile.select(candidates(newest.text), newest.text,
                if (research) minOf(3, memorySettings.factsInContext) else memorySettings.factsInContext,
                now.toInstant().toEpochMilli(), taken = core)
        }
        val prepared = assemble(recent, chats.contextSize(chat), facts, s, ContextRequest(research, media, previousMedia,
            memorySettings.enabled, tools, toolBytes, now, web, files, loaded.summary?.text, scale))
        sentFrom[chat] = prepared.from
        return prepared.context
    }
}

/** Everything about one request that is not the stored chat; see [ChatContext.prepare]. */
internal data class ContextRequest(
    val research: Boolean = false,
    val media: dev.kento.data.media.MediaInput? = null,
    val previousMedia: Pair<Long, dev.kento.data.media.MediaInput>? = null,
    val memory: Boolean = true,
    val tools: Set<String> = emptySet(),
    val toolBytes: Int = 0,
    val now: ZonedDateTime = ZonedDateTime.now(),
    val web: WebSearch? = null,
    val files: List<Attachment> = emptyList(),
    /** The chat's summary of everything before the stored rows (see [ChatHistory]); the second system message. */
    val summary: String? = null,
    val scale: TokenScale = TokenScale(),
)

/** A request and the oldest stored message it carries word for word. */
internal data class Assembled(val context: PreparedContext, val from: Long) {
    val turns get() = context.turns
    val usage get() = context.usage
}

/**
 * The request from the stored chat: [recent] newest first, the messages after the summary as [ChatHistory] loaded
 * them, [total] the number of messages the chat could send, [facts] the notes chosen for the newest message. History
 * turns are the stored text (plus the sources line of a searched answer); only the newest message gets the runtime
 * context. History goes whole messages, newest first, while it fits both the window ([inputBudget], bytes) and
 * [Settings.historyTokens] (tokens, [ContextRequest.scale]); below the budget that is the whole chat.
 */
internal fun assemble(recent: List<Message>, total: Int, facts: List<MemoryEntry>, s: Settings, r: ContextRequest): Assembled {
    val newest = recent.first()
    val media = r.media
    var left = inputBudget(s)
    if (r.research) left = left * 2 / 3
    left -= media?.budget ?: 0
    left -= r.toolBytes
    val system = SystemPrompt.build(s, r.tools, runtime = r.memory || r.tools.isNotEmpty())
    val summary = r.summary?.takeIf(String::isNotBlank)?.let { SUMMARY_HEADER + it }
    val clock = r.now.takeIf { s.promptClock }
    var runtime = RuntimeContext.build(clock, facts, r.files, r.web, r.now.zone)
    left -= cost(system) + cost(runtime) + cost(newest.text) + 48 + (summary?.let(::cost) ?: 0)
    if (left < 0 && facts.isNotEmpty()) {
        // A tiny window: drop the notes before refusing the message.
        left += cost(runtime)
        runtime = RuntimeContext.build(clock, emptyList(), r.files, r.web, r.now.zone)
        left -= cost(runtime)
    }
    if (left < 0 && media != null) throw ProviderIssue(tr("Все вложения целиком не помещаются в контекст. Разделите сообщение или выберите модель с большим окном."))
    if (left < 0) throw ProviderIssue(tr("Сообщение и системный промпт превышают бюджет контекста"))
    // The summary counts against the history budget too; what is left goes to the verbatim part.
    var tokens = s.historyTokens - (summary?.let(r.scale::of) ?: 0)
    val history = mutableListOf<Turn>()
    var from = newest.id
    var shortened = false
    for (message in recent.drop(1)) {
        val attachment = r.previousMedia?.takeIf { it.first == message.id }?.second
        val text = historyText(message)
        val size = r.scale.of(text)
        if (attachment != null && cost(text) + attachment.budget <= left && size <= tokens) {
            history += Turn(message.role, listOf(text, attachment.text).filter(String::isNotBlank).joinToString("\n\n"), attachment.images)
            left -= cost(text) + attachment.budget
            tokens -= size
            from = message.id
            continue
        }
        if (cost(text) > left || size > tokens) {
            shortened = true
            break
        }
        history += Turn(message.role, text)
        left -= cost(text)
        tokens -= size
        from = message.id
    }
    val current = Turn(newest.role, listOf(runtime, newest.text, media?.text.orEmpty()).filter(String::isNotBlank)
        .joinToString("\n\n"), media?.images.orEmpty())
    val turns = (history.asReversed() + current).dropWhile { it.role != "user" }
    val messages = buildList<Turn> {
        if (system.isNotBlank()) add(Turn("system", system))
        if (summary != null) add(Turn("system", summary))
        // Strict chat templates expect alternating roles even after an interrupted reply.
        for (turn in turns) {
            if (lastOrNull()?.role == turn.role) {
                val previous = removeAt(lastIndex)
                add(turn.copy(content = previous.content + "\n\n" + turn.content, images = previous.images + turn.images))
            } else add(turn)
        }
    }
    val omitted = (total - turns.size).coerceAtLeast(0)
    val estimate = estimatedTokens(messages) + r.toolBytes
    return Assembled(PreparedContext(messages, ContextUsage(if (r.research) null else estimate, omitted = omitted,
        shortened = shortened), estimate), from)
}
