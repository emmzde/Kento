package dev.kento.app

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.kento.data.settings.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class ChatEdgeJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val input get() = ui.onNodeWithContentDescription("Сообщение")
    private val send get() = ui.onNodeWithContentDescription("Отправить")
    private val stop get() = ui.onNodeWithContentDescription("Остановить")

    @Test fun inputStressAndNetworkFailuresRecoverInOrder() {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        val model = "edge-fixture"
        MockWebServer().use { server ->
            server.start()
            app.apiBase = server.url("/v1").toString()
            runBlocking { app.settings.update { Settings(id = it.id, model = model, promptClock = false) } }
            // A cold start opens a fresh chat; focusing the composer reveals the model chip.
            input.performClick()
            awaitText(model)

            send.assertIsNotEnabled()
            input.performTextReplacement("   ")
            send.assertIsNotEnabled()
            assertEquals(0, server.requestCount)
            Log.i("KentoEdgeQA", "empty and whitespace input passed")

            val cases = listOf("a".repeat(10_000), "Проверка 🎹👩🏽‍💻🌍", "```kotlin\nval n = 42\n```", "你好，世界", "مرحبا بالعالم")
            for ((index, original) in cases.withIndex()) {
                val response = "Round trip ${index + 1}"
                val body = if (index == 2) "```kotlin\nval n = 42\n```" else response
                server.enqueue(stream(body))
                submit(original)
                if (index == 2) ui.waitUntil(10_000) {
                    ui.onAllNodesWithContentDescription("Скопировать код").fetchSemanticsNodes().isNotEmpty()
                } else awaitText(response)
                awaitIdle()
                val payload = request(server)
                val message = payload.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
                assertTrue("Input changed in case ${index + 1}", message.endsWith(original))
                Log.i("KentoEdgeQA", "round-trip case ${index + 1} passed")
            }

            val beforeRapid = server.requestCount
            server.enqueue(stream("First rapid reply").setBodyDelay(8, TimeUnit.SECONDS))
            submit("Rapid message 1")
            for (number in 2..10) {
                input.performTextReplacement("Rapid message $number")
                send.assertDoesNotExist()
                stop.assertExists()
            }
            assertEquals(beforeRapid + 1, server.requestCount)
            awaitText("First rapid reply", timeout = 12_000)
            awaitIdle()
            ui.onNodeWithText("Rapid message 10", substring = true).assertExists()
            request(server)
            Log.i("KentoEdgeQA", "rapid inputs stayed single-flight and preserved the last draft")

            val partial = event("Partial stream") + ": pending\n\n".repeat(100) + "data: [DONE]\n\n"
            failure(server, MockResponse().setHeader("Content-Type", "text/event-stream").setBody(partial)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY), "Disconnect mid-stream",
                "Не удалось подключиться. Проверь сеть")
            awaitText("Partial stream")
            failure(server, MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), "Read timeout", "Провайдер не ответил вовремя", 16_000)
            failure(server, MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {malformed}\n\n"),
                "Invalid JSON", "Не удалось обработать данные. Проверь настройки подключения")
            failure(server, MockResponse().setResponseCode(401), "Unauthorized", "Проверь API-ключ (HTTP 401)")
            // A busy provider is asked three times in all (two quiet retries) before the error shows.
            failure(server, MockResponse().setResponseCode(429).setHeader("Retry-After", "0"), "Rate limited",
                "Превышен лимит запросов (HTTP 429)", times = 3)
            // One 503 and then an answer: the retry hides the hiccup.
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(stream("Answer after retry"))
            submit("Busy provider")
            awaitText("Answer after retry")
            awaitIdle()
            repeat(2) { request(server) }

            server.enqueue(stream("Provider recovered"))
            submit("Recovery after failures")
            awaitText("Provider recovered")
            awaitIdle()
            request(server)
        }
    }

    /**
     * A cold model answers 429 `model_warming` with Retry-After. Kento waits by itself, the
     * status line says "Модель запускается…" meanwhile (not the error text asking to try again), and the answer
     * replaces it. The screenshot of the waiting line lands in the app's external files, `shots/warming.png`.
     */
    @Test fun aColdModelShowsTheStartingLineWhileKentoWaits() {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        MockWebServer().use { server ->
            server.start()
            app.apiBase = server.url("/v1").toString()
            runBlocking { app.settings.update { Settings(id = it.id, model = "warming-fixture", promptClock = false) } }
            input.performClick()
            awaitText("warming-fixture")
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "4")
                .setBody("""{"error":{"code":"model_warming","message":"Cold pod starting","type":"api_error"}}"""))
            server.enqueue(stream("Warm answer"))
            submit("Привет")
            awaitText("Модель запускается…", 5_000)
            ui.onAllNodesWithText("попробуйте", substring = true).assertCountEquals(0)
            val shot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (shot != null) {
                val dir = java.io.File(app.getExternalFilesDir(null), "shots").apply { mkdirs() }
                java.io.File(dir, "warming.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                shot.recycle()
            }
            awaitText("Warm answer", 12_000)
            awaitIdle()
            ui.onAllNodesWithText("Модель запускается…").assertCountEquals(0)
            repeat(2) { request(server) }
        }
    }

    private fun submit(text: String) {
        input.performTextReplacement(text)
        send.performClick()
    }

    private fun awaitText(text: String, timeout: Long = 10_000) = ui.waitUntil(timeout) {
        ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitIdle() = ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("Остановить").fetchSemanticsNodes().isEmpty() }

    private fun failure(server: MockWebServer, response: MockResponse, prompt: String, message: String, timeout: Long = 10_000,
        times: Int = 1) {
        repeat(times) { server.enqueue(response) }
        submit(prompt)
        awaitText(message, timeout)
        awaitIdle()
        repeat(times) { request(server) }
        Log.i("KentoEdgeQA", "$prompt passed")
    }

    private fun request(server: MockWebServer) =
        Json.parseToJsonElement(checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()).jsonObject

    private fun event(text: String): String {
        val content = kotlinx.serialization.json.JsonPrimitive(text)
        return "data: {\"choices\":[{\"delta\":{\"content\":$content}}]}\n\n"
    }

    private fun stream(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(event(text) + "data: [DONE]\n\n")
}
