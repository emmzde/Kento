package dev.kento.core.ui

import dev.kento.core.text.tr

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val GroupShape = RoundedCornerShape(22.dp)

/** A rounded card of rows. On black screens pass [Card]; inside sheets the default stands out from [Panel]. */
@Composable
fun ChoiceGroup(color: Color = SurfaceHigh, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().surface(GroupShape, color, outline = color == Card), content = content)
}

@Composable
fun ChoiceDivider(inset: Boolean = false) = HorizontalDivider(Modifier.padding(start = if (inset) 52.dp else 0.dp),
    thickness = 1.dp, color = Color.White.copy(alpha = .06f))

@Composable
fun NavigationRow(title: String, value: String = "", icon: Glyph? = null, subtitle: String = "", tint: Color = Paper,
    trailing: Glyph? = Glyph.Right, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Symbol(icon, color = tint, size = 21.dp)
            Spacer(Modifier.width(15.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = tint, fontSize = 16.sp, lineHeight = 21.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.5.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value.isNotEmpty()) Text(value, Modifier.padding(start = 12.dp).widthIn(max = 170.dp), textAlign = TextAlign.End,
            color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Symbol(trailing, color = Muted, size = 15.dp)
        }
    }
}

/** A row with a switch; the whole row toggles. */
@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String = "", icon: Glyph? = null, enabled: Boolean = true,
    onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.heightIn(min = 56.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Symbol(icon, color = Paper, size = 21.dp)
            Spacer(Modifier.width(15.dp))
        }
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else .5f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Paper, fontSize = 16.sp, lineHeight = 21.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.5.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, null, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = Panel, checkedTrackColor = Accent,
            uncheckedThumbColor = Muted, uncheckedTrackColor = Raised, uncheckedBorderColor = Line))
    }
}

@Composable
internal fun OptionRow(title: String, checked: Boolean, subtitle: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 54.dp)
        .padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Paper, fontSize = 16.sp, lineHeight = 21.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (checked) {
            Spacer(Modifier.width(10.dp))
            Symbol(Glyph.Check, tr("Выбрано"), Accent)
        }
    }
}

/**
 * A text input drawn as a card: the caption sits inside, above the value, so the whole card reads as one field
 * (and assistive services and tests find it by its caption).
 */
@Composable
fun FieldCard(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 12,
    keyboard: KeyboardOptions = KeyboardOptions.Default, hidden: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null) {
    BasicTextField(value, change, modifier.fillMaxWidth(), singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        keyboardOptions = keyboard, visualTransformation = hidden, cursorBrush = SolidColor(Accent),
        textStyle = TextStyle(color = Paper, fontSize = 16.sp, lineHeight = 22.sp),
        decorationBox = { field ->
            Row(Modifier.fillMaxWidth().surface(GroupShape, Card, outline = true)
                .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 11.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label, color = Muted, fontSize = 12.5.sp, lineHeight = 16.sp)
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Muted.copy(alpha = .55f),
                            fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        field()
                    }
                }
                trailing?.invoke()
            }
        })
}

/**
 * A quiet search line for long lists (models, image models): a rounded pill with a magnifier, the hint inside and a
 * clear button once something is typed. Tests and screen readers find it by its [hint].
 */
@Composable
fun SearchField(query: String, change: (String) -> Unit, hint: String, modifier: Modifier = Modifier, color: Color = SurfaceHigh) {
    BasicTextField(query, change, modifier.fillMaxWidth(), singleLine = true, cursorBrush = SolidColor(Accent),
        textStyle = TextStyle(color = Paper, fontSize = 15.5.sp, lineHeight = 20.sp),
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        decorationBox = { field ->
            Row(Modifier.fillMaxWidth().height(44.dp).surface(CircleShape, color).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Symbol(Glyph.Search, color = Muted, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(hint, color = Muted.copy(alpha = .8f), fontSize = 15.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
                if (query.isNotEmpty()) Box(Modifier.size(32.dp).clip(CircleShape).clickable { change("") }, contentAlignment = Alignment.Center) {
                    Symbol(Glyph.Close, tr("Очистить поиск"), Muted, 14.dp)
                }
            }
        })
}

/** Muted footnote under a card. */
@Composable
fun Hint(text: String) {
    Text(text, Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp), color = Muted.copy(alpha = .8f), fontSize = 13.sp, lineHeight = 18.sp)
}

/** A full-width quiet action, e.g. "check the connection", drawn as a card with accent text. */
@Composable
fun CardAction(label: String, action: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).surface(GroupShape, Card, outline = true, tint = Accent.takeIf { enabled })
        .clickable(enabled = enabled, onClick = action).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Accent else Muted, fontSize = 15.5.sp)
    }
}

enum class Tone { Info, Problem }

/**
 * A quiet status line: a small pulsing pair of dots followed by muted text. Used for connection problems,
 * failed replies and check results instead of bare red text.
 */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Problem, pulse: Boolean = true) {
    val color = if (tone == Tone.Problem) Warning else Accent
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(21.dp), contentAlignment = Alignment.Center) { StatusDots(color, pulse) }
        Text(text, color = Muted, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun StatusDots(color: Color, pulse: Boolean) {
    val beat = if (pulse) {
        val wave = rememberInfiniteTransition(label = "notice")
        wave.animateFloat(1f, .55f, infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse), label = "beat").value
    } else 1f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(7.dp).scale(.85f + .15f * beat).clip(CircleShape).background(color))
        Box(Modifier.size(4.dp).alpha(beat).clip(CircleShape).background(color.copy(alpha = .7f)))
    }
}

fun effortName(id: String): String = when (id) {
    "" -> tr("Авто")
    "none", "off", "enabled:false" -> tr("Выключен")
    "enabled:true" -> tr("Включён")
    "minimal" -> tr("Минимальный")
    "low" -> tr("Низкий")
    "medium" -> tr("Средний")
    "high" -> tr("Высокий")
    "xhigh" -> tr("Сверхвысокий")
    "max" -> tr("Максимальный")
    else -> if (id.startsWith("budget:")) id.substringAfter(':') + tr(" ток.") else id
}

fun modelName(id: String) = id
