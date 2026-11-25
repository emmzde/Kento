package dev.kento.data.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val wordPattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}+#.-]*")
private val stopWords = setOf("what", "where", "when", "which", "does", "the", "for", "and", "with", "about", "find",
    "как", "что", "для", "где", "про", "это", "чем", "чего", "какие", "найди", "найти", "расскажи", "только", "такое")

internal fun searchTerms(text: String) = wordPattern.findAll(text.lowercase())
    .map { it.value.trimEnd('.', '-').take(50) }
    .filter { it.length >= 2 && it !in stopWords }.distinct().take(24).toList()

private fun matchesTerm(term: String, words: Set<String>): Boolean =
    term in words || term.length >= 5 && hasCyrillic(term) && words.any {
        it.length >= 5 && it.startsWith(term.dropLast(2))
    }

internal data class RankedHit(val hit: SearchHit, val identity: String, val score: Double)

internal fun rankHits(results: List<SearchResult>, variants: List<List<String>>, domains: List<String>): List<RankedHit> {
    data class Candidate(val hit: SearchHit, val engine: String, val score: Double)
    val candidates = mutableMapOf<String, MutableList<Candidate>>()
    for ((engine, hits) in results) {
        for ((position, hit) in hits.withIndex()) {
            val url = cleanUrl(hit.url) ?: continue
            if (domains.isNotEmpty() && domains.none { belongsTo(url.host, it) }) continue
            val title = searchTerms(hit.title).toSet()
            val snippet = searchTerms(hit.excerpt).toSet()
            val relevance = variants.maxOfOrNull { terms ->
                terms.sumOf { term ->
                    when {
                        matchesTerm(term, title) -> 1.0
                        matchesTerm(term, snippet) -> 0.60
                        else -> 0.0
                    }
                } / terms.size.coerceAtLeast(1)
            } ?: 0.0
            val score = 1.7 / (1 + position * 0.14) + 2.5 * relevance +
                if (domains.isEmpty()) 0.0 else 0.50
            val key = urlIdentity(url)
            candidates.getOrPut(key) { mutableListOf() } += Candidate(
                hit.copy(title = compact(hit.title, 120).ifBlank { url.host }, url = url.toString(),
                    excerpt = compact(hit.excerpt, 500)), engine, score
            )
        }
    }
    return candidates.map { (key, group) ->
        val best = group.maxBy { it.score }
        val score = best.score + (group.distinctBy { it.engine }.size - 1).coerceAtMost(2) * 0.65
        RankedHit(best.hit.copy(date = best.hit.date ?: group.firstNotNullOfOrNull { it.hit.date }), key, score)
    }.filter { it.score >= 2.3 }.sortedByDescending { it.score }
}

private val siteOperator = Regex("(?i)\\bsite:([^\\s()\"',;]+)")

internal fun domainName(raw: String): String? {
    val candidate = raw.trim().trimEnd('.').removePrefix("*.").lowercase()
    if (candidate.length > 253 || candidate.any { !it.isLetterOrDigit() && it !in ".-" }) return null
    val host = "https://$candidate".toHttpUrlOrNull()?.host ?: return null
    return host.takeIf { '.' in it && it.substringAfterLast('.').any(Char::isLetter) && !it.endsWith(".local") }
}

internal fun searchQuery(text: String, domains: List<String> = emptyList()): SearchQuery? {
    val sites = siteOperator.findAll(text).mapNotNull { domainName(it.groupValues[1]) }.distinct().take(4).toList()
    val query = compact(siteOperator.replace(text, "").trim(' ', '"', '«', '»', '(', ')'), 240)
    return query.takeIf { it.isNotBlank() }?.let { SearchQuery(it, domains.ifEmpty { sites }) }
}
