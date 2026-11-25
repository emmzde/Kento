package dev.kento.data.memory

import dev.kento.data.db.Attachment
import dev.kento.data.db.MemoryEntry
import dev.kento.data.db.Message
import dev.kento.data.media.MEDIA_TOOLS
import dev.kento.data.net.Provider
import dev.kento.data.net.Turn
import dev.kento.data.net.payload
import dev.kento.data.settings.ModelOption
import dev.kento.data.settings.Settings
import dev.kento.data.settings.WebSettings
import dev.kento.data.tools.ChatTool
import dev.kento.data.tools.ToolLoop
import dev.kento.data.tools.ToolOutput
import dev.kento.data.tools.ToolRefusal
import dev.kento.data.tools.functionSchema
import dev.kento.data.web.ReadPage
import dev.kento.data.web.SearchAccess
import dev.kento.data.web.SearchHit
import dev.kento.data.web.WebTools
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The start of a request does not change from turn to turn or between chats. The system
 * prompt and the tools are the same byte for byte, the history is the stored text, and whatever changes per message
 * (clock, chosen notes, attachments, web_search) lives in the runtime context of the newest message only.
 */
class ContextLayoutTest {
    private val s = Settings(model = "m", systemPrompt = "Persona block.", promptFormat = "Format block.",
        promptValues = "Values block.", catalog = listOf(ModelOption("m", efforts = listOf("off", "low", "high", "max"), contextLength = 1_048_576)))

    private val noWeb = object : SearchAccess {
        override suspend fun search(settings: WebSettings, query: String) = emptyList<SearchHit>()
        override suspend fun read(hit: SearchHit, question: String) = ReadPage("", hit.url, "")
    }
    private fun web(settings: WebSettings = WebSettings(), allowed: Boolean = true, pageBytes: Int = 8_000) =
        WebTools(noWeb, settings, pageBytes, allowed = allowed)

    /** The declarations of a chat with memory on and attachments, in the order Generation puts them. */
    private val schemas = JsonArray(web().all().map { it.schema } + MemoryTools.SCHEMAS + MEDIA_TOOLS.map { it.schema })
    private val names = schemas.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }.toSet()

    private fun fact(id: Long, text: String) = MemoryEntry(id, "fact", text, "f$id", topic = "topic$id", createdAt = 1_795_000_000_000L + id)
    private fun file(id: String) = Attachment(id, "c", 1L, "$id.jpg", "image/jpeg", 1234, "h$id")

    /** One chat, turn by turn, as Generation drives it: stored rows, a request, a stored reply. */
    private class Chat(val id: String) {
        val rows = mutableListOf<Message>()
        fun add(role: String, text: String, thoughts: String = "") {
            rows += Message(id = rows.size + 1L, chatId = id, role = role, text = text, thoughts = thoughts)
        }
    }

    private fun request(chat: Chat, facts: List<MemoryEntry>, now: ZonedDateTime, web: WebSearch, files: List<Attachment>): JsonObject {
        val prepared = assemble(chat.rows.asReversed(), chat.rows.size, facts, s,
            ContextRequest(tools = names, now = now, web = web, files = files))
        return payload(s, prepared.turns, schemas, chat = true)
    }

    private fun JsonObject.messages() = getValue("messages").jsonArray.map { it.jsonObject }

    /** Five turns with a different clock, different notes, a growing attachment list and every web flag. */
    private fun conversation(chat: Chat): List<JsonObject> {
        val start = ZonedDateTime.of(2026, 11, 23, 14, 5, 0, 0, ZoneOffset.ofHours(3))
        val flags = listOf(WebSearch.ENABLED, WebSearch.REQUIRED, WebSearch.DISABLED, WebSearch.ENABLED, WebSearch.REQUIRED)
        return (0 until 5).map { turn ->
            chat.add("user", "Question $turn of ${chat.id}")
            val facts = listOf(fact(turn + 1L, "Fact for turn $turn"), fact(40L + turn, "Another note $turn"))
            val body = request(chat, facts, start.plusMinutes(turn * 7L), flags[turn], (0..turn).map { file("f$it") })
            chat.add("assistant", "Answer $turn", thoughts = "private reasoning $turn")
            body
        }
    }

    @Test fun systemAndToolsAreTheSameByteForByteAcrossTurnsAndChats() {
        val first = conversation(Chat("one"))
        val second = conversation(Chat("two"))
        val systems = (first + second).map { it.messages().first().toString() }.toSet()
        val tools = (first + second).map { it.getValue("tools").toString() }.toSet()
        assertEquals(1, systems.size)
        assertEquals(1, tools.size)
        val system = first.first().messages().first().getValue("content").jsonPrimitive.content
        assertTrue(system.startsWith("# Role and format\nFormat block.\n\n# Persona\nPersona block.\n\n# Values and motivation\nValues block."))
        // No facts, no clock, no attachment ids in the cached part.
        for (word in listOf("Fact for turn", "Another note", "2026-11-23", "f0", "Question")) assertFalse(word, system.contains(word))
        // Web tool settings and the per-message permission do not touch the declarations either.
        val variants = listOf(web(), web(WebSettings(modelDecides = false, paidSearches = 3)), web(allowed = false), web(pageBytes = 2_000))
        assertEquals(1, variants.map { v -> JsonArray(v.all().map { it.schema }).toString() }.toSet().size)
    }

    @Test fun attachmentToolsAreAFixedSetWithoutIds() {
        val media = JsonArray(MEDIA_TOOLS.map { it.schema }).toString()
        assertEquals(listOf("read_media_queue", "image_edit", "ocr", "exif", "pdf_extract", "transcribe_audio"), MEDIA_TOOLS.map { it.name })
        assertFalse(media.contains("\"enum\":[\"f"))
        // uri is a free string checked by the tool, not an enum of the chat's files.
        val uri = MEDIA_TOOLS.first { it.name == "ocr" }.schema.getValue("function").jsonObject.getValue("parameters").jsonObject
            .getValue("properties").jsonObject.getValue("uri").jsonObject
        assertEquals(setOf("type", "description"), uri.keys)
    }

    @Test fun nextRequestStartsWithThePreviousOneWithoutItsLastMessage() {
        val requests = conversation(Chat("one"))
        for ((previous, next) in requests.zipWithNext()) {
            val before = previous.messages()
            val after = next.messages()
            assertEquals(before.dropLast(1), after.take(before.size - 1))
            // The previous question reappears as the user wrote it, without its runtime context.
            assertTrue(after[before.size - 1].getValue("content").jsonPrimitive.content.startsWith("Question"))
        }
    }

    @Test fun runtimeContextIsOnlyInTheNewestMessageAndNeverStored() {
        val chat = Chat("one")
        val requests = conversation(chat)
        val last = requests.last().messages()
        // The system rules name the block; the block itself is in one message only.
        assertEquals(1, last.drop(1).count { "<runtime_context>" in it.getValue("content").jsonPrimitive.content })
        val newest = last.last().getValue("content").jsonPrimitive.content
        assertEquals("user", last.last().getValue("role").jsonPrimitive.content)
        assertTrue(newest.startsWith("<runtime_context>\ntime: 2026-11-23 14:33 Monday UTC+03:00 (2026-11-23 11:33 UTC)\nmemory:\n- [Date: "))
        assertTrue(newest.contains("Topic: topic5 Content: Fact for turn 4"))
        assertTrue(newest.contains("attachments:\n- f0: f0.jpg (image/jpeg, 1234 bytes)\n"))
        assertTrue(newest.contains("web_search: required for this message\n</runtime_context>\n\nQuestion 4 of one"))
        // The stored rows hold the user's words only; history turns repeat them unchanged.
        assertTrue(chat.rows.none { "<runtime_context>" in it.text })
        val history = last.drop(1).dropLast(1).map { it.getValue("content").jsonPrimitive.content }
        assertEquals(chat.rows.dropLast(2).map { it.text }, history)
    }

    @Test fun webSearchFlagFollowsTheSettingAndTheGlobe() {
        assertEquals(WebSearch.REQUIRED, WebSearch.of(globe = true, modelDecides = false))
        assertEquals(WebSearch.REQUIRED, WebSearch.of(globe = true, modelDecides = true))
        assertEquals(WebSearch.ENABLED, WebSearch.of(globe = false, modelDecides = true))
        assertEquals(WebSearch.DISABLED, WebSearch.of(globe = false, modelDecides = false))
        assertEquals("web_search: disabled (do not call search_web or read_webpage)",
            RuntimeContext.build(null, emptyList(), emptyList(), WebSearch.DISABLED).lines()[1])
        // The rule that explains the flag is static: one sentence in the web research rules.
        assertTrue(SystemPrompt.build(s, names).contains("Follow the web_search field of the runtime context"))
        // Declared but disabled tools refuse, in case the model ignores the flag.
        val disabled = web(allowed = false).all()
        runBlocking {
            for (tool in disabled) {
                val args = buildJsonObject { put("query", "kotlin"); put("url", "https://kotlinlang.org") }
                assertThrows(ToolRefusal::class.java) { runBlocking { tool.run(args) } }
            }
        }
        // Without web tools the runtime context carries no flag.
        val prepared = assemble(listOf(Message(1, "c", "user", "hi")), 1, emptyList(), s.copy(promptClock = false), ContextRequest(memory = false))
        assertEquals(listOf(Turn("system", "# Role and format\nFormat block.\n\n# Persona\nPersona block.\n\n# Values and motivation\nValues block."),
            Turn("user", "hi")), prepared.turns)
    }

    @Test fun pastTurnsCarryNoReasoningButTheToolRoundDoes() = runBlocking {
        // History from the database: stored thoughts never travel back.
        val requests = conversation(Chat("one"))
        for (message in requests.last().messages()) assertFalse("reasoning_content" in message)
        // Inside the tool loop of the current turn the thinking that led to the calls goes back with them.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning\":\"need the docs\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\"," +
                    "\"function\":{\"name\":\"memory_search\",\"arguments\":\"{\\\"query\\\":\\\"kento\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n\n"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val tool = object : ChatTool {
                override val name = "memory_search"
                override val schema = functionSchema(name, "Test")
                override fun status(args: JsonObject) = ""
                override suspend fun run(args: JsonObject) = ToolOutput("nothing")
            }
            val history = requests.last().messages().map { Turn(it.getValue("role").jsonPrimitive.content, it.getValue("content").jsonPrimitive.content) }
            ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(s, history, listOf(tool)).toList()
            server.takeRequest(2, TimeUnit.SECONDS)
            val second = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject.messages()
            val withReasoning = second.filter { "reasoning_content" in it }
            assertEquals(1, withReasoning.size)
            assertEquals("need the docs", withReasoning.single().getValue("reasoning_content").jsonPrimitive.content)
            assertEquals(history.size, second.indexOf(withReasoning.single()))
        }
    }

    // The whole chat by tokens, one summary past the budget.

    @org.junit.Before fun freshCompressionState() = ChatHistory.reset()

    /** Russian chat text of about [chars] characters, different for every [seed]. */
    private fun prose(seed: Int, chars: Int): String {
        val words = listOf("модель", "запрос", "кэш", "история", "сводка", "токен", "функция", "Kento", "ответ", "контекст", "решение", "число")
        return buildString {
            var i = seed
            while (length < chars) { append(words[i % words.size]).append(' '); i = (i * 31 + 7) and 0xffff }
        }.trim() + " #$seed"
    }

    /** The stored chat as ChatDao keeps it; [edit] does what ChatDao.edit does to rows and summary. */
    private class Store(val chat: String) : SummaryStore {
        val rows = mutableListOf<Message>()
        var saved: dev.kento.data.db.ChatSummary? = null
        override suspend fun summary(chat: String) = saved
        override suspend fun save(summary: dev.kento.data.db.ChatSummary) { saved = summary }
        override suspend fun since(chat: String, after: Long) = rows.filter { it.id > after }.asReversed()
        fun add(role: String, text: String, sources: String = "", research: String = "", thoughts: String = "") {
            rows += Message(id = (rows.lastOrNull()?.id ?: 0) + 1, chatId = chat, role = role, text = text, sources = sources,
                research = research, thoughts = thoughts)
        }
        fun edit(id: Long, text: String) {
            val at = rows.indexOfFirst { it.id == id }
            rows[at] = rows[at].copy(text = text)
            while (rows.size > at + 1) rows.removeAt(rows.lastIndex)
            if ((saved?.upToMessageId ?: -1) >= id) saved = null
        }
    }

    /** A background model that records what it was given. */
    private class Folds {
        val calls = mutableListOf<Pair<String?, List<Message>>>()
        var fail: Exception? = null
        val summarize: suspend (Settings, String?, List<Message>) -> String = { _, previous, rows ->
            fail?.let { throw it }
            calls += previous to rows
            "Summary ${calls.size} up to #${rows.last().id}"
        }
    }

    private val noon = ZonedDateTime.of(2026, 11, 24, 12, 0, 0, 0, ZoneOffset.UTC)

    /** One turn as Generation drives it: the request, the stored reply, then the fold ahead of the next message. */
    private fun turn(store: Store, history: ChatHistory, settings: Settings, question: String, answer: String,
        inline: () -> Unit = {}, ahead: Boolean = true, sources: String = "", research: String = ""): JsonObject = runBlocking {
        store.add("user", question)
        val loaded = history.load(store.chat, settings, TokenScale(), compressing = inline)
        val prepared = assemble(loaded.rows, store.rows.size, emptyList(), settings,
            ContextRequest(tools = names, now = noon, web = WebSearch.ENABLED, summary = loaded.summary?.text))
        store.add("assistant", answer, thoughts = "private reasoning", sources = sources, research = research)
        if (ahead) history.load(store.chat, settings, TokenScale(), ahead = true)
        payload(settings, prepared.turns, schemas, chat = true)
    }

    private fun JsonObject.texts() = messages().map { it.getValue("content").jsonPrimitive.content }
    /** History tokens of a request as the budget counts them: the summary message and every turn but the newest. */
    private fun JsonObject.historyTokens(): Int = messages().drop(1).dropLast(1).sumOf { TokenScale().of(it.getValue("content").jsonPrimitive.content) }

    @Test fun seventyExchangesGoWholeAndWordForWord() {
        val store = Store("seventy")
        val folds = Folds()
        val history = ChatHistory(store, folds.summarize)
        val docs = dev.kento.data.db.encodeSources(listOf(dev.kento.data.db.Source("Docs", "https://example.org/docs")))
        for (n in 0 until 70) {
            // A searched answer keeps its sources line; the raw research and the thoughts stay out.
            turn(store, history, s, prose(n, 260), prose(1000 + n, 1100),
                sources = if (n == 3) docs else "", research = if (n == 3) "{\"queries\":[\"raw tool result\"]}" else "")
        }
        val last = turn(store, history, s, "Что мы решили в самом начале?", "ok")
        assertTrue(folds.calls.isEmpty())
        val texts = last.texts()
        // system + 140 stored messages + the new question, nothing summarized, nothing dropped.
        assertEquals(1 + 140 + 1, texts.size)
        assertEquals(store.rows.dropLast(2).map(::historyText), texts.drop(1).dropLast(1))
        assertTrue(texts.any { it.endsWith("Sources: [Docs](https://example.org/docs)") })
        for (word in listOf("raw tool result", "private reasoning", "<runtime_context>")) assertFalse(word, texts.drop(1).dropLast(1).any { word in it })
        // A real-sized chat: tens of thousands of tokens even by the byte estimate, below the budget.
        assertTrue("${last.historyTokens()}", last.historyTokens() in 40_000..s.historyTokens)
    }

    @Test fun pastTheBudgetOneSummaryAndThePrefixHoldsUntilTheNextOne() {
        val store = Store("long")
        val folds = Folds()
        val history = ChatHistory(store, folds.summarize)
        var inline = 0
        val requests = (0 until 220).map { n -> turn(store, history, s, prose(n, 260), prose(1000 + n, 1100), inline = { inline++ }) }
        // Two folds: past 100K, then again after about 50K tokens of new conversation.
        assertEquals(2, folds.calls.size)
        assertNull(folds.calls[0].first)
        assertEquals(1L, folds.calls[0].second.first().id)
        assertEquals("Summary 1 up to #${folds.calls[0].second.last().id}", folds.calls[1].first)
        assertEquals(folds.calls[0].second.last().id + 1, folds.calls[1].second.first().id)
        // Each fold ran right after a reply, so no message waited for it.
        assertEquals(0, inline)
        val breaks = requests.zipWithNext().withIndex().filter { (_, pair) ->
            val (before, after) = pair
            before.messages().dropLast(1) != after.messages().take(before.messages().size - 1)
        }.map { it.index + 1 }
        assertEquals(2, breaks.size)
        // About 50 thousand tokens of conversation between the folds.
        val between = requests.subList(breaks[0], breaks[1]).zipWithNext().sumOf { (a, b) -> b.historyTokens() - a.historyTokens() }
        assertTrue("$between", between in 40_000..60_000)
        val first = requests.first().messages().first()
        for ((i, request) in requests.withIndex()) {
            val messages = request.messages()
            // The user's prompt is the same first message in every request; the summary is the second system message.
            assertEquals(first, messages.first())
            assertTrue(request.historyTokens() <= s.historyTokens)
            if (i < breaks[0]) assertEquals("user", messages[1].getValue("role").jsonPrimitive.content)
            else {
                assertEquals("system", messages[1].getValue("role").jsonPrimitive.content)
                assertTrue(messages[1].getValue("content").jsonPrimitive.content.startsWith(SUMMARY_HEADER + "Summary "))
                assertEquals("user", messages[2].getValue("role").jsonPrimitive.content)
            }
        }
        // Right after a fold about half the budget stays word for word.
        val after = requests[breaks[0]]
        val verbatim = after.historyTokens() - TokenScale().of(after.texts()[1])
        assertTrue("$verbatim", verbatim in 45_000..52_000)
        assertEquals(1, requests.last().texts().count { it.startsWith(SUMMARY_HEADER) })
    }

    @Test fun aLongChatFromBeforeTheSummariesIsFoldedBeforeItsNextMessage() {
        val store = Store("old")
        repeat(150) { n -> store.add("user", prose(n, 260)); store.add("assistant", prose(1000 + n, 1100)) }
        val folds = Folds()
        var inline = 0
        val request = turn(store, ChatHistory(store, folds.summarize), s, "Продолжим", "ok", inline = { inline++ }, ahead = false)
        assertEquals(1, inline)
        assertEquals(1, folds.calls.size)
        assertTrue(request.texts()[1].startsWith(SUMMARY_HEADER))
        assertTrue(request.historyTokens() <= s.historyTokens * 0.55)
    }

    @Test fun editingASummarizedMessageRebuildsTheSummary() {
        val small = s.copy(historyTokens = 20_000)
        val store = Store("edit")
        val folds = Folds()
        val history = ChatHistory(store, folds.summarize)
        repeat(40) { n -> turn(store, history, small, prose(n, 260), prose(1000 + n, 1100)) }
        val summary = checkNotNull(store.saved)
        val made = folds.calls.size
        // An edit after the summarized part keeps the summary.
        val late = store.rows.last { it.role == "user" && it.id > summary.upToMessageId + 4 }
        store.edit(late.id, "Исправленный поздний вопрос")
        runBlocking { history.load(store.chat, small, TokenScale()) }
        assertEquals(summary, store.saved)
        assertEquals(made, folds.calls.size)
        // An edit inside it: the summary describes messages that are gone, so it is built again from the start.
        val early = store.rows.last { it.role == "user" && it.id < summary.upToMessageId }
        store.edit(early.id, "Исправленный ранний вопрос")
        assertNull(store.saved)
        val loaded = runBlocking { history.load(store.chat, small, TokenScale()) }
        assertEquals(made + 1, folds.calls.size)
        val (previous, rows) = folds.calls.last()
        assertNull(previous)
        assertEquals(1L, rows.first().id)
        assertTrue(rows.all { it.id < early.id })
        assertEquals("Исправленный ранний вопрос", loaded.rows.first().text)
        assertTrue(checkNotNull(store.saved).upToMessageId < early.id)
        // A summary that reaches the newest message (an edit raced the fold) is not trusted.
        store.saved = dev.kento.data.db.ChatSummary(store.chat, 1_000_000, "stale")
        val distrusted = runBlocking { ChatHistory(store, null).load(store.chat, small, TokenScale()) }
        assertNull(distrusted.summary)
        assertEquals(store.rows.size, distrusted.rows.size)
    }

    @Test fun aFailedFoldDoesNotBlockTheReply() {
        val store = Store("fail")
        val folds = Folds()
        var now = 0L
        val history = ChatHistory(store, folds.summarize, clock = { now })
        repeat(150) { n -> store.add("user", prose(n, 260)); store.add("assistant", prose(1000 + n, 1100)) }
        folds.fail = java.io.IOException("background model down")
        val request = turn(store, history, s, "Новый вопрос", "ok", ahead = false)
        // No summary: the verbatim part is cut to the budget by whole messages, newest first; the question goes.
        assertNull(store.saved)
        val texts = request.texts()
        assertTrue(texts.last().endsWith("Новый вопрос"))
        assertTrue(request.historyTokens() <= s.historyTokens)
        assertTrue(request.historyTokens() > s.historyTokens * 0.9)
        assertEquals(store.rows.dropLast(2).takeLast(texts.size - 2).map(::historyText), texts.drop(1).dropLast(1))
        // The next messages do not wait for another try until the pause is over; then it is tried again.
        folds.fail = null
        turn(store, history, s, "Ещё вопрос", "ok", ahead = false)
        assertTrue(folds.calls.isEmpty())
        now += SUMMARY_BACKOFF_MS + 1
        val recovered = turn(store, history, s, "И ещё", "ok", ahead = false)
        assertEquals(1, folds.calls.size)
        assertTrue(recovered.texts()[1].startsWith(SUMMARY_HEADER))
    }

    @Test fun aFoldThatTimesOutIsAFailureNotACancelledReply() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setHeadersDelay(5, TimeUnit.SECONDS)
                .setBody("data: [DONE]\n\n"))
            val api = Provider(base = { server.url("/v1").toString() })
            val store = Store("timeout")
            repeat(150) { n -> store.add("user", prose(n, 260)); store.add("assistant", prose(1000 + n, 1100)) }
            store.add("user", "Вопрос")
            val started = System.nanoTime()
            val loaded = ChatHistory(store, ChatSummaries(api, timeoutMs = 300)::summarize).load(store.chat, s, TokenScale())
            assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000)
            assertNull(loaded.summary)
            assertEquals(store.rows.size, loaded.rows.size)
        }
    }

    @Test fun theFoldRunsOnTheBackgroundModelWithoutReasoningAndInParts() = runBlocking {
        MockWebServer().use { server ->
            fun reply(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            repeat(6) { server.enqueue(reply("S${it + 1}")) }
            val settings = s.copy(catalog = s.catalog + ModelOption("deepseek-v4.1-flash-abliterated-fast", efforts = listOf("off", "low", "high", "max"),
                contextLength = 1_048_576))
            val docs = dev.kento.data.db.encodeSources(listOf(dev.kento.data.db.Source("Docs", "https://example.org/docs")))
            val rows = (1..300).map { n -> Message(n.toLong(), "c", if (n % 2 == 1) "user" else "assistant", prose(n, 1500),
                sources = if (n == 2) docs else "") }
            val text = ChatSummaries(Provider(base = { server.url("/v1").toString() })).summarize(settings, "Old summary", rows)
            val count = server.requestCount
            assertTrue("$count", count >= 2)
            assertEquals("S$count", text)
            val bodies = (1..count).map { Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject }
            for (body in bodies) {
                assertEquals("deepseek-v4.1-flash-abliterated-fast", body.getValue("model").jsonPrimitive.content)
                assertEquals("off", body.getValue("reasoning_effort").jsonPrimitive.content)
                assertEquals(4096, body.getValue("max_tokens").jsonPrimitive.int)
                assertFalse("tools" in body)
                assertEquals(SUMMARY_PROMPT, body.messages()[0].getValue("content").jsonPrimitive.content)
            }
            val inputs = bodies.map { it.messages()[1].getValue("content").jsonPrimitive.content }
            assertTrue(inputs[0].startsWith("<current_summary>\nOld summary\n</current_summary>\n\n<messages>\nuser: " + prose(1, 1500)))
            assertTrue(inputs[0].contains("assistant: " + prose(2, 1500) + "\n\nSources: [Docs](https://example.org/docs)"))
            // Each part updates the summary of the parts before it, and every message is in exactly one part.
            for (i in 1 until count) assertTrue(inputs[i].startsWith("<current_summary>\nS$i\n</current_summary>"))
            for (n in 1..300) assertEquals("#$n", 1, inputs.count { prose(n, 1500) in it })
            assertTrue(inputs.all { cost(it) <= SUMMARY_PART_BYTES })
        }
    }

    @Test fun theTokenCountFollowsWhatTheProviderMeasured() {
        assertEquals(1.0, TokenScale.of(null).factor, 0.0)
        assertEquals(0.5, TokenScale.of(dev.kento.data.db.PromptSize(30_000, 60_000)).factor, 1e-9)
        assertEquals(0.25, TokenScale.of(dev.kento.data.db.PromptSize(1, 60_000)).factor, 1e-9)
        assertEquals(2.0, TokenScale.of(dev.kento.data.db.PromptSize(500_000, 60_000)).factor, 1e-9)
        // Cyrillic is about twice the provider's count by the byte estimate: measured, the same chat fits.
        val rows = (0 until 150).flatMap { n -> listOf(Message(2L * n + 1, "c", "user", prose(n, 260)),
            Message(2L * n + 2, "c", "assistant", prose(1000 + n, 1100))) }.asReversed()
        assertNotNull(foldPlan(null, rows, s, TokenScale()))
        assertNull(foldPlan(null, rows, s, TokenScale(0.5)))
        // The probe keeps the first chat request of a reply: the one with the bare history.
        MockWebServer().use { server ->
            fun reply(prompt: Int) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":$prompt,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n")
            listOf(50, 1000, 1500).forEach { server.enqueue(reply(it)) }
            val api = Provider(base = { server.url("/v1").toString() })
            val probe = dev.kento.data.net.PromptProbe()
            runBlocking { kotlinx.coroutines.withContext(probe) {
                api.stream(s, listOf(Turn("user", "vision check"))).toList()
                api.stream(s, listOf(Turn("user", "q")), chat = true).toList()
                api.stream(s, listOf(Turn("user", "q"), Turn("user", "more")), chat = true).toList()
            } }
            assertEquals(1000L, probe.tokens)
        }
    }

    @Test fun webBudgetsDoNotGrowWithTheWindow() {
        val big = s
        val usual = s.copy(catalog = listOf(ModelOption("m", efforts = listOf("off", "low", "high", "max"), contextLength = 131_072)))
        assertEquals(12_000 to 48_000, dev.kento.data.web.webBudgets(big))
        assertEquals(dev.kento.data.web.webBudgets(usual), dev.kento.data.web.webBudgets(big))
        assertEquals(40_000L, dev.kento.data.tools.RESEARCH_TOKENS)
        assertEquals(2, dev.kento.data.tools.RESEARCH_STEPS)
        // The background reader keeps its 128K window; only the chat request uses the model's million.
        assertEquals(backgroundBudget(usual), backgroundBudget(big))
        assertEquals(1_048_576 - 32_768, inputBudget(big))
    }
}
