package dev.kento.feature.chat

import dev.kento.core.text.tr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.db.Conversation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Every chat, newest first, with a search pill at the bottom where the thumb already is. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentsScreen(vm: ChatViewModel, searchFirst: Boolean, back: () -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    val active by vm.activeId.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val canSwitch = ready && !sending
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Selection mode: "Выбрать" in the bar or in a chat's menu, then taps toggle chats and the bottom pill deletes them.
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val feedback = LocalHapticFeedback.current
    val export = rememberChatExport(vm)
    fun leaveSelection() { selecting = false; selected = emptyList() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    BackHandler { if (selecting) leaveSelection() else back() }
    LaunchedEffect(Unit) { if (searchFirst) runCatching { field.requestFocus() } }
    // Titles match at once; the text of every message is searched a moment after typing stops.
    var inside by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { inside = emptyMap(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        inside = vm.findInChats(query)
    }
    val filtered = remember(history, query, inside) {
        history.filter { it.title.contains(query.trim(), ignoreCase = true) || it.id in inside }
    }
    // Chats deleted elsewhere drop out of the selection.
    LaunchedEffect(history) {
        val ids = history.map { it.id }.toSet()
        if (selected.any { it !in ids }) selected = selected.filter { it in ids }
    }

    Screen(Modifier.fillMaxSize(), Night) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        if (selecting) {
            val all = filtered.isNotEmpty() && filtered.all { it.id in selected }
            ScreenBar(if (selected.isEmpty()) tr("Выберите чаты") else tr("Выбрано: {0}", selected.size), ::leaveSelection) {
                BarAction(if (all) tr("Снять") else tr("Все"), {
                    val shown = filtered.map { it.id }
                    selected = if (all) selected - shown.toSet() else (selected + shown).distinct()
                }, filtered.isNotEmpty())
            }
        } else ScreenBar(tr("Чаты"), back) {
            if (history.isNotEmpty()) BarAction(tr("Выбрать"), { focus.clearFocus(); selecting = true }, canSwitch)
            else Spacer(Modifier.size(44.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            if (filtered.isEmpty()) item {
                Text(if (history.isEmpty()) tr("Пока нет переписок") else tr("Совпадений нет"),
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp), color = Muted, fontSize = 15.sp)
            }
            items(filtered, key = { it.id }) { chat ->
                val checked = chat.id in selected
                Box(Modifier.animateItem()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (checked) Accent.copy(alpha = .14f) else if (!selecting && chat.id == active) SurfaceHigh else Night)
                        .combinedClickable(enabled = canSwitch, onLongClick = {
                            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (selecting) toggle(chat.id) else menuFor = chat.id
                        }) { if (selecting) toggle(chat.id) else { vm.select(chat.id); back() } }
                        .semantics { if (selecting) contentDescription = (if (checked) tr("Выбран: ") else tr("Не выбран: ")) + chat.title }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.padding(top = 1.dp).size(20.dp), contentAlignment = Alignment.Center) {
                            if (selecting) SelectMark(checked)
                            else Symbol(if (chat.pinned) Glyph.Pin else Glyph.Chat, color = Muted, size = 16.dp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(chat.title, color = Paper, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val found = inside[chat.id]?.takeIf { query.isNotBlank() && !chat.title.contains(query.trim(), ignoreCase = true) }
                            if (found != null) Text(found, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            else Text(whenText(chat.updatedAt), color = Muted, fontSize = 13.sp)
                        }
                    }
                    DropdownMenu(menuFor == chat.id, { menuFor = null }, containerColor = SurfaceHigh) {
                        DropdownMenuItem(text = { Text(tr("Выбрать")) }, onClick = { menuFor = null; selecting = true; selected = listOf(chat.id) },
                            leadingIcon = { Symbol(Glyph.Check, color = Paper, size = 18.dp) })
                        DropdownMenuItem(text = { Text(if (chat.pinned) tr("Открепить") else tr("Закрепить")) },
                            onClick = { menuFor = null; vm.pin(chat.id, !chat.pinned) },
                            leadingIcon = { Symbol(Glyph.Pin, color = Paper, size = 18.dp) })
                        DropdownMenuItem(text = { Text(tr("Экспортировать")) }, onClick = { menuFor = null; export(chat.id) },
                            leadingIcon = { Symbol(Glyph.Download, color = Paper, size = 18.dp) })
                        DropdownMenuItem(text = { Text(tr("Удалить чат")) }, onClick = { menuFor = null; deleting = setOf(chat.id) },
                            leadingIcon = { Symbol(Glyph.Trash, color = Paper, size = 18.dp) })
                    }
                }
            }
        }
        if (selecting) {
            val count = selected.size
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).height(48.dp).clip(CircleShape)
                .background(if (count > 0) Warning.copy(alpha = .16f) else SurfaceHigh)
                .clickable(enabled = count > 0 && canSwitch, role = Role.Button) { deleting = selected.toSet() }
                .semantics(mergeDescendants = true) { contentDescription = tr("Удалить выбранные чаты") },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Symbol(Glyph.Trash, color = if (count > 0) Warning else Muted, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (count > 0) tr("Удалить ({0})", count) else tr("Удалить"), color = if (count > 0) Warning else Muted, fontSize = 15.sp)
            }
        } else Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).height(48.dp).clip(CircleShape).background(SurfaceHigh)
                .clickable { runCatching { field.requestFocus() } }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(Glyph.Search, color = Muted, size = 18.dp)
                BasicTextField(query, { query = it }, Modifier.weight(1f).focusRequester(field)
                    .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = tr("Найти чат") },
                    singleLine = true, textStyle = TextStyle(color = Paper, fontSize = 16.sp), cursorBrush = SolidColor(Accent),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text(tr("Поиск"), color = Muted, fontSize = 16.sp); inner() } })
            }
            AnimatedVisibility(focused || query.isNotEmpty(), enter = fadeIn() + scaleIn(initialScale = .7f), exit = fadeOut() + scaleOut(targetScale = .7f)) {
                ToolButton(Glyph.Close, tr("Закрыть поиск"), { query = ""; focus.clearFocus() })
            }
        }
    }
    }
    if (deleting.isNotEmpty()) {
        val ids = deleting
        DeleteChatDialog({
            if (ids.size == 1) vm.delete(ids.single()) else vm.deleteMany(ids)
            deleting = emptySet()
            if (selecting) leaveSelection()
        }, { deleting = emptySet() }, ids.size)
    }
}

/** A round check mark for selection mode: filled with a tick when chosen, an outline otherwise. */
@Composable
private fun SelectMark(checked: Boolean) {
    Box(Modifier.size(20.dp).clip(CircleShape).background(if (checked) Accent else Night)
        .border(1.5.dp, if (checked) Accent else Muted, CircleShape), contentAlignment = Alignment.Center) {
        if (checked) Symbol(Glyph.Check, color = Panel, size = 14.dp)
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
private val yearFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))

private fun whenText(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (ChronoUnit.DAYS.between(day, today)) {
        0L -> tr("Сегодня")
        1L -> tr("Вчера")
        2L -> tr("Позавчера")
        else -> if (day.year == today.year) day.format(dayFormat) else day.format(yearFormat)
    }
}
