package dev.kento.data.web

import dev.kento.core.text.tr

import dev.kento.data.net.ProviderIssue
import dev.kento.data.net.str
import dev.kento.data.net.strings
import dev.kento.data.net.validBase
import dev.kento.data.settings.AUTO_SEARCH
import dev.kento.data.settings.DUCKDUCKGO
import dev.kento.data.settings.WebSettings
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

@Serializable
data class SearchHit(val title: String, val url: String, @SerialName("snippet") val excerpt: String, val date: String? = null)

@Serializable
data class ReadPage(val url: String, val title: String, val text: String, val date: String? = null,
    /**
     * When Kento itself fetched the page (epoch ms), kept through the page cache. Null for text that came with search
     * results (Tavily `raw_content`): the engine's copy, taken at a time it does not report.
     */
    val retrieved: Long? = null)

/**
 * [freshness] limits results to the last "day", "week", "month" or "year" where the engine supports it; null means
 * any time. Live data (prices, scores, breaking news) needs it, because engines rank old evergreen pages first.
 */
data class SearchQuery(val text: String, val domains: List<String> = emptyList(), val freshness: String? = null,
    /**
     * Whether this query may use a paid API (Tavily, Brave, Serper, Exa). One answer spends at most
     * [WebSettings.paidSearches] of them; the rest go to free engines and to the pages the paid call already brought.
     */
    val paid: Boolean = true) {
    internal fun forKeywordEngine() = when (domains.size) {
        0 -> text
        1 -> "$text site:${domains.single()}"
        else -> text + domains.joinToString(" OR ", " (", ")") { "site:$it" }
    }
}

data class SearchResult(val engine: String, val hits: List<SearchHit>)

interface SearchAccess {
    suspend fun search(settings: WebSettings, query: String): List<SearchHit>
    suspend fun read(hit: SearchHit, question: String): ReadPage

    /** A quick look at a result while the search is still answering: no script rendering, a few seconds at most. */
    suspend fun peek(hit: SearchHit): ReadPage? = try { read(hit, "") } catch (_: IOException) { null }

    suspend fun searchRound(settings: WebSettings, queries: List<SearchQuery>): List<SearchResult> =
        queries.map { SearchResult(settings.engine, search(settings, it.forKeywordEngine())) }

    /** Full pages a search API already delivered with its results (Tavily raw content), newest first. */
    fun corpus(): List<ReadPage> = emptyList()
}

private const val BRAVE = "Brave"
private const val SERPER = "Serper"
private const val EXA = "Exa"
private const val SEARXNG = "SearXNG"
private const val TAVILY = "Tavily"
val FRESHNESS = listOf("day", "week", "month", "year")

/**
 * How long a result list stays reusable. Snippets of a fresh search go stale within minutes (prices, news), an
 * ordinary lookup within the hour; the old one-day cache served yesterday's price as "now".
 */
/** A search for live data (prices, scores, this week's news): its pages are read from the site, not from an engine's copy. */
internal fun live(freshness: String?) = freshness == "day" || freshness == "week"

internal fun queryTtl(freshness: String?) = when (freshness) {
    "day" -> 10 * 60_000L
    "week" -> 30 * 60_000L
    else -> 60 * 60_000L
}

/** Paid search API calls made by this process, per engine: diagnostics (KentoSearch log) and the benchmark. */
object SearchStats {
    val calls = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    internal fun count(engine: String) { calls.getOrPut(engine) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() }
    fun snapshot(): Map<String, Int> = calls.mapValues { it.value.get() }
}

/** Searches may reach a paid API (Tavily, Brave, Serper, Exa), so the per-answer allowance applies. */
internal fun WebSettings.usesPaidApi() = engine == TAVILY ||
    engine == AUTO_SEARCH && listOf(braveKey, serperKey, exaKey, tavilyKey).any(String::isNotBlank)

/** Paid APIs one query may try: the first configured one, and one more only when it fails or finds nothing. */
private const val PAID_TRIES = 2

/**
 * Engines one query tries, in order, until one returns results. Paid first, one API per query: Exa or Tavily for a
 * query restricted to domains (both filter sites themselves), otherwise Tavily (20 full pages per call), Brave,
 * Serper, Exa. Then the free ones, which need no key: SearXNG when an instance is set, and DuckDuckGo Lite.
 * A query past the paid allowance of the answer ([SearchQuery.paid] false) starts at the free ones.
 */
internal fun WebSettings.engines(query: SearchQuery): List<String> {
    val paid = when (engine) {
        AUTO_SEARCH -> (if (query.domains.isNotEmpty()) listOf(EXA, TAVILY, BRAVE, SERPER) else listOf(TAVILY, BRAVE, SERPER, EXA))
            .filter { key(it).isNotBlank() }.take(PAID_TRIES)
        TAVILY -> listOf(TAVILY)
        else -> emptyList()
    }
    val free = buildList {
        if (engine == SEARXNG || engine != DUCKDUCKGO && instance.isNotBlank()) add(SEARXNG)
        add(DUCKDUCKGO)
    }
    return (if (query.paid) paid else emptyList()) + free
}

private fun WebSettings.key(engine: String) = when (engine) {
    TAVILY -> tavilyKey
    BRAVE -> braveKey
    SERPER -> serperKey
    EXA -> exaKey
    else -> ""
}

/**
 * Engines that did not answer during one reply. WebTools runs its calls with one of these in the coroutine context:
 * an engine that failed twice is skipped until the reply ends, so a dead API or a blocked DuckDuckGo does not make
 * every later search of the reply wait for it again.
 */
class EngineStrikes : kotlin.coroutines.AbstractCoroutineContextElement(EngineStrikes) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<EngineStrikes>
    private val misses = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    internal fun miss(engine: String) { misses.getOrPut(engine) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() }
    internal fun out(engine: String) = (misses[engine]?.get() ?: 0) >= 2
}

/** DuckDuckGo's HTML-only search. A plain POST gets results; a GET from an app meets the bot check far more often. */
internal const val DUCK_LITE = "https://lite.duckduckgo.com/lite/"

/** The search tab of [DuckDuckGoSearch], counted apart in [SearchStats]: it runs only after DuckDuckGo asked for a check. */
const val DUCK_BROWSER = "DuckDuckGo WebView"

/** DuckDuckGo Lite over plain HTTP: the result page as HTML, the bot check included (it comes as HTTP 202). */
internal suspend fun liteHtml(client: OkHttpClient, query: String, period: String?): String {
    val form = okhttp3.FormBody.Builder().add("q", query.take(500)).apply { if (period != null) add("df", period) }.build()
    return webFetch(client, Request.Builder().url(DUCK_LITE).post(form).build(), 1_048_576).text()
}

/** [liteHtml] through the app's search client; journeys wrap it to serve fixture pages. */
suspend fun duckDuckGoLite(query: String, period: String?): String = liteHtml(webHttp, query, period)

class WebAccess internal constructor(private val searchClient: OkHttpClient, pageClient: OkHttpClient,
    private val cache: WebCache = WebCache(), renderer: PageRenderer? = null,
    /** Adds a free Wikipedia lookup next to the chosen engine; off in tests, which must not reach the network. */
    private val wikipedia: Boolean = false,
    /** DuckDuckGo Lite over HTTP: the keyword query and its `df` time filter (d, w, m, y or null) to the page HTML. */
    private val duckHttp: suspend (String, String?) -> String = { q, period -> liteHtml(searchClient, q, period) },
    /** The WebView tab with its verification window, used only when [duckHttp] met DuckDuckGo's bot check. */
    private val duckBrowser: suspend (String, String?) -> List<SearchHit>) : SearchAccess {
    constructor(browser: DuckDuckGoSearch, cache: WebCache = WebCache(), renderer: PageRenderer? = null,
        duckHttp: suspend (String, String?) -> String = ::duckDuckGoLite) :
        this(webHttp, publicPages, cache, renderer, wikipedia = true, duckHttp = duckHttp, duckBrowser = browser::search)

    private val pages = PageReader(pageClient, cache, renderer)
    // Peeks skip the WebView and keep their own short-lived cache, so a page that needs scripts is still read in
    // full when the model opens it.
    private val quickPages = PageReader(pageClient, WebCache(), null)
    private val duckLock = Mutex()
    private val pace = HostPace()
    private val searchSlots = Semaphore(4)
    private val made = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** Engine requests this instance made (cache hits excluded); [SearchStats] holds the same for the whole process. */
    fun calls(): Map<String, Int> = made.mapValues { it.value.get() }

    private fun count(engine: String) {
        SearchStats.count(engine)
        made.getOrPut(engine) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }

    /**
     * Pages that arrived inside search results (Tavily `raw_content`): the engine's copies, taken at a time it does not
     * report. They stand in for a page the site does not give (blocked, robots, errors) and feed the free searches of
     * the answer ([corpus]); a page the site does give is read from the site.
     */
    private val prefetched = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ReadPage>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReadPage>) = size > 80
    })
    private fun ready(url: String) = prefetched[cleanUrl(url)?.toString() ?: url]

    override fun corpus(): List<ReadPage> = synchronized(prefetched) { prefetched.values.toList().asReversed() }

    /**
     * The site first, the engine's copy only when the site fails. Copies first gave a model a bitcoin page four hours
     * old next to the live one, and it picked the old figure (search bench).
     */
    override suspend fun read(hit: SearchHit, question: String): ReadPage =
        try { pages.read(hit) } catch (e: IOException) { ready(hit.url) ?: throw e }

    override suspend fun peek(hit: SearchHit): ReadPage? = withTimeoutOrNull(4_500) {
        try { quickPages.read(hit) } catch (_: IOException) { null }
    } ?: ready(hit.url)

    /**
     * The first engine of the settings alone, as the settings probe (the SearXNG check) and fixtures see it: its own
     * error, no fallback, no Wikipedia supplement.
     */
    override suspend fun search(settings: WebSettings, query: String): List<SearchHit> {
        val first = SearchQuery(query)
        return answer(settings, first, settings.engines(first).take(1), null).hits
    }

    override suspend fun searchRound(settings: WebSettings, queries: List<SearchQuery>): List<SearchResult> = coroutineScope {
        if (queries.isEmpty()) return@coroutineScope emptyList()
        // Wikipedia answers "who / what is X" (artists, brands, aliases) better than any snippet, costs nothing and
        // needs no key. Its two best articles join the results of the main engine; not for news or live data.
        val encyclopedia = queries.filter { wikipedia && it.freshness !in setOf("day", "week") &&
            (it.domains.isEmpty() || it.domains.any { d -> d.endsWith("wikipedia.org") }) }
            .take(2).map { q -> async { try { wiki(q) } catch (_: IOException) { null } } }
        val strikes = currentCoroutineContext()[EngineStrikes]
        val answers = queries.map { q -> async {
            try { Result.success(answer(settings, q, settings.engines(q), strikes)) } catch (e: IOException) { Result.failure(e) }
        } }.awaitAll()
        val main = answers.mapNotNull { it.getOrNull() }
        val extra = encyclopedia.awaitAll().filterNotNull().filter { it.hits.isNotEmpty() }
        // Every engine failed for every query: the encyclopedia still answers what it can.
        if (main.isEmpty() && extra.isEmpty()) answers.first().getOrThrow()
        main + extra
    }

    /**
     * Tries [chain] in order: an engine that fails or finds nothing hands the query to the next one. The answer is the
     * results of the first engine that has some, else the empty answer of the first that replied, else the first failure.
     */
    private suspend fun answer(settings: WebSettings, query: SearchQuery, chain: List<String>, strikes: EngineStrikes?): SearchResult {
        var failure: IOException? = null
        var empty: SearchResult? = null
        for (engine in chain) {
            if (strikes?.out(engine) == true) continue
            val hits = try {
                if (engine == DUCKDUCKGO) duck(query) else searchSlots.withPermit { lookup(settings, engine, query) }
            } catch (e: IOException) {
                strikes?.miss(engine)
                failure = failure ?: e
                continue
            } catch (_: IllegalArgumentException) {
                strikes?.miss(engine)
                failure = failure ?: ProviderIssue(tr("Проверьте настройки {0}", engine))
                continue
            }
            if (hits.isNotEmpty()) return SearchResult(engine, hits)
            if (empty == null) empty = SearchResult(engine, hits)
        }
        return empty ?: throw failure ?: ProviderIssue(tr("Поиск недоступен"))
    }

    private suspend fun wiki(query: SearchQuery): SearchResult {
        val lang = if (hasCyrillic(query.text)) "ru" else "en"
        val hits = cache.cached<List<SearchHit>>("query", cacheKey("Wikipedia-$lang", query), 800, queryTtl(query.freshness)) {
            count("Wikipedia")
            val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query").addQueryParameter("list", "search")
                .addQueryParameter("srsearch", query.text.take(300)).addQueryParameter("srlimit", "2")
                .addQueryParameter("srprop", "snippet|timestamp").addQueryParameter("format", "json")
                .addQueryParameter("utf8", "1").build()
            val obj = webObject(webFetch(searchClient, Request.Builder().url(url).header("Accept", "application/json").build(), 262_144).text())
            val rows = ((obj?.get("query") as? JsonObject)?.get("search") as? JsonArray).orEmpty()
            rows.mapNotNull { row ->
                val o = row as? JsonObject ?: return@mapNotNull null
                val title = o.str("title") ?: return@mapNotNull null
                val page = "https://$lang.wikipedia.org/wiki/" + java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%2F", "/")
                SearchHit("$title — Wikipedia", page, Jsoup.parse(o.str("snippet").orEmpty()).text(), o.str("timestamp"))
            }.let { cleanHits(it, emptyList()) }
        }
        return SearchResult("Wikipedia", hits)
    }

    /**
     * DuckDuckGo Lite as a plain HTTP request: no browser, about a second. Only when DuckDuckGo answers with its bot
     * check does the search move to the WebView tab, where the user can pass the check.
     */
    private suspend fun duck(query: SearchQuery): List<SearchHit> =
        cache.cached<List<SearchHit>>("query", cacheKey(DUCKDUCKGO, query), 800, queryTtl(query.freshness)) {
            duckLock.withLock {
                pace.wait("lite.duckduckgo.com")
                val keywords = query.forKeywordEngine()
                val period = query.freshness?.take(1)
                count(DUCKDUCKGO)
                val html = duckHttp(keywords, period)
                val hits = when (val page = withContext(Dispatchers.Default) { duckDuckGoPage(html, DUCK_LITE) }) {
                    is DuckPage.Results -> page.hits
                    DuckPage.Verification -> {
                        count(DUCK_BROWSER)
                        duckBrowser(keywords, period)
                    }
                    DuckPage.Unknown -> throw ProviderIssue(tr("DuckDuckGo вернул страницу без распознаваемой выдачи"))
                }
                cleanHits(hits, query.domains)
            }
        }

    private suspend fun lookup(settings: WebSettings, engine: String, query: SearchQuery): List<SearchHit> {
        val namespace = if (engine == SEARXNG) engine + "\n" + settings.instance.trim() else engine
        return cache.cached("query", cacheKey(namespace, query), 800, queryTtl(query.freshness)) {
            val keywordQuery = query.forKeywordEngine()
            val fresh = query.freshness
            val request = when (engine) {
                BRAVE -> {
                    val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
                        .addQueryParameter("q", keywordQuery.take(400)).addQueryParameter("count", "8")
                        .addQueryParameter("search_lang", if (hasCyrillic(query.text)) "ru" else "en")
                        .apply { if (fresh != null) addQueryParameter("freshness", "p" + fresh.take(1)) }.build()
                    Request.Builder().url(url).header("X-Subscription-Token", settings.braveKey.trim())
                }
                SERPER -> post("https://google.serper.dev/search", "X-API-KEY", settings.serperKey.trim(), buildJsonObject {
                    put("q", keywordQuery)
                    put("num", 8)
                    put("hl", if (hasCyrillic(query.text)) "ru" else "en")
                    if (fresh != null) put("tbs", "qdr:" + fresh.take(1))
                })
                EXA -> post("https://api.exa.ai/search", "x-api-key", settings.exaKey.trim(), buildJsonObject {
                    put("query", query.text)
                    put("numResults", 8)
                    if (query.domains.isNotEmpty()) putJsonArray("includeDomains") { query.domains.forEach { add(it) } }
                    since(fresh)?.let { put("startPublishedDate", it) }
                    // Without contents Exa returns bare links; highlights are short, query-relevant passages.
                    putJsonObject("contents") { putJsonObject("highlights") { put("numSentences", 3) } }
                })
                TAVILY -> {
                    if (settings.tavilyKey.isBlank()) throw ProviderIssue(tr("Укажите ключ Tavily в настройках поиска"))
                    post("https://api.tavily.com/search", "Authorization", "Bearer " + settings.tavilyKey.trim(),
                        buildJsonObject {
                            // Tavily filters sites itself; a site: operator in the text would only blur the query.
                            put("query", query.text)
                            // One credit buys up to 20 results with their full text: the model then reads those pages
                            // without further requests, so a single search can feed a whole research.
                            put("max_results", 20)
                            put("search_depth", "basic")
                            // Not for live data: Tavily's copy of a page is hours old at times (on the bench a "−1.5%"
                            // bitcoin page while the market was down 4%), so those pages are read from the site.
                            if (!live(fresh)) put("include_raw_content", "markdown")
                            put("include_answer", false)
                            if (fresh != null) put("time_range", fresh)
                            if (query.domains.isNotEmpty()) putJsonArray("include_domains") { query.domains.forEach { add(it) } }
                        })
                }
                SEARXNG -> {
                    val base = settings.instance.trim().trimEnd('/')
                    if (!validBase(base)) throw ProviderIssue(tr("Проверьте адрес SearXNG"))
                    val endpoint = base.toHttpUrl()
                    val url = endpoint.newBuilder().apply {
                        if (endpoint.pathSegments.last() != "search") addPathSegment("search")
                        addQueryParameter("q", keywordQuery)
                        addQueryParameter("format", "json")
                        if (fresh != null) addQueryParameter("time_range", fresh)
                    }.build()
                    Request.Builder().url(url)
                }
                else -> throw ProviderIssue(tr("Выберите поисковик в настройках"))
            }
            count(engine)
            val response = webFetch(searchClient, request.header("Accept", "application/json").build(),
                if (engine == TAVILY) 6_291_456 else 1_048_576)
            if (response.redirect) throw ProviderIssue(tr("Сервер перенаправляет запрос. Укажите конечный адрес поиска"))
            val obj = webObject(response.text())
            val rows = when (engine) {
                BRAVE -> (obj?.get("web") as? JsonObject)?.get("results")
                SERPER -> obj?.get("organic")
                else -> obj?.get("results")
            }
            // These APIs may omit their organic section when the search has no results.
            val results = rows as? JsonArray ?: if ((engine == BRAVE && obj?.str("type") == "search") ||
                (engine == SERPER && obj?.get("searchParameters") is JsonObject)) JsonArray(emptyList())
                else if (engine == SEARXNG) throw ProviderIssue(
                    tr("Сервер не вернул JSON со списком results. Возможно, включена проверка браузера"))
                else throw ProviderIssue(tr("Поисковик {0} не вернул распознаваемую выдачу", engine))
            if (engine == SEARXNG && results.isEmpty() &&
                (obj?.get("unresponsive_engines") as? JsonArray)?.isNotEmpty() == true) {
                throw ProviderIssue(tr("Движки SearXNG не смогли получить результаты"))
            }
            val hits = results.mapNotNull { item ->
                val row = item as? JsonObject ?: return@mapNotNull null
                val url = row.str(if (engine == SERPER) "link" else "url") ?: return@mapNotNull null
                val snippet = when (engine) {
                    BRAVE -> row.str("description")
                    SERPER -> row.str("snippet")
                    EXA -> row.str("text") ?: row.strings("highlights")?.joinToString(" … ")
                    else -> row.str("content")
                }
                val date = when (engine) {
                    BRAVE -> row.str("page_age")
                    SERPER -> row.str("date")
                    EXA -> row.str("publishedDate")
                    else -> row.str("published_date") ?: row.str("publishedDate")
                }
                val title = row.str("title").orEmpty()
                row.str("raw_content")?.takeIf { it.length >= 400 && !live(fresh) }?.let { raw ->
                    cleanUrl(url)?.let { clean -> prefetched[clean.toString()] = ReadPage(clean.toString(), title, raw.take(60_000), date) }
                }
                // Brave highlights results with HTML. Other providers return plain text.
                if (engine == BRAVE) SearchHit(Jsoup.parse(title).text(), url, Jsoup.parse(snippet.orEmpty()).text(), date)
                else SearchHit(title, url, snippet.orEmpty(), date)
            }
            cleanHits(hits, query.domains, if (engine == TAVILY) 20 else 8)
        }
    }

    private fun cacheKey(engine: String, query: SearchQuery) =
        listOf(engine, query.text.trim(), query.domains.sorted().joinToString(","), query.freshness.orEmpty()).joinToString("\n")

    private fun since(freshness: String?): String? {
        val days = when (freshness) { "day" -> 1L; "week" -> 7L; "month" -> 31L; "year" -> 366L; else -> return null }
        return java.time.Instant.now().minus(java.time.Duration.ofDays(days)).toString()
    }

    private fun post(url: String, name: String, value: String, body: JsonObject) =
        Request.Builder().url(url).header(name, value).post(body.toString().toRequestBody("application/json".toMediaType()))

    private fun cleanHits(hits: List<SearchHit>, domains: List<String>, limit: Int = 8) = hits.mapNotNull { hit ->
        val url = cleanUrl(hit.url) ?: return@mapNotNull null
        if (domains.isNotEmpty() && domains.none { belongsTo(url.host, it) }) return@mapNotNull null
        hit.copy(url = url.toString(), title = compact(hit.title, 120).ifBlank { url.host.take(120) },
            excerpt = compact(hit.excerpt, 500), date = hit.date?.let { compact(it, 64) }?.takeIf(String::isNotBlank))
    }.distinctBy { it.url }.take(limit)
}
