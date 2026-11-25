package dev.kento.feature.chat

import dev.kento.core.text.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kento.core.ui.*
import dev.kento.data.net.Balance
import dev.kento.data.net.Usage
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** "$0.0123": small sums keep three significant digits, larger ones cents. */
internal fun money(value: Double, currency: String = "USD"): String {
    val number = when {
        value == 0.0 -> "0"
        value < 1 -> BigDecimal.valueOf(value).round(MathContext(3, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
        else -> BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
    return if (currency.equals("USD", true)) "$$number" else "$number ${currency.uppercase()}"
}

private fun count(value: Long) = java.text.NumberFormat.getIntegerInstance().format(value)

/**
 * The chat's spending, from the chat menu. The large figure is the money: what the provider reported it charged
 * where it did, the catalogue price of the counted tokens elsewhere (marked "≈"). Below are the tokens, the split by
 * model and what is left on the key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpendingSheet(vm: ChatViewModel, chat: String, dismiss: () -> Unit) {
    val spending by produceState<Spending?>(null, chat) { value = vm.spending(chat) }
    var balance by remember { mutableStateOf<Balances?>(null) }
    LaunchedEffect(Unit) { balance = vm.balance() }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = sheetColor) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(tr("Расход чата"), color = Muted, fontSize = 15.sp)
            val total = spending?.total
            if (total == null) {
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            // Without any price there is no honest sum of money: the tokens are the figure then.
            val noPrices = total.requests > 0 && total.unpriced == total.requests
            Text(if (noPrices) tr("{0} токенов", count(total.input + total.output))
                else (if (total.precise || total.requests == 0) "" else "≈ ") + money(total.cost),
                Modifier.padding(top = 4.dp), color = Paper, fontSize = 38.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp)
            Text(when {
                total.requests == 0 -> tr("В этом чате ещё не было запросов")
                total.precise -> tr("Списано по данным провайдера")
                total.exact > 0 -> tr("Часть по данным провайдера, остальное по ценам каталога")
                noPrices -> tr("Провайдер не сообщает цены этой модели. Обновите каталог моделей, если цены у него есть")
                else -> tr("Оценка по ценам каталога: провайдер не сообщает списания")
            }, color = Muted, fontSize = 13.5.sp, lineHeight = 18.sp)
            if (total.requests > 0) {
                Spacer(Modifier.height(18.dp))
                ChoiceGroup {
                    Figure(tr("Запросов"), count(total.requests.toLong()))
                    ChoiceDivider()
                    Figure(tr("Входящие токены"), (if (total.guessed > 0) "≈ " else "") + count(total.input),
                        tr("из кэша: {0}", count(total.cached)).takeIf { total.cached > 0 })
                    ChoiceDivider()
                    Figure(tr("Исходящие токены"), (if (total.guessed > 0) "≈ " else "") + count(total.output),
                        tr("из них рассуждения: {0}", count(total.reasoning)).takeIf { total.reasoning > 0 })
                }
                val models = spending?.byModel.orEmpty()
                if (models.size > 1) {
                    SectionLabel(tr("По моделям"))
                    ChoiceGroup {
                        models.entries.sortedByDescending { it.value.cost }.forEachIndexed { index, (model, usage) ->
                            if (index > 0) ChoiceDivider()
                            Figure(model, (if (usage.precise) "" else "≈ ") + money(usage.cost))
                        }
                    }
                }
            }
            SectionLabel(tr("Баланс"))
            ChoiceGroup {
                val known = balance
                fun shown(b: Balance?) = when { known == null -> "…"; b != null -> money(b.amount, b.currency); else -> tr("не получен") }
                Figure("Refuseless", shown(known?.text), known?.text?.spent?.let { tr("этим ключом потрачено {0}", money(it)) }
                    ?: tr("Проверьте ключ в настройках подключения").takeIf { known != null && known.text == null })
                if (known?.imagesConfigured == true) {
                    ChoiceDivider()
                    Figure("OpenUncensored", shown(known.images), tr("Проверьте ключ в настройках изображений").takeIf { known.images == null })
                }
            }
        }
    }
}

@Composable
private fun Figure(title: String, value: String, note: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Paper, fontSize = 15.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = Muted, fontSize = 12.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(value, color = Paper.copy(alpha = .9f), fontSize = 15.5.sp)
    }
}
