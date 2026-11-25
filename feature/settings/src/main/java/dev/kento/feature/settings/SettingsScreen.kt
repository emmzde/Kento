package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.data.settings.modelDetails
import dev.kento.core.ui.*
import dev.kento.data.settings.Settings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SettingsPage(val title: String) {
    Main(tr("Настройки")), Connection(tr("Подключение")), Models(tr("Модели")), Images(tr("Изображения")), Memory(tr("Память")), Search(tr("Веб-поиск")), Persona(tr("Инструкции")),
    Speech(tr("Распознавание речи")), Advanced(tr("Для продвинутых")), Journal(tr("Журнал ошибок")), Backup(tr("Резервная копия"))
}

@Composable
fun SettingsScreen(vm: SettingsEditor, back: () -> Unit) {
    val book by vm.book.collectAsStateWithLifecycle()
    val draft = book?.active
    val message by vm.message.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    var chooser by rememberSaveable { mutableStateOf<ModelSetting?>(null) }
    val focus = LocalFocusManager.current
    val leave = { vm.close(); back() }
    val up = { focus.clearFocus(); if (page == SettingsPage.Main) leave() else page = SettingsPage.Main }
    BackHandler { if (!saving) up() }
    LaunchedEffect(Unit) { vm.open() }
    Screen(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ScreenBar(page.title, up, !saving) {
            BarAction(tr("Сохранить"), { focus.clearFocus(); vm.save(leave) }, draft != null && !saving)
        }
        AnimatedVisibility(message != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            var shown by remember { mutableStateOf("") }
            message?.let { shown = it }
            val good = shown.startsWith(tr("Каталог доступен"))
            Notice(shown, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), tone = if (good) Tone.Info else Tone.Problem, pulse = !good)
        }
        val s = draft ?: return@Column
        val profiles = book ?: return@Column
        AnimatedContent(page, Modifier.weight(1f), label = "settingsPage", transitionSpec = {
            val deeper = targetState != SettingsPage.Main
            (fadeIn(tween(220, 40)) + slideInHorizontally(tween(300, easing = EaseOutCubic)) { if (deeper) it / 10 else -it / 10 }) togetherWith
                (fadeOut(tween(140)) + slideOutHorizontally(tween(260)) { if (deeper) -it / 12 else it / 12 })
        }) { target ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (target) {
                    SettingsPage.Main -> MainPage(s, profiles, BubbleTone.of(profiles.bubble), vm::editBubble) { page = it }
                    SettingsPage.Connection -> Connections(profiles, vm)
                    SettingsPage.Models -> {
                        SectionLabel(tr("Чат"))
                        ChoiceGroup(Card) {
                            NavigationRow(tr("Основная модель"), s.model.ifBlank { tr("Выбрать") }) { focus.clearFocus(); if (!s.catalogLoaded) vm.check(); chooser = ModelSetting.Chat }
                            val reasoning = s.reasoning()
                            if (reasoning.any) {
                                ChoiceDivider()
                                NavigationRow(tr("Рассуждения"), reasoningLabel(s.effort().orEmpty(), reasoning)) { focus.clearFocus(); chooser = ModelSetting.Effort }
                            }
                        }
                        Hint(tr("Модель и уровень можно сменить и прямо в чате, нажав на её название."))
                    }
                    SettingsPage.Memory -> MemoryPage(s, profiles.memory, vm) {
                        focus.clearFocus(); if (!s.catalogLoaded) vm.check(); chooser = ModelSetting.Memory
                    }
                    SettingsPage.Search -> WebOptions(profiles.web, vm)
                    SettingsPage.Images -> ImagesPage(profiles.images, vm)
                    SettingsPage.Persona -> InstructionsPage(s, vm)
                    SettingsPage.Speech -> SpeechPage(profiles.speech, vm)
                    SettingsPage.Advanced -> AdvancedPage(s, vm)
                    SettingsPage.Journal -> JournalPage()
                    SettingsPage.Backup -> BackupPage(vm)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
    }
    val s = draft
    chooser?.let { target ->
        if (s != null) {
            val memory = target == ModelSetting.Memory
            ModelPicker(if (memory) s.memoryModel else s.model, s.modelIds(), s.effort().orEmpty(), s.reasoning(),
                dismiss = { chooser = null }, forMemory = memory,
                names = s.catalog.associate { it.id to it.name }, details = s.modelDetails(),
                start = if (target == ModelSetting.Effort) PickerPage.Effort else PickerPage.Models,
                pickModel = { id ->
                    vm.edit { it.select(id, memory) }
                    chooser = null
                },
                pickEffort = { value -> vm.edit { it.withEffort(value) }; chooser = null })
        }
    }
}

@Composable
private fun MainPage(s: Settings, book: dev.kento.data.settings.ProfileBook, tone: BubbleTone, pickTone: (String) -> Unit,
    open: (SettingsPage) -> Unit) {
    Spacer(Modifier.height(4.dp))
    ChoiceGroup(Card) {
        // The connection plays the part of an account: the provider's initial, its name and whether a key is set.
        Row(Modifier.fillMaxWidth().clickable { open(SettingsPage.Connection) }.semantics { contentDescription = tr("Подключение") }
            .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(tone.fill), contentAlignment = Alignment.Center) {
                Text("R", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Refuseless", color = Paper, fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (s.apiKey.isBlank()) tr("Ключ не задан") else host(s.baseUrl), color = Muted, fontSize = 13.5.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Symbol(Glyph.Right, color = Muted, size = 15.dp)
        }
    }
    Spacer(Modifier.height(6.dp))
    ChoiceGroup(Card) {
        NavigationRow(tr("Модели"), s.option()?.name?.ifBlank { null } ?: s.model.ifBlank { tr("Не выбрана") }, Glyph.Spark) { open(SettingsPage.Models) }
        ChoiceDivider(inset = true)
        NavigationRow(tr("Изображения"), if (book.images.configured) "OpenUncensored" else tr("Ключ не задан"), Glyph.Art) {
            open(SettingsPage.Images)
        }
        ChoiceDivider(inset = true)
        NavigationRow(tr("Память"), if (book.memory.enabled) tr("Включена") else tr("Выключена"), Glyph.Book) { open(SettingsPage.Memory) }
        ChoiceDivider(inset = true)
        NavigationRow(tr("Веб-поиск"), tr(book.web.engine), Glyph.Globe) { open(SettingsPage.Search) }
        ChoiceDivider(inset = true)
        val blocks = listOf(s.promptFormat, s.systemPrompt, s.promptValues).count(String::isNotBlank)
        NavigationRow(tr("Инструкции"), if (blocks == 0) tr("Не заданы") else tr("Блоков: {0}", blocks), Glyph.Person) { open(SettingsPage.Persona) }
        ChoiceDivider(inset = true)
        NavigationRow(tr("Распознавание речи"), if (book.speech.configured) tr("Облако") else tr("Офлайн"), Glyph.Mic) { open(SettingsPage.Speech) }
    }
    ChoiceGroup(Card) {
        val custom = s.sampling().let { !it.isDefault() } || s.historyTokens != dev.kento.data.settings.HISTORY_TOKENS
        NavigationRow(tr("Для продвинутых"), if (custom) tr("Настроено") else "", Glyph.Settings,
            subtitle = tr("Температура, top P, штрафы, длина ответа, история")) { open(SettingsPage.Advanced) }
        ChoiceDivider(inset = true)
        NavigationRow(tr("Журнал ошибок"), "", Glyph.Info) { open(SettingsPage.Journal) }
    }
    ChoiceGroup(Card) {
        NavigationRow(tr("Резервная копия"), "", Glyph.Download, subtitle = tr("Чаты, вложения, досье и настройки в одном файле")) {
            open(SettingsPage.Backup)
        }
    }
    SectionLabel(tr("Оформление"))
    ChoiceGroup(Card) { BubblePicker(tone, pickTone) }
    Hint(tr("Цвет ваших сообщений в чате."))
    ChoiceGroup(Card) {
        val context = LocalContext.current
        SwitchRow(tr("Жидкое стекло"), Appearance.glass, tr("Стеклянные панели и мягкий свет фона, как на iPhone"), Glyph.Spark) {
            Appearance.setGlass(context, it)
        }
    }
    Hint(tr("Применяется сразу, без сохранения."))
    ChoiceGroup(Card) { LanguagePicker() }
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    Text("Kento $version".trim(), Modifier.fillMaxWidth().padding(top = 22.dp), color = Muted.copy(alpha = .6f), fontSize = 13.sp,
        textAlign = TextAlign.Center)
}

/** Interface language: system default, Russian or English. Saving restarts the app so every screen follows. */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val current = remember { dev.kento.core.text.I18n.choice(context) }
    val names = linkedMapOf(dev.kento.core.text.I18n.AUTO to tr("Как в системе"), dev.kento.core.text.I18n.RU to "Русский",
        dev.kento.core.text.I18n.EN to "English")
    Box {
        NavigationRow(tr("Язык"), names[current].orEmpty(), Glyph.Globe, trailing = Glyph.Down,
            subtitle = tr("Язык интерфейса. Приложение перезапустится.")) { open = true }
        Box(Modifier.align(Alignment.TopEnd)) {
            SettingsMenu(open, { open = false }) {
                names.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    if (id != current) {
                        dev.kento.core.text.I18n.choose(context, id)
                        restartApp(context)
                    }
                }, trailingIcon = if (id == current) { { Symbol(Glyph.Check, color = Accent, size = 18.dp) } } else null) }
            }
        }
    }
}

/** Starts the app afresh, so strings built once at start (menus, lists) come up in the new language too. */
private fun restartApp(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(launch)
    Runtime.getRuntime().exit(0)
}

/** "Message colour" row with a swatch; the menu previews each colour as a tiny bubble. */
@Composable
private fun BubblePicker(tone: BubbleTone, pick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Symbol(Glyph.Chat, color = Paper, size = 21.dp)
            Spacer(Modifier.width(15.dp))
            Text(tr("Цвет сообщений"), Modifier.weight(1f), color = Paper, fontSize = 16.sp)
            Box(Modifier.size(10.dp).clip(CircleShape).background(tone.swatch))
            Spacer(Modifier.width(8.dp))
            Text(tone.title, color = Muted, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Symbol(Glyph.Down, color = Muted, size = 15.dp)
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            SettingsMenu(open, { open = false }) {
                BubbleTone.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.title) }, onClick = { open = false; pick(option.id) },
                        leadingIcon = { Box(Modifier.size(width = 26.dp, height = 18.dp).clip(RoundedCornerShape(9.dp)).background(option.fill),
                            contentAlignment = Alignment.Center) { Box(Modifier.size(6.dp).clip(CircleShape).background(option.swatch)) } },
                        trailingIcon = if (option == tone) { { Symbol(Glyph.Check, color = Accent, size = 18.dp) } } else null)
                }
            }
        }
    }
}

internal fun host(url: String) = url.trim().toHttpUrlOrNull()?.let { if (it.port == 443 || it.port == 80) it.host else it.host + ":" + it.port }
    ?: url.ifBlank { tr("Адрес не указан") }

private enum class ModelSetting { Chat, Memory, Effort }

/** The API key field with a show/hide toggle; used by connections. */
@Composable
internal fun SecretField(label: String, value: String, change: (String) -> Unit, revealable: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    FieldCard(label, value, change, placeholder = tr("Не задан"), keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
        hidden = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = if (revealable) { {
            IconButton({ visible = !visible }) { Symbol(Glyph.Eye, if (visible) tr("Скрыть ключ") else tr("Показать ключ"), Muted) }
        } } else null)
}
