package dev.kento.data.memory

import dev.kento.data.db.FactChange
import dev.kento.data.db.MemoryEntry
import dev.kento.data.net.Provider
import dev.kento.data.net.ProviderIssue
import dev.kento.data.net.RefuselessSse
import dev.kento.data.settings.ModelOption
import dev.kento.data.settings.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

/**
 * The background reader answers with one forced save_facts call, whose operations
 * are checked one by one. What the database does with them is covered on the device (MemoryJourneyTest).
 */
class MemoryAnalysisTest {
    private val catalog = listOf(ModelOption("deepseek-v4.1-flash-abliterated-fast", efforts = listOf("off", "low", "high", "max")),
        ModelOption("glm", efforts = listOf("low", "high", "max")))
    private val s = Settings(model = "glm", catalog = catalog)
    private val job = MemoryEntry(12, "fact", "Пользователь работает в Яндексе.", "k12", topic = "работа", importance = 3,
        createdAt = 1_795_000_000_000L)
    private val own = MemoryEntry(13, "fact", "Меня зовут Эмиль.", "manual:13", edited = true, importance = 3, createdAt = 1_795_000_000_000L)
    private val notes = listOf(job, own)

    private fun op(vararg pairs: Pair<String, Any?>) = buildJsonObject {
        for ((key, value) in pairs) when (value) {
            is String -> put(key, value)
            is Number -> put(key, value)
            is List<*> -> putJsonArray(key) { value.forEach { add(JsonPrimitive(it as Number)) } }
            null -> put(key, JsonNull)
        }
    }

    private fun args(vararg ops: JsonObject) = buildJsonObject { putJsonArray("operations") { ops.forEach(::add) } }

    @Test fun theCallIsForcedOnTheBackgroundModelAndTextAroundItIsIgnored() = runBlocking {
        MockWebServer().use { server ->
            val call = args(op("op" to "update", "id" to 12, "text" to "Пользователь работает в Сбере.", "message_ids" to listOf(41))).toString()
            server.enqueue(MockResponse().setBody(RefuselessSse.callsBody(Triple("call_1", "save_facts", call),
                text = "Sure! Here is what changed: {not json")))
            val api = Provider(base = { server.url("/v1").toString() })
            val messages = buildJsonArray { add(buildJsonObject { put("id", 41); put("text", "Я перешёл в Сбер.") }) }
            val result = askSaveFacts(api, s.copy(model = s.background()), notesInput(notes, ZoneOffset.UTC), messages)
            assertEquals(Json.parseToJsonElement(call), result)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("deepseek-v4.1-flash-abliterated-fast", body.getValue("model").jsonPrimitive.content)
            assertEquals("off", body.getValue("reasoning_effort").jsonPrimitive.content)
            assertEquals(2048, body.getValue("max_tokens").jsonPrimitive.int)
            assertEquals(buildJsonObject { put("type", "function"); putJsonObject("function") { put("name", "save_facts") } },
                body.getValue("tool_choice"))
            assertEquals(listOf("save_facts"), body.getValue("tools").jsonArray.map {
                it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content })
            // The input: the related notes with ids and dates (the user's own marked) and the user's new messages only.
            val input = Json.parseToJsonElement(body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            assertEquals(listOf(12L, 13L), input.getValue("saved_notes").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long })
            assertEquals("2026-11-18", input.getValue("saved_notes").jsonArray[0].jsonObject.getValue("date").jsonPrimitive.content)
            assertNull(input.getValue("saved_notes").jsonArray[0].jsonObject["by_user"])
            assertTrue(input.getValue("saved_notes").jsonArray[1].jsonObject.getValue("by_user").jsonPrimitive.boolean)
            assertEquals(messages, input.getValue("messages"))
        }
    }

    @Test fun aReplyWithoutTheCallFailsTheBatchInsteadOfGuessing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(RefuselessSse.answerBody("""{"operations":[{"op":"add"}]}""")))
            val api = Provider(base = { server.url("/v1").toString() })
            val failure = runCatching { askSaveFacts(api, s, JsonArray(emptyList()), JsonArray(emptyList())) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is ProviderIssue)
        }
    }

    @Test fun invalidOperationsAreDroppedAndValidOnesApply() {
        val changes = factChanges(args(
            op("op" to "add", "text" to "У пользователя аллергия на арахис.", "topic" to "здоровье", "importance" to 8, "message_ids" to listOf(41)),
            op("op" to "update", "id" to "#12", "text" to "Пользователь работает в Сбере.", "message_ids" to listOf(41, 43)),
            op("op" to "outdate", "id" to 12, "message_ids" to listOf(43)),
            op("op" to "none", "message_ids" to listOf(41)),
            // Dropped: unknown op, a note that was not offered, the user's own note, a message outside the batch,
            // no message ids, no text, a missing id.
            op("op" to "delete", "id" to 12, "message_ids" to listOf(41)),
            op("op" to "update", "id" to 99, "text" to "Чужой факт.", "message_ids" to listOf(41)),
            op("op" to "update", "id" to 13, "text" to "Меня зовут Эмиль Иванов.", "message_ids" to listOf(41)),
            op("op" to "outdate", "id" to 13, "message_ids" to listOf(41)),
            op("op" to "add", "text" to "Факт из чужого сообщения.", "message_ids" to listOf(7)),
            op("op" to "add", "text" to "Без источника."),
            op("op" to "add", "text" to " ", "message_ids" to listOf(41)),
            op("op" to "update", "text" to "Без id.", "message_ids" to listOf(41)),
        ), notes, setOf(41L, 43L))
        assertEquals(listOf(
            FactChange(FactChange.Op.ADD, listOf(41), "У пользователя аллергия на арахис.", "здоровье", 3),
            FactChange(FactChange.Op.UPDATE, listOf(41, 43), "Пользователь работает в Сбере.", "", 2, 12),
            FactChange(FactChange.Op.OUTDATE, listOf(43), target = 12),
        ), changes)
        // A call without operations, or with junk in their place, changes nothing.
        assertTrue(factChanges(buildJsonObject {}, notes, setOf(41L)).isEmpty())
        assertTrue(factChanges(buildJsonObject { put("operations", "add everything") }, notes, setOf(41L)).isEmpty())
    }

    @Test fun thePromptAndSchemaDescribeTheFourOperations() {
        val schema = SAVE_FACTS.toString()
        for (op in listOf("add", "update", "outdate", "none")) {
            assertTrue(op, memoryPrompt.contains("- $op:"))
            assertTrue(op, schema.contains("\"$op\""))
        }
        assertTrue(memoryPrompt.contains("Notes with by_user are never updated or outdated"))
        assertTrue(schema.contains("\"required\":[\"op\",\"message_ids\"]"))
    }
}
