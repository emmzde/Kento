package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.media.SpeechModel
import dev.kento.data.settings.MemorySettings
import dev.kento.data.settings.Settings

/** Examples the user can drop into an empty block with one tap and then adjust. */
private val FORMAT_EXAMPLE = tr("Ты — личный ассистент. Отвечай на языке вопроса. Сначала суть в 1–2 предложениях, ") +
    tr("детали — только если нужны. Используй Markdown: короткие абзацы, списки для перечислений, блоки кода с указанием языка. ") +
    tr("Не повторяй вопрос и не добавляй вежливых вступлений.")
private val PERSONA_EXAMPLE = tr("Тон прямой и спокойный, без воды и канцелярита. Можно с иронией, если уместно. ") +
    tr("Если я ошибаюсь — говори прямо и объясняй почему. Не льсти.")
private val VALUES_EXAMPLE = tr("Мне важны честность и практичность: лучше неудобная правда, чем утешение. ") +
    tr("Помогай двигаться к цели маленькими конкретными шагами.")

/** The system prompt as separate, optional blocks; empty ones are not sent at all. */
@Composable
internal fun InstructionsPage(s: Settings, vm: SettingsEditor) {
    Spacer(Modifier.height(4.dp))
    Hint(tr("Блоки собираются в системный промпт этого подключения. Пустые не отправляются и не тратят токены."))
    PromptBlock(tr("Роль и формат"), tr("Кто Kento для вас, длина ответов, Markdown"), s.promptFormat, FORMAT_EXAMPLE) { v ->
        vm.edit { it.copy(promptFormat = v) }
    }
    PromptBlock(tr("Характер"), tr("Тон: прямота, юмор, без воды"), s.systemPrompt, PERSONA_EXAMPLE) { v -> vm.edit { it.copy(systemPrompt = v) } }
    PromptBlock(tr("Ценности и мотивация"), tr("Во что вы верите и к чему стремитесь"), s.promptValues, VALUES_EXAMPLE) { v ->
        vm.edit { it.copy(promptValues = v) }
    }
    SectionLabel(tr("Контекст"))
    ChoiceGroup(Card) {
        SwitchRow(tr("Дата и время"), s.promptClock, tr("Модель знает, какой сегодня день. Строка добавляется в конец запроса, ") +
            tr("поэтому кэш промпта у провайдера не сбрасывается"), Glyph.Clock) { on -> vm.edit { it.copy(promptClock = on) } }
    }
    val bytes = listOf(s.promptFormat, s.systemPrompt, s.promptValues).sumOf { it.trim().toByteArray().size }
    if (bytes > 0) Hint(tr("Ваши блоки: примерно {0} токенов. Они идут первыми и не меняются от сообщения к сообщению, поэтому Refuseless берёт их из кэша в несколько раз дешевле.", (bytes + 2) / 3))
    Hint(tr("Промпт хранится зашифрованным."))
}

@Composable
private fun PromptBlock(label: String, placeholder: String, value: String, example: String, change: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldCard(label, value, change, placeholder = placeholder, singleLine = false, minLines = 3)
        if (value.isBlank()) Row(Modifier.padding(start = 6.dp).clip(CircleShape).background(Raised).clickable { change(example) }
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(tr("Вставить пример"), color = Accent, fontSize = 13.5.sp)
        }
    }
}

@Composable
internal fun MemoryPage(s: Settings, memory: MemorySettings, vm: SettingsEditor, pickModel: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Spacer(Modifier.height(4.dp))
    ChoiceGroup(Card) {
        SwitchRow(tr("Память"), memory.enabled, tr("Модель видит главное о вас и сама ищет в досье и прошлых чатах"), Glyph.Book) { on ->
            vm.editMemory { it.copy(enabled = on) }
        }
        ChoiceDivider(inset = true)
        Box {
            NavigationRow(tr("Фактов в запросе"), memory.factsInContext.toString(), trailing = Glyph.Down,
                subtitle = tr("Самые важные и подходящие к теме чата")) { if (memory.enabled) menu = true }
            Box(Modifier.align(Alignment.TopEnd)) {
                SettingsMenu(menu, { menu = false }) {
                    listOf(3, 6, 10, 15).forEach { n -> DropdownMenuItem(text = { Text(n.toString()) }, onClick = {
                        menu = false; vm.editMemory { it.copy(factsInContext = n) }
                    }, trailingIcon = if (n == memory.factsInContext) { { Symbol(Glyph.Check, color = Accent, size = 18.dp) } } else null) }
                }
            }
        }
    }
    Hint(tr("Остальное досье и прошлые чаты модель находит сама через поиск по памяти, когда это нужно. ") +
        tr("Каждая запись хранится с датой и темой. Досье можно открыть из боковой панели."))
    SectionLabel(tr("Фоновая работа"))
    ChoiceGroup(Card) {
        NavigationRow(tr("Фоновая модель"), s.memoryModel.ifBlank {
            s.background().takeIf(String::isNotBlank)?.let { tr("{0} · по умолчанию", it) } ?: tr("Не выбрана")
        }, onClick = pickModel)
        ChoiceDivider()
        SwitchRow(tr("Запоминать факты"), memory.background,
            tr("После ответа фоновая модель находит в ваших сообщениях факты о вас и сохраняет их в досье"), enabled = memory.enabled) { on ->
            vm.editMemory { it.copy(background = on) }
        }
    }
    Hint(tr("Фоновая модель работает без рассуждений: запоминает факты и придумывает названия чатам. По умолчанию — ") +
        tr("DeepSeek V4.1 Flash Abliterated Fast: не отказывает и стоит доли цента. deepseek-v4.1-flash-fast в 12 раз дешевле, но может отказать."))
}

/** Offline dictation and audio transcription: the Whisper model is downloaded here or from the chat when first needed. */
@Composable
internal fun SpeechPage(speech: dev.kento.data.settings.SpeechEndpoint, vm: SettingsEditor) {
    val context = LocalContext.current
    val state by SpeechModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { SpeechModel.refresh(context) }
    Spacer(Modifier.height(4.dp))
    SectionLabel(tr("Без сети"))
    ChoiceGroup(Card) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Модель Whisper small"), color = Paper, fontSize = 16.sp)
            val current = state
            Text(when (current) {
                SpeechModel.State.Ready -> tr("Установлена · {0} МБ. Работает без сети", SpeechModel.SIZE_MB)
                SpeechModel.State.Missing -> tr("Не скачана · {0} МБ", SpeechModel.SIZE_MB)
                is SpeechModel.State.Loading -> tr("Скачивается · {0}%", (current.fraction * 100).toInt())
                is SpeechModel.State.Failed -> current.message
            }, color = Muted, fontSize = 13.5.sp)
            if (current is SpeechModel.State.Loading) LinearProgressIndicator({ current.fraction },
                Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = Accent, trackColor = Raised, drawStopIndicator = {})
        }
    }
    when (state) {
        SpeechModel.State.Ready -> CardAction(tr("Удалить модель"), { SpeechModel.delete(context) })
        is SpeechModel.State.Loading -> CardAction(tr("Отменить загрузку"), SpeechModel::cancel)
        else -> CardAction(tr("Скачать"), { SpeechModel.download(context) })
    }
    Hint(tr("Нужна для диктовки на телефонах без встроенного офлайн-распознавания и для расшифровки аудиофайлов."))
    SpeechCloud(speech, vm)
}
