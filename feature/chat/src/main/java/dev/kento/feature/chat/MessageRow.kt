package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.db.Message
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

@Composable
internal fun MessageRow(row: Message, live: StateFlow<DraftReply?>, progress: StateFlow<String>, retry: (() -> Unit)?,
    edit: (() -> Unit)? = null, trail: StateFlow<LiveTrail?> = kotlinx.coroutines.flow.MutableStateFlow(null), skip: () -> Unit = {}) {
    if (row.role == "user") {
        // A tap on the bubble shows "copy" and "edit" under it, as in ChatGPT; another tap hides them.
        var actions by rememberSaveable(row.id) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(start = 56.dp), horizontalAlignment = Alignment.End) {
            val bubbleShape = RoundedCornerShape(22.dp)
            Text(row.text, Modifier.clip(bubbleShape).background(LocalBubble.current.fill)
                .then(if (LocalGlass.current) Modifier.glass(bubbleShape) else Modifier)
                .clickable(role = Role.Button) { actions = !actions }
                .padding(horizontal = 16.dp, vertical = 10.dp), color = Paper, fontSize = 16.5.sp, lineHeight = 23.sp)
            AnimatedVisibility(actions, enter = fadeIn(tween(180)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
                Row(Modifier.offset(x = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CopyAction(row.text, tr("Скопировать сообщение"))
                    if (edit != null) IconButton(onClick = { actions = false; edit() }, modifier = Modifier.size(40.dp)) {
                        Symbol(Glyph.Edit, tr("Изменить сообщение"), Muted, 18.dp)
                    }
                }
            }
        }
        return
    }
    val active = row.state == "active"
    val delta = if (active) live.collectAsStateWithLifecycle().value?.takeIf { it.id == row.id } else null
    val target = delta?.text ?: row.text
    val answer = smoothed(target, active)
    // Until the visible text has caught up with the received one, it keeps the streaming renderer: the full Markdown
    // renderer parses the whole reply off the main thread, and re-parsing it every frame of the catch-up froze the
    // screen and then showed the rest in one jump.
    val typing = active || answer.length < target.length
    val thoughts = delta?.thoughts ?: row.thoughts
    val step = if (active) progress.collectAsStateWithLifecycle().value else ""
    val liveTrail = if (active) trail.collectAsStateWithLifecycle().value?.takeIf { it.id == row.id }?.trail else null
    val saved = remember(row.research, row.sources) {
        dev.kento.data.web.Trail.decode(row.research) ?: row.sourceList().takeIf { it.isNotEmpty() }?.let(dev.kento.data.web.Trail::ofSources)
    }
    val research = liveTrail ?: saved
    var sheet by remember { mutableStateOf(false) }
    // The text written before the first search (what the model is going to check) stays above the search line.
    val split = research?.at?.takeIf { research.queries.isNotEmpty() && it >= 0 }?.coerceAtMost(answer.length)
    val before = if (split != null) answer.substring(0, split) else answer
    val after = if (split != null) answer.substring(split) else ""
    // The line shimmers only while lookups actually run; between and after them the model is thinking, and the
    // thoughts line says so.
    val searching = active && split != null && step.isNotBlank()
    val pondering = active && (answer.isBlank() || split != null && after.isBlank() && step.isBlank())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (thoughts.isNotBlank()) Thoughts(row.id, thoughts, thinking = pondering)
        if (before.isNotBlank()) SelectionContainer {
            if (typing) StreamingMarkdown(before.trimEnd())
            else Markdown(before.trimEnd())
        }
        if (split != null && research != null) SearchLine(research, searching, skip.takeIf { searching }) { sheet = true }
        if (after.isNotBlank()) SelectionContainer {
            if (typing) StreamingMarkdown(after.trimStart())
            else Markdown(after.trimStart())
        }
        if (sheet && research != null) SourcesSheet(research) { sheet = false }
        when (row.state) {
            "failed" -> Notice(row.issue.ifBlank { tr("Ответ не получен") }, pulse = false)
            "stopped" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(Muted.copy(alpha = .7f)))
                Text(tr("Остановлено"), color = Muted, fontSize = 13.5.sp)
            }
            "active" -> if (thoughts.isBlank() && (answer.isEmpty() && split == null || split != null && pondering)) {
                Row(Modifier.heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dots()
                    AnimatedContent(step, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "step") {
                        Text(it, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        AnimatedVisibility(!active && (answer.isNotEmpty() || retry != null),
            enter = fadeIn(tween(260, 120)), exit = fadeOut(tween(80))) {
            Row(Modifier.fillMaxWidth().offset(x = (-10).dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (answer.isNotEmpty()) {
                    CopyAction(answer, tr("Скопировать ответ"))
                    ShareAction(answer)
                }
                if (retry != null) IconButton(onClick = retry, modifier = Modifier.size(40.dp)) {
                    Symbol(Glyph.Refresh, tr("Повторить ответ"), Muted, 18.dp)
                }
                if (research != null && research.visits.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.offset(x = 10.dp)) { SourcesButton(research) { sheet = true } }
                }
            }
        }
    }
}

@Composable
private fun ShareAction(text: String) {
    val context = LocalContext.current
    IconButton(onClick = {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }, modifier = Modifier.size(40.dp)) { Symbol(Glyph.Share, tr("Поделиться ответом"), Muted, 18.dp) }
}

/**
 * Reasoning as a quiet disclosure line, the way mobile assistants show it: while the model thinks the label shimmers and
 * the newest line of thought drifts underneath; afterwards one muted line opens the full text beside a hairline.
 */
@Composable
private fun Thoughts(id: Long, thoughts: String, thinking: Boolean) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = .8f, stiffness = 500f), label = "chevron")
    Column(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 1f, stiffness = 420f))) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open = !open }
            .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (thinking) Shimmer(tr("Думает")) else Text(tr("Мысли модели"), color = Muted, fontSize = 15.sp)
            Box(Modifier.rotate(turn)) { Symbol(Glyph.Right, if (open) tr("Свернуть") else tr("Раскрыть"), Muted, 13.dp) }
        }
        if (open) Row(Modifier.padding(top = 6.dp, bottom = 2.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(1.5.dp).fillMaxHeight().clip(CircleShape).background(Line))
            SelectionContainer { Text(thoughts.trim(), Modifier.padding(start = 14.dp), color = Muted, fontSize = 14.5.sp, lineHeight = 22.sp) }
        } else if (thinking) {
            val last = remember(thoughts) { thoughts.trimEnd().substringAfterLast('\n').trim() }
            AnimatedContent(last, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "latest") {
                Text(it, Modifier.padding(top = 2.dp), color = Muted.copy(alpha = .6f), fontSize = 14.sp, lineHeight = 20.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Text with a soft light band sweeping across it. */
@Composable
internal fun Shimmer(text: String) {
    val sweep = rememberInfiniteTransition(label = "shimmer")
    val x by sweep.animateFloat(-1f, 2f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "x")
    val band = 120f
    val brush = Brush.linearGradient(listOf(Muted.copy(alpha = .55f), Paper, Muted.copy(alpha = .55f)),
        start = androidx.compose.ui.geometry.Offset(x * band * 1.4f - band, 0f),
        end = androidx.compose.ui.geometry.Offset(x * band * 1.4f, 0f))
    Text(text + "…", style = TextStyle(brush = brush, fontSize = 15.sp))
}

/**
 * The reply as it should appear on screen: providers send text in uneven bursts (and research rounds release a held
 * paragraph at once), so the visible text catches up with the received one frame by frame, faster the further it
 * lags. Replies that were never live in this composition (history) show at once.
 */
@Composable
internal fun smoothed(target: String, live: Boolean): String {
    var wasLive by remember { mutableStateOf(live) }
    if (live) wasLive = true
    if (!wasLive) return target
    val latest by rememberUpdatedState(target)
    val streaming by rememberUpdatedState(live)
    var count by remember { mutableIntStateOf(0) }
    // Restarts whenever the reply goes live again (a retry reuses the same message and starts from empty text).
    LaunchedEffect(live) {
        while (true) {
            if (count >= latest.length) {
                count = latest.length
                if (!streaming) break
                // Nothing to show yet: sleep until more text arrives (no frames, so the screen and tests stay idle).
                androidx.compose.runtime.snapshotFlow { latest.length != count || !streaming }.first { it }
                continue
            }
            androidx.compose.runtime.withFrameMillis { }
            // A steady pace that drains a burst over about half a second instead of dumping it in a few frames;
            // a large backlog (a long text released at once) still catches up within a second or two.
            val backlog = latest.length - count
            count += (backlog / 24).coerceIn(if (backlog > 2_000) 40 else 1, 80)
        }
    }
    var end = count.coerceAtMost(latest.length)
    if (end in 1 until latest.length && latest[end - 1].isHighSurrogate()) end--
    return latest.substring(0, end)
}
