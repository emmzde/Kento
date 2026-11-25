package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.kento.core.ui.*
import dev.kento.data.media.seesImages
import dev.kento.data.settings.Settings

private val Motion = spring<Dp>(dampingRatio = 1f, stiffness = 520f)

/**
 * One pill that keeps the text field in place and slides the controls around it:
 * idle it is a single row, while typing (or answering) it grows a second row with the model chip and send.
 */
@Composable
internal fun Composer(vm: ChatViewModel, settings: Settings?, sending: Boolean, ready: Boolean,
    focus: FocusRequester, choose: () -> Unit, send: () -> Unit) {
    val text by vm.input.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val imageMode by vm.imageMode.collectAsStateWithLifecycle()
    val imageApi by vm.images.collectAsStateWithLifecycle()
    val all by vm.attachments.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val chat by vm.activeId.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val draft = all.filter { it.chatId == chat && it.messageId == null }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val voice = vm.voice
    val recordAudio = Manifest.permission.RECORD_AUDIO
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) voice.start()
        else if (!granted) voice.issue = tr("Разреши Kento доступ к микрофону")
    }
    var focused by remember { mutableStateOf(false) }
    val expanded = imageMode || focused || draft.isNotEmpty() || text.isNotEmpty() || sending || voice.listening || voice.finishing
    val toggleVoice = { enable: Boolean ->
        if (!enable) voice.stop()
        else if (context.checkSelfPermission(recordAudio) == PackageManager.PERMISSION_GRANTED) voice.start()
        else permission.launch(recordAudio)
    }

    val start by animateDpAsState(if (expanded) 16.dp else 108.dp, Motion, label = "fieldStart")
    val end by animateDpAsState(if (expanded) 16.dp else 60.dp, Motion, label = "fieldEnd")
    val top by animateDpAsState(if (expanded) 15.dp else 17.dp, Motion, label = "fieldTop")
    val bottom by animateDpAsState(if (expanded) 58.dp else 17.dp, Motion, label = "fieldBottom")
    val shape = RoundedCornerShape(28.dp)
    val locked = sending || importing
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
        .then(if (LocalGlass.current) Modifier.glass(shape) else Modifier.clip(shape).background(SurfaceHigh).border(1.dp, Color.White.copy(alpha = .05f), shape))
        .pointerInput(focus) { detectTapGestures { runCatching { focus.requestFocus() } } }) {
        AnimatedVisibility(draft.isNotEmpty() || importing, enter = fadeIn(tween(220)) + expandVertically(spring(dampingRatio = 1f, stiffness = 520f)),
            exit = fadeOut(tween(120)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 520f))) {
            Column {
                DraftTiles(vm.media, draft, importing,
                    remove = if (locked) null else { row -> vm.changeMedia { vm.media.remove(row) } },
                    edit = if (locked) null else { row, edit -> vm.changeMedia { vm.media.editDraft(row, edit) } })
                val pictures = draft.filter { it.mime.startsWith("image/") }
                if (!imageMode && pictures.isNotEmpty() && settings?.seesImages() != false) {
                    // A vision model reads the photos itself; their full OCR text goes along only when asked for here.
                    val full = pictures.all { it.includeOcr }
                    FilterChip(selected = full, enabled = !locked,
                        onClick = { vm.changeMedia { vm.media.dao.setDraftOcr(chat, !full) } },
                        label = { Text(tr("Приложить полный текст OCR"), fontSize = 13.sp) },
                        leadingIcon = if (full) { { Symbol(Glyph.Check, color = Accent, size = 15.dp) } } else null,
                        shape = CircleShape, border = null,
                        colors = FilterChipDefaults.filterChipColors(containerColor = Raised.copy(alpha = .6f), selectedContainerColor = Raised,
                            labelColor = Muted, selectedLabelColor = Paper, disabledSelectedContainerColor = Raised.copy(alpha = .6f)),
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp))
                }
            }
        }
        AnimatedVisibility(editing != 0L, enter = fadeIn(tween(200)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(Glyph.Edit, color = Accent, size = 16.dp)
                Text(tr("Изменение сообщения: ответы после него будут заменены"), Modifier.weight(1f).padding(start = 8.dp),
                    color = Muted, fontSize = 13.sp, maxLines = 2)
                ToolButton(Glyph.Close, tr("Отменить изменение"), vm::cancelEdit, enabled = !sending, size = 18.dp)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val modelWidth = (maxWidth - 216.dp).coerceIn(48.dp, 190.dp)
            BasicTextField(text, { edited ->
                if (edited != vm.input.value) {
                    voice.newPhrase()
                    vm.input.value = edited
                }
            }, Modifier.fillMaxWidth().padding(start = start, end = end, top = top, bottom = bottom)
                .focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = tr("Сообщение") }, maxLines = 6,
                textStyle = TextStyle(color = Paper, fontSize = 17.sp, lineHeight = 22.sp), cursorBrush = SolidColor(Accent),
                decorationBox = { field -> Box {
                    if (text.isEmpty()) Text(when { voice.listening -> tr("Говори…"); voice.finishing -> tr("Распознаю…"); imageMode && draft.isNotEmpty() -> tr("Что изменить на фото?"); imageMode -> tr("Опишите изображение"); else -> tr("Напиши Kento") }, color = Muted.copy(alpha = .8f),
                        fontSize = 17.sp, lineHeight = 22.sp, maxLines = 1)
                    field()
                } })

            Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaPicker(vm, ready && !sending && !importing)
                if (imageMode) Round(Glyph.Art, tr("Выйти из генерации изображений"), { vm.setImageMode(false) }, enabled = !sending,
                    background = Accent.copy(alpha = .18f), tint = Accent, role = Role.Switch)
                else Round(Glyph.Globe, if (search) tr("Выключить веб-поиск") else tr("Включить веб-поиск"), vm::toggleSearch, enabled = !sending,
                    background = if (search) Accent.copy(alpha = .18f) else SurfaceHigh, tint = if (search) Accent else Paper, role = Role.Switch)
                AnimatedVisibility(expanded, enter = fadeIn(tween(180, 60)) + expandHorizontally(expandFrom = Alignment.Start),
                    exit = fadeOut(tween(90)) + shrinkHorizontally(shrinkTowards = Alignment.Start)) {
                    Row(Modifier.widthIn(max = modelWidth).height(40.dp).surface(CircleShape, Raised)
                        .clickable(enabled = settings != null, onClick = choose)
                        .semantics { contentDescription = if (imageMode) tr("Модель изображений") else tr("Модель и рассуждения") }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (imageMode) imageApi.option(draft.size)?.name ?: tr("Выбрать модель") else settings?.let { it.option()?.name?.ifBlank { it.model } ?: it.model }?.ifBlank { tr("Модель") }.orEmpty(),
                            Modifier.weight(1f, fill = false), color = Paper, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // The level always travels with the request now, so the chip always names it.
                        val effort = settings?.effort().orEmpty()
                        if (!imageMode && effort.isNotEmpty()) Text(effortName(effort), color = Muted, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }

            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val listening = voice.listening
                // Idle, the microphone is the primary action; once a second row exists send takes that place.
                val micPrimary = !expanded
                Round(if (listening) Glyph.Stop else Glyph.Mic, if (listening) tr("Отключить микрофон") else tr("Включить микрофон"),
                    { toggleVoice(!listening) }, enabled = ready,
                    background = when { listening -> Accent; micPrimary -> Paper; else -> SurfaceHigh },
                    tint = if (listening || micPrimary) Panel else Paper, role = Role.Switch)
                AnimatedVisibility(expanded, enter = fadeIn(tween(180, 60)) + scaleIn(tween(220), initialScale = .6f),
                    exit = fadeOut(tween(90)) + scaleOut(tween(120), targetScale = .6f)) {
                    // While the last piece of a cloud dictation is being transcribed, sending would drop it.
                    val enabled = sending || ((text.isNotBlank() || !imageMode && draft.isNotEmpty()) && ready && !importing &&
                        settings != null && !voice.finishing)
                    val fill by animateColorAsState(when { sending -> Paper; enabled -> Accent; else -> Raised }, tween(180), label = "send")
                    Round(if (sending) Glyph.Stop else Glyph.Up, if (sending) tr("Остановить") else tr("Отправить"),
                        if (sending) vm::stop else send, enabled = enabled, background = fill,
                        tint = if (enabled) Panel else Muted.copy(alpha = .6f), iconSize = if (sending) 18.dp else 20.dp)
                }
            }
        }
    }
}

@Composable
internal fun Round(icon: Glyph, label: String, action: () -> Unit, enabled: Boolean = true, background: Color = SurfaceHigh,
    tint: Color = Paper, iconSize: Dp = 20.dp, role: Role = Role.Button) {
    // In the glass theme the plain buttons are panes of glass, the primary one milky glass, coloured ones tinted glass.
    val look = when {
        !LocalGlass.current -> Modifier.clip(CircleShape).background(background)
        background == Paper -> Modifier.lightGlass(CircleShape)
        background == SurfaceHigh || background == Raised -> Modifier.glass(CircleShape)
        else -> Modifier.glass(CircleShape, tint = background.copy(alpha = 1f)).background(background.copy(alpha = background.alpha * .85f), CircleShape)
    }
    Box(Modifier.size(44.dp).then(look)
        .clickable(enabled = enabled, role = role, onClick = action), contentAlignment = Alignment.Center) {
        Symbol(icon, label, if (enabled) tint else tint.copy(alpha = .45f), iconSize)
    }
}
