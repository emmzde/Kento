package dev.kento.data.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

internal fun HttpUrl.isDuckDuckGo() = isHttps && port == 443 && username.isEmpty() && password.isEmpty() &&
    host in setOf("lite.duckduckgo.com", "html.duckduckgo.com", "duckduckgo.com")

internal sealed interface DuckPage {
    data object Verification : DuckPage
    data class Results(val hits: List<SearchHit>) : DuckPage
    data object Unknown : DuckPage
}

internal fun duckDuckGoPage(html: String, base: String): DuckPage {
    val doc = Jsoup.parse(html, base)
    if (doc.selectFirst("#challenge-form, #anomaly-modal, .anomaly-modal__modal, form[action*=anomaly]") != null)
        return DuckPage.Verification
    val hits = mutableListOf<SearchHit>()
    val seen = mutableSetOf<String>()
    for (link in doc.select("a.result-link[href], a.result__a[href]")) {
        val href = link.absUrl("href").toHttpUrlOrNull() ?: continue
        val url = if (href.isDuckDuckGo() && href.encodedPath.trimEnd('/') == "/l") {
            href.queryParameter("uddg")?.toHttpUrlOrNull() ?: continue
        } else href
        if (url.isDuckDuckGo() || url.username.isNotEmpty() || url.password.isNotEmpty()) continue
        if (link.closest(".result--ad, .result-sponsored") != null || !seen.add(url.toString())) continue
        val snippet = link.closest(".result")?.selectFirst(".result__snippet")
            ?: link.closest("tr")?.nextElementSibling()?.selectFirst(".result-snippet")
        hits += SearchHit(compact(link.text().ifBlank { url.host }, 120), url.toString(), compact(snippet?.text().orEmpty(), 300))
        if (hits.size == 6) break
    }
    if (hits.isEmpty() && doc.selectFirst(".no-results, .no-results__message, .result--no-result") == null &&
        !doc.text().contains("No results", ignoreCase = true)) {
        return DuckPage.Unknown
    }
    return DuckPage.Results(hits)
}
