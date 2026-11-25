package dev.kento.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.kento.core.ui.Paper
import dev.kento.core.ui.ReadingStyle

/** Only completed lines can close a block. Scan new text once as the reply grows. */
private class LiveBlocks {
    val finished = mutableListOf<String>()
    var fence: String? = null
        private set
    var language = ""
        private set
    var tailStart = 0
        private set
    private var lineStart = 0
    private var scanned = 0
    private var blockStart = 0

    fun advance(text: String) {
        while (scanned < text.length) {
            val newline = text.indexOf('\n', scanned)
            if (newline < 0) { scanned = text.length; break }
            val line = text.substring(lineStart, newline).trimEnd('\r')
            val trimmed = line.trimStart(' ')
            val marker = fence
            if (marker == null) {
                val char = trimmed.firstOrNull()
                val run = if (char == '`' || char == '~') trimmed.takeWhile { it == char } else ""
                if (run.length >= 3 && line.length - trimmed.length <= 3) {
                    finish(text, lineStart)
                    blockStart = lineStart
                    fence = run
                    language = trimmed.drop(run.length).trim().substringBefore(' ')
                    tailStart = newline + 1
                } else if (line.isBlank()) {
                    finish(text, newline)
                    blockStart = newline + 1
                    tailStart = blockStart
                }
            } else if (trimmed.trimEnd().let { it.length >= marker.length && it.all { ch -> ch == marker[0] } }) {
                finish(text, newline)
                fence = null
                blockStart = newline + 1
                tailStart = blockStart
            }
            lineStart = newline + 1
            scanned = lineStart
        }
    }

    private fun finish(text: String, end: Int) {
        if (end <= blockStart) return
        val block = text.substring(blockStart, end).trimEnd('\n', '\r')
        if (block.isNotBlank()) finished += block
    }
}

@Composable
fun StreamingMarkdown(source: String) {
    val blocks = remember { LiveBlocks() }
    blocks.advance(source)
    // When the text stops growing (the model went to search, or is thinking) the faded tail settles to full ink
    // instead of hanging half transparent.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(source) { settled = false; kotlinx.coroutines.delay(350); settled = true }
    val fade by androidx.compose.animation.core.animateFloatAsState(if (settled) 0f else 1f,
        androidx.compose.animation.core.tween(if (settled) 420 else 0), label = "tail")
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        blocks.finished.forEach { Markdown(it) }
        val tail = source.substring(blocks.tailStart.coerceAtMost(source.length))
        if (blocks.fence != null) CodeBlock(blocks.language, tail, streaming = true)
        else if (tail.isNotEmpty()) {
            // A formula still being written is held back until it closes, so raw LaTeX never flashes by.
            val shown = unclosedMath(tail)?.let { tail.substring(0, it).trimEnd() } ?: tail
            // The paragraph being written is formatted at once: bold, links and list marks never flash by raw.
            if (shown.isNotEmpty()) {
                if (looksPlain(shown)) Text(fading(shown, strength = fade), color = Paper, style = ReadingStyle)
                else Markdown(closeInline(shown))
            }
        }
    }
}

/** Text without any Markdown marks keeps the soft fade-in; anything else goes through the Markdown renderer. */
private fun looksPlain(text: String) = text.none { it in "*_`[#|>~" } && !text.trimStart().let {
    it.startsWith("- ") || it.startsWith("+ ") || it.firstOrNull()?.isDigit() == true && it.contains(". ")
}

/**
 * Closes what a half-written paragraph has opened, so it renders formatted: an odd `**`, `__`, `*` or backtick gets
 * its partner at the end, and a link whose address is still arriving shows as its text.
 */
internal fun closeInline(text: String): String {
    var out = text
    val link = Regex("""\[([^\]]*)\]\([^)]*$""").find(out)
    if (link != null) out = out.substring(0, link.range.first) + link.groupValues[1]
    val openBracket = out.lastIndexOf('[')
    if (openBracket >= 0 && out.indexOf(']', openBracket) < 0) out = out.removeRange(openBracket, openBracket + 1)
    fun odd(mark: String) = Regex(Regex.escape(mark)).findAll(out).count() % 2 == 1
    if (out.count { it == '`' } % 2 == 1) out += "`"
    if (odd("**")) out = out.trimEnd() + "**"
    if (odd("__")) out = out.trimEnd() + "__"
    if (out.replace("**", "").count { it == '*' } % 2 == 1) out = out.trimEnd() + "*"
    return out
}

/** Where an opened but not yet closed `$$` or `\[` formula starts in the streaming tail, if anywhere. */
internal fun unclosedMath(tail: String): Int? {
    for ((open, close) in listOf("$$" to "$$", "\\[" to "\\]")) {
        var at = tail.indexOf(open)
        while (at >= 0) {
            val end = tail.indexOf(close, at + open.length)
            if (end < 0) return at
            at = tail.indexOf(open, end + close.length)
        }
    }
    return null
}

/** The newest characters arrive faded and settle to full ink as more text lands after them. */
private fun fading(text: String, span: Int = 18, strength: Float = 1f) = buildAnnotatedString {
    val clusters = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(text) }
    val start = (text.length - span).coerceAtLeast(0)
    var index = if (clusters.isBoundary(start)) start else clusters.preceding(start)
    append(text, 0, index)
    while (index < text.length) {
        val next = clusters.following(index)
        val alpha = 1f - .85f * strength * (next - start) / (span + 1).toFloat()
        withStyle(SpanStyle(color = Paper.copy(alpha = alpha))) { append(text, index, next) }
        index = next
    }
}
