package dev.kento.data.tools

import dev.kento.data.net.Provider
import dev.kento.data.net.RefuselessSse
import dev.kento.data.net.Turn
import dev.kento.data.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ToolLoopTest {
    private fun call(name: String, args: String, prompt: Int = 0) = RefuselessSse.call(name, args, prompt = prompt)
    private fun answer(text: String, prompt: Int = 0) = RefuselessSse.answer(text, prompt = prompt)

    private fun tool(name: String, timeout: Long = 30_000, run: suspend (JsonObject) -> ToolOutput) = object : ChatTool {
        override val name = name
        override val timeoutMs = timeout
        override val schema = functionSchema(name, "Test tool", listOf("query")) {
            putJsonObject("query") { put("type", "string") }
        }
        override fun status(args: JsonObject) = "running $name"
        override suspend fun run(args: JsonObject) = run(args)
    }

    @Test fun declaresToolsInOpenAiFormatAndReturnsResultsById() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(call("search_web", """{"query":"kotlin 2.2 release"}"""))
            server.enqueue(answer("Done"))
            val s = Settings(model = "m")
            val seen = mutableListOf<String>()
            val text = ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(s, listOf(Turn("user", "q")), listOf(tool("search_web") {
                seen += it.getValue("query").jsonPrimitive.content; ToolOutput("""{"results":[]}""")
            })).toList().joinToString("") { it.text }
            assertEquals("Done", text)
            assertEquals(listOf("kotlin 2.2 release"), seen)
            val first = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            val declared = first.getValue("tools").jsonArray.single().jsonObject
            assertEquals("function", declared.getValue("type").jsonPrimitive.content)
            val function = declared.getValue("function").jsonObject
            assertEquals("search_web", function.getValue("name").jsonPrimitive.content)
            assertEquals("object", function.getValue("parameters").jsonObject.getValue("type").jsonPrimitive.content)
            val second = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            val messages = second.getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals("call_1", messages[1].getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("tool", messages[2].getValue("role").jsonPrimitive.content)
            assertEquals("call_1", messages[2].getValue("tool_call_id").jsonPrimitive.content)
        }
    }

    /**
     * A picture from a tool goes inside its `role: tool` result as an image_url part (Refuseless shows
     * it to the model, checked live); the request never gets a user message the user did not write.
     */
    @Test fun toolPicturesTravelInTheToolResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(call("image_edit", """{"uri":"a1","operation":"crop"}"""))
            server.enqueue(answer("7351"))
            val picture = "data:image/jpeg;base64,AAAA"
            val edit = tool("image_edit") { ToolOutput("Edited image 1 is attached to this result.", listOf(picture)) }
            val text = ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(Settings(model = "m"),
                listOf(Turn("user", "What number is on it?")), listOf(edit)).toList().joinToString("") { it.text }
            assertEquals("7351", text)
            server.takeRequest()
            val messages = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
                .getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(listOf("user", "assistant", "tool"), messages.map { it.getValue("role").jsonPrimitive.content })
            assertEquals("What number is on it?", messages[0].getValue("content").jsonPrimitive.content)
            val result = messages[2]
            assertEquals("call_1", result.getValue("tool_call_id").jsonPrimitive.content)
            val parts = result.getValue("content").jsonArray.map { it.jsonObject }
            assertEquals(listOf("text", "image_url"), parts.map { it.getValue("type").jsonPrimitive.content })
            assertEquals("Edited image 1 is attached to this result.", parts[0].getValue("text").jsonPrimitive.content)
            assertEquals(picture, parts[1].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        }
    }

    @Test fun parallelSafeCallsOfOneTurnRunTogetherAndAnswerInOrder() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(RefuselessSse.calls(Triple("call_a", "search_web", """{"query":"a"}"""),
                Triple("call_b", "search_web", """{"query":"b"}""")))
            server.enqueue(answer("Done"))
            val s = Settings(model = "m")
            // Each call waits until both have started: run one by one, the first would time out.
            val started = java.util.concurrent.atomic.AtomicInteger()
            val both = kotlinx.coroutines.CompletableDeferred<Unit>()
            val search = object : ChatTool by tool("search_web", timeout = 3_000, run = { args ->
                if (started.incrementAndGet() == 2) both.complete(Unit)
                both.await()
                ToolOutput("result " + args.getValue("query").jsonPrimitive.content)
            }) { override val parallel = true }
            assertEquals("Done", ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(s, listOf(Turn("user", "q")), listOf(search)).toList().joinToString("") { it.text })
            server.takeRequest()
            val messages = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
                .getValue("messages").jsonArray.map { it.jsonObject }.filter { it.getValue("role").jsonPrimitive.content == "tool" }
            assertEquals(listOf("call_a", "call_b"), messages.map { it.getValue("tool_call_id").jsonPrimitive.content })
            assertEquals(listOf("result a", "result b"), messages.map { it.getValue("content").jsonPrimitive.content })
        }
    }

    @Test fun failingOrSlowToolAnswersWithErrorAndTheModelContinues() = runBlocking {
        for (broken in listOf(
            tool("search_web") { throw java.io.IOException("boom") },
            tool("search_web", timeout = 50) { delay(5_000); ToolOutput("late") }
        )) MockWebServer().use { server ->
            server.enqueue(call("search_web", """{"query":"x"}"""))
            server.enqueue(answer("Answered without search"))
            val s = Settings(model = "m")
            val text = ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(s, listOf(Turn("user", "q")), listOf(broken)).toList().joinToString("") { it.text }
            assertEquals("Answered without search", text)
            server.takeRequest()
            val messages = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
                .getValue("messages").jsonArray
            val error = Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            // Only the fact of the failure, in English: no instructions on how to go on.
            val said = error.getValue("error").jsonPrimitive.content
            assertTrue(said, said.startsWith("search_web ") && !said.contains("proceed"))
        }
    }

    @Test fun forcedToolUsesStandardToolChoiceOnlyInTheFirstRound() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(call("search_web", """{"query":"x"}"""))
            server.enqueue(answer("ok"))
            val s = Settings(model = "m")
            ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(s, listOf(Turn("user", "q")), listOf(tool("search_web") { ToolOutput("[]") }),
                force = "search_web").toList()
            val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("search_web", first.getValue("tool_choice").jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            assertFalse("tool_choice" in Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject)
        }
    }

    @Test fun lastRoundKeepsTheSameToolsAndForbidsCalls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(call("search_web", """{"query":"x"}"""))
            // The model calls again in the last round anyway: the call is ignored and the reply ends.
            server.enqueue(call("search_web", """{"query":"y"}"""))
            val s = Settings(model = "m")
            var runs = 0
            ToolLoop(Provider(base = { server.url("/v1").toString() }), maxRounds = 2).stream(s, listOf(Turn("user", "q")),
                listOf(tool("search_web") { runs++; ToolOutput("[]") })).toList()
            assertEquals(1, runs)
            val first = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            val last = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            // Same declarations, so the cached prefix (tools are rendered first) still matches.
            assertEquals(first.getValue("tools"), last.getValue("tools"))
            assertFalse("tool_choice" in first)
            assertEquals("none", last.getValue("tool_choice").jsonPrimitive.content)
            assertEquals(ANSWER_NOW, last.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content)
        }
    }

    /**
     * Decided on the search bench: a round without calls is the answer, also when it
     * only announces more work, and its text streams as it comes. No CONTINUE request, nothing held back for the
     * thoughts: on high reasoning neither aid ever acted in 84 researched answers, and without them no process note
     * reached an answer.
     */
    @Test fun aRoundWithoutCallsIsTheAnswerAndStreamsAsItComes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(call("search_web", """{"query":"x"}"""))
            server.enqueue(answer("Now let me check the changelog."))
            val s = Settings(model = "m")
            val deltas = ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(s, listOf(Turn("user", "q")),
                listOf(tool("search_web") { ToolOutput("[]") })).toList()
            assertEquals(2, server.requestCount)
            assertTrue(deltas.none { it.thinking })
            assertEquals("Now let me check the changelog.", deltas.joinToString("") { it.text })
        }
    }

    @Test fun providerThatRejectsToolsFallsBackAndIsRemembered() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"tools are not supported"}"""))
            val s = Settings(model = "no-tools-model")
            assertTrue(ToolLoop.supports(s))
            val text = ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(s, listOf(Turn("user", "q")), listOf(tool("search_web") { ToolOutput("") }),
                fallback = { flowOf(dev.kento.data.net.Delta("plain answer")) }).toList().joinToString("") { it.text }
            assertEquals("plain answer", text)
            assertFalse(ToolLoop.supports(s))
        }
    }

    /**
     * The research budget counts what tool results add to the rounds, not the conversation every round repeats: a
     * chat with 100 thousand tokens of history still gets its second research step, and big results still end it.
     */
    @Test fun researchBudgetIgnoresTheLongHistoryButNotWhatTheResultsAdd() = runBlocking {
        val s = Settings(model = "m")
        MockWebServer().use { server ->
            server.enqueue(call("search_web", """{"query":"a"}""", 100_000))
            server.enqueue(call("search_web", """{"query":"b"}""", 104_000))
            server.enqueue(answer("Done", 108_000))
            kotlinx.coroutines.withContext(dev.kento.data.net.UsageMeter()) {
                ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(s, listOf(Turn("user", "q")),
                    listOf(tool("search_web") { ToolOutput("[]") })).toList()
            }
            val choices = (1..3).map { Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject["tool_choice"] }
            // The second round may still search; the third is the answer after RESEARCH_STEPS.
            assertNull(choices[1])
            assertEquals("none", choices[2]!!.jsonPrimitive.content)
        }
        MockWebServer().use { server ->
            // A non-web tool, so only the budget can end the loop: results added 44 thousand tokens to round two.
            server.enqueue(call("memory_search", """{"query":"a"}""", 1_000))
            server.enqueue(call("memory_search", """{"query":"b"}""", 45_000))
            server.enqueue(answer("Done", 46_000))
            kotlinx.coroutines.withContext(dev.kento.data.net.UsageMeter()) {
                ToolLoop(Provider(base = { server.url("/v1").toString() })).stream(s, listOf(Turn("user", "q")),
                    listOf(tool("memory_search") { ToolOutput("[]") })).toList()
            }
            val choices = (1..3).map { Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject["tool_choice"] }
            assertNull(choices[1])
            assertEquals("none", choices[2]!!.jsonPrimitive.content)
        }
    }
}
