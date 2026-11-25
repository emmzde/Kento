package dev.kento.data.web

import dev.kento.core.text.tr

import dev.kento.data.net.ProviderIssue
import dev.kento.data.net.str
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

fun interface PageRenderer {
    suspend fun render(html: String): String?
}

internal class PageReader(private val client: OkHttpClient, private val cache: WebCache, private val renderer: PageRenderer?) {
    private val pace = HostPace()

    suspend fun read(hit: SearchHit): ReadPage {
        val url = cleanUrl(hit.url) ?: throw ProviderIssue(tr("Неверный адрес страницы"))
        // Twenty minutes: long enough for one research session to reuse a page, short enough for live figures.
        val page = cache.cached<ReadPage>("page", url.toString(), 300, 20 * 60_000L) {
            val fetched = System.currentTimeMillis()
            try { readAt(url) }
            catch (original: ProviderIssue) {
                if (original.status !in setOf(404, 410) || belongsTo(url.host, "archive.org")) throw original
                try {
                    val snapshot = archive(url) ?: throw original
                    readAt(snapshot, archiveOnly = true)
                } catch (_: IOException) { throw original }
            }.copy(retrieved = fetched)
        }
        return page.copy(title = page.title.ifBlank { hit.title }, date = page.date ?: hit.date)
    }

    private suspend fun readAt(start: HttpUrl, archiveOnly: Boolean = false): ReadPage {
        var url = start
        repeat(6) {
            if (archiveOnly && (url.host != "web.archive.org" || !url.isHttps || url.port != 443))
                throw ProviderIssue(tr("Архив перенаправил на другой сайт"))
            val rules = robots(url)
            if (!withContext(Dispatchers.Default) { robotsAllows(rules, url) })
                throw ProviderIssue(tr("Сайт ограничивает чтение через robots.txt"))
            val response = request(url, "text/html, application/xhtml+xml, text/plain", 2_097_152)
            // A small page that only says "moved" through <meta http-equiv="refresh"> (docs sites do this) is followed
            // like an HTTP redirect, instead of handing the model a stub.
            val moved = if (!response.redirect && response.bytes.size < 20_000) metaRefresh.find(response.text().take(6_000))
                ?.groupValues?.get(1)?.trim()?.let { url.resolve(it) }?.takeIf { it != url } else null
            if (moved != null) { url = moved; return@repeat }
            if (!response.redirect) {
                val mime = response.type?.let { "${it.type}/${it.subtype}" }
                val page = when (mime) {
                    "text/plain" -> ReadPage(url.toString(), "", normalizePageText(response.text()))
                    "text/html", "application/xhtml+xml", null -> htmlPage(url, response, rules)
                    else -> throw ProviderIssue(tr("Формат страницы не поддерживается: {0}", mime))
                }
                if (page.text.length < 50) throw ProviderIssue(tr("На странице недостаточно доступного текста"))
                return page
            }
            url = redirect(url, response)
        }
        throw ProviderIssue(tr("Слишком много перенаправлений"))
    }

    private suspend fun htmlPage(url: HttpUrl, response: WebBody, rules: String): ReadPage {
        val doc = withContext(Dispatchers.Default) {
            Jsoup.parse(response.bytes.inputStream(), response.type?.charset()?.name(), url.toString())
        }
        val page = withContext(Dispatchers.Default) { extractPage(doc, url) }
        if (renderer == null || page.text.length >= 400) return page
        withContext(Dispatchers.Default) { inlineScripts(doc, url, rules) }
        val snapshot = withContext(Dispatchers.Default) { doc.outerHtml() }
        val rendered = renderer.render(snapshot) ?: return page
        val dynamicPage = withContext(Dispatchers.Default) { extractPage(Jsoup.parse(rendered, url.toString()), url) }
        return if (dynamicPage.text.length > page.text.length) dynamicPage.copy(date = dynamicPage.date ?: page.date) else page
    }

    private suspend fun inlineScripts(doc: Document, page: HttpUrl, rules: String) {
        val scripts = doc.select("script[src]:not([integrity])").asSequence().mapNotNull { script ->
            val url = page.resolve(script.attr("src"))?.takeIf {
                it.host == page.host && it.port == page.port && it.scheme == page.scheme &&
                    it.username.isEmpty() && it.password.isEmpty() && robotsAllows(rules, it)
            } ?: return@mapNotNull null
            script to url
        }.take(2).toList()
        withTimeoutOrNull(4_500) {
            for ((element, url) in scripts) {
                val response = try { withTimeoutOrNull(2_200) { request(url, "application/javascript, text/javascript", 262_144) } }
                catch (_: IOException) { null }
                if (response == null || response.redirect || response.type?.subtype !in jsSubtypes) continue
                val js = closingScript.replace(response.text()) { it.value.replace("/", "\\/") }
                element.removeAttr("src").appendChild(DataNode(js))
            }
        }
    }

    private suspend fun robots(page: HttpUrl): String {
        val start = page.newBuilder().encodedPath("/robots.txt").query(null).build()
        return cache.cached("robots", start.toString(), 400) {
            var url = start
            repeat(6) {
                val response = try { request(url, "text/plain", 524_288) }
                catch (error: ProviderIssue) {
                    if (error.status in setOf(404, 410)) return@cached ""
                    // Authentication, throttling and transient failures should not trigger a crawl.
                    throw ProviderIssue(tr("Не удалось прочитать robots.txt для {0}", start.host))
                }
                if (!response.redirect) return@cached response.text()
                url = redirect(url, response)
            }
            throw ProviderIssue(tr("Слишком много перенаправлений robots.txt для {0}", start.host))
        }
    }

    private suspend fun archive(url: HttpUrl): HttpUrl? {
        val api = "https://archive.org/wayback/available".toHttpUrl().newBuilder()
            .addQueryParameter("url", url.toString()).build()
        val response = request(api, "application/json", 65_536)
        if (response.redirect) return null
        val snapshots = webObject(response.text())?.get("archived_snapshots") as? JsonObject
        val closest = snapshots?.get("closest") as? JsonObject ?: return null
        if (closest.str("available") != "true" || closest.str("status") != "200") return null
        val snapshot = closest.str("url")?.let(::cleanUrl)?.takeIf { it.host == "web.archive.org" && it.port in setOf(80, 443) }
        return snapshot?.newBuilder()?.scheme("https")?.port(443)?.build()
    }

    private fun redirect(from: HttpUrl, response: WebBody): HttpUrl =
        response.location?.let(from::resolve)?.toString()?.let(::cleanUrl) ?: throw ProviderIssue(tr("Ошибка перенаправления"))

    private suspend fun request(url: HttpUrl, accept: String, cap: Long): WebBody {
        pace.wait(url.host)
        return webFetch(client, Request.Builder().url(url).header("Accept", accept).build(), cap)
    }
}

private val dateValue = Regex("""^\d{4}-\d{2}-\d{2}(?:[T ].*)?$""")
private val closingScript = Regex("</script", RegexOption.IGNORE_CASE)
private val jsSubtypes = setOf("javascript", "x-javascript", "ecmascript")
private val pageWhitespace = Regex("[\\t\\p{Zs}]+")
private val emptyLines = Regex("\\n\\s*\\n+")
private val extraBlankLines = Regex("\\n{3,}")

/** Plain-text pages: collapse whitespace into paragraphs and keep whole paragraphs up to the cache limit. */
private fun normalizePageText(text: String): String =
    leadingBlocks(text.replace(pageWhitespace, " ").replace(emptyLines, "\n\n").trim(), PAGE_LIMIT)

/** Markdown keeps code indentation; only runs of blank lines are folded. */
private fun normalizeMarkdown(text: String): String = leadingBlocks(text.replace(extraBlankLines, "\n\n").trim(), PAGE_LIMIT)

/** A cached page holds at most this many bytes of text; read_webpage then returns as much as the model's window allows. */
private const val PAGE_LIMIT = 60_000

private val dateSelectors = listOf("meta[property=article:published_time]", "meta[name=article:published_time]",
    "meta[itemprop=datePublished]", "meta[name=date]", "meta[name=pubdate]", "meta[name=publish-date]",
    "meta[name=DC.date.issued]", "meta[name=dcterms.created]", "meta[property=og:published_time]",
    "meta[property=article:modified_time]", "meta[property=og:updated_time]")

/** The page as Markdown; a page too deeply nested even for the depth limits falls back to its plain text. */
internal fun extractPage(source: Document, url: HttpUrl): ReadPage = try { extractStructured(source, url) }
    catch (_: StackOverflowError) { ReadPage(url.toString(), source.title(), normalizePageText(source.text())) }

private fun extractStructured(source: Document, url: HttpUrl): ReadPage {
    val doc = source.clone()
    val metadata = doc.select("script[type=application/ld+json]").asSequence().filter { it.data().length <= 120_000 }
        .mapNotNull { runCatching { Json.parseToJsonElement(it.data()) }.getOrNull() }
        .flatMap { articleObjects(it, depth = 0) }.toList()
    // Published dates first; a modification date still beats no date for judging freshness.
    val date = (dateSelectors.asSequence().map { doc.selectFirst(it)?.attr("content") } +
        metadata.asSequence().map { it.str("datePublished") } +
        sequenceOf(doc.selectFirst("article time[datetime], main time[datetime], time[datetime]")?.attr("datetime")) +
        metadata.asSequence().map { it.str("dateModified") }).filterNotNull()
        .map(String::trim).firstOrNull { it.length <= 64 && dateValue.matches(it) }
    val title = compact(doc.title(), 120)
    if (doc.selectFirst("#challenge-form, #cf-challenge-running, #challenge-stage, #anomaly-modal") != null) {
        return ReadPage(url.toString(), title, "", date)
    }
    doc.select("script, style, nav, footer, aside, form, noscript, template, iframe, svg, canvas, button, select, dialog, " +
        "[hidden], [aria-hidden=true], [role=navigation], [role=banner], [role=contentinfo], [role=complementary], body > header, " +
        "#wm-ipp-base, .breadcrumb, .breadcrumbs, .cookie, .cookies, #cookie-banner, .share, .social, .newsletter, .advert, .ads, .ad").remove()
    val forumSelector = if (belongsTo(url.host, "reddit.com")) "shreddit-post, shreddit-comment, .comment"
        else ".postbody, .cooked, .comments, #comments"
    val main = doc.selectFirst("main, [role=main]") ?: doc.selectFirst("article") ?: doc.body()
    val extra = doc.select(forumSelector).filter { it !== main && main !in it.parents() && it !in main.parents() }
    val extraSet = extra.toSet()
    val roots = extra.filter { element -> element.parents().none { it in extraSet } }
    val extracted = buildString {
        append(markdown(main))
        roots.forEach { append("\n\n"); append(markdown(it)) }
    }
    val structured = metadata.mapNotNull { it.str("articleBody") }.maxByOrNull(String::length).orEmpty()
    return ReadPage(url.toString(), title, extracted.ifBlank { structured }.let {
        if (it.length < 400 && structured.length > it.length) normalizePageText(structured) else normalizeMarkdown(it)
    }, date)
}

private fun articleObjects(value: JsonElement, depth: Int): Sequence<JsonObject> {
    if (depth > 5) return emptySequence()
    return when (value) {
        is JsonArray -> value.asSequence().flatMap { articleObjects(it, depth + 1) }
        is JsonObject -> sequenceOf(value) + listOfNotNull(value["@graph"], value["mainEntity"]).asSequence()
            .flatMap { articleObjects(it, depth + 1) }
        else -> emptySequence()
    }
}

private val headings = setOf("h1", "h2", "h3", "h4", "h5", "h6")
private val listTags = setOf("ul", "ol")
private val blockTags = headings + listTags + setOf("p", "div", "section", "article", "main", "header", "pre", "blockquote",
    "table", "figure", "figcaption", "dl", "dt", "dd", "hr", "details", "summary", "center", "address")
private val spaceRun = Regex("[\\s\\u00a0]+")

/**
 * Turns cleaned HTML into compact Markdown: headings, paragraphs, lists, tables, quotes and code blocks.
 * Links keep only their text, which is what the model reads anyway, and saves tokens.
 */
internal fun markdown(root: Element): String {
    val out = StringBuilder()
    fun block(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (out.isNotEmpty()) out.append("\n\n")
        out.append(t)
    }
    // Some pages nest thousands of wrappers; past a sane depth the rest is taken as plain text instead of recursing
    // until the stack runs out (that crashed the app).
    fun inline(node: Node, into: StringBuilder, depth: Int = 0) {
        when (node) {
            is TextNode -> into.append(node.wholeText.replace(spaceRun, " "))
            is Element -> when {
                depth > MAX_DEPTH -> into.append(node.text())
                node.tagName() == "br" -> into.append('\n')
                node.tagName() in setOf("code", "kbd", "samp") ->
                    node.text().takeIf(String::isNotBlank)?.let { into.append('`').append(it).append('`') }
                node.tagName() == "img" -> Unit
                else -> node.childNodes().forEach { inline(it, into, depth + 1) }
            }
        }
    }
    fun inlineText(element: Element) = StringBuilder().also { sb -> element.childNodes().forEach { inline(it, sb) } }
        .toString().lines().joinToString("\n") { it.trim() }.trim()
    fun list(element: Element, depth: Int, into: StringBuilder) {
        var n = 1
        for (item in element.children()) {
            if (item.tagName() != "li") continue
            val own = item.clone().apply { select("ul, ol").remove() }
            val text = own.text().trim()
            if (text.isNotEmpty()) into.append("  ".repeat(depth)).append(if (element.tagName() == "ol") "${n++}." else "-")
                .append(' ').append(text).append('\n')
            if (depth < 12) item.children().filter { it.tagName() in listTags }.forEach { list(it, depth + 1, into) }
        }
    }
    fun table(element: Element): String {
        val rows = element.select("tr").map { row -> row.children().filter { it.tagName() in setOf("td", "th") }
            .map { it.text().replace("|", "/").trim() } }.filter { cells -> cells.any(String::isNotEmpty) }
        if (rows.isEmpty()) return ""
        val width = rows.maxOf { it.size }
        if (width < 2) return rows.joinToString("\n") { it.joinToString(" ") }
        return buildString {
            rows.forEachIndexed { index, cells ->
                append("| ").append((cells + List(width - cells.size) { "" }).joinToString(" | ")).append(" |\n")
                if (index == 0) append("|").append(" --- |".repeat(width)).append('\n')
            }
        }
    }
    fun walk(element: Element, depth: Int = 0) {
        if (depth > MAX_DEPTH) { block(element.text()); return }
        when (val tag = element.tagName()) {
            in headings -> block("#".repeat(tag[1].digitToInt()) + " " + element.text())
            "p", "figcaption", "dt", "dd", "summary", "address" -> block(inlineText(element))
            "pre" -> block("```\n" + element.wholeText().trimEnd() + "\n```")
            "blockquote" -> {
                val inner = markdown(element)
                block(inner.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" })
            }
            in listTags -> block(StringBuilder().also { list(element, 0, it) }.toString())
            "table" -> block(table(element))
            "hr" -> Unit
            else -> {
                // Containers mix inline runs with block children: flush each run as its own paragraph.
                val run = StringBuilder()
                fun flush() { block(run.toString().lines().joinToString("\n") { it.trim() }); run.clear() }
                for (child in element.childNodes()) {
                    if (child is Element && (child.tagName() in blockTags || child.tagName() == "li")) { flush(); walk(child, depth + 1) }
                    else inline(child, run)
                }
                flush()
            }
        }
    }
    walk(root)
    return out.toString()
}

/** How deep the Markdown conversion follows nested elements before it takes the rest as plain text. */
private const val MAX_DEPTH = 160

/** `<meta http-equiv="refresh" content="0; url=…">`, the redirect of static sites. */
private val metaRefresh = Regex("""(?i)<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["']?\s*\d+\s*;\s*url\s*=\s*([^"'>\s]+)""")
