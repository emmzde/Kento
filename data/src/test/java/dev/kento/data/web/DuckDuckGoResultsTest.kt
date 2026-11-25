package dev.kento.data.web

import dev.kento.data.settings.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class DuckDuckGoResultsTest {
    private val base = "https://lite.duckduckgo.com/lite/"
    private fun results(html: String) = (duckDuckGoPage(html, base) as DuckPage.Results).hits

    @Test fun readsLiteRowsAndDecodesOnlyTheRedirectParameter() {
        val rows = """<table><tr><td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa%3Fx%3D1%252B2&amp;rut=abc">A &amp; B</a></td></tr>
            <tr><td class="result-snippet">First <b>snippet</b></td></tr>
            <tr><td><a class="result-link" href="https://example.org/a?x=1%2B2">Duplicate</a></td></tr>
            <tr><td><a class="result-link" href="javascript:void(0)">Skip</a></td></tr></table>"""
        assertEquals(listOf(SearchHit("A & B", "https://example.org/a?x=1%2B2", "First snippet")), results(rows))
    }

    @Test fun readsHtmlVariantAndSkipsAds() {
        val html = """<div class="result result--ad"><a href="https://ad.example" class="result__a">Ad</a></div>
            <div class="result"><a href="https://example.org/guide" class="result__a">Guide</a><div class="result__snippet">Details</div></div>"""
        assertEquals(listOf(SearchHit("Guide", "https://example.org/guide", "Details")), results(html))
    }

    @Test fun distinguishesEmptyResultsChallengesAndUnexpectedMarkup() {
        assertTrue(results("""<p class="no-results">Nothing found</p>""").isEmpty())
        assertEquals(DuckPage.Verification, duckDuckGoPage("""<form id="challenge-form"/>""", base))
        assertEquals(DuckPage.Unknown, duckDuckGoPage("<html>Unexpected</html>", base))
    }

    @Test fun defaultEngineUsesOnlyDeviceSearchAndPreservesExplicitChoice() = runBlocking {
        val access = WebAccess(OkHttpClient(), OkHttpClient(), duckHttp = lite { q, _ -> listOf(SearchHit(q, "https://example.org", "")) },
            duckBrowser = noBrowser)
        assertEquals("запрос", access.search(WebSettings(), "запрос").single().title)
        val saved = """{"profiles":[{"id":"initial"}],"web":{"engine":"Tavily","tavilyKey":"test"}}"""
        assertEquals("Tavily", decodeBook(saved).web.engine)
    }
}
