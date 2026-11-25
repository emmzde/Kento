package dev.kento.data.web

import dev.kento.data.settings.WebSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** One answer, one paid search: Tavily brings 20 full pages, later searches use DuckDuckGo and those pages. */
class PaidSearchTest {
    private fun tavily(server: MockWebServer, bodies: CopyOnWriteArrayList<JsonObject>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/page")) return MockResponse().setResponseCode(500)
                bodies += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val rows = (1..20).joinToString(",") { i ->
                    val raw = "# Kotlin release $i\n\nKotlin 2.$i brings coroutines changes and compiler speedups. " +
                        "The changelog of version $i lists K2 improvements and Compose updates. ".repeat(8)
                    """{"title":"Kotlin $i","url":"https://site$i.example.org/kotlin-$i","content":"Kotlin $i notes","raw_content":${JsonPrimitive(raw)}}"""
                }
                return MockResponse().setBody("""{"results":[$rows]}""")
            }
        }
    }

    private fun client(server: MockWebServer) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val target = if (request.url.host == "api.tavily.com") server.url("/search") else server.url("/page" + request.url.encodedPath)
        chain.proceed(request.newBuilder().url(target).build())
    }.build()

    @Test fun oneAnswerMakesOnePaidRequestAndReadsItsPagesForFree() = runBlocking {
        MockWebServer().use { server ->
            val bodies = CopyOnWriteArrayList<JsonObject>()
            tavily(server, bodies)
            val duck = CopyOnWriteArrayList<String>()
            val access = WebAccess(client(server), client(server), duckHttp = lite { q, _ ->
                duck += q
                listOf(SearchHit("Other result", "https://other.example.net/$q".replace(' ', '-'), "something about $q"))
            }, duckBrowser = noBrowser)
            val web = WebSettings(engine = "Tavily", tavilyKey = "test-only", paidSearches = 1)
            val tools = WebTools(access, web, pageBytes = 8_000, totalBytes = 200_000).all()
            val search = tools.first { it.name == "search_web" }
            val read = tools.first { it.name == "read_webpage" }
            // The model's first step: four searches at once, as the research rules ask.
            val queries = listOf("kotlin release changelog", "kotlin compiler speedups", "compose updates kotlin", "kotlin coroutines changes")
            val results = queries.map { q -> async { search.run(buildJsonObject { put("query", q) }).text } }.awaitAll()
            assertEquals("Exactly one paid request for the whole answer", 1, bodies.size)
            assertEquals(20, bodies.single().getValue("max_results").jsonPrimitive.int)
            assertEquals("markdown", bodies.single().getValue("include_raw_content").jsonPrimitive.content)
            assertEquals(3, duck.size)
            // Free searches also surface pages the paid search already brought.
            results.drop(1).forEach { assertTrue(it, it.contains("example.org/kotlin-")) }
            // A site that does not give its page (the page server answers 500 to anything): the copy among the twenty
            // pages of the paid search stands in, without another paid request.
            val page = read.run(buildJsonObject { put("url", "https://site17.example.org/kotlin-17") }).text
            assertTrue(page, page.contains("Kotlin 2.17"))
            assertEquals(1, bodies.size)
        }
    }

    /**
     * The model sees when page text was taken, and gets the site's page whenever the site gives it: Tavily's copy (age
     * unknown, marked so) only stands in for a page the site refuses. A search for live data (freshness day or week)
     * does not ask Tavily for copies at all. On the bench a copy four hours old sat next to the live page and a model
     * picked its stale bitcoin price.
     */
    @Test fun pagesSayWhenTheirTextWasTakenAndTheSiteComesBeforeTheEnginesCopy() = runBlocking {
        MockWebServer().use { server ->
            val bodies = CopyOnWriteArrayList<JsonObject>()
            val siteUp = java.util.concurrent.atomic.AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.endsWith("/robots.txt")) return MockResponse().setResponseCode(404)
                    if (request.path!!.startsWith("/page")) return if (!siteUp.get()) MockResponse().setResponseCode(503)
                        else MockResponse().setHeader("Content-Type", "text/html")
                            .setBody("<article><p>Bitcoin trades at 59,500 dollars, down four percent in a day. ".repeat(3) + "</p></article>")
                    bodies += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val raw = JsonPrimitive("# Bitcoin\n\nBitcoin trades at 60,400 dollars, down 1.5 percent. ".repeat(10))
                    return MockResponse().setBody("""{"results":[{"title":"BTC","url":"https://prices.example.org/btc","content":"bitcoin price now","raw_content":$raw}]}""")
                }
            }
            val access = WebAccess(client(server), client(server), duckHttp = lite { _, _ -> emptyList() }, duckBrowser = noBrowser)
            val web = WebSettings(engine = "Tavily", tavilyKey = "test-only", paidSearches = 0)
            val tools = WebTools(access, web, pageBytes = 8_000, totalBytes = 200_000, peek = 1).all()
            val search = tools.first { it.name == "search_web" }
            val read = tools.first { it.name == "read_webpage" }

            val stamp = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2} UTC \(live page\)""")

            // The site refuses: the engine's copy stands in, marked as such, in the excerpt and in the page.
            val blocked = Json.parseToJsonElement(search.run(buildJsonObject { put("query", "bitcoin price") }).text).jsonObject
            assertEquals("markdown", bodies.last().getValue("include_raw_content").jsonPrimitive.content)
            val copy = blocked.getValue("results").jsonArray.single().jsonObject
            assertTrue(copy.toString(), copy.getValue("page_excerpt").jsonPrimitive.content.contains("60,400"))
            assertEquals("unknown (search engine's copy)", copy.getValue("page_retrieved").jsonPrimitive.content)
            val copied = read.run(buildJsonObject { put("url", "https://prices.example.org/btc") }).text
            assertTrue(copied, copied.contains("Retrieved: unknown (search engine's copy)"))

            // The site answers: its page wins over the copy that is still at hand, in the excerpt and in the page.
            siteUp.set(true)
            val open = Json.parseToJsonElement(search.run(buildJsonObject { put("query", "bitcoin price usd") }).text).jsonObject
            val site = open.getValue("results").jsonArray.single().jsonObject
            assertTrue(site.toString(), site.getValue("page_excerpt").jsonPrimitive.content.contains("59,500"))
            assertTrue(site.toString(), stamp.matches(site.getValue("page_retrieved").jsonPrimitive.content))
            val page = read.run(buildJsonObject { put("url", "https://prices.example.org/btc") }).text
            assertTrue(page, page.contains("59,500") && !page.contains("60,400"))

            // A search for today's figure does not ask Tavily for copies.
            search.run(buildJsonObject { put("query", "bitcoin price now"); put("freshness", "day") })
            assertFalse("include_raw_content" in bodies.last())
            assertEquals("day", bodies.last().getValue("time_range").jsonPrimitive.content)
        }
        // A page read from the site carries the moment of the read.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<article><p>${"Release notes of version two. ".repeat(10)}</p></article>"))
            val access = WebAccess(OkHttpClient(), OkHttpClient(), duckHttp = lite { _, _ -> emptyList() }, duckBrowser = noBrowser)
            val read = WebTools(access, WebSettings(), pageBytes = 8_000).all().first { it.name == "read_webpage" }
            val before = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
            val text = read.run(buildJsonObject { put("url", server.url("/notes").toString()) }).text
            val stamp = Regex("""Retrieved: (\d{4}-\d{2}-\d{2} \d{2}:\d{2}) UTC \(live page\)""").find(text)?.groupValues?.get(1)
            assertNotNull(text, stamp)
            val at = java.time.LocalDateTime.parse(stamp!!.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC)
            assertTrue("$at vs $before", !at.isBefore(before) && at.isBefore(before.plusSeconds(180)))
        }
    }

    @Test fun withoutTheLimitEverySearchIsPaid() = runBlocking {
        MockWebServer().use { server ->
            val bodies = CopyOnWriteArrayList<JsonObject>()
            tavily(server, bodies)
            val access = WebAccess(client(server), client(server), duckHttp = { _, _ -> error("DuckDuckGo is not needed") }, duckBrowser = noBrowser)
            val web = WebSettings(engine = "Tavily", tavilyKey = "test-only", paidSearches = 0)
            val search = WebTools(access, web, pageBytes = 8_000, totalBytes = 200_000).all().first { it.name == "search_web" }
            listOf("one query", "two query", "three query").forEach { search.run(buildJsonObject { put("query", it) }) }
            assertEquals(3, bodies.size)
        }
    }

    @Test fun duckDuckGoOnlySetupsDoNotWaitForAPaidSearch() = runBlocking {
        val access = WebAccess(OkHttpClient(), OkHttpClient(), duckHttp = lite { q, _ -> listOf(SearchHit("R", "https://r.example.com/${q.length}", "r")) },
            duckBrowser = noBrowser)
        val search = WebTools(access, WebSettings(), pageBytes = 8_000).all().first { it.name == "search_web" }
        val started = System.currentTimeMillis()
        listOf("a b", "c d e").map { q -> async { search.run(buildJsonObject { put("query", q) }) } }.awaitAll()
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }
}
