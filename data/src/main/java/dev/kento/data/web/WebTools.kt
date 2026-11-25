package dev.kento.data.web

import dev.kento.core.text.tr

import dev.kento.data.memory.ToolNames
import dev.kento.data.net.ProviderIssue
import dev.kento.data.settings.WebSettings
import dev.kento.data.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * search_web and read_webpage. The model writes its own keyword query and may target domains; results come back
 * with URL and publication date. read_webpage returns the cleaned page as Markdown, sized to the model's window.
 */
class WebTools(private val access: SearchAccess, private val web: WebSettings,
    /** How much page text one read may return, in UTF-8 bytes. */
    private val pageBytes: Int,
    /**
     * Everything search and reading may add to one answer, in UTF-8 bytes. Tool results stay in the request for every
     * following round, so without a cap a long research (now with parallel reads) would overflow the model's window.
     */
    totalBytes: Int = pageBytes * 4,
    /** Records queries and pages for the interface; null in tests and background work. */
    private val trail: TrailRecorder? = null,
    /** How many top results of a search Kento opens itself and quotes briefly (see [search]). */
    private val peek: Int = 0,
    /**
     * False when web_search is "disabled" for this message. The tools stay declared (the request prefix stays cached)
     * and refuse instead, in case the model ignores the flag of its runtime context.
     */
    private val allowed: Boolean = true,
    /** How long the engine chain of one search may take; see [SEARCH_DEADLINE_MS]. */
    private val searchDeadline: Long = SEARCH_DEADLINE_MS,
    private val today: () -> LocalDate = LocalDate::now) {
    private val left = java.util.concurrent.atomic.AtomicInteger(totalBytes)
    private val read = java.util.concurrent.ConcurrentLinkedQueue<dev.kento.data.db.Source>()
    private val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Paid API searches left for this answer (see [WebSettings.paidSearches]). */
    private val paidLeft = java.util.concurrent.atomic.AtomicInteger(web.paidSearches)
    /** Completes when the paid search of this answer has its results, so parallel free searches can use its pages. */
    private val paidDone = kotlinx.coroutines.CompletableDeferred<Unit>()
    /** Engines that failed during this answer; one that failed twice is skipped for the rest of it. */
    private val strikes = EngineStrikes()

    /**
     * The pages behind [answer]: every page the model read, then search results it linked to without reading,
     * newest research first, at most eight.
     */
    fun sources(answer: String): List<dev.kento.data.db.Source> {
        val opened = read.toList()
        val cited = seen.filterKeys { url -> url in answer && opened.none { it.url == url } }
            .map { (url, title) -> dev.kento.data.db.Source(title, url) }
        return (opened + cited).distinctBy { it.url }.take(8)
    }
    private fun spent(text: String) = text.also { left.addAndGet(-it.toByteArray().size) }
    private val exhausted = buildJsonObject {
        put("error", "The reading budget of this reply is used up: no more searches or page reads.")
    }.toString()

    fun all(): List<ChatTool> = listOf(search, reader)

    private val search = object : ChatTool {
        override val name = ToolNames.SEARCH
        override val timeoutMs = 25_000L
        override val parallel = true
        override val schema = functionSchema(name,
            "Search the web, only for current, recent or unfamiliar facts you cannot answer reliably yourself (not for " +
                "explanations, coding, math or general knowledge). Returns title, url, published date and snippet for each result; the top results also carry " +
                "page_excerpt, text Kento read from the page, and page_retrieved, when. Snippets can be stale copies from the engine's index: " +
                "open pages with read_webpage before relying on numbers or recent facts. Several searches can run in parallel in one turn.",
            required = listOf("query")) {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Keyword query, not the user's sentence. English for technical and international topics, " +
                    "the local language for local ones. Add versions or a year for releases and docs, never for live data")
            }
            putJsonObject("freshness") {
                put("type", "string")
                putJsonArray("enum") { FRESHNESS.forEach { add(it) } }
                put("description", "Only results published within this period. day: prices, markets, scores, breaking news; " +
                    "week or month: recent developments. Omit for evergreen topics")
            }
            putJsonObject("domains") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("maxItems", 4)
                put("description", "Optional sites to search only, e.g. [\"developer.android.com\", \"reddit.com\"]")
            }
        }
        override fun status(args: JsonObject) = tr("Ищу: ") + args.text("query").orEmpty().take(60)
        override suspend fun run(args: JsonObject): ToolOutput {
            if (!allowed) throw ToolRefusal(DISABLED)
            if (left.get() <= 0) return ToolOutput(exhausted)
            val text = args.text("query") ?: throw ToolRefusal("query is empty")
            val domains = (args["domains"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::domainName) }
                .distinct().take(4)
            val freshness = args.text("freshness")?.lowercase()?.takeIf { it in FRESHNESS }
            // The first search of the answer may use the paid API; it brings up to 20 full pages. Later ones are free
            // and also look through those pages first.
            val base = searchQuery(text, domains) ?: throw ToolRefusal("query is empty")
            val paid = !web.usesPaidApi() || web.paidSearches <= 0 || paidLeft.getAndDecrement() > 0
            val query = base.copy(freshness = freshness, paid = paid)
            trail?.query(text)
            val terms = searchTerms(text)
            val need = ((terms.size + 1) / 2).coerceAtLeast(1)
            if (!paid && paidDone.isActive) kotlinx.coroutines.withTimeoutOrNull(8_000) { paidDone.await() }
            val known = if (paid) emptyList() else access.corpus().mapNotNull { page ->
                val lower = (page.title + " " + page.text).lowercase()
                val count = terms.count { it in lower }
                if (count < need || query.domains.isNotEmpty() && query.domains.none { d -> page.url.contains(d) }) null
                else count to SearchHit(page.title, page.url, dev.kento.data.memory.excerpt(page.text, terms, 300), page.date)
            }.sortedByDescending { it.first }.take(3).map { it.second }
            // Every engine of the chain failed, or the chain ran out of time: the model gets the fact, not a hung call.
            val found = try {
                withTimeoutOrNull(searchDeadline) { withContext(strikes) { access.searchRound(web, listOf(query)) } }
            } catch (_: IOException) { null }
            finally { if (paid) paidDone.complete(Unit) }
            if (found == null && known.isEmpty()) return ToolOutput(toolError(UNAVAILABLE))
            val results = found.orEmpty()
            val hits = results.flatMap { it.hits }
            val many = hits.size > 10
            val ranked = (known + withContext(Dispatchers.Default) {
                rankHits(results, listOf(searchTerms(query.text)), query.domains).map { it.hit }
            }.ifEmpty { hits.distinctBy { it.url } }).distinctBy { it.url }.take(if (many) 10 else 7)
            val now = today()
            ranked.forEach { seen.putIfAbsent(it.url, it.title) }
            trail?.found(ranked)
            // The best results are opened right away and quoted around the query words. Most questions are settled
            // by these few lines, which saves a whole round (every round sends the conversation again).
            // Only results that carry most of the query words are worth opening; a blind top three wasted the
            // reading budget on catalogue pages and homepages.
            val relevant = ranked.filter { hit ->
                val seenText = (hit.title + " " + hit.excerpt + " " + hit.url).lowercase()
                terms.count { it in seenText } >= need
            }
            val peeks = if (peek <= 0 || left.get() < 8_000) emptyMap() else coroutineScope {
                relevant.take(peek).map { hit -> async {
                    val page = withTimeoutOrNull(5_000) { access.peek(hit) }
                    page?.takeIf { it.text.isNotBlank() }?.let { p ->
                        trail?.read(hit.url, p.title.ifBlank { hit.title })
                        hit.url to (focusedBlocks(p.text, text, PEEK_BYTES) to retrievedAt(p))
                    }
                } }.awaitAll().filterNotNull().toMap()
            }
            return ToolOutput(buildJsonObject {
                put("query", query.forKeywordEngine())
                freshness?.let { put("freshness", it) }
                put("retrieved", now.toString())
                putJsonArray("results") {
                    ranked.forEach { hit -> add(buildJsonObject {
                        put("title", hit.title)
                        put("url", hit.url)
                        put("published", publishedDate(hit.date, now) ?: "unknown")
                        put("snippet", hit.excerpt.take(300))
                        peeks[hit.url]?.takeIf { it.first.isNotBlank() }?.let { (excerpt, retrieved) ->
                            put("page_excerpt", excerpt)
                            put("page_retrieved", retrieved)
                        }
                    }) }
                }
                if (ranked.isEmpty()) put("note", when {
                    query.domains.isNotEmpty() -> "No results on these domains. Try without domains."
                    freshness != null -> "No results in this period. Try a longer freshness or none."
                    else -> "No results. Try other keywords."
                })
            }.toString().let(::spent))
        }
    }

    private val reader = object : ChatTool {
        override val name = ToolNames.READ
        override val timeoutMs = 35_000L
        override val parallel = true
        override val schema = functionSchema(name,
            "Open a web page and return its current main content as Markdown, without navigation, ads and scripts. " +
                "Use it to verify facts from search results; several pages can be read in parallel in one turn.",
            required = listOf("url")) {
            putJsonObject("url") { put("type", "string"); put("description", "Absolute http(s) URL, usually from search_web") }
            putJsonObject("focus") {
                put("type", "string")
                put("description", "What you need from the page, e.g. \"current price and 24h change\". Long pages are cut " +
                    "down to the parts about it, which saves tokens")
            }
        }
        override fun status(args: JsonObject) = tr("Читаю: ") + (args.text("url")?.let { cleanUrl(it)?.host } ?: tr("страницу"))
        override suspend fun run(args: JsonObject): ToolOutput {
            if (!allowed) throw ToolRefusal(DISABLED)
            // Small leftovers are not worth a request: a few hundred bytes of a page would only mislead.
            val room = minOf(pageBytes, left.get() - 600)
            if (room < 1_500) return ToolOutput(exhausted)
            val raw = args.text("url") ?: throw ToolRefusal("url is empty")
            val url = cleanUrl(raw) ?: throw ToolRefusal("url is not a valid http(s) address")
            val page = try { access.read(SearchHit("", url.toString(), ""), "") }
            catch (e: IOException) {
                return ToolOutput(buildJsonObject {
                    put("error", "Page unavailable or blocked")
                    put("detail", failure(e))
                }.toString())
            }
            if (page.text.isBlank()) return ToolOutput(buildJsonObject { put("error", "Page unavailable or blocked") }.toString())
            val body = focusedBlocks(page.text, args.text("focus"), room)
            read += dev.kento.data.db.Source(page.title.ifBlank { url.host }, page.url)
            trail?.read(page.url, page.title.ifBlank { url.host })
            val rest = page.text.length - body.length
            return ToolOutput(buildString {
                if (page.title.isNotBlank()) appendLine("# " + page.title)
                appendLine("URL: " + page.url)
                appendLine("Published: " + (publishedDate(page.date, today()) ?: "unknown"))
                appendLine("Retrieved: " + retrievedAt(page))
                appendLine()
                append(body)
                if (rest > 200) append("\n\n[The page continues for about $rest more characters.]")
            }.let(::spent))
        }
    }
}

private val retrievedFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC)

/**
 * When the text of [page] was taken, for the model to judge live figures: Kento's own fetch in UTC (a web archive
 * snapshot says so), or the engine's copy of unknown age. Without it a model took a four-hour-old price page for the
 * current one (search bench).
 */
internal fun retrievedAt(page: ReadPage): String {
    val at = page.retrieved ?: return "unknown (search engine's copy)"
    val archived = cleanUrl(page.url)?.host == "web.archive.org"
    return retrievedFormat.format(java.time.Instant.ofEpochMilli(at)) + if (archived) " (web archive copy)" else " (live page)"
}

/** The refusal of a web tool while web_search is "disabled". */
internal const val DISABLED = "web search is disabled for this message"

/**
 * UTF-8 bytes of a page_excerpt: the opening block and the blocks about the query, as read_webpage cuts a page to its
 * focus. 600 characters around the query words were too little, so the second round of web calls went to reading the
 * same pages instead of filling gaps (seen in the search bench).
 */
internal const val PEEK_BYTES = 1_800

/** What search_web returns when no engine answered: paid, SearXNG, DuckDuckGo over HTTP and in the WebView. */
internal const val UNAVAILABLE = "search unavailable"

/**
 * How long the engine chain of one search may take, a little under the tool's own 25 s, so the model gets
 * [UNAVAILABLE] rather than a timeout.
 */
internal const val SEARCH_DEADLINE_MS = 23_000L

/** What went wrong, in English for the model: the HTTP status when there is one (UI messages are localized). */
internal fun failure(e: IOException): String = (e as? ProviderIssue)?.status?.let { "HTTP $it" } ?: "network error"

/**
 * A long page cut to what the model asked for: the opening block for orientation, then the blocks that mention the
 * [focus] words with one neighbour of context each, in page order, "[…]" marking the gaps. Without a focus or a
 * match it falls back to the top of the page. A price table in the middle of a long article now fits in a few KB.
 */
internal fun focusedBlocks(markdown: String, focus: String?, bytes: Int): String {
    if (markdown.toByteArray().size <= bytes) return markdown
    val terms = focus?.let(::searchTerms).orEmpty().filter { it.length >= 3 }
    if (terms.isEmpty()) return leadingBlocks(markdown, bytes)
    val blocks = markdown.split("\n\n").filter(String::isNotBlank)
    val scores = blocks.map { block -> val words = block.lowercase(); terms.count { it in words } }
    if (scores.all { it == 0 }) return leadingBlocks(markdown, bytes)
    val size = blocks.map { it.toByteArray().size + 2 }
    val chosen = sortedSetOf(0)
    var used = minOf(size[0], 600)
    // Best blocks first; each brings its predecessor (often the heading or the table header) when it fits.
    for (i in blocks.indices.sortedByDescending { scores[it] * 10_000 - it }) {
        if (scores[i] == 0) break
        for (j in listOf(i - 1, i).filter { it > 0 && it !in chosen }) {
            if (used + size[j] + 5 > bytes) continue
            chosen += j
            used += size[j] + 5
        }
    }
    val out = StringBuilder()
    var previous = -1
    for (i in chosen) {
        if (out.isNotEmpty()) out.append(if (i == previous + 1) "\n\n" else "\n\n[…]\n\n")
        out.append(if (i == 0 && size[0] > 600) dev.kento.data.memory.excerpt(blocks[0], terms, 600) else blocks[i])
        previous = i
    }
    return out.toString()
}

/** Whole Markdown blocks from the top of the page while they fit into [bytes]. */
internal fun leadingBlocks(markdown: String, bytes: Int): String {
    if (markdown.toByteArray().size <= bytes) return markdown
    val out = StringBuilder()
    var used = 0
    for (block in markdown.split("\n\n")) {
        val size = block.toByteArray().size + 2
        if (used + size > bytes) {
            if (out.isEmpty()) out.append(dev.kento.data.memory.excerpt(block, emptyList(), bytes))
            break
        }
        if (out.isNotEmpty()) out.append("\n\n")
        out.append(block)
        used += size
    }
    return out.toString()
}

private val isoDate = Regex("""^(\d{4}-\d{2}-\d{2})""")
private val relative = Regex("""(?i)^(\d+)\s+(minute|hour|day|week|month|year)s?\s+ago""")
private val englishDates = listOf("MMM d, yyyy", "MMMM d, yyyy", "d MMM yyyy", "d MMMM yyyy").map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

/**
 * Engines report dates as ISO timestamps, "3 days ago" or "Mar 2, 2025". The model gets an ISO day whenever the
 * value can be read, so it can judge freshness against the current date; unknown formats pass through as they are.
 */
internal fun publishedDate(raw: String?, today: LocalDate): String? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    isoDate.find(value)?.let { return it.groupValues[1] }
    relative.find(value)?.let { match ->
        val n = match.groupValues[1].toLong()
        return when (match.groupValues[2].lowercase()) {
            "minute", "hour" -> today
            "day" -> today.minusDays(n)
            "week" -> today.minusWeeks(n)
            "month" -> today.minusMonths(n)
            else -> today.minusYears(n)
        }.toString()
    }
    for (format in englishDates) runCatching { return LocalDate.parse(value, format).toString() }
    return value.take(40)
}
