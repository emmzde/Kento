package dev.kento.feature.chat

import dev.kento.core.text.tr

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kento.core.ui.Accent
import kotlin.math.PI
import kotlin.math.sin

/**
 * Kento's mark and its "thinking" state: three dots. While waiting a soft wave runs through them,
 * each dot swelling and brightening a little after the previous one.
 */
@Composable
internal fun Dots(modifier: Modifier = Modifier, animated: Boolean = true, dot: Dp = 7.dp, color: Color = Accent) {
    val phase = if (animated) {
        val wave = rememberInfiniteTransition(label = "dots")
        val value by wave.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
        value
    } else -1f
    Canvas(modifier.size(dot * 4.6f, dot * 1.6f).semantics { if (animated) contentDescription = tr("Kento думает") }) {
        val radius = dot.toPx() / 2
        val gap = size.width / 3
        for (index in 0..2) {
            val wave = if (phase < 0) 1f else {
                val local = ((phase - index * .16f) % 1f + 1f) % 1f
                // Only the first half of each cycle lifts the dot; the rest is a quiet pause.
                if (local < .5f) sin(local * 2 * PI).toFloat().coerceAtLeast(0f) else 0f
            }
            val scale = if (phase < 0) 1f else .62f + .38f * wave
            val alpha = if (phase < 0) 1f else .38f + .62f * wave
            drawCircle(color.copy(alpha = alpha), radius * scale, Offset(gap * index + gap / 2, size.height / 2))
        }
    }
}
