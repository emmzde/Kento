package dev.kento.data.memory

import java.text.BreakIterator
import java.util.Locale

/**
 * Quotes handed to the model are made of whole sentences. A window around the first match grows sentence by
 * sentence in both directions while it fits; nothing is cut mid-word and no ellipses are invented. Only a single
 * sentence longer than the whole budget (a code block, a table) is shortened, at a word boundary.
 */
internal fun excerpt(text: String, terms: List<String>, bytes: Int): String {
    if (bytes < 12) return ""
    val clean = text.trim()
    if (cost(clean) <= bytes) return clean
    val spans = sentenceSpans(clean)
    if (spans.isEmpty()) return wordCut(clean, bytes)
    val lower = clean.lowercase()
    val hit = terms.asSequence().map { lower.indexOf(it.lowercase()) }.filter { it >= 0 }.minOrNull()
        ?.let { at -> spans.indexOfFirst { at < it.last + 1 }.coerceAtLeast(0) } ?: 0
    fun piece(from: Int, to: Int) = clean.substring(spans[from].first, spans[to].last + 1).trim()
    if (cost(piece(hit, hit)) > bytes) {
        val sentence = piece(hit, hit)
        val at = terms.asSequence().map { sentence.lowercase().indexOf(it.lowercase()) }.filter { it >= 0 }.minOrNull() ?: 0
        return wordWindow(sentence, at, bytes)
    }
    var from = hit
    var to = hit
    var grew = true
    while (grew) {
        grew = false
        if (to + 1 < spans.size && cost(piece(from, to + 1)) <= bytes) { to++; grew = true }
        if (from > 0 && cost(piece(from - 1, to)) <= bytes) { from--; grew = true }
    }
    return piece(from, to)
}

/** The first sentences of [text] that fit into [bytes]; used for titles and short previews. */
internal fun leadingSentences(text: String, bytes: Int): String = excerpt(text, emptyList(), bytes)

private fun sentenceSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    val iterator = BreakIterator.getSentenceInstance(Locale.forLanguageTag("ru"))
    // Paragraph breaks always end a sentence, even without punctuation (lists, headings, code).
    var offset = 0
    for (paragraph in text.split('\n')) {
        if (paragraph.isNotBlank()) {
            iterator.setText(paragraph)
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                if (paragraph.substring(start, end).isNotBlank()) spans += (offset + start) until (offset + end)
                start = end
                end = iterator.next()
            }
        }
        offset += paragraph.length + 1
    }
    return spans
}

/** A sentence longer than the budget: whole words around [at], marked as shortened on the cut side(s). */
private fun wordWindow(text: String, at: Int, bytes: Int): String {
    if (at <= 0) return wordCut(text, bytes)
    var start = at
    var end = at
    fun size(a: Int, b: Int) = text.substring(a, b).toByteArray().size + 14
    while (true) {
        val grown = (end + 1).coerceAtMost(text.length) to (start - 1).coerceAtLeast(0)
        var moved = false
        if (end < text.length && size(start, grown.first) <= bytes) { end = grown.first; moved = true }
        if (start > 0 && size(grown.second, end) <= bytes) { start = grown.second; moved = true }
        if (!moved) break
    }
    if (start > 0 && text[start - 1].isLowSurrogate() == false) {
        val space = text.indexOf(' ', start).takeIf { it in start until at } ?: start
        start = if (space > start) space + 1 else start
    }
    if (end < text.length) {
        val space = text.lastIndexOf(' ', end).takeIf { it > at } ?: end
        end = space
    }
    if (start > 0 && text[start].isLowSurrogate()) start++
    if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end--
    return (if (start > 0) "… " else "") + text.substring(start, end).trim() + (if (end < text.length) " …" else "")
}

private fun wordCut(text: String, bytes: Int): String {
    var used = 10
    var end = 0
    while (end < text.length) {
        val cp = text.codePointAt(end)
        val n = when { cp < 128 -> 1; cp < 2048 -> 2; cp < 65536 -> 3; else -> 4 }
        if (used + n > bytes) break
        used += n
        end += Character.charCount(cp)
    }
    val space = text.lastIndexOf(' ', end).takeIf { it > end / 2 } ?: end
    return text.take(space).trimEnd() + " …"
}
