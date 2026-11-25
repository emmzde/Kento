package dev.kento.data.net

import dev.kento.data.settings.ModelOption
import dev.kento.data.settings.Settings
import dev.kento.data.web.SearchHit
import dev.kento.data.web.Trail
import dev.kento.data.web.TrailRecorder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UsageTest {
    private fun packet(raw: String) = Json.parseToJsonElement(raw).jsonObject

    /** Only the Refuseless `usage`; other gateways' money and shapes (OpenRouter cost, DeepSeek, Groq, ou) are not read. */
    @Test fun readsTheRefuselessUsage() {
        val full = reportedUsage(packet("""{"choices":[],"usage":{"prompt_tokens":341,"completion_tokens":119,"total_tokens":460,
            "prompt_tokens_details":{"cached_tokens":256},"completion_tokens_details":{"reasoning_tokens":37}}}"""))!!
        assertEquals(Reported(341, 119, 256, 37), full)
        // The first chunk of a stream reports the prompt before anything is written.
        assertEquals(Reported(50, 0, 0, 0), reportedUsage(packet("""{"usage":{"prompt_tokens":50,"completion_tokens":0,"total_tokens":50,
            "completion_tokens_details":{"reasoning_tokens":0}}}""")))
        assertNull(reportedUsage(packet("""{"usage":{"input_tokens":10,"output_tokens":5,"cost":0.002},"ou":{"cost_usd":0.0001}}""")))
        assertNull(reportedUsage(packet("""{"x_groq":{"usage":{"prompt_tokens":10,"completion_tokens":5}}}""")))
        assertNull(reportedUsage(packet("""{"choices":[]}""")))
    }

    @Test fun pricesTheReportedTokens() {
        val option = ModelOption("m", priceIn = 1.0, priceOut = 4.0, priceCached = .1)
        val usage = priced(Reported(1_000_000, 500_000, 400_000, 0), option, 0, 0)
        assertEquals(.6 + .04 + 2.0, usage.estimated, 1e-9)
        assertFalse(usage.precise)
        // A picture is the one request with the provider's own money (OpenUncensored cost_usd).
        val picture = Usage(charged = .5, requests = 1, exact = 1)
        assertTrue(picture.precise)
        val sum = usage + picture
        assertEquals(2, sum.requests)
        assertEquals(1, sum.exact)
        assertEquals(sum, Usage.decode(sum.encode()))
        assertEquals(1, priced(null, null, 100, 10).guessed)
    }

    @Test fun recordedStreamFeedsTheMeterAndAsksForUsage() = runBlocking {
        MockWebServer().use { server ->
            val recorded = checkNotNull(javaClass.getResource("/refuseless/tools-deepseek.sse")).readBytes()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(okio.Buffer().write(recorded)))
            val meter = UsageMeter()
            val option = ModelOption("deepseek-v4.1-flash-abliterated", priceIn = 1.0, priceOut = 2.0, priceCached = .1)
            val out = withContext(meter) {
                Provider(base = { server.url("/v1").toString() }).stream(Settings(model = option.id, catalog = listOf(option)),
                    listOf(Turn("user", "q"))).toList()
            }
            assertEquals(2, out.count { it.tool != null })
            val u = meter.value
            assertEquals(Usage(341, 119, 256, 37, estimated = (85 * 1.0 + 256 * .1 + 119 * 2.0) / 1e6, requests = 1), u)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"include_usage\":true"))
        }
    }

    @Test fun balancesOfBothProviders() {
        fun read(raw: String) = readBalance(Json.parseToJsonElement(raw))
        // Refuseless: what a new request can reserve, not the balance that still holds requests in flight.
        assertEquals(Balance(41.3, "USD", 3.184211), read("""{"object":"credits","balance":42.5,"reserved":1.2,"available":41.3,
            "key_limit":null,"key_spent":3.184211,"key_spent_since":null,"unit":"usd","updated_at":1793836800}"""))
        assertEquals(2.04, read("""{"object":"credits","data":{"remaining_usd":2.04,"balance_usd":2.04,"currency":"usd"}}""")!!.amount, 1e-9)
        assertNull(read("""{"data":[]}"""))
    }

    @Test fun longPromptsUseTheLongContextRates() {
        val option = dev.kento.data.settings.ModelOption("kimi", priceIn = 1.5, priceOut = 3.0, priceCached = 0.15,
            longContext = dev.kento.data.settings.LongContext(200_000, 3.0, 0.3, 6.0))
        val short = priced(Reported(100_000, 1_000, 50_000, 0), option, 0, 0)
        assertEquals((50_000 * 1.5 + 50_000 * 0.15 + 1_000 * 3.0) / 1e6, short.estimated, 1e-12)
        val long = priced(Reported(300_000, 1_000, 100_000, 0), option, 0, 0)
        assertEquals((200_000 * 3.0 + 100_000 * 0.3 + 1_000 * 6.0) / 1e6, long.estimated, 1e-12)
    }

    @Test fun catalogOtherThanRefuselessIsRejected() {
        assertTrue(runCatching { modelCatalog("""{"models":[{"name":"llama3:latest"}]}""") }.exceptionOrNull() is ProviderIssue)
        assertTrue(runCatching { modelCatalog("""["a","b"]""") }.exceptionOrNull() is ProviderIssue)
    }

    @Test fun trailKeepsWhereTheSearchBeganAndWhatWasRead() {
        var length = 17
        val recorder = TrailRecorder({ length })
        recorder.query("lostrushi")
        length = 99
        recorder.found(listOf(SearchHit("A", "https://www.a.org/x", ""), SearchHit("B", "https://b.org/y", "")))
        recorder.read("https://b.org/y", "B page")
        val trail = recorder.finish()
        assertEquals(17, trail.at)
        assertEquals(listOf("a.org", "b.org"), trail.sites)
        assertEquals(listOf("https://b.org/y"), trail.read.map { it.url })
        assertEquals(trail, Trail.decode(trail.encode()))
    }
}
