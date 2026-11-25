package dev.kento.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Line icons on a 24-unit grid. `weight` is the stroke width in grid units; `fill` paints the outline solid. */
enum class Glyph(val path: String, val weight: Float = 1.6f, val fill: Boolean = false) {
    Menu("M4 7h16 M4 12h16 M4 17h10"),
    Photo("M4 5h16v14H4z M4 16l5-5 4 4 3-3 4 4 M14.5 9a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"),
    Camera("M3 7.5h4l2-3h6l2 3h4V20H3z M8.5 13a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0"),
    Screen("M3 7V4h5 M16 4h5v3 M21 17v3h-5 M8 20H3v-3 M7 14l3-3 3 3 2-2 2 2"),
    Art("M4 6a2 2 0 0 1 2-2h9 M20 12v6a2 2 0 0 1 -2 2H6a2 2 0 0 1 -2-2V6 M5 17l5-5 4 4 2-2 4 4 M18 2l1.3 3.7L23 7l-3.7 1.3L18 12l-1.3-3.7L13 7l3.7-1.3z"),
    Search("M18 11a7 7 0 1 1 -14 0a7 7 0 1 1 14 0 M16.1 16.1L20.5 20.5"),
    Clip("M8 13l6.2-6.2a2.1 2.1 0 0 1 3 3L9.8 17a4 4 0 0 1 -5.6 -5.6l8-8a5.5 5.5 0 0 1 7.8 7.8l-8.5 8.5"),
    Mic("M9 5.5a3 3 0 0 1 6 0v5.5a3 3 0 0 1 -6 0z M5.5 10.8a6.5 6.5 0 0 0 13 0 M12 17.3v3.2"),
    Close("M6 6l12 12 M18 6L6 18"),
    Right("M9.5 6l6 6 -6 6"),
    Plus("M12 5v14 M5 12h14"),
    New("M11 4.5H7a2.5 2.5 0 0 0 -2.5 2.5v10A2.5 2.5 0 0 0 7 19.5h10a2.5 2.5 0 0 0 2.5 -2.5v-4.5 M17.4 4a1.9 1.9 0 0 1 2.7 2.7L12.4 14.4 9.3 15.1 10 12z"),
    More("M12 5.5h.01 M12 12h.01 M12 18.5h.01", weight = 2.6f),
    Settings("M4 8h9 M17 8h3 M4 16h3 M11 16h9 M13 8a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M7 16a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
    Globe("M20.5 12a8.5 8.5 0 1 1 -17 0a8.5 8.5 0 1 1 17 0 M3.5 12h17 M12 3.5c2.3 2.4 3.5 5.2 3.5 8.5s-1.2 6.1 -3.5 8.5c-2.3 -2.4 -3.5 -5.2 -3.5 -8.5S9.7 5.9 12 3.5z"),
    Book("M5 6a2 2 0 0 1 2 -2h11v13H7a2 2 0 0 0 -2 2z M5 19a2 2 0 0 0 2 2h11v-4 M9 8.5h5"),
    Down("M7 10l5 5 5 -5"),
    Back("M19.5 12H5 M11 6l-6 6 6 6"),
    Up("M12 19V5.5 M6 11.5l6 -6 6 6", weight = 1.9f),
    Stop("M9.5 7.5h5a2 2 0 0 1 2 2v5a2 2 0 0 1 -2 2h-5a2 2 0 0 1 -2 -2v-5a2 2 0 0 1 2 -2z", fill = true),
    Copy("M9 10a1.5 1.5 0 0 1 1.5 -1.5h8A1.5 1.5 0 0 1 20 10v8a1.5 1.5 0 0 1 -1.5 1.5h-8A1.5 1.5 0 0 1 9 18z M15 8.5V6a1.5 1.5 0 0 0 -1.5 -1.5h-8A1.5 1.5 0 0 0 4 6v8a1.5 1.5 0 0 0 1.5 1.5H9"),
    Download("M12 4v11 M7 10.5l5 5 5 -5 M5 19.5h14"),
    Refresh("M19.5 12a7.5 7.5 0 1 1 -2.2 -5.3 M19.5 4.5v3.7h-3.7"),
    Check("M5 12.5l4.5 4.5L19 7.5"),
    Brain("M5 12h3l2 -6 4 12 2 -6h3"),
    Trash("M4.5 7h15 M9.5 7V4.5h5V7 M6.5 7l.8 12.5h9.4L17.5 7 M10 10.5v5.5 M14 10.5v5.5"),
    Chat("M12 5c4.1 0 7.5 2.8 7.5 6.3s-3.4 6.2 -7.5 6.2c-.9 0 -1.8 -.13 -2.6 -.38L5.5 19l.95 -3.1C5.2 14.7 4.5 13.1 4.5 11.3 4.5 7.8 7.9 5 12 5z"),
    Eye("M2.5 12Q12 -1.5 21.5 12Q12 25.5 2.5 12z M10 12a2 2 0 1 1 4 0a2 2 0 1 1 -4 0"),
    File("M6.5 3.5h7.5l4.5 4.5v11a1.5 1.5 0 0 1 -1.5 1.5h-10.5a1.5 1.5 0 0 1 -1.5 -1.5v-14a1.5 1.5 0 0 1 1.5 -1.5z M14 3.5V8h4.5 M8.5 13h7 M8.5 16.5h4.5"),
    Wave("M4 10v4 M8 7v10 M12 4v16 M16 7v10 M20 10v4", weight = 2f),
    Share("M12 14.5V4 M7.5 8.5L12 4l4.5 4.5 M5 12.5v6A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5 -1.5v-6"),
    Plug("M9 3.5v4.5 M15 3.5v4.5 M6.5 8h11v3a5.5 5.5 0 0 1 -11 0z M12 16.5v4"),
    Spark("M12 3.5l2 6.5 6.5 2 -6.5 2 -2 6.5 -2 -6.5 -6.5 -2 6.5 -2z"),
    Person("M16 8a4 4 0 1 1 -8 0a4 4 0 1 1 8 0 M4.5 20.5c.8 -3.6 3.8 -5.5 7.5 -5.5s6.7 1.9 7.5 5.5"),
    Info("M20.5 12a8.5 8.5 0 1 1 -17 0a8.5 8.5 0 1 1 17 0 M12 11v5.5 M12 7.8h.01"),
    Clock("M20.5 12a8.5 8.5 0 1 1 -17 0a8.5 8.5 0 1 1 17 0 M12 7.5V12l3 2"),
    Edit("M4.5 19.5l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3L8.5 18z M13.5 7l3 3"),
    Pin("M9 3.8h6 M10 3.8v5.4L7.2 13.4h9.6L14 9.2V3.8 M12 13.4v6.8"),
    /** Spending: a dial with its needle. */
    Gauge("M4 17a8 8 0 1 1 16 0 M12 17l3.6 -4.6 M6.8 11.6l.9 .6 M12 9v1 M17.2 11.6l-.9 .6"),
    Github("M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12", fill = true)
}

@Composable
fun Symbol(glyph: Glyph, label: String? = null, color: Color = Paper, size: Dp = 20.dp) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    Canvas(Modifier.size(size).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)) {
        withTransform({ scale(this@Canvas.size.width / 24f, this@Canvas.size.height / 24f, androidx.compose.ui.geometry.Offset.Zero) }) {
            drawPath(path, color, style = if (glyph.fill) Fill else Stroke(glyph.weight, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
