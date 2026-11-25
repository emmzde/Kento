package dev.kento.feature.chat

import dev.kento.core.text.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kento.core.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.*
import org.commonmark.parser.Parser
import androidx.compose.foundation.text.appendInlineContent
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints

private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
private val parser = Parser.builder().extensions(extensions).build()
private fun Node.children() = generateSequence(firstChild) { it.next }

/** One parsed answer: the Markdown tree and its formulas, drawn off the main thread. */
private class Rendered(val root: Node, val math: List<MathArt>, val formulas: List<Formula>)

/** Formulas of the answer being drawn, so paragraphs, lists and table cells all find theirs. */
private val LocalMath = staticCompositionLocalOf<Rendered?> { null }

/**
 * Answers parsed lately, so an answer that scrolls back into view (or a finished block of a streaming reply drawn
 * again) is not parsed a second time.
 */
private object ParsedCache {
    private val entries = object : LinkedHashMap<Pair<String, Float>, Rendered>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Float>, Rendered>) = size > 64
    }
    @Synchronized fun get(key: Pair<String, Float>) = entries[key]
    @Synchronized fun put(key: Pair<String, Float>, value: Rendered) { entries[key] = value }
}

/**
 * Text without formulas up to this size is parsed during composition: a few milliseconds, cheaper than drawing the raw
 * text first and the formatted one a frame later (which also flashed raw `**` and `|`). Longer text and formulas
 * (JLatexMath draws them) are parsed off the main thread.
 */
private const val QUICK_PARSE_CHARS = 12_000

private fun render(source: String, textPx: Float): Rendered {
    val lifted = liftMath(source)
    return Rendered(parser.parse(lifted.text), drawMath(lifted.formulas, textPx, Paper.toArgb()), lifted.formulas)
}

@Composable
fun Markdown(source: String) {
    val density = LocalDensity.current
    val textPx = with(density) { ReadingStyle.fontSize.toPx() }
    val quick = remember(source, textPx) {
        val key = source to textPx
        ParsedCache.get(key) ?: if (source.length <= QUICK_PARSE_CHARS && liftMath(source).formulas.isEmpty())
            render(source, textPx).also { ParsedCache.put(key, it) } else null
    }
    val slow by produceState<Rendered?>(null, source, textPx) {
        if (quick == null) value = withContext(Dispatchers.Default) { render(source, textPx).also { ParsedCache.put(source to textPx, it) } }
    }
    val document = quick ?: slow
    if (document == null) Text(source, color = Paper, style = ReadingStyle)
    else CompositionLocalProvider(LocalMath provides document) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            document.root.children().forEach { Block(it) }
        }
    }
}

@Composable
private fun Block(node: Node) {
    when (node) {
        is FencedCodeBlock -> CodeBlock(node.info.substringBefore(' '), node.literal.removeSuffix("\n"))
        is IndentedCodeBlock -> CodeBlock("", node.literal.removeSuffix("\n"))
        is Heading -> RichText(node, Modifier.padding(top = 6.dp), TextStyle(fontWeight = FontWeight.SemiBold,
            fontSize = (23 - 1.5f * node.level.coerceAtMost(5)).sp, lineHeight = (30 - 1.5f * node.level.coerceAtMost(5)).sp))
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.children().forEachIndexed { i, child -> Row {
                Text(if (node is OrderedList) "${node.markerStartNumber + i}." else "•", Modifier.widthIn(min = 22.dp), style = ReadingStyle, color = if (node is OrderedList) Muted else Paper)
                Column { child.children().forEach { Block(it) } }
            } }
        }
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Spacer(Modifier.width(2.dp).fillMaxHeight().background(Line))
            Column(Modifier.padding(start = 12.dp)) { node.children().forEach { Block(it) } }
        }
        is ThematicBreak -> HorizontalDivider(color = Line)
        is TableBlock -> Table(node)
        is org.commonmark.node.Paragraph -> {
            // A formula alone in its paragraph ($$…$$ on its own lines) is drawn as a display block.
            val only = (node.firstChild as? org.commonmark.node.Text)?.takeIf { node.firstChild == node.lastChild }?.literal?.trim()
            val index = only?.let { mathMark.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull()
            val math = LocalMath.current
            if (index != null && math != null && math.formulas.getOrNull(index)?.display == true) MathBlock(math.math[index], math.formulas[index].latex)
            else RichText(node)
        }
        else -> RichText(node)
    }
}

/** Inline Markdown with its formulas: drawn ones as inline pictures, unparsable ones as readable text. */
@Composable
private fun RichText(node: Node, modifier: Modifier = Modifier, style: TextStyle = ReadingStyle) {
    val math = LocalMath.current
    val text = remember(node, math) { inline(node, math) }
    Text(text, modifier, color = Paper, style = ReadingStyle.merge(style), inlineContent = mathInline(text, math?.math.orEmpty()))
}

private fun inline(root: Node, math: Rendered?): AnnotatedString = buildAnnotatedString {
    fun literal(text: String) {
        var last = 0
        for (match in mathMark.findAll(text)) {
            append(text, last, match.range.first)
            val index = match.groupValues[1].toInt()
            val art = math?.math?.getOrNull(index)
            when {
                art?.drawable != null -> appendInlineContent("math$index", art.fallback.ifBlank { "∑" })
                art != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(art.fallback) }
                else -> append(match.value)
            }
            last = match.range.last + 1
        }
        append(text, last, text.length)
    }
    fun visit(n: Node) {
        when (n) {
            is org.commonmark.node.Text -> literal(n.literal)
            is SoftLineBreak, is HardLineBreak -> append("\n")
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.5.sp, background = SurfaceHigh, color = Accent)) { append(n.literal) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { n.children().forEach(::visit) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { n.children().forEach(::visit) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Muted)) { n.children().forEach(::visit) }
            is Link -> {
                if (n.destination.startsWith("https://") || n.destination.startsWith("http://")) {
                    withLink(LinkAnnotation.Url(n.destination, TextLinkStyles(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline)))) {
                        n.children().forEach(::visit)
                    }
                } else n.children().forEach(::visit)
            }
            is HtmlInline -> append(n.literal)
            is HtmlBlock -> append(n.literal)
            else -> n.children().forEach(::visit)
        }
    }
    visit(root)
}

/**
 * A Markdown table as a real grid: columns as wide as their widest cell (up to a limit, then the text wraps),
 * a tinted bold header, hairlines between rows, and sideways scrolling when the table is wider than the screen.
 */
@Composable
private fun Table(table: TableBlock) {
    val rows = remember(table) {
        table.children().flatMap { section -> section.children().filterIsInstance<TableRow>().map { row -> (section is TableHead) to row } }.toList()
    }
    if (rows.isEmpty()) return
    val columns = rows.maxOf { (_, row) -> row.children().count() }
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        TableGrid(columns, Modifier.clip(shape).border(1.dp, Line.copy(alpha = .6f), shape)) {
            rows.forEachIndexed { r, (head, row) ->
                val cells = row.children().filterIsInstance<TableCell>().toList()
                for (c in 0 until columns) {
                    val cell = cells.getOrNull(c)
                    Box(Modifier.background(if (head) SurfaceHigh else if (r % 2 == 0) Night else Card)
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (cell != null) RichText(cell, style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp,
                            fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = when (cell.alignment) {
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            }))
                    }
                }
            }
        }
    }
}

/** Lays cells out row by row: each column takes its widest cell (max 260 dp), each row its tallest cell. */
@Composable
private fun TableGrid(columns: Int, modifier: Modifier, content: @Composable () -> Unit) {
    // The 1 px gaps between cells let this background show through as hairlines.
    Layout(content, modifier.background(Line.copy(alpha = .45f))) { measurables, _ ->
        val cap = 260.dp.roundToPx()
        val rowsCount = (measurables.size + columns - 1) / columns
        val widths = IntArray(columns) { c ->
            (0 until rowsCount).maxOf { r -> measurables.getOrNull(r * columns + c)?.maxIntrinsicWidth(Constraints.Infinity) ?: 0 }
                .coerceIn(48.dp.roundToPx(), cap)
        }
        val placeables = measurables.mapIndexed { i, m -> i to m }.groupBy { it.first / columns }.map { (_, row) ->
            val height = row.maxOf { (i, m) -> m.minIntrinsicHeight(widths[i % columns]) }
            row.map { (i, m) -> m.measure(Constraints.fixed(widths[i % columns], height)) }
        }
        val gap = 1
        val width = widths.sum() + gap * (columns - 1)
        val height = placeables.sumOf { it.first().height } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            placeables.forEach { row ->
                var x = 0
                row.forEachIndexed { c, p -> p.place(x, y); x += widths[c] + gap }
                y += row.first().height + gap
            }
        }
    }
}

@Composable
internal fun CodeBlock(language: String, code: String, streaming: Boolean = false) {
    val syntax = if (streaming) AnnotatedString(code) else produceState(AnnotatedString(code), code) {
        value = withContext(Dispatchers.Default) { highlight(code) }
    }.value
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp))
        .border(1.dp, Line.copy(alpha = .4f), RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 14.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(language.ifBlank { tr("Код") }, Modifier.weight(1f), color = Muted, fontSize = 12.sp)
            CopyAction(code, tr("Скопировать код"))
        }
        Text(syntax, Modifier.padding(end = 10.dp).horizontalScroll(rememberScrollState()), color = Paper,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, softWrap = false)
    }
}
