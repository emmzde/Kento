package dev.kento.data.net

import dev.kento.data.settings.*
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/**
 * The stream reader on Refuseless streams recorded live on 2026-11-24 (data/src/test/resources/refuseless): a plain
 * answer, thinking as `reasoning` (DeepSeek, GLM) and as `reasoning_content` (Kimi), two parallel tool calls, the
 * `max_tokens` cap. The error event comes from the documented shape (it could not be provoked live).
 */
class StreamReaderTest {
    private fun recorded(name: String): String =
        checkNotNull(javaClass.getResource("/refuseless/$name.sse")) { name }.readBytes().toString(Charsets.UTF_8)
    private fun parse(raw: String) = buildList { SseReader(wireJson).read(Buffer().writeUtf8(raw), ::add) }
    private fun List<Delta>.answer() = filter { !it.thinking && it.tool == null }.joinToString("") { it.text }
    private fun List<Delta>.thoughts() = filter { it.thinking }.joinToString("") { it.text }
    private fun events(raw: String) = raw.split("\n\n").filter(String::isNotBlank)

    @Test fun plainAnswerWithUsageFromTheLastChunk() {
        val out = parse(recorded("plain"))
        assertEquals("17 умножить на 3 будет 51.", out.answer())
        assertTrue(out.none { it.thinking || it.tool != null })
        // Every chunk of this model repeats a growing usage; one figure comes out, the final one, after the text.
        assertEquals(Reported(50, 12, 0, 0), out.mapNotNull { it.usage }.single())
        assertNotNull(out.last().usage)
    }

    @Test fun thinkingArrivesAsReasoningOrReasoningContent() {
        val deepseek = parse(recorded("reasoning-deepseek"))
        assertTrue(deepseek.thoughts().startsWith("We need answer one sentence Russian: 17*3=51."))
        assertEquals("17 умножить на 3 будет 51.", deepseek.answer())
        assertEquals(Reported(75, 48, 0, 35), deepseek.mapNotNull { it.usage }.single())
        val glm = parse(recorded("reasoning-glm"))
        assertEquals("17×3=51", glm.thoughts())
        assertEquals("17 умножить на 3 будет 51.", glm.answer())
        // Kimi streams its thinking as reasoning_content.
        assertTrue(recorded("reasoning-kimi").contains("\"reasoning_content\""))
        val kimi = parse(recorded("reasoning-kimi"))
        assertTrue(kimi.thoughts().startsWith("Мы должны ответить одним предложением по-русски"))
        assertEquals("17 умножить на 3 равно 51.", kimi.answer())
        assertEquals(91L, kimi.mapNotNull { it.usage }.single().reasoning)
    }

    @Test fun parallelToolCallsAreJoinedByIndex() {
        for (name in listOf("tools-deepseek", "tools-glm")) {
            val out = parse(recorded(name))
            val calls = out.mapNotNull { it.tool }
            assertEquals(name, 2, calls.size)
            assertTrue(name, calls.all { it.name == "search_web" && it.id.startsWith("chatcmpl-tool-") })
            assertNotEquals(calls[0].id, calls[1].id)
            val queries = calls.map { wireJson.parseToJsonElement(it.arguments).jsonObject.getValue("query").jsonPrimitive.content }
            assertTrue(name, queries[0].contains("Oslo") && queries[1].contains("Bergen"))
        }
        val deepseek = parse(recorded("tools-deepseek"))
        assertTrue(deepseek.thoughts().isNotBlank())
        assertEquals(256L, deepseek.mapNotNull { it.usage }.single().cached)
        // GLM says a few words before its calls; they stay text for the tool loop to place.
        assertEquals("I'll search for both populations in parallel.\n\n", parse(recorded("tools-glm")).answer())
    }

    @Test fun lengthCapIsAFailure() {
        val issue = assertThrows(ProviderIssue::class.java) { parse(recorded("length")) }
        assertEquals("Достигнут лимит ответа", issue.message)
    }

    @Test fun streamCutBeforeTheEndIsAnError() {
        val head = events(recorded("plain")).take(3).joinToString("\n\n", postfix = "\n\n")
        assertThrows(StreamCut::class.java) { parse(head) }
    }

    @Test fun eventsWithoutBlankLinesBetweenThemReadTheSame() {
        val raw = recorded("tools-glm")
        val tight = events(raw).joinToString("\n", postfix = "\n")
        assertEquals(parse(raw), parse(tight))
    }

    @Test fun characterSplitBetweenEventsOfARecordedStreamIsJoined() {
        // The same answer, but "у" of "умножить" (D1 83) is cut between two events, as a byte-streaming server would.
        val bytes = Buffer()
        val word = "умножить".toByteArray()
        for (piece in listOf(word.copyOfRange(0, 1), word.copyOfRange(1, word.size))) {
            bytes.writeUtf8("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"").write(piece).writeUtf8("\"},\"finish_reason\":null}]}\n\n")
        }
        bytes.writeUtf8(events(recorded("plain")).last { it.contains("\"stop\"") } + "\n\ndata: [DONE]\n\n")
        val out = buildList { SseReader(wireJson).read(bytes, ::add) }
        assertEquals("умножить 51.", out.answer())
    }

    /** A cold or busy model is an error event of a successful stream; its status lets the client wait and retry. */
    @Test fun errorEventsKeepTheirCode() {
        val issue = runCatching { parse("""data: {"error":{"code":"model_warming","message":"Cold pod starting"}}""" + "\n\ndata: [DONE]\n") }
            .exceptionOrNull() as ProviderIssue
        assertEquals("model_warming", issue.code)
        assertEquals(429, issue.status)
        assertTrue(retryable(issue))
        // Credits running out after some text: the documented terminal event, then [DONE].
        val head = events(recorded("plain")).take(3).joinToString("\n\n", postfix = "\n\n")
        val broke = runCatching { parse(head + """data: {"error":{"code":"insufficient_credits","message":"Credits ran out. Generation stopped."}}""" +
            "\n\ndata: [DONE]\n\n") }.exceptionOrNull() as ProviderIssue
        assertEquals(402, broke.status)
        assertFalse(retryable(broke))
        assertTrue(broke.message!!.startsWith("На счёте закончились кредиты"))
    }

    /** Arguments cut short reach the tool loop as they are; it tells the model they are not JSON. */
    @Test fun incompleteArgumentsDoNotFailTheReply() {
        val raw = """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"id":"c1","type":"function","index":0,"function":{"name":"read_webpage"}}]}}]}""" +
            "\n\n" + """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{"}}]}}]}""" +
            "\n\n" + """data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""" + "\n\ndata: [DONE]\n\n"
        assertEquals(ToolCall("c1", "read_webpage", "{"), parse(raw).single().tool)
    }

    /** Shapes of other gateways are not read any more: snapshots, legacy function_call, reasoning_details, bare JSON lines. */
    @Test fun onlyTheRefuselessShapeIsRead() {
        val foreign = listOf(
            """data: {"choices":[{"delta":{},"message":{"content":"snapshot","reasoning_content":"old"}}]}""",
            """data: {"choices":[{"delta":{"function_call":{"name":"read","arguments":"{}"},"reasoning_details":[{"text":"detail"}]}}]}""",
            """{"choices":[{"delta":{"content":"bare line"}}]}""",
            """data: {"choices":[{"delta":{"content":"ok","reasoning":"R","reasoning_content":"R"},"finish_reason":"stop"}]}""",
            "data: [DONE]").joinToString("\n\n", postfix = "\n\n")
        assertEquals(listOf(Delta("R", true), Delta("ok")), parse(foreign))
    }

    @Test fun levelsComeOnlyFromTheCatalogue() {
        assertTrue(Settings().levels("example").isEmpty())
        assertEquals(emptyList<String>(), Settings(catalog = listOf(ModelOption("example", emptyList()))).levels("example"))
        assertEquals(listOf("off", "high"), Settings(catalog = listOf(ModelOption("example", listOf("high", "none", "medium")))).levels("example"))
    }
    @Test fun baseCannotCarryCredentialsOrPlaintext() {
        for (url in listOf("http://example.test/v1", "https://key@example.test", "https://example.test?key=bad"))
            assertFalse(validBase(url))
        assertTrue(validBase("https://example.test/custom/v1"))
    }
    @Test fun pickerRespectsProviderAndSavedCatalog() {
        assertTrue(Settings().modelIds().isEmpty())
        assertEquals(listOf("custom"), Settings(catalog = listOf(ModelOption("custom"))).modelIds())
        assertTrue(Settings(catalogLoaded = true).modelIds().isEmpty())
    }
}
