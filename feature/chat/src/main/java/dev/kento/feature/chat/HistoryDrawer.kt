package dev.kento.feature.chat

import dev.kento.core.text.tr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kento.core.ui.*
import dev.kento.data.db.Conversation

private const val RECENT_LIMIT = 6

/** The compact side panel: brand, places, pinned and recent chats, then settings and a new chat at the bottom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryDrawer(history: List<Conversation>, active: String, canSwitch: Boolean,
    select: (String) -> Unit, newChat: () -> Unit, settings: () -> Unit, dossier: () -> Unit,
    showAll: () -> Unit, search: () -> Unit, pin: (String, Boolean) -> Unit, delete: (String) -> Unit = {}) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    val feedback = LocalHapticFeedback.current
    Screen(Modifier.fillMaxSize(), Night) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(dev.kento.core.ui.R.drawable.kento_wordmark_mask), "Kento", Modifier.height(25.dp),
                colorFilter = ColorFilter.tint(Paper))
            Spacer(Modifier.weight(1f))
            ToolButton(Glyph.Search, tr("Поиск по чатам"), search)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Place(Glyph.Book, tr("Досье"), dossier)
            HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), color = Line.copy(alpha = .6f))
            Text(tr("Недавние"), Modifier.padding(start = 10.dp, bottom = 6.dp), color = Muted, fontSize = 13.sp)
            if (history.isEmpty()) Text(tr("Пока нет переписок"), Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                color = Muted.copy(alpha = .7f), fontSize = 14.sp)
            // Pinned chats come first from the database and are always shown, whatever the recent limit.
            val shown = history.filter { it.pinned } + history.filterNot { it.pinned }.take(RECENT_LIMIT)
            shown.forEach { chat ->
                Box {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .then(if (chat.id == active) Modifier.surface(RoundedCornerShape(12.dp), SurfaceHigh) else Modifier)
                        .combinedClickable(enabled = canSwitch, onLongClick = {
                            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuFor = chat.id
                        }) { select(chat.id) }.heightIn(min = 42.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(if (chat.pinned) Glyph.Pin else Glyph.Chat, color = Muted, size = 16.dp)
                        Text(chat.title, color = Paper, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(menuFor == chat.id, { menuFor = null }, containerColor = menuColor) {
                        DropdownMenuItem(text = { Text(if (chat.pinned) tr("Открепить") else tr("Закрепить")) },
                            onClick = { menuFor = null; pin(chat.id, !chat.pinned) },
                            leadingIcon = { Symbol(Glyph.Pin, color = Paper, size = 18.dp) })
                        DropdownMenuItem(text = { Text(tr("Удалить чат")) }, onClick = { menuFor = null; delete(chat.id) },
                            leadingIcon = { Symbol(Glyph.Trash, color = Paper, size = 18.dp) })
                    }
                }
            }
            if (history.isNotEmpty()) Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = showAll)
                .padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("Показать все"), color = Muted, fontSize = 14.sp)
                Symbol(Glyph.Right, color = Muted, size = 13.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).surface(CircleShape, SurfaceHigh).clickable(role = Role.Button, onClick = settings)
                .semantics(mergeDescendants = true) { contentDescription = tr("Настройки") }, contentAlignment = Alignment.Center) {
                Symbol(Glyph.Settings, color = Paper, size = 21.dp)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.height(46.dp).then(if (LocalGlass.current) Modifier.lightGlass(CircleShape) else Modifier.clip(CircleShape).background(Paper))
                .clickable(enabled = canSwitch, onClick = newChat)
                .padding(start = 16.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(Glyph.Plus, color = Panel, size = 18.dp)
                Text(tr("Новый чат"), color = Panel, fontSize = 15.sp)
            }
        }
    }
    }
}

@Composable
private fun Place(icon: Glyph, title: String, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (LocalGlass.current) Modifier.glass(RoundedCornerShape(24.dp)) else Modifier.clip(RoundedCornerShape(12.dp)))
        .clickable(onClick = open).heightIn(min = 48.dp)
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Symbol(icon, color = Paper, size = 21.dp)
        Text(title, color = Paper, fontSize = 16.sp)
    }
}
