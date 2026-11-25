package dev.kento.data.web

import dev.kento.data.settings.WebSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WebToolsTest {
    private val today = LocalDate.of(2026, 11, 20)

    @Test fun pageBecomesCleanMarkdown() {
        val html = """<html><head><title>Guide</title><meta property="article:published_time" content="2026-10-02T08:00:00Z"></head>
            <body><nav>Home | Blog</nav><header>Site header</header>
            <main><h1>Install</h1><p>Run the <code>setup</code> tool first.</p>
            <ul><li>Fast</li><li>Small<ul><li>Really</li></ul></li></ul>
            <pre>val x = 1
  indented()</pre>
            <table><tr><th>Name</th><th>Size</th></tr><tr><td>Kento</td><td>20 MB</td></tr></table>
            <script>track()</script></main><footer>© 2026</footer></body></html>"""
        val page = extractPage(Jsoup.parse(html, "https://example.org/guide"), "https://example.org/guide".toHttpUrl())
        val text = page.text
        assertTrue(text, text.startsWith("# Install"))
        assertTrue(text.contains("Run the `setup` tool first."))
        assertTrue(text.contains("- Fast\n- Small\n  - Really"))
        assertTrue(text.contains("```\nval x = 1\n  indented()\n```"))
        assertTrue(text.contains("| Name | Size |\n| --- | --- |\n| Kento | 20 MB |"))
        for (junk in listOf("Home | Blog", "Site header", "track()", "© 2026")) assertFalse(junk, text.contains(junk))
        assertEquals("2026-10-02T08:00:00Z", page.date)
    }

    @Test fun datesBecomeIsoDays() {
        assertEquals("2026-10-02", publishedDate("2026-10-02T08:00:00Z", today))
        assertEquals("2026-11-17", publishedDate("3 days ago", today))
        assertEquals("2025-03-02", publishedDate("Mar 2, 2025", today))
        assertNull(publishedDate(null, today))
    }

    @Test fun searchReturnsUrlAndPublishedForEveryResult() = runBlocking {
        val access = object : SearchAccess {
            var seen: SearchQuery? = null
            override suspend fun search(settings: WebSettings, query: String) = error("unused")
            override suspend fun searchRound(settings: WebSettings, queries: List<SearchQuery>): List<SearchResult> {
                seen = queries.single()
                return listOf(SearchResult("test", listOf(
                    SearchHit("Kotlin 2.2 released", "https://blog.jetbrains.com/kotlin/2.2", "Kotlin 2.2 is out", "2 weeks ago"),
                    SearchHit("Kotlin release notes", "https://kotlinlang.org/docs/whatsnew22.html", "What's new in Kotlin 2.2")
                )))
            }
            override suspend fun read(hit: SearchHit, question: String) = error("unused")
        }
        val search = WebTools(access, WebSettings(), 8_000) { today }.all().first { it.name == "search_web" }
        val out = Json.parseToJsonElement(search.run(buildJsonObject {
            put("query", "kotlin 2.2 release"); putJsonArray("domains") { add("kotlinlang.org"); add("blog.jetbrains.com"); add("not a domain") }
        }).text).jsonObject
        assertEquals(listOf("kotlinlang.org", "blog.jetbrains.com"), access.seen!!.domains)
        assertNull(access.seen!!.freshness)
        search.run(buildJsonObject { put("query", "bitcoin price"); put("freshness", "day") })
        assertEquals("day", access.seen!!.freshness)
        search.run(buildJsonObject { put("query", "bitcoin price"); put("freshness", "yesterday") })
        assertNull(access.seen!!.freshness)
        val results = out.getValue("results").jsonArray.map { it.jsonObject }
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.containsKey("url") && it.containsKey("published") })
        assertTrue(results.any { it.getValue("published").jsonPrimitive.content == "2026-11-06" })
        assertTrue(results.any { it.getValue("published").jsonPrimitive.content == "unknown" })
    }

    @Test fun unreadablePageIsReportedAsError() = runBlocking {
        val access = object : SearchAccess {
            override suspend fun search(settings: WebSettings, query: String) = emptyList<SearchHit>()
            override suspend fun read(hit: SearchHit, question: String): ReadPage = throw java.io.IOException("403")
        }
        val read = WebTools(access, WebSettings(), 8_000).all().first { it.name == "read_webpage" }
        val out = Json.parseToJsonElement(read.run(buildJsonObject { put("url", "https://example.org/a") }).text).jsonObject
        assertEquals("Page unavailable or blocked", out.getValue("error").jsonPrimitive.content)
    }

    @Test fun readingStopsWhenTheAnswerBudgetIsSpent() = runBlocking {
        val access = object : SearchAccess {
            override suspend fun search(settings: WebSettings, query: String) = emptyList<SearchHit>()
            override suspend fun read(hit: SearchHit, question: String) =
                ReadPage(hit.url, "Long", (1..400).joinToString("\n\n") { "Paragraph $it with some words in it." })
        }
        val read = WebTools(access, WebSettings(), 4_000, totalBytes = 6_000).all().first { it.name == "read_webpage" }
        val first = read.run(buildJsonObject { put("url", "https://example.org/a") }).text
        assertTrue(first.startsWith("# Long"))
        assertTrue(first.toByteArray().size <= 4_300)
        // About 2 KB are left: less than the next page needs, so the model is told to answer.
        val second = read.run(buildJsonObject { put("url", "https://example.org/b") }).text
        assertTrue(second, second.startsWith("# Long") || second.contains("budget"))
        val third = read.run(buildJsonObject { put("url", "https://example.org/c") }).text
        assertTrue(third, third.contains("reading budget"))
    }

    @Test fun longPagesAreCutToTheFocus() {
        val filler = (1..60).joinToString("\n\n") { "Background paragraph $it about the history of money and markets." }
        val page = "# Bitcoin\n\n" + filler + "\n\n## Market data\n\nCurrent price: \$58,237, 24h change -6.4%.\n\n" + filler
        val cut = focusedBlocks(page, "current price 24h change", 600)
        assertTrue(cut, cut.startsWith("# Bitcoin"))
        assertTrue(cut, cut.contains("Current price: \$58,237"))
        assertTrue(cut, cut.contains("## Market data"))
        assertTrue(cut.contains("[…]"))
        assertTrue(cut.toByteArray().size <= 600)
        // No focus: the top of the page, as before.
        assertEquals(leadingBlocks(page, 600), focusedBlocks(page, null, 600))
    }

    @Test fun readPagesBecomeTheAnswerSources() = runBlocking {
        val access = object : SearchAccess {
            override suspend fun search(settings: WebSettings, query: String) = emptyList<SearchHit>()
            override suspend fun read(hit: SearchHit, question: String) = ReadPage(hit.url, "Live price", "Price is 58k today, says the page.")
        }
        val tools = WebTools(access, WebSettings(), 8_000)
        tools.all().first { it.name == "read_webpage" }.run(buildJsonObject { put("url", "https://example.org/btc") })
        assertEquals(listOf(dev.kento.data.db.Source("Live price", "https://example.org/btc")), tools.sources("answer"))
    }

    @Test fun longPagesAreCutBetweenBlocks() {
        val text = (1..50).joinToString("\n\n") { "Paragraph $it has a few words in it." }
        val cut = leadingBlocks(text, 200)
        assertTrue(cut.endsWith("words in it."))
        assertTrue(cut.toByteArray().size <= 200)
    }

    /**
     * A page_excerpt is the page cut to the query, as read_webpage cuts it to its focus: the opening block and the
     * blocks about the query, deep in the page too, up to PEEK_BYTES. The old 600 characters around the query words
     * sent models to read the same pages in their second round.
     */
    @Test fun pageExcerptCarriesTheBlocksAboutTheQuery() = runBlocking {
        val filler = (1..30).joinToString("\n\n") { "Paragraph $it about the history of the company and its offices." }
        val page = "# Phone review\n\n$filler\n\n## Battery\n\nThe battery lasted 31 hours of video playback in our test.\n\n$filler"
        val access = object : SearchAccess {
            override suspend fun search(settings: WebSettings, query: String) = error("unused")
            override suspend fun searchRound(settings: WebSettings, queries: List<SearchQuery>) =
                listOf(SearchResult("test", listOf(SearchHit("Phone review: battery", "https://review.example.org/phone", "battery test"))))
            override suspend fun read(hit: SearchHit, question: String) = error("unused")
            override suspend fun peek(hit: SearchHit) = ReadPage(hit.url, "Phone review", page, retrieved = 1_795_000_000_000L)
        }
        val search = WebTools(access, WebSettings(), 8_000, totalBytes = 100_000, peek = 3) { today }.all().first { it.name == "search_web" }
        val result = Json.parseToJsonElement(search.run(buildJsonObject { put("query", "phone battery video playback") }).text)
            .jsonObject.getValue("results").jsonArray.single().jsonObject
        val excerpt = result.getValue("page_excerpt").jsonPrimitive.content
        assertTrue(excerpt, excerpt.startsWith("# Phone review"))
        assertTrue(excerpt, excerpt.contains("The battery lasted 31 hours of video playback in our test."))
        assertTrue(excerpt.length.toString(), excerpt.toByteArray().size <= PEEK_BYTES)
        assertEquals("2026-11-18 11:06 UTC (live page)", result.getValue("page_retrieved").jsonPrimitive.content)
    }
}
