package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kento.core.ui.*
import dev.kento.data.settings.HISTORY_CHOICES
import dev.kento.data.settings.HISTORY_TOKENS
import dev.kento.data.settings.Sampling
import dev.kento.data.settings.Settings
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/**
 * Sampling for the selected model. Every value starts as "provider default" and is only sent once the user sets it,
 * except the answer length: without a value a chat request is capped by its reasoning level (see Settings.maxTokens).
 */
@Composable
internal fun AdvancedPage(s: Settings, vm: SettingsEditor) {
    val model = s.model
    val sampling = s.sampling()
    fun change(next: Sampling) = vm.edit { it.withSampling(next) }
    // Refuseless accepts every one of these parameters (checked live) and publishes no defaults of its own.
    fun takes(@Suppress("UNUSED_PARAMETER") name: String) = true
    fun default(@Suppress("UNUSED_PARAMETER") name: String): Double? = null

    Spacer(Modifier.height(4.dp))
    if (model.isBlank()) {
        Hint(tr("Сначала выберите основную модель: параметры хранятся отдельно для каждой модели."))
        return
    }
    Hint(tr("Для модели {0}. У каждой модели свои значения. «По умолчанию» значит, что параметр не отправляется ", model) +
        tr("и модель работает со своими настройками."))
    SectionLabel(tr("Генерация"))
    ChoiceGroup(Card) {
        Param(tr("Температура"), tr("Ниже — точнее и предсказуемее, выше — разнообразнее. При «каше» из символов и языков уменьшите"),
            sampling.temperature, 0.0..2.0, 0.05, default("temperature"), takes("temperature")) { change(sampling.copy(temperature = it)) }
        ChoiceDivider()
        Param("Top P", tr("Модель выбирает только из самых вероятных слов с суммарной вероятностью P"),
            sampling.topP, 0.0..1.0, 0.01, default("top_p"), takes("top_p")) { change(sampling.copy(topP = it)) }
        ChoiceDivider()
        Param("Min P", tr("Отсекает слова, вероятность которых ниже доли от самого вероятного. Хорошо убирает мусорные токены"),
            sampling.minP, 0.0..1.0, 0.01, default("min_p"), takes("min_p")) { change(sampling.copy(minP = it)) }
        ChoiceDivider()
        Param("Top K", tr("Сколько самых вероятных слов рассматривать на каждом шаге"),
            sampling.topK?.toDouble(), 0.0..200.0, 1.0, default("top_k"), takes("top_k")) { change(sampling.copy(topK = it?.roundToInt())) }
    }
    SectionLabel(tr("Повторы"))
    ChoiceGroup(Card) {
        Param(tr("Штраф за частоту"), tr("Чем выше, тем реже модель повторяет одни и те же слова"),
            sampling.frequencyPenalty, -2.0..2.0, 0.05, default("frequency_penalty"), takes("frequency_penalty")) {
            change(sampling.copy(frequencyPenalty = it))
        }
        ChoiceDivider()
        Param(tr("Штраф за присутствие"), tr("Подталкивает к новым темам и словам"),
            sampling.presencePenalty, -2.0..2.0, 0.05, default("presence_penalty"), takes("presence_penalty")) {
            change(sampling.copy(presencePenalty = it))
        }
        ChoiceDivider()
        Param(tr("Штраф за повторы"), tr("1 — без штрафа. Используется открытыми моделями (llama.cpp, vLLM)"),
            sampling.repetitionPenalty, 0.5..2.0, 0.01, default("repetition_penalty"), takes("repetition_penalty")) {
            change(sampling.copy(repetitionPenalty = it))
        }
    }
    SectionLabel(tr("Ответ"))
    NumberField(tr("Максимум токенов ответа"), sampling.maxTokens?.toLong(), takes("max_tokens"),
        tr("Ограничивает длину ответа и расход. У думающих моделей сюда входят и рассуждения. Без значения — по уровню рассуждений: 8 192 для off, 16 384 для low, 32 768 для high, 65 536 для max. ") +
            tr("Refuseless заранее резервирует на балансе цену промпта и этого числа токенов")) {
        change(sampling.copy(maxTokens = it?.toInt()?.coerceAtLeast(1)))
    }
    NumberField("Seed", sampling.seed, takes("seed"), tr("С одинаковым seed и температурой ответы повторяются, если провайдер это поддерживает")) {
        change(sampling.copy(seed = it))
    }
    SectionLabel(tr("Контекст"))
    ChatMemory(s.historyTokens) { n -> vm.edit { it.copy(historyTokens = n) } }
    Hint(tr("До этого объёма весь чат уходит в запрос дословно. Длиннее — самая старая часть сворачивается фоновой моделью в сводку, и дословно остаётся половина. Точные детали из начала модель найдёт поиском по памяти."))
    if (!sampling.isDefault()) CardAction(tr("Сбросить параметры модели"), { change(Sampling()) })
}

private fun format(value: Double, step: Double): String {
    val digits = BigDecimal.valueOf(step).stripTrailingZeros().scale().coerceAtLeast(0)
    return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()
}

@Composable
private fun Param(title: String, help: String, value: Double?, range: ClosedFloatingPointRange<Double>, step: Double,
    default: Double?, enabled: Boolean, change: (Double?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (enabled) 1f else .45f),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = Paper, fontSize = 16.sp)
            Text(when {
                !enabled -> tr("Модель не принимает")
                value != null -> format(value, step)
                default != null -> tr("По умолчанию · ") + format(default, step)
                else -> tr("По умолчанию")
            }, color = if (value != null) Accent else Muted, fontSize = 14.5.sp)
            if (value != null && enabled) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(CircleShape).background(Raised).clickable { change(null) }.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(tr("Сброс"), color = Paper, fontSize = 13.sp)
                }
            }
        }
        Text(help, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
        val position = (value ?: default ?: (range.start + range.endInclusive) / 2).coerceIn(range)
        val steps = ((range.endInclusive - range.start) / step).roundToInt() - 1
        Slider(position.toFloat(), { picked ->
            val snapped = range.start + ((picked - range.start) / step).roundToInt() * step
            change(BigDecimal.valueOf(snapped).setScale(6, RoundingMode.HALF_UP).toDouble())
        }, enabled = enabled, valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = if (steps in 1..400) steps else 0,
            colors = SliderDefaults.colors(thumbColor = if (value != null) Accent else Muted,
                activeTrackColor = if (value != null) Accent else Line, inactiveTrackColor = Raised,
                activeTickColor = androidx.compose.ui.graphics.Color.Transparent, inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable
private fun NumberField(label: String, value: Long?, enabled: Boolean, help: String, change: (Long?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    Column(Modifier.alpha(if (enabled) 1f else .45f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldCard(label, text, { raw ->
            if (!enabled) return@FieldCard
            text = raw.filter(Char::isDigit).take(12)
            change(text.toLongOrNull())
        }, placeholder = if (enabled) tr("По умолчанию") else tr("Модель не принимает"),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        Hint(help)
    }
}

/** "50 тыс." for the chat memory choices. */
private fun thousands(tokens: Int) = tr("{0} тыс.", tokens / 1000)

@Composable
private fun ChatMemory(current: Int, pick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ChoiceGroup(Card) {
        Box {
            NavigationRow(tr("Память чата"), tr("{0} токенов", thousands(current)), trailing = Glyph.Down) { open = true }
            Box(Modifier.align(Alignment.TopEnd)) {
                SettingsMenu(open, { open = false }) {
                    HISTORY_CHOICES.forEach { n ->
                        val label = thousands(n) + if (n == HISTORY_TOKENS) tr(" (по умолчанию)") else ""
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; pick(n) },
                            trailingIcon = if (n == current) { { Symbol(Glyph.Check, color = Accent, size = 18.dp) } } else null)
                    }
                }
            }
        }
    }
}
