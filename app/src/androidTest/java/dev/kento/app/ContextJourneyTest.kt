package dev.kento.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.kento.data.settings.MemorySettings
import dev.kento.data.settings.ModelOption
import dev.kento.data.settings.Settings
import dev.kento.data.settings.WebSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class ContextJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun trimPreservesSystemAndRecallAndShowsInputUsage() {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        val model = "small-context-fixture"
        val system = "Maintain short, literal replies."
        val first = "The almanac tag is LumenAxle."
        val chatId = "context-trim-fixture"
        MockWebServer().use { server ->
            server.start()
            app.apiBase = server.url("/v1").toString()
            val previous = runBlocking { app.settings.profiles.first() }
            runBlocking {
                // Tool schemas and rules would take most of this small window, and whether they fit would depend on
                // the settings an earlier suite left. This journey checks trimming, so memory and self-search are off;
                // the web tools, always declared now, do not fit 4096 tokens, and the request goes out plain.
                app.settings.updateBook { book ->
                    val s = Settings(id = book.active.id, model = model, systemPrompt = system,
                        catalog = listOf(ModelOption(id = model, contextLength = 4096)))
                    book.replace(s).copy(memory = MemorySettings(enabled = false), web = WebSettings(modelDecides = false))
                }
                val answer = app.db.start(chatId, first, model)
                app.db.checkpoint(answer, "Stored.", "", "done")
                repeat(13) { index ->
                    val filler = app.db.start(chatId, "Filler $index " + "unrelated background ".repeat(55), model)
                    app.db.checkpoint(filler, "Continue.", "", "done")
                }
            }
            try {
                ui.onNodeWithContentDescription("Сообщение").performClick()
                ui.waitUntil(10_000) { ui.onAllNodesWithText(model).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithContentDescription("История чатов").performClick()
                ui.onNode(hasText(first) and hasClickAction()).performClick()
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Context fixture answer\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":137,\"completion_tokens\":4}}\n\ndata: [DONE]\n\n"
                ))
                ui.onNodeWithContentDescription("Сообщение").performTextInput("Recall the almanac tag.")
                ui.onNodeWithContentDescription("Отправить").performClick()
                ui.waitUntil(10_000) { ui.onAllNodesWithText("Context fixture answer").fetchSemanticsNodes().isNotEmpty() }
                val request = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val messages = payload.getValue("messages").jsonArray
                assertEquals("system", messages.first().jsonObject.getValue("role").jsonPrimitive.content)
                assertEquals("# Persona\n$system", messages.first().jsonObject.getValue("content").jsonPrimitive.content)
                // Trimmed history is not pasted back as quotes any more; with memory on, the model looks it up with memory_search.
                assertFalse("Trimmed message was pasted back", messages.any { it.jsonObject.getValue("content").jsonPrimitive.content.contains("LumenAxle") })
                assertFalse(payload.containsKey("tools"))
                assertTrue("Fixture did not cross the context boundary", messages.size < 10)
                assertEquals("true", payload.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.content)

                ui.onNodeWithContentDescription("Сообщение").performTextInput("Too large. ".repeat(480))
                ui.onNodeWithContentDescription("Отправить").performClick()
                ui.waitUntil(10_000) {
                    ui.onAllNodesWithText("Сообщение и системный промпт превышают бюджет контекста").fetchSemanticsNodes().isNotEmpty()
                }
                assertNull("Oversized request reached provider", server.takeRequest(500, TimeUnit.MILLISECONDS))
            } finally { runBlocking { app.settings.updateBook { previous } } }
        }
    }
}
