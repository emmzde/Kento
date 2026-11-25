package dev.kento.data.net

import dev.kento.data.settings.Settings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ProviderRetryTest {
    private fun answer(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\ndata: [DONE]\n\n")

    @Test fun busyProviderIsRetriedQuietly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(answer("ok"))
            val s = Settings(model = "m")
            val text = Provider(backoff = listOf(0L, 0L), base = { server.url("/v1").toString() }).stream(s, listOf(Turn("user", "q")))
                .toList().joinToString("") { it.text }
            assertEquals("ok", text)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun clientErrorsAndStartedAnswersAreNotRepeated() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val s = Settings(model = "m")
            val api = Provider(backoff = listOf(0L, 0L), base = { server.url("/v1").toString() })
            val error = runCatching { api.stream(s, listOf(Turn("user", "q"))).toList() }.exceptionOrNull()
            assertEquals(401, (error as ProviderIssue).status)
            assertEquals(1, server.requestCount)

            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"half\"}}]}\n\n" + ": pad\n\n".repeat(200))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val parts = mutableListOf<String>()
            runCatching { api.stream(s, listOf(Turn("user", "q"))).collect { parts += it.text } }
            assertEquals(2, server.requestCount)
            assertTrue(parts.joinToString("").startsWith("half"))
        }
    }

    /** Refuseless answers a cold model with an error event and Retry-After; the client says so and waits. */
    @Test fun warmingModelIsWaitedForAndAnnounced() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
                .setBody("""{"error":{"code":"model_warming","message":"Cold pod starting","type":"api_error"}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"error\":{\"code\":\"model_busy\",\"message\":\"busy\"}}\n\ndata: [DONE]\n\n"))
            server.enqueue(answer("ok"))
            val notices = mutableListOf<String>()
            val text = kotlinx.coroutines.withContext(ProviderNotice { notices += it }) {
                Provider(backoff = listOf(0L, 0L), base = { server.url("/v1").toString() }).stream(Settings(model = "m"), listOf(Turn("user", "q")))
                    .toList().joinToString("") { it.text }
            }
            assertEquals("ok", text)
            assertEquals(3, server.requestCount)
            // While Kento waits by itself the line says what happens and asks the user for nothing.
            assertEquals(listOf("Модель запускается…", "Модель занята, жду…"), notices)
            val sent = server.takeRequest()
            assertEquals("/v1/chat/completions", sent.path)
        }
    }

    @Test fun moneyProblemsAreNamedAndNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(402)
                .setBody("""{"error":{"code":"insufficient_credits_for_hold","message":"Balance below hold","type":"api_error"}}"""))
            val error = runCatching {
                Provider(backoff = listOf(0L, 0L), base = { server.url("/v1").toString() }).stream(Settings(model = "m"), listOf(Turn("user", "q"))).toList()
            }.exceptionOrNull() as ProviderIssue
            assertEquals("insufficient_credits_for_hold", error.code)
            assertTrue(error.message!!.contains("Максимум токенов ответа"))
            assertEquals(1, server.requestCount)
        }
    }
}
