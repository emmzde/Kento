package dev.kento.data.web

import org.jsoup.nodes.Entities

/** A DuckDuckGo Lite result page as the HTTP endpoint returns it: one `result-link` row and one snippet row per hit. */
internal fun liteResults(hits: List<SearchHit>): String = if (hits.isEmpty()) """<p class="no-results">No results.</p>"""
    else hits.joinToString("", "<table>", "</table>") { hit ->
        """<tr><td><a rel="nofollow" href="${Entities.escape(hit.url)}" class='result-link'>${Entities.escape(hit.title)}</a></td></tr>""" +
            """<tr><td class='result-snippet'>${Entities.escape(hit.excerpt)}</td></tr>"""
    }

/** DuckDuckGo's bot check, as it comes back with HTTP 202. */
internal const val LITE_CHECK = """<div class="anomaly-modal__modal"><form id="challenge-form"></form></div>"""

/** A DuckDuckGo Lite endpoint for [WebAccess]: [lookup] gets the keyword query and the `df` period. */
internal fun lite(lookup: (String, String?) -> List<SearchHit>): suspend (String, String?) -> String =
    { q, period -> liteResults(lookup(q, period)) }

/** The WebView tab that a test does not expect to open. */
internal val noBrowser: suspend (String, String?) -> List<SearchHit> = { _, _ -> error("The WebView runs only after a bot check") }
