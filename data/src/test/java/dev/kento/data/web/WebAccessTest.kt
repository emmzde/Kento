package dev.kento.data.web

import dev.kento.data.net.ProviderIssue
import dev.kento.data.settings.WebSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebAccessTest {
    @Test fun selectsExaForDomainsAndAsksSerperOnlyAfterBraveFails() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path!!.substringBefore('?')) {
                    "/api.search.brave.com" -> MockResponse().setResponseCode(429)
                    "/api.exa.ai" -> MockResponse().setBody("""{"results":[
                        {"url":"https://community.example.org/thread?utm_source=ad","title":"${"A".repeat(140)}","publishedDate":"2024-04-02"},
                        {"url":"https://example.org.invalid/thread","title":"wrong domain"}]}""")
                    "/google.serper.dev" -> MockResponse().setBody("""{"organic":[
                        {"link":"https://example.net/notes","title":"Android release notes","snippet":"${"S".repeat(550)}"}]}""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val destination = server.url("/" + request.url.host).newBuilder().encodedQuery(request.url.encodedQuery).build()
                chain.proceed(request.newBuilder().url(destination).build())
            }.build()
            val access = WebAccess(client, OkHttpClient(), duckHttp = { _, _ -> error("An available provider makes fallback unnecessary") },
                duckBrowser = noBrowser)
            val settings = WebSettings(braveKey = "brave-key", serperKey = "serper-key", exaKey = "exa-key")
            val queries = listOf(SearchQuery("Android release"), SearchQuery("Android discussions", listOf("example.org")))
            val hits = access.searchRound(settings, queries).flatMap { it.hits }
            assertEquals(setOf("https://example.net/notes", "https://community.example.org/thread"), hits.map { it.url }.toSet())
            assertEquals(120, hits.single { it.date != null }.title.length)
            assertEquals(500, hits.single { it.date == null }.excerpt.length)
            // One paid API per query: Exa for the one with domains, Brave for the other, Serper only because Brave failed.
            assertEquals(3, server.requestCount)
            val requests = List(3) { server.takeRequest(2, TimeUnit.SECONDS)!! }.groupBy { it.path!!.substringBefore('?') }
            val exa = requests.getValue("/api.exa.ai").single()
            val exaBody = Json.parseToJsonElement(exa.body.readUtf8()).jsonObject
            assertEquals("exa-key", exa.getHeader("x-api-key"))
            assertEquals("example.org", exaBody.getValue("includeDomains").jsonArray.single().jsonPrimitive.content)
            assertEquals("Android discussions", exaBody.getValue("query").jsonPrimitive.content)
            assertEquals("serper-key", requests.getValue("/google.serper.dev").single().getHeader("X-API-KEY"))
            assertEquals("brave-key", requests.getValue("/api.search.brave.com").single().getHeader("X-Subscription-Token"))
        }
    }

    @Test fun freshnessReachesEveryEngine() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.path!!.substringBefore('?')) {
                    "/api.search.brave.com" -> MockResponse().setBody("""{"type":"search","web":{"results":[]}}""")
                    "/google.serper.dev" -> MockResponse().setBody("""{"searchParameters":{},"organic":[]}""")
                    else -> MockResponse().setBody("""{"results":[]}""")
                }
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val destination = server.url("/" + request.url.host).newBuilder().encodedQuery(request.url.encodedQuery).build()
                chain.proceed(request.newBuilder().url(destination).build())
            }.build()
            val periods = mutableListOf<String?>()
            val access = WebAccess(client, OkHttpClient(), duckHttp = lite { _, period -> periods += period; emptyList() }, duckBrowser = noBrowser)
            access.searchRound(WebSettings(braveKey = "b", serperKey = "s"), listOf(SearchQuery("bitcoin price", freshness = "day")))
            val requests = List(2) { server.takeRequest(2, TimeUnit.SECONDS)!! }.associateBy { it.path!!.substringBefore('?') }
            assertEquals("pd", requests.getValue("/api.search.brave.com").requestUrl!!.queryParameter("freshness"))
            val serper = Json.parseToJsonElement(requests.getValue("/google.serper.dev").body.readUtf8()).jsonObject
            assertEquals("qdr:d", serper.getValue("tbs").jsonPrimitive.content)
            // Brave came back empty, so Serper was asked, then DuckDuckGo with the same period.
            assertEquals(listOf<String?>("d"), periods)

            access.searchRound(WebSettings(tavilyKey = "t"), listOf(SearchQuery("ai news", freshness = "week")))
            val tavily = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            assertEquals("week", tavily.getValue("time_range").jsonPrimitive.content)
        }
    }

    @Test fun everyQueryFallsBackToDuckDuckGoWhenItsPaidRequestFails() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
            val failedApi = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
            }.build()
            val fallback = java.util.concurrent.CopyOnWriteArrayList<String>()
            val access = WebAccess(failedApi, OkHttpClient(), duckHttp = lite { q, _ -> fallback += q; emptyList() }, duckBrowser = noBrowser)
            val results = access.searchRound(WebSettings(braveKey = "key"),
                listOf(SearchQuery("first"), SearchQuery("second")))
            assertEquals(setOf("first", "second"), fallback.toSet())
            assertEquals(listOf("DuckDuckGo Lite", "DuckDuckGo Lite"), results.map { it.engine })
            assertTrue(results.all { it.hits.isEmpty() })
        }
    }

    @Test fun rejectsSearxChallengesAndReportsHttpStatus() = runBlocking {
        MockWebServer().use { server ->
            val access = WebAccess(OkHttpClient(), OkHttpClient(), duckHttp = { _, _ -> error("Unexpected DuckDuckGo search") }, duckBrowser = noBrowser)
            val settings = WebSettings(engine = "SearXNG", instance = server.url("/").toString())
            server.enqueue(MockResponse().setBody("<html>Challenge</html>"))
            assertTrue(runCatching { access.search(settings, "check") }.exceptionOrNull() is ProviderIssue)
            server.enqueue(MockResponse().setResponseCode(429))
            assertEquals(429, (runCatching { access.search(settings, "check") }.exceptionOrNull() as ProviderIssue).status)
            server.enqueue(MockResponse().setBody("""{"results":[]}"""))
            assertTrue(access.search(settings, "check").isEmpty())
            server.enqueue(MockResponse().setBody("""{"results":[],"unresponsive_engines":[["google","timeout"]]}"""))
            assertEquals("Движки SearXNG не смогли получить результаты",
                runCatching { access.search(settings, "check") }.exceptionOrNull()?.message)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("json", request.requestUrl!!.queryParameter("format"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun readsForumTextAndPublicationDateWithoutSendingCredentialsToPage() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("https://api.tavily.com/search", request.url.toString())
                chain.proceed(request.newBuilder().url(server.url("/search")).build())
            }.build()
            val page = server.url("/article")
            server.enqueue(MockResponse().setBody("""{"results":[{"title":"Статья","url":"$page","content":"анонс"}]}"""))
            server.enqueue(MockResponse().setBody("User-agent: *\nDisallow: /downloads"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""
                <title>Статья</title><script type="application/ld+json">{"@graph":[{"datePublished":"2024-05-12"}]}</script>
                <nav>unrelated nav</nav><article><p>Текст статьи содержит подробности, которых нет в поисковой выдаче.</p></article>
                <div class="postbody">Отдельный комментарий форума содержит подтверждение.</div>"""))
            val access = WebAccess(client, OkHttpClient(), duckHttp = { _, _ -> error("Unexpected DuckDuckGo search") }, duckBrowser = noBrowser)
            val found = access.search(WebSettings(engine = "Tavily", tavilyKey = "test-search-only"), "данные")
            val read = access.read(found.single(), "данные")
            assertTrue(read.text.contains("подробности"))
            assertTrue(read.text.contains("Отдельный комментарий"))
            assertFalse(read.text.contains("unrelated nav"))
            assertEquals("2024-05-12", read.date)
            val search = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer test-search-only", search.getHeader("Authorization"))
            assertEquals("данные", Json.parseToJsonElement(search.body.readUtf8()).jsonObject.getValue("query").jsonPrimitive.content)
            val robots = server.takeRequest(2, TimeUnit.SECONDS)!!
            val article = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/robots.txt", robots.path)
            assertEquals("/article", article.path)
            assertNull(robots.getHeader("Authorization"))
            assertNull(article.getHeader("Authorization"))
        }
    }

    @Test fun blocksDisallowedPagesAndUsesTheLongestMatchingRobotsRule() = runBlocking {
        val robots = """User-agent: *
            |Disallow: /
            |
            |User-agent: Kento
            |Disallow: /private/*
            |Allow: /private/public$
            |Disallow: /%70rivate/public?
        """.trimMargin()
        assertTrue(robotsAllows(robots, "https://example.org/private/public".toHttpUrl()))
        assertFalse(robotsAllows(robots, "https://example.org/private/public?x".toHttpUrl()))
        assertFalse(robotsAllows(robots, "https://example.org/private/draft".toHttpUrl()))
        assertTrue(robotsAllows(robots, "https://example.org/other".toHttpUrl()))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(robots))
            val access = WebAccess(OkHttpClient(), OkHttpClient(), duckHttp = lite { _, _ -> emptyList() }, duckBrowser = noBrowser)
            val error = runCatching { access.read(SearchHit("Blocked", server.url("/private/draft").toString(), ""), "") }
            assertTrue(error.exceptionOrNull() is ProviderIssue)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rendererReceivesSmallSameOriginScriptsThroughThePageClient() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("User-agent: *\nDisallow: /blocked.js"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""
                <main></main><script src="/blocked.js"></script><script src="/article.js"></script>
                <script src="https://external.example/script.js"></script>
            """))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/javascript")
                .setBody("const content = 'Article text comes from a same-origin JavaScript bundle';"))
            val renderer = PageRenderer { html ->
                assertTrue(html.contains("const content"))
                assertFalse(html.contains("src=\"/article.js\""))
                "<main>Article text comes from a same-origin JavaScript bundle and is readable after rendering.</main>"
            }
            val access = WebAccess(OkHttpClient(), OkHttpClient(), renderer = renderer, duckHttp = lite { _, _ -> emptyList() }, duckBrowser = noBrowser)
            val page = access.read(SearchHit("JS article", server.url("/article").toString(), ""), "")
            assertTrue(page.text.contains("readable after rendering"))
            assertEquals(listOf("/robots.txt", "/article", "/article.js"),
                List(3) { server.takeRequest(2, TimeUnit.SECONDS)!!.path })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun readsArchivedCopyOfMissingPage() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                val url = original.url
                val target = server.url("/" + url.host + url.encodedPath).newBuilder().encodedQuery(url.encodedQuery).build()
                chain.proceed(original.newBuilder().url(target).build())
            }.build()
            val snapshot = "https://web.archive.org/web/20240101000000/https://example.org/dead"
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(410))
            server.enqueue(MockResponse().setBody(
                """{"archived_snapshots":{"closest":{"available":true,"status":"200","url":"$snapshot"}}}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain")
                .setBody("The archived copy preserves readable evidence from before the original page went away."))
            val access = WebAccess(OkHttpClient(), client, duckHttp = lite { _, _ -> emptyList() }, duckBrowser = noBrowser)
            val read = access.read(SearchHit("Archived story", "https://example.org/dead", ""), "")
            assertEquals(snapshot, read.url)
            assertNull(read.date)
            assertTrue(read.text.contains("readable evidence"))
            repeat(2) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            val api = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/archive.org/wayback/available", api.requestUrl!!.encodedPath)
            assertEquals("https://example.org/dead", api.requestUrl!!.queryParameter("url"))
        }
    }
}
