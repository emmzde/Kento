package dev.kento.data.web

import dev.kento.data.settings.Settings
import dev.kento.data.settings.WebSettings
import org.junit.Assert.*
import org.junit.Test

/**
 * Research is only the factory of the web tools now: the old pipeline that rewrote
 * the question, searched in rounds and ranked pages for it (collectSources) is gone, and its tests with it.
 */
class ResearchTest {
    private val access = object : SearchAccess {
        override suspend fun search(settings: WebSettings, query: String) = emptyList<SearchHit>()
        override suspend fun read(hit: SearchHit, question: String) = ReadPage(hit.url, "", "")
    }

    @Test fun makesTheTwoWebToolsAndNothingElse() {
        val tools = Research(access).tools(WebSettings(), Settings(model = "m")).all().map { it.name }
        assertEquals(listOf("search_web", "read_webpage"), tools)
        val methods = Research::class.java.declaredMethods.map { it.name }.filterNot { it.contains('$') }.toSet()
        assertEquals(setOf("tools"), methods)
    }
}
