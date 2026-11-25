package dev.kento.feature.chat

import dev.kento.core.text.tr

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.data.settings.modelDetails
import dev.kento.core.ui.*
import dev.kento.data.db.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.roundToInt

/** Where the chat screen can send the user. Each target is a full screen of its own. */
class ChatRoutes(val settings: () -> Unit, val dossier: () -> Unit, val recents: (search: Boolean) -> Unit)

@Composable
fun ChatScreen(vm: ChatViewModel, routes: ChatRoutes) {
    val profile by vm.settings.collectAsStateWithLifecycle()
    val imageMode by vm.imageMode.collectAsStateWithLifecycle()
    val images by vm.images.collectAsStateWithLifecycle()
    LaunchedEffect(imageMode, images.apiKey) {
        if (imageMode) vm.refreshImages()
    }
    val page by vm.page.collectAsStateWithLifecycle()
    val id by vm.activeId.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val drawer = remember(scope) { PushDrawer(scope) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var spending by remember { mutableStateOf(false) }
    val export = rememberChatExport(vm)
    val askNotifications = rememberNotificationAsk()
    val feedback = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val composer = remember { FocusRequester() }
    val loaded = page.id == id
    val rows = if (loaded) page.rows else emptyList()
    val idle = ready && !sending
    val title = history.firstOrNull { it.id == id }?.title.takeIf { rows.isNotEmpty() }.orEmpty()
    val startNew = {
        vm.newChat()
        drawer.close()
        scope.launch { runCatching { composer.requestFocus() } }
        Unit
    }
    BackHandler(drawer.opening, onBack = drawer::close)

    val bubble by vm.bubble.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalBubble provides BubbleTone.of(bubble)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Night)
        .draggable(rememberDraggableState(drawer::drag), Orientation.Horizontal,
            onDragStarted = { focus.clearFocus() }, onDragStopped = { drawer.settle(it) })) {
        val density = LocalDensity.current
        val panel = (maxWidth - 58.dp).coerceAtMost(420.dp)
        drawer.width = with(density) { panel.toPx() }
        if (drawer.visible) Box(Modifier.width(panel).fillMaxHeight()
            .offset { IntOffset((drawer.offset - drawer.width).roundToInt(), 0) }) {
            HistoryDrawer(history, id, idle,
                select = { vm.select(it); drawer.close() },
                newChat = startNew,
                settings = routes.settings, dossier = routes.dossier,
                showAll = { routes.recents(false) }, search = { routes.recents(true) }, pin = vm::pin,
                delete = { id -> deleting = id })
        }
        Screen(Modifier.fillMaxSize().offset { IntOffset(drawer.offset.roundToInt(), 0) }) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) {
                        CircleButton(Glyph.Menu, tr("История чатов"), { focus.clearFocus(); drawer.open() })
                    }
                    AnimatedContent(title, Modifier.align(Alignment.Center).padding(horizontal = 108.dp),
                        transitionSpec = { fadeIn(tween(240, 60)) togetherWith fadeOut(tween(120)) }, label = "title") {
                        Text(it, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    androidx.compose.animation.AnimatedVisibility(rows.isNotEmpty(), Modifier.align(Alignment.CenterEnd),
                        enter = fadeIn(tween(220)) + scaleIn(tween(260, easing = EaseOutCubic), initialScale = .9f), exit = fadeOut(tween(120))) {
                        // One pill holds both actions, like a segmented control.
                        Row(Modifier.height(44.dp).surface(CircleShape, SurfaceHigh).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            PillIcon(Glyph.New, tr("Новый чат"), startNew, idle, 20.dp)
                            Box {
                                PillIcon(Glyph.More, tr("Ещё"), { menu = true }, idle, 20.dp)
                                DropdownMenu(menu, { menu = false }, containerColor = menuColor, shape = RoundedCornerShape(18.dp)) {
                                    val pinned = history.any { it.id == id && it.pinned }
                                    if (history.any { it.id == id }) DropdownMenuItem(text = { Text(if (pinned) tr("Открепить") else tr("Закрепить")) },
                                        onClick = { menu = false; vm.pin(id, !pinned) },
                                        leadingIcon = { Symbol(Glyph.Pin, color = Paper, size = 18.dp) })
                                    DropdownMenuItem(text = { Text(tr("Посмотреть расход")) }, onClick = { menu = false; spending = true },
                                        leadingIcon = { Symbol(Glyph.Gauge, color = Paper, size = 18.dp) })
                                    DropdownMenuItem(text = { Text(tr("Экспортировать")) }, onClick = { menu = false; export(id) },
                                        leadingIcon = { Symbol(Glyph.Download, color = Paper, size = 18.dp) })
                                    DropdownMenuItem(text = { Text(tr("Удалить чат"), color = Warning) }, onClick = { menu = false; deleting = id },
                                        leadingIcon = { Symbol(Glyph.Trash, color = Warning, size = 18.dp) })
                                }
                            }
                        }
                    }
                }
                val mode = when { !ready || !loaded -> Stage.Loading; rows.isEmpty() -> Stage.Empty; else -> Stage.Talk }
                val held = remember { Held() }
                AnimatedContent(id to mode, Modifier.weight(1f).fillMaxWidth(), contentKey = { it }, label = "chat",
                    transitionSpec = {
                        if (initialState.first == targetState.first && targetState.second == Stage.Talk)
                            fadeIn(tween(260, 110)) togetherWith (fadeOut(tween(150)) + scaleOut(tween(220), targetScale = .96f))
                        else (fadeIn(tween(260, 70)) + scaleIn(tween(360, easing = EaseOutCubic), initialScale = .965f)) togetherWith
                            fadeOut(tween(140))
                    }) { (chat, stage) ->
                    when (stage) {
                        Stage.Empty -> Greeting()
                        Stage.Talk -> {
                            if (chat == id && loaded) { held.rows = rows; held.more = page.hasMore }
                            Messages(vm, held.rows, held.more, vm::older, vm.live, vm.progress, if (idle && chat == id) vm::regenerate else null)
                        }
                        Stage.Loading -> Box(Modifier.fillMaxSize())
                    }
                }
                SpeechModelCard(vm.voice.needsModel) { vm.voice.needsModel = false }
                val notice = problem ?: vm.voice.issue
                var shownNotice by remember { mutableStateOf("") }
                if (notice != null) shownNotice = notice
                AnimatedVisibility(notice != null, enter = fadeIn(tween(220)) + expandVertically(), exit = fadeOut(tween(160)) + shrinkVertically()) {
                    Notice(shownNotice, Modifier.fillMaxWidth()
                        .clickable { vm.problem.value = null; vm.voice.issue = null }
                        .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp))
                }
                Composer(vm, profile, sending, ready, composer, { focus.clearFocus(); if (!imageMode) vm.refreshModels(); picker = true }) {
                    if (!imageMode && profile?.model.isNullOrBlank()) routes.settings()
                    else {
                        feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        askNotifications()
                        vm.send()
                    }
                }
            }
            if (drawer.visible) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f * drawer.progress))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = drawer::close))
        }
    }
    }
    deleting?.let { chat -> DeleteChatDialog({ vm.delete(chat); deleting = null }, { deleting = null }) }
    if (spending) SpendingSheet(vm, id) { spending = false }
    val broken by vm.settingsBroken.collectAsStateWithLifecycle()
    var later by rememberSaveable { mutableStateOf(false) }
    if (broken && !later) AlertDialog(onDismissRequest = { later = true }, containerColor = Panel, shape = RoundedCornerShape(28.dp),
        title = { Text(tr("Настройки не открываются")) },
        text = { Text(tr("Android не смог расшифровать сохранённые подключения. Так бывает после смены блокировки экрана ") +
            tr("или восстановления телефона. Переписка и досье на месте. Сбросьте настройки и заново введите API-ключ."), color = Muted) },
        confirmButton = { TextButton({ vm.resetSettings { later = true; routes.settings() } }) { Text(tr("Сбросить"), color = Warning) } },
        dismissButton = { TextButton({ later = true }) { Text(tr("Позже")) } })
    if (picker) profile?.let { s ->
        if (imageMode) ImageModelPicker(vm) { picker = false }
        else ModelPicker(s.model, s.modelIds(), s.effort().orEmpty(), s.reasoning(), { picker = false },
            names = s.catalog.associate { it.id to it.name }, details = s.modelDetails(),
            pickModel = { next -> vm.choose(next); picker = false },
            pickEffort = { vm.chooseEffort(it); picker = false })
    }
}

private enum class Stage { Loading, Empty, Talk }

/** Keeps the last rows of a chat so its exit animation does not flash empty after the page moved on. */
private class Held { var rows: List<Message> = emptyList(); var more = false }

/**
 * A drawer that pushes the chat aside instead of covering it. The offset is the panel's visible width in pixels;
 * drags move it directly and release settles it with the fling velocity.
 */
private class PushDrawer(private val scope: CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
        private set
    var width = 1f
        set(value) {
            if (value != field && value > 0f) { if (opening) offset = value; field = value }
        }
    var opening by mutableStateOf(false)
        private set
    private var motion: Job? = null
    val progress get() = (offset / width).coerceIn(0f, 1f)
    val visible get() = offset > .5f || opening

    fun drag(delta: Float) {
        motion?.cancel()
        offset = (offset + delta).coerceIn(0f, width)
        opening = offset > 0f
    }
    fun settle(velocity: Float) = when {
        velocity > 900f -> open(velocity)
        velocity < -900f -> close(velocity)
        offset > width / 2 -> open(velocity)
        else -> close(velocity)
    }
    fun open(velocity: Float = 0f) = move(width, velocity)
    fun close() = close(0f)
    private fun close(velocity: Float) = move(0f, velocity)
    private fun move(target: Float, velocity: Float) {
        motion?.cancel()
        opening = target > 0f
        motion = scope.launch {
            animate(offset, target, velocity, spring(dampingRatio = 1f, stiffness = 420f)) { value, _ -> offset = value }
        }
    }
}

@Composable
private fun Greeting() {
    val hour = remember { LocalTime.now().hour }
    val greeting = when (hour) {
        in 5..11 -> tr("Доброе утро")
        in 12..17 -> tr("Добрый день")
        in 18..22 -> tr("Добрый вечер")
        else -> tr("Доброй ночи")
    }
    Column(Modifier.fillMaxSize().padding(bottom = 56.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Dots(animated = false, dot = 9.dp)
        Spacer(Modifier.height(18.dp))
        Text(greeting, color = Paper, fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp)
    }
}

@Composable
internal fun DeleteChatDialog(confirm: () -> Unit, dismiss: () -> Unit, count: Int = 1) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (count == 1) tr("Удалить чат?") else tr("Удалить чаты: {0}?", count)) },
        containerColor = Panel, shape = RoundedCornerShape(28.dp),
        text = { Text(if (count == 1) tr("Переписка и её вложения исчезнут с устройства.")
            else tr("Эти переписки и их вложения исчезнут с устройства. Отменить удаление нельзя."), color = Muted) },
        confirmButton = { TextButton(confirm) { Text(tr("Удалить"), color = Warning) } },
        dismissButton = { TextButton(dismiss) { Text(tr("Отмена")) } })
}

/** Asks for a destination and writes the chat there; one export at a time. */
@Composable
internal fun rememberChatExport(vm: ChatViewModel): (String) -> Unit {
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val resolver = LocalContext.current.contentResolver
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val chatId = exporting
        exporting = null
        if (uri != null && chatId != null) vm.exportChat(chatId) { checkNotNull(resolver.openOutputStream(uri, "wt")) }
    }
    return remember(save) {
        { chatId -> if (exporting == null) { exporting = chatId; save.launch("Kento-chat.txt") } }
    }
}

@Composable
private fun Messages(vm: ChatViewModel, rows: List<Message>, more: Boolean, older: () -> Unit, live: StateFlow<DraftReply?>,
    progress: StateFlow<String>, regenerate: ((Long) -> Unit)?) {
    val media by vm.attachments.collectAsStateWithLifecycle()
    // The bottom is index zero. A growing reply stays anchored until the reader scrolls away.
    val scroll = rememberLazyListState()
    var displayedNewest by rememberSaveable { mutableLongStateOf(0L) }
    val newest = rows.firstOrNull()?.id ?: 0L
    LaunchedEffect(newest) {
        if (displayedNewest > 0 && newest > displayedNewest) {
            if (scroll.firstVisibleItemIndex > 4) scroll.scrollToItem(0) else scroll.animateScrollToItem(0)
        }
        displayedNewest = newest
    }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, reverseLayout = true,
        contentPadding = PaddingValues(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(rows, key = { it.id }, contentType = { it.role }) { message ->
            val retry = regenerate?.takeIf { message.id == newest && message.role == "assistant" && message.state != "active" }
            Box(Modifier.animateItem(fadeInSpec = tween(320), placementSpec = null, fadeOutSpec = tween(120))) {
                Column {
                    val files = media.filter { it.messageId == message.id }
                    if (message.imageGeneration) {
                        // Only a picture that finished while on screen develops; history shows pictures at once.
                        val painted = remember(message.id) { mutableStateOf(false) }
                        if (message.state == "active") painted.value = true
                        if (message.state == "active") ImageWaiting()
                        else if (message.state == "done") files.forEach { GeneratedImage(vm.media, it, reveal = painted.value) }
                        else MessageRow(message, live, progress, retry?.let { { it(message.id) } }, trail = vm.trail, skip = vm::skipSearch)
                    } else {
                        SentAttachments(vm.media, files, message.role == "user")
                        if (message.role != "user" || message.text.isNotEmpty())
                            MessageRow(message, live, progress, retry?.let { { it(message.id) } },
                                edit = if (message.role == "user" && regenerate != null) { { vm.startEdit(message) } } else null,
                                trail = vm.trail, skip = vm::skipSearch)
                    }
                }
            }
        }
        if (more) item { TextButton(onClick = older, modifier = Modifier.fillMaxWidth()) { Text(tr("Ранние сообщения"), color = Muted) } }
    }
}

@Composable
private fun PillIcon(icon: Glyph, label: String, action: () -> Unit, enabled: Boolean, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClick = action), contentAlignment = Alignment.Center) {
        Symbol(icon, label, if (enabled) Paper else Muted.copy(alpha = .45f), size)
    }
}

/**
 * Asks once, on the first message, whether Kento may notify when an answer is ready in the background
 * (Android 13+). A refusal is remembered and never asked again.
 */
@Composable
private fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher) {
        {
            val prefs = context.getSharedPreferences("kento.ui", android.content.Context.MODE_PRIVATE)
            if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotifications", false) &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("askedNotifications", true).apply()
                runCatching { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}
