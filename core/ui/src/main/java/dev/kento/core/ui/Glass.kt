package dev.kento.core.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The optional "liquid glass" look: panels turn into frosted, softly lit glass over a black backdrop with slow
 * folds of light, as on recent iPhones. The classic OLED look stays the default; the switch is a plain interface
 * preference (not a secret), stored next to the language in `kento.ui` and applied at once, without a restart.
 */
object Appearance {
    private const val PREFS = "kento.ui"
    private const val GLASS = "glass"
    var glass by mutableStateOf(false)
        private set

    fun load(context: Context) {
        glass = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(GLASS, false)
    }

    fun setGlass(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(GLASS, on).apply()
        glass = on
    }
}

/** True inside the liquid-glass theme; components pick their fill and border from it. */
val LocalGlass = staticCompositionLocalOf { false }

/** Fill of glass panels: a little brighter at the top, where light falls on the pane. */
private val GlassFill = Brush.verticalGradient(listOf(Color.White.copy(alpha = .125f), Color.White.copy(alpha = .045f)))

/** The rim: a bright specular edge at the top left that fades, with a softer bounce of light at the bottom right. */
private val GlassRim = Brush.linearGradient(
    0f to Color.White.copy(alpha = .55f), .3f to Color.White.copy(alpha = .12f), .65f to Color.White.copy(alpha = .05f),
    1f to Color.White.copy(alpha = .3f), start = Offset.Zero, end = Offset.Infinite)

/** Light glass for the main action of a screen (save, new chat, send): milky and bright, with dark text on it. */
private val LightFill = Brush.verticalGradient(listOf(Color(0xFFF7F7F5), Color(0xFFC9C9C6)))
private val LightRim = Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = .35f), Color.White.copy(alpha = .8f)),
    start = Offset.Zero, end = Offset.Infinite)

/** A frosted glass pane; [tint] colours the rim, e.g. the accent for a quiet action. */
fun Modifier.glass(shape: Shape, tint: Color? = null): Modifier = clip(shape).background(GlassFill, shape)
    .border(1.dp, tint?.let { Brush.linearGradient(listOf(it.copy(alpha = .8f), Color.White.copy(alpha = .08f), it.copy(alpha = .45f)),
        start = Offset.Zero, end = Offset.Infinite) } ?: GlassRim, shape)

/** Milky light glass for the primary action of a screen. */
fun Modifier.lightGlass(shape: Shape): Modifier = clip(shape).background(LightFill, shape).border(1.dp, LightRim, shape)

/**
 * The common fill of a panel: [color] in the OLED theme, frosted glass in the liquid-glass one. [outline] keeps the
 * hairline border of OLED cards.
 */
@Composable
fun Modifier.surface(shape: Shape, color: Color, outline: Boolean = false, tint: Color? = null): Modifier =
    if (LocalGlass.current) glass(shape, tint)
    else clip(shape).background(color).then(if (outline) Modifier.border(1.dp, Line.copy(alpha = .45f), shape) else Modifier)

/** The background of a whole screen: plain [color] in the OLED theme, the lit backdrop in the glass one. */
@Composable
fun Screen(modifier: Modifier = Modifier, color: Color = Color.Black, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(Color.Black)) {
        if (LocalGlass.current) Backdrop(Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(color))
        content()
    }
}

/**
 * Black with two slow folds of light, like satin under a lamp: each fold is a soft band whose edge catches the light.
 * Static on purpose, so the screen does not redraw behind the text.
 */
@Composable
fun Backdrop(modifier: Modifier = Modifier, glow: Float = 1f) {
    // Its own layer: streaming text above it must not re-record these wide strokes on every frame.
    Canvas(modifier.graphicsLayer {}) {
        val w = size.width
        val h = size.height
        fold(Offset(w * 1.05f, h * .02f), Offset(w * .55f, h * .18f), Offset(w * .95f, h * .42f), Offset(-w * .1f, h * .62f), glow)
        fold(Offset(w * 1.1f, h * .64f), Offset(w * .7f, h * .72f), Offset(w * .45f, h * .9f), Offset(w * .2f, h * 1.05f), glow * .85f)
    }
}

private fun DrawScope.fold(start: Offset, c1: Offset, c2: Offset, end: Offset, glow: Float) {
    val edge = Path().apply { moveTo(start.x, start.y); cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y) }
    // The lit side of the fold: the area beyond the edge, brightest along it and fading into the dark.
    val body = Path().apply { addPath(edge); lineTo(size.width * 1.2f, size.height * 1.2f); lineTo(size.width * 1.2f, start.y); close() }
    val mid = Offset((c1.x + c2.x) / 2, (c1.y + c2.y) / 2)
    drawPath(body, Brush.radialGradient(listOf(Color.White.copy(alpha = .15f * glow), Color.White.copy(alpha = .045f * glow), Color.Transparent),
        center = mid, radius = size.maxDimension * .6f))
    // Wide soft halos and a fine bright line make the edge read as a curved, glossy surface of satin.
    for ((width, alpha) in listOf(220f to .018f, 110f to .03f, 46f to .045f, 16f to .08f, 5f to .14f, 1.6f to .42f)) {
        drawPath(edge, Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = alpha * glow), Color.White.copy(alpha = alpha * glow * .4f),
            Color.Transparent), start = start, end = end), style = Stroke(width * density / 2.6f, cap = StrokeCap.Round))
    }
}
