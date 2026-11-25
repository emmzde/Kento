package dev.kento.data.web

import dev.kento.data.settings.WebSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One paid API per query, the next only after a failure or an empty answer; without keys (or
 * when every paid API fails) SearXNG, then DuckDuckGo Lite over plain HTTP, and its WebView tab only for a bot check.
 */
class FreeSearchTest {
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Every host goes to [server] as `/<host><path>`; [answers] decides per host. */
    private fun MockWebServer.serve(answers: (String) -> MockResponse) {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answers(request.path!!.substringBefore('?').removePrefix("/").substringBefore('/'))
            }
        }
    }

    private fun MockWebServer.client() = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val destination = url("/" + request.url.host + request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
        chain.proceed(request.newBuilder().url(destination).build())
    }.build()

    private fun hosts() = requests.map { it.path!!.removePrefix("/").substringBefore('/') }

    private val tavilyHit = """{"results":[{"title":"Tavily result","url":"https://tavily.example.org/a","content":"from tavily"}]}"""
    private val braveHit = """{"type":"search","web":{"results":[{"title":"Brave result","url":"https://brave.example.org/b","description":"from brave"}]}}"""
    private val liteHit = liteResults(listOf(SearchHit("Lite result", "https://lite.example.org/c", "from duckduckgo")))

    private fun search(access: SearchAccess, web: WebSettings, deadline: Long = SEARCH_DEADLINE_MS) =
        WebTools(access, web, pageBytes = 8_000, totalBytes = 200_000, searchDeadline = deadline).all().first { it.name == "search_web" }

    private suspend fun call(tool: dev.kento.data.tools.ChatTool, query: String) =
        Json.parseToJsonElement(tool.run(buildJsonObject { put("query", query) }).text).jsonObject

    @Test fun withTwoKeysOneSearchMakesExactlyOnePaidCall() = runBlocking {
        MockWebServer().use { server ->
            server.serve { host -> when (host) {
                "api.tavily.com" -> MockResponse().setBody(tavilyHit)
                "api.search.brave.com" -> MockResponse().setBody(braveHit)
                else -> MockResponse().setResponseCode(500)
            } }
            val access = WebAccess(server.client(), server.client(), duckBrowser = noBrowser)
            val before = SearchStats.snapshot()
            val out = call(search(access, WebSettings(tavilyKey = "t", braveKey = "b")), "kotlin release")
            assertEquals(listOf("api.tavily.com"), hosts())
            assertTrue(out.toString(), out.toString().contains("tavily.example.org"))
            // SearchStats counts the calls that were made: Tavily once, Brave never.
            val after = SearchStats.snapshot()
            assertEquals(1, (after["Tavily"] ?: 0) - (before["Tavily"] ?: 0))
            assertEquals(0, (after["Brave"] ?: 0) - (before["Brave"] ?: 0))
        }
    }

    @Test fun aFailedFirstApiMakesExactlyTwoPaidCalls() = runBlocking {
        MockWebServer().use { server ->
            server.serve { host -> when (host) {
                "api.tavily.com" -> MockResponse().setResponseCode(503)
                "api.search.brave.com" -> MockResponse().setBody(braveHit)
                else -> MockResponse().setResponseCode(500)
            } }
            val access = WebAccess(server.client(), server.client(), duckBrowser = noBrowser)
            val out = call(search(access, WebSettings(tavilyKey = "t", braveKey = "b", serperKey = "s")), "kotlin release")
            assertEquals(listOf("api.tavily.com", "api.search.brave.com"), hosts())
            assertTrue(out.toString(), out.toString().contains("brave.example.org"))
        }
    }

    @Test fun anEmptyAnswerAlsoHandsTheQueryOnButAtMostTwoPaidApisTryIt() = runBlocking {
        MockWebServer().use { server ->
            server.serve { host -> when (host) {
                "api.tavily.com" -> MockResponse().setBody("""{"results":[]}""")
                "api.search.brave.com" -> MockResponse().setBody("""{"type":"search","web":{"results":[]}}""")
                "lite.duckduckgo.com" -> MockResponse().setBody(liteHit)
                else -> MockResponse().setBody("""{"organic":[{"link":"https://serper.example.org","title":"never"}]}""")
            } }
            val access = WebAccess(server.client(), server.client(), duckBrowser = noBrowser)
            val out = call(search(access, WebSettings(tavilyKey = "t", braveKey = "b", serperKey = "s", exaKey = "e")), "kotlin release")
            assertEquals(listOf("api.tavily.com", "api.search.brave.com", "lite.duckduckgo.com"), hosts())
            assertTrue(out.toString(), out.toString().contains("lite.example.org"))
        }
    }

    @Test fun aQueryWithDomainsGoesToExaOrTavily() {
        val domains = SearchQuery("compose state", listOf("developer.android.com"))
        val plain = SearchQuery("compose state")
        val all = WebSettings(tavilyKey = "t", braveKey = "b", serperKey = "s", exaKey = "e")
        assertEquals(listOf("Exa", "Tavily", "DuckDuckGo Lite"), all.engines(domains))
        assertEquals(listOf("Tavily", "Brave", "DuckDuckGo Lite"), all.engines(plain))
        assertEquals(listOf("Brave", "Serper", "DuckDuckGo Lite"), WebSettings(braveKey = "b", serperKey = "s").engines(domains))
        // Past the paid allowance of the answer, and without keys: SearXNG when an instance is set, then DuckDuckGo.
        assertEquals(listOf("DuckDuckGo Lite"), all.engines(plain.copy(paid = false)))
        assertEquals(listOf("SearXNG", "DuckDuckGo Lite"), WebSettings(instance = "https://searx.example.org").engines(plain))
        assertEquals(listOf("DuckDuckGo Lite"), WebSettings(engine = "DuckDuckGo Lite", instance = "https://searx.example.org").engines(plain))
        assertEquals(listOf("Tavily", "DuckDuckGo Lite"), WebSettings(engine = "Tavily", braveKey = "b").engines(plain))
    }

    @Test fun withoutKeysTheSearchReachesDuckDuckGoLiteOverHttp() = runBlocking {
        MockWebServer().use { server ->
            server.serve { host -> if (host == "lite.duckduckgo.com") MockResponse().setBody(liteHit) else MockResponse().setResponseCode(500) }
            val access = WebAccess(server.client(), server.client(), duckBrowser = noBrowser)
            val out = Json.parseToJsonElement(search(access, WebSettings()).run(buildJsonObject {
                put("query", "kotlin release"); put("freshness", "week")
            }).text).jsonObject
            val results = out.getValue("results").jsonArray.map { it.jsonObject }
            assertEquals("https://lite.example.org/c", results.single().getValue("url").jsonPrimitive.content)
            assertEquals("from duckduckgo", results.single().getValue("snippet").jsonPrimitive.content)
            val request = requests.single()
            assertEquals("POST", request.method)
            assertEquals("/lite.duckduckgo.com/lite/", request.path)
            val form = request.body.readUtf8()
            assertTrue(form, form.contains("q=kotlin%20release") || form.contains("q=kotlin+release"))
            assertTrue(form, form.contains("df=w"))
        }
    }

    @Test fun aBotCheckSendsTheSearchToTheWebView() = runBlocking {
        MockWebServer().use { server ->
            server.serve { MockResponse().setResponseCode(202).setBody(LITE_CHECK) }
            val opened = CopyOnWriteArrayList<String>()
            val access = WebAccess(server.client(), server.client()) { q, _ ->
                opened += q
                listOf(SearchHit("Checked result", "https://checked.example.org", "after the check"))
            }
            val before = SearchStats.snapshot()[DUCK_BROWSER] ?: 0
            val hits = access.search(WebSettings(), "android 17")
            assertEquals(listOf("android 17"), opened)
            assertEquals("https://checked.example.org/", hits.single().url)
            assertEquals(1, (SearchStats.snapshot()[DUCK_BROWSER] ?: 0) - before)
        }
        // Ordinary results never open it.
        val direct = WebAccess(OkHttpClient(), OkHttpClient(), duckHttp = { _, _ -> liteHit }, duckBrowser = noBrowser)
        assertEquals("Lite result", direct.search(WebSettings(), "android 17").single().title)
    }

    @Test fun aFailedPaidApiHandsTheQueryToTheFreeEngines() = runBlocking {
        MockWebServer().use { server ->
            server.serve { host -> when (host) {
                "api.tavily.com" -> MockResponse().setResponseCode(401)
                "searx.example.org" -> MockResponse().setResponseCode(429)
                "lite.duckduckgo.com" -> MockResponse().setBody(liteHit)
                else -> MockResponse().setResponseCode(500)
            } }
            val access = WebAccess(server.client(), server.client(), duckBrowser = noBrowser)
            val web = WebSettings(tavilyKey = "t", instance = "https://searx.example.org")
            val out = call(search(access, web), "kotlin release")
            assertEquals(listOf("api.tavily.com", "searx.example.org", "lite.duckduckgo.com"), hosts())
            assertTrue(out.toString(), out.toString().contains("lite.example.org"))
        }
    }

    @Test fun anEngineThatFailedTwiceIsSkippedForTheRestOfTheAnswer() = runBlocking {
        MockWebServer().use { server ->
            server.serve { host -> if (host == "lite.duckduckgo.com") MockResponse().setBody(liteHit) else MockResponse().setResponseCode(503) }
            val access = WebAccess(server.client(), server.client(), duckBrowser = noBrowser)
            // No paid allowance: every search of the answer may use Tavily.
            val tool = search(access, WebSettings(engine = "Tavily", tavilyKey = "t", paidSearches = 0))
            listOf("first query", "second query", "third query", "fourth query").forEach { call(tool, it) }
            assertEquals(2, hosts().count { it == "api.tavily.com" })
            assertEquals(4, hosts().count { it == "lite.duckduckgo.com" })
            // A new answer tries it again.
            call(search(access, WebSettings(engine = "Tavily", tavilyKey = "t", paidSearches = 0)), "fifth query")
            assertEquals(3, hosts().count { it == "api.tavily.com" })
        }
    }

    @Test fun whenNoEngineAnswersTheModelGetsSearchUnavailable() = runBlocking {
        MockWebServer().use { server ->
            server.serve { MockResponse().setResponseCode(503) }
            val access = WebAccess(server.client(), server.client(), duckBrowser = noBrowser)
            val out = call(search(access, WebSettings(tavilyKey = "t", instance = "https://searx.example.org")), "kotlin release")
            assertEquals("""{"error":"search unavailable"}""", out.toString())
            assertEquals(listOf("api.tavily.com", "searx.example.org", "lite.duckduckgo.com"), hosts())
        }
        // A chain that hangs ends at the deadline with the same answer, not with a hung call.
        val stuck = WebAccess(OkHttpClient(), OkHttpClient(), duckHttp = { _, _ -> delay(60_000); "" }, duckBrowser = noBrowser)
        val started = System.currentTimeMillis()
        val out = call(search(stuck, WebSettings(), deadline = 300), "kotlin release")
        assertEquals("""{"error":"search unavailable"}""", out.toString())
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test fun theSettingsProbeAsksOnlyTheChosenEngine() = runBlocking {
        MockWebServer().use { server ->
            server.serve { host -> if (host == "lite.duckduckgo.com") MockResponse().setBody(liteHit) else MockResponse().setResponseCode(429) }
            val access = WebAccess(server.client(), server.client(), duckBrowser = noBrowser)
            val error = runCatching { access.search(WebSettings(engine = "SearXNG", instance = "https://searx.example.org"), "Android") }
            assertEquals(429, (error.exceptionOrNull() as dev.kento.data.net.ProviderIssue).status)
            assertEquals(listOf("searx.example.org"), hosts())
        }
    }
}
