package dev.kento.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable

/** A formula found in an answer: `$…$` and `\(…\)` inline, `$$…$$` and `\[…\]` on their own. */
internal data class Formula(val latex: String, val display: Boolean)

/** The answer with every formula swapped for a placeholder commonmark leaves alone, plus the formulas in order. */
internal class MathSource(val text: String, val formulas: List<Formula>)

internal const val MATH_OPEN = ''
internal const val MATH_CLOSE = ''
internal val mathMark = Regex("$MATH_OPEN(\\d+)$MATH_CLOSE")
/** The annotation tag of androidx.compose.foundation.text.appendInlineContent. */
private const val INLINE_CONTENT = "androidx.compose.foundation.text.inlineContent"

/**
 * Lifts formulas out before Markdown parsing, which would otherwise eat their backslashes, underscores and
 * asterisks. Code blocks and code spans are copied untouched. A single `$` opens a formula only when a
 * non-space follows it and the closing `$` on the same line has a non-space before it and no digit after, so
 * "from $5 to $10" stays money.
 */
internal fun liftMath(source: String): MathSource {
    if ('$' !in source && "\\(" !in source && "\\[" !in source) return MathSource(source, emptyList())
    val out = StringBuilder(source.length)
    val formulas = mutableListOf<Formula>()
    var fence: String? = null
    var i = 0
    var lineStart = true
    fun formula(latex: String, display: Boolean) {
        out.append(MATH_OPEN).append(formulas.size).append(MATH_CLOSE)
        formulas += Formula(latex.trim(), display)
    }
    while (i < source.length) {
        if (lineStart) {
            val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
            val line = source.substring(i, end)
            val trimmed = line.trimStart()
            val mark = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
            val run = if (mark != null) trimmed.takeWhile { it == mark } else ""
            val open = fence
            if (open != null || run.length >= 3 && line.length - trimmed.length <= 3) {
                if (open == null) fence = run
                else if (trimmed.trimEnd().let { it.length >= open.length && it.all { c -> c == open[0] } }) fence = null
                out.append(line)
                if (end < source.length) out.append('\n')
                i = end + 1
                continue
            }
        }
        val c = source[i]
        val next = source.getOrNull(i + 1)
        lineStart = false
        when {
            c == '`' -> {
                val run = source.drop(i).takeWhile { it == '`' }
                val close = source.indexOf(run, i + run.length)
                val end = if (close < 0) i + run.length else close + run.length
                out.append(source, i, end)
                i = end
                continue
            }
            c == '\\' && next == '$' -> { out.append("\\$"); i += 2; continue }
            c == '\\' && (next == '[' || next == '(') -> {
                val closer = if (next == '[') "\\]" else "\\)"
                val close = source.indexOf(closer, i + 2)
                if (close > i + 2) {
                    formula(source.substring(i + 2, close), next == '[')
                    i = close + 2
                    continue
                }
            }
            c == '$' && next == '$' -> {
                val close = source.indexOf("$$", i + 2)
                if (close > i + 2) {
                    formula(source.substring(i + 2, close), true)
                    i = close + 2
                    continue
                }
            }
            c == '$' && next != null && !next.isWhitespace() -> {
                val lineEnd = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                var close = source.indexOf('$', i + 1)
                while (close in (i + 1) until lineEnd && (source[close - 1].isWhitespace() || source[close - 1] == '\\' ||
                        source.getOrNull(close + 1)?.isDigit() == true)) close = source.indexOf('$', close + 1)
                if (close in (i + 2) until lineEnd) {
                    formula(source.substring(i + 1, close), false)
                    i = close + 1
                    continue
                }
            }
        }
        out.append(c)
        if (c == '\n') lineStart = true
        i++
    }
    return MathSource(out.toString(), formulas)
}

/** A formula ready to draw, or null when the LaTeX does not parse (then [plainMath] shows it as text). */
internal class MathArt(val drawable: JLatexMathDrawable?, val fallback: String)

internal fun drawMath(formulas: List<Formula>, textPx: Float, color: Int): List<MathArt> = formulas.map { f ->
    val drawable = runCatching {
        JLatexMathDrawable.builder(f.latex).textSize(if (f.display) textPx * 1.12f else textPx).color(color)
            .align(JLatexMathDrawable.ALIGN_LEFT).build()
    }.getOrNull()?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
    MathArt(drawable, plainMath(f.latex))
}

/** Inline formulas of one paragraph as placeholders sized to the drawing; wide ones are scaled to fit. */
@Composable
internal fun mathInline(text: AnnotatedString, art: List<MathArt>): Map<String, InlineTextContent> {
    if (art.isEmpty()) return emptyMap()
    val density = LocalDensity.current
    val maxPx = with(density) { 300.sp.toPx() }
    // appendInlineContent leaves the formula's alternate text in the string and marks it with this annotation;
    // the placeholder characters are gone by now, so the IDs come from the annotations.
    return text.getStringAnnotations(INLINE_CONTENT, 0, text.length)
        .mapNotNull { it.item.removePrefix("math").toIntOrNull() }.distinct()
        .mapNotNull { index -> art.getOrNull(index)?.drawable?.let { index to it } }.associate { (index, drawable) ->
            val scale = minOf(1f, maxPx / drawable.intrinsicWidth)
            val w = drawable.intrinsicWidth * scale
            val h = drawable.intrinsicHeight * scale
            fun sp(px: Float) = (px / density.density / density.fontScale).sp
            "math$index" to InlineTextContent(Placeholder(sp(w), sp(h), PlaceholderVerticalAlign.TextCenter)) {
                MathCanvas(drawable, Modifier.fillMaxSize())
            }
        }
}

/** A formula on its own line: drawn at full size, scrolling sideways when it is wider than the screen. */
@Composable
internal fun MathBlock(art: MathArt, latex: String) {
    val drawable = art.drawable
    if (drawable == null) {
        androidx.compose.material3.Text(art.fallback, color = dev.kento.core.ui.Paper, style = dev.kento.core.ui.ReadingStyle)
        return
    }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)
        .semantics { contentDescription = latex }, contentAlignment = Alignment.CenterStart) {
        with(density) {
            MathCanvas(drawable, Modifier.size(drawable.intrinsicWidth.toDp(), drawable.intrinsicHeight.toDp()))
        }
    }
}

@Composable
private fun MathCanvas(drawable: JLatexMathDrawable, modifier: Modifier) {
    Canvas(modifier) {
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

private val symbols = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ",
    "eta" to "η", "theta" to "θ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
    "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Omega" to "Ω",
    "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
    "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡", "infty" to "∞", "to" to "→", "rightarrow" to "→",
    "leftarrow" to "←", "Rightarrow" to "⇒", "Leftrightarrow" to "⇔", "in" to "∈", "notin" to "∉", "subset" to "⊂",
    "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃", "partial" to "∂", "nabla" to "∇", "sum" to "∑",
    "prod" to "∏", "int" to "∫", "sqrt" to "√", "degree" to "°", "circ" to "°", "ldots" to "…", "cdots" to "⋯")
private val superscripts = "0123456789+-=()n".zip("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿ").toMap()
private val subscripts = "0123456789+-=()".zip("₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎").toMap()

/**
 * The formula as readable text for the rare LaTeX the renderer cannot parse: `\frac{a}{b}` becomes `(a)/(b)`,
 * Greek letters and operators become their characters, simple powers turn into superscripts. Never raw commands.
 */
internal fun plainMath(latex: String): String {
    var s = latex.replace(Regex("\\\\(left|right|displaystyle|,|;|!|quad|qquad)"), " ")
    repeat(4) {
        s = s.replace(Regex("\\\\[dt]?frac\\{([^{}]*)\\}\\{([^{}]*)\\}")) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
        s = s.replace(Regex("\\\\sqrt\\{([^{}]*)\\}")) { "√(${it.groupValues[1]})" }
        s = s.replace(Regex("\\\\(?:text|mathrm|mathbf|operatorname)\\{([^{}]*)\\}")) { it.groupValues[1] }
    }
    s = s.replace(Regex("\\^\\{([^{}]*)\\}|\\^(.)")) { m ->
        val v = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (v.all { it in superscripts }) v.map { superscripts.getValue(it) }.joinToString("") else "^($v)"
    }
    s = s.replace(Regex("_\\{([^{}]*)\\}|_(.)")) { m ->
        val v = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (v.all { it in subscripts }) v.map { subscripts.getValue(it) }.joinToString("") else "_$v"
    }
    s = s.replace(Regex("\\\\([A-Za-z]+)")) { symbols[it.groupValues[1]] ?: it.groupValues[1] }
    return s.replace("{", "").replace("}", "").replace(Regex("\\s+"), " ").trim()
}
