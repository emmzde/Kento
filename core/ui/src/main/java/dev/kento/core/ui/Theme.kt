package dev.kento.core.ui

import dev.kento.core.text.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFAAC5EA)
val Panel = Color(0xFF171716)
val SurfaceHigh = Color(0xFF242422)
/** Chips and pressed controls that sit on top of [SurfaceHigh]. */
val Raised = Color(0xFF31312E)
/** Grouped cards on the pure black of settings and other OLED screens. */
val Card = Color(0xFF161615)
val Line = Color(0xFF383834)
val Muted = Color(0xFFAAA9A3)
val Paper = Color(0xFFF3F1EC)
/** Colours the user can pick for their own messages: a deep fill for the bubble and a bright swatch for the picker. */
enum class BubbleTone(val id: String, val title: String, val fill: Color, val swatch: Color) {
    Blue("blue", tr("Синий"), Color(0xFF1C3158), Color(0xFF5B8DEF)),
    Violet("violet", tr("Фиолетовый"), Color(0xFF30285A), Color(0xFF9B7BEA)),
    Green("green", tr("Зелёный"), Color(0xFF1C3D31), Color(0xFF4FBF8A)),
    Amber("amber", tr("Янтарный"), Color(0xFF473217), Color(0xFFE7A44A)),
    Rose("rose", tr("Розовый"), Color(0xFF4A2137), Color(0xFFE57AA6)),
    Graphite("graphite", tr("Графит"), Color(0xFF2B2B29), Color(0xFF9C9C96));
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: Blue }
}
val LocalBubble = androidx.compose.runtime.compositionLocalOf { BubbleTone.Blue }
/** Soft coral for problems; calmer than Material's error red on OLED black. */
val Warning = Color(0xFFE98C73)
/** Drawer and secondary screens sit one step above the OLED black of the chat. */
val Night = Color(0xFF0E0E0D)
/** Replies are set in the system sans like the rest of the interface, sized for long reading. */
val ReadingStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.5.sp, lineHeight = 25.sp, letterSpacing = .1.sp)

@Composable
fun KentoTheme(glass: Boolean = Appearance.glass, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides glass) { MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, onPrimary = Panel, background = Color.Black,
        surface = Panel, surfaceContainerLow = Panel, surfaceContainer = SurfaceHigh, surfaceContainerHigh = SurfaceHigh,
        surfaceContainerHighest = SurfaceHigh, surfaceContainerLowest = Night, secondaryContainer = Raised, onSecondaryContainer = Paper,
        onSurface = Paper, onBackground = Paper, onSurfaceVariant = Muted, error = Warning,
        outline = Line, outlineVariant = Line, surfaceTint = Color.Transparent
    ), content = content) }
}

/** Sheets and menus: solid panel in the OLED theme, a darker smoked glass in the glass one (text stays readable). */
val sheetColor: Color @Composable get() = if (LocalGlass.current) Color(0xFF111113) else Panel
val menuColor: Color @Composable get() = if (LocalGlass.current) Color(0xFF1D1D20) else SurfaceHigh

@Composable
fun ToolButton(icon: Glyph, label: String, action: () -> Unit, enabled: Boolean = true, tint: Color = Paper, size: Dp = 22.dp) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Symbol(icon, label, if (enabled) tint else Muted.copy(alpha = .45f), size)
    }
}

/** A round 44 dp button on a filled disc, as used in the top bars. */
@Composable
fun CircleButton(icon: Glyph, label: String, action: () -> Unit, enabled: Boolean = true, background: Color = SurfaceHigh,
    tint: Color = Paper, size: Dp = 20.dp) {
    Box(Modifier.size(44.dp).surface(CircleShape, background).clickable(enabled = enabled, role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center) {
        Symbol(icon, label, if (enabled) tint else tint.copy(alpha = .4f), size)
    }
}

/** Shared title row for secondary screens: round back button, centred title, optional trailing action. */
@Composable
fun ScreenBar(title: String, back: () -> Unit, backEnabled: Boolean = true, trailing: @Composable () -> Unit = { Spacer(Modifier.size(44.dp)) }) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.widthIn(min = 92.dp)) { CircleButton(Glyph.Back, tr("Назад"), back, backEnabled) }
        Text(title, Modifier.weight(1f), color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
        Box(Modifier.widthIn(min = 92.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

/** A compact filled pill for the main action of a screen bar, such as saving. */
@Composable
fun BarAction(label: String, action: () -> Unit, enabled: Boolean = true) {
    val glass = LocalGlass.current
    Box(Modifier.height(40.dp).then(when {
        glass && enabled -> Modifier.lightGlass(CircleShape)
        glass -> Modifier.glass(CircleShape)
        else -> Modifier.clip(CircleShape).background(if (enabled) Paper else SurfaceHigh)
    }).clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Panel else Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(start = 6.dp, top = 20.dp, bottom = 2.dp), color = Muted, fontSize = 13.5.sp)
}
