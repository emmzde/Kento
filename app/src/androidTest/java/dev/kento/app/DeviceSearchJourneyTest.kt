package dev.kento.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.kento.data.settings.*
import dev.kento.data.web.AndroidPageRenderer
import dev.kento.data.web.DuckDuckGoSearch
import dev.kento.data.web.ReadPage
import dev.kento.data.web.WebAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeviceSearchJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()

    @Test fun stopCancelsAnOffscreenSearch() = runBlocking {
        app.searchHtml = """<div class="no-results">No results</div>"""
        try {
            withContext(Dispatchers.Main) {
                val job = launch(start = CoroutineStart.UNDISPATCHED) { app.web.search(WebSettings(), "Android") }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        } finally { app.searchHtml = null }
    }

    @Test fun manualCheckReturnsToResultsInSameWebView() = searchJourney(live = false)

    @Test fun queryCacheSurvivesANewAccessInstance() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val browser = DuckDuckGoSearch(app) { view, url ->
            calls.incrementAndGet()
            view.loadDataWithBaseURL(url, """<a class="result-link" href="https://example.org/cache">Cached</a>""",
                "text/html", "utf-8", null)
        }
        val query = "cache fixture " + java.util.UUID.randomUUID()
        // The HTTP request meets a bot check, so each search would open the tab: the cache keeps it to one.
        val check: suspend (String, String?) -> String = { _, _ -> """<form id="challenge-form"></form>""" }
        val first = WebAccess(browser, app.webCache, duckHttp = check).search(WebSettings(), query)
        val second = WebAccess(browser, dev.kento.data.web.WebCache(app.database.web()), duckHttp = check).search(WebSettings(), query)
        assertEquals(first, second)
        assertEquals("Cached", second.single().title)
        assertEquals(1, calls.get())
    }

    @Test fun inlineJavascriptCanRenderPageText() = runBlocking {
        val html = """<main id="content"></main><script>
            document.getElementById("content").textContent = "Visible after rendering";
            </script><script src="https://example.org/never-loaded.js"></script>"""
        val rendered = AndroidPageRenderer(app).render(html)
        assertTrue(rendered.orEmpty().contains("""id="content">Visible after rendering"""))
    }

    @Test fun persistentCookieIsAvailableOnNextLaunch() = runBlocking {
        // Run the two phases in separate instrumentation processes to check a cold start.
        val phase = InstrumentationRegistry.getArguments().getString("cookiePhase")
        suspend fun fixture(script: String) {
            app.searchHtml = """<a id="result" href="https://example.org/cookie">Session</a>
                <script>$script</script>"""
            // The WebView tab itself: it keeps DuckDuckGo's cookies for the checks it is opened for.
            assertEquals("Session", app.searchBrowser.search("cookie fixture").single().title)
        }
        try {
            if (phase != "read") fixture("""
                document.cookie = 'kento_qa_durable=ready; Path=/lite/; Max-Age=600; Secure';
                document.getElementById('result').className = 'result-link';
            """)
            if (phase != "write") fixture("""
                if (document.cookie.split('; ').includes('kento_qa_durable=ready')) {
                    document.getElementById('result').className = 'result-link';
                }
                document.cookie = 'kento_qa_durable=; Path=/lite/; Max-Age=0; Secure';
            """)
        } finally { app.searchHtml = null }
    }

    @Test fun liveSearchCompletesOrOffersManualCheck() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicApis") == "true")
        searchJourney(live = true)
    }

    private fun searchJourney(live: Boolean) {
        val previous = runBlocking { app.settings.profiles.first() }
        val question = "Для чего IANA резервирует example domains?"
        val page = ReadPage("https://example.org/qa", "IANA example domains", "Проверяемая цитата находится только в полном тексте локальной страницы.")
        if (!live) {
            app.searchHtml = """<form id="challenge-form"><label>Fixture verification</label></form>"""
            app.fixturePage = page
        }
        MockWebServer().use { server ->
            app.apiBase = server.url("/v1").toString()
            fun reply(text: String) = RefuselessSse.answer(text)
            fun call(id: String, name: String, arguments: JsonObject) = RefuselessSse.call(name, arguments.toString(), id = id)
            // Every Refuseless model calls tools: the model asks search_web, which goes to DuckDuckGo (the journey
            // checks its verification form), then reads the found page with read_webpage and answers.
            val s = Settings(model = "web-qa", catalogLoaded = true,
                catalog = listOf(dev.kento.data.settings.ModelOption("web-qa")),
                catalogVersion = dev.kento.data.settings.CATALOG_VERSION)
            server.enqueue(call("search_1", "search_web", buildJsonObject { put("query", "IANA example domains") }))
            if (!live) server.enqueue(call("read_1", "read_webpage", buildJsonObject { put("url", page.url) }))
            server.enqueue(reply("Прочитан источник [IANA example domains](https://example.org/qa)."))
            try {
                runBlocking { app.settings.updateBook { ProfileBook(listOf(s), s.id) } }
                ui.onNodeWithContentDescription("Сообщение").performClick()
                ui.waitUntil(10000) { ui.onAllNodesWithText("web-qa").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithContentDescription("Включить веб-поиск").performClick()
                ui.onNodeWithContentDescription("Сообщение").performTextInput(question)
                ui.onNodeWithContentDescription("Отправить").performClick()
                val chat = runBlocking { withTimeout(10000) {
                    app.db.history().first { rows -> rows.any { it.title == question } }.first { it.title == question }.id
                } }
                fun state() = runBlocking { app.db.recent(chat, 1).first().single().state }
                val checkTitle = "Проверка DuckDuckGo"
                ui.waitUntil(65000) {
                    ui.onAllNodesWithText(checkTitle).fetchSemanticsNodes().isNotEmpty() || state() != "active"
                }
                if (!live) {
                    val row = runBlocking { app.db.recent(chat, 1).first().single() }
                    assertTrue("No verification: ${row.state} ${row.issue}",
                        ui.onAllNodesWithText(checkTitle).fetchSemanticsNodes().isNotEmpty())
                    val view = app.searchBrowser.verification.value!!.view
                    assertTrue(ui.runOnIdle { view.isAttachedToWindow })
                    app.searchHtml = """<script>
                        sessionStorage.setItem("kento_qa_tab", "active");
                        document.cookie = "kento_qa_session=roundtrip; Max-Age=90; Path=/lite/; Secure";
                        </script><a class="result-link" href="${page.url}">IANA example domains</a>"""
                    ui.onNodeWithText("Вернуться к выдаче").performClick()
                } else if (app.searchBrowser.verification.value != null) {
                    ui.onNodeWithText("Стоп").performClick()
                    ui.waitUntil(10000) { state() == "stopped" && app.searchBrowser.verification.value == null }
                    android.util.Log.i("KentoSearchQA", "DuckDuckGo returned a live verification form; displayed and stopped.")
                    assertEquals(1, server.requestCount)
                    return
                }
                val reply = runBlocking { withTimeout(70000) {
                    app.db.recent(chat, 1).first { it.firstOrNull()?.state in setOf("done", "failed") }.single()
                } }
                assertEquals(reply.issue, "done", reply.state)
                assertTrue(reply.text.contains("Прочитан источник") && reply.text.contains("https://"))
                val requests = List(if (live) 2 else 3) { server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8() }
                val first = Json.parseToJsonElement(requests.first()).jsonObject
                val declared = first.getValue("tools").jsonArray.map {
                    it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
                }
                assertTrue(declared.toString(), "search_web" in declared && "read_webpage" in declared)
                val messages = Json.parseToJsonElement(requests.last()).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
                fun result(id: String) = messages.single { it["role"]?.jsonPrimitive?.content == "tool" &&
                    it["tool_call_id"]?.jsonPrimitive?.content == id }.getValue("content").jsonPrimitive.content
                val found = Json.parseToJsonElement(result("search_1")).jsonObject
                assertEquals("IANA example domains", found.getValue("query").jsonPrimitive.content)
                assertTrue("Нет результатов поиска", found.getValue("results").jsonArray.isNotEmpty())
                if (live) android.util.Log.i("KentoSearchQA", "Live DuckDuckGo search and reading succeeded.")
                else {
                    assertTrue(found.toString(), found.getValue("results").jsonArray.any { it.jsonObject.getValue("url").jsonPrimitive.content == page.url })
                    assertTrue("Страница не прочитана", result("read_1").contains(page.text))
                    // This local HTML fixture reads only a synthetic marker in the isolated QA app.
                    app.searchHtml = """<a id="next" href="${page.url}">Second search</a><script>
                        if (sessionStorage.getItem("kento_qa_tab") === "active" &&
                            document.cookie.split("; ").includes("kento_qa_session=roundtrip")) {
                            document.querySelector("#next").className = "result-link";
                        }
                        sessionStorage.removeItem("kento_qa_tab");
                        document.cookie = "kento_qa_session=; Max-Age=0; Path=/lite/; Secure";
                        </script>"""
                    // DuckDuckGo is asked over HTTP first and the tab opens only on a bot check, so the second search
                    // meets a check too: it then runs in the same tab, whose session survived the first one.
                    val check: suspend (String, String?) -> String = { _, _ -> """<form id="challenge-form"></form>""" }
                    assertEquals(page.url, runBlocking { WebAccess(app.searchBrowser, duckHttp = check).search(WebSettings(), "second query") }.single().url)
                    assertNull(app.searchBrowser.verification.value)
                }
            } finally {
                app.searchHtml = null
                app.fixturePage = null
                runBlocking {
                    app.settings.updateBook { previous }
                    app.db.history().first().filter { it.title == question }.forEach { app.db.delete(it.id) }
                }
            }
        }
    }
}
