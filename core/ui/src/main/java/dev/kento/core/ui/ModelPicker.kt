package dev.kento.core.ui

import dev.kento.core.text.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler

enum class PickerPage { Models, Effort, Catalog }

/**
 * The reasoning levels Refuseless publishes for the selected model ([levels], from "off" to "max") and the one it
 * would use if the request named none ([defaultLevel]). Kento always sends a level, so there is no "auto" choice.
 */
class Reasoning(val levels: List<String> = emptyList(), val defaultLevel: String? = null) {
    val any get() = levels.isNotEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    model: String, ids: List<String>, effort: String, reasoning: Reasoning,
    dismiss: () -> Unit, pickModel: (String) -> Unit, pickEffort: (String) -> Unit,
    forMemory: Boolean = false, names: Map<String, String> = emptyMap(), details: Map<String, String> = emptyMap(),
    start: PickerPage = PickerPage.Models
) {
    var page by rememberSaveable(start) { mutableStateOf(start) }
    val back = { if (page == start) dismiss() else page = start }
    val known = remember(model, ids) { (listOf(model) + ids).filter(String::isNotBlank).distinct() }
    ModalBottomSheet(onDismissRequest = dismiss, dragHandle = null, containerColor = sheetColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = page == start),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BackHandler(enabled = page != start, onBack = back)
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Box(Modifier.fillMaxWidth().height(66.dp)) {
                val nested = page != start
                Box(Modifier.align(Alignment.CenterStart)) {
                    ToolButton(if (nested) Glyph.Back else Glyph.Close, if (nested) tr("Назад") else tr("Закрыть"), back)
                }
                Text(when (page) {
                    PickerPage.Models -> if (forMemory) tr("Модель для памяти") else tr("Выбор модели")
                    PickerPage.Effort -> tr("Рассуждения")
                    PickerPage.Catalog -> tr("Другие модели")
                }, Modifier.align(Alignment.Center), color = Paper, fontSize = 17.sp)
            }
            if (page == PickerPage.Catalog) {
                CatalogList(known, model, details, pickModel)
            } else key(page) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (page) {
                        PickerPage.Models -> {
                            if (known.isNotEmpty()) ChoiceGroup {
                                known.take(4).forEachIndexed { index, id ->
                                    if (index > 0) ChoiceDivider()
                                    val name = names[id]?.ifBlank { id } ?: id
                                    val subtitle = listOfNotNull(id.takeIf { name != id }, details[id]?.takeIf(String::isNotBlank)).joinToString(" · ")
                                    OptionRow(name, id == model, subtitle) { pickModel(id) }
                                }
                            }
                            if (!forMemory && reasoning.any) ChoiceGroup {
                                NavigationRow(tr("Рассуждения"), reasoningLabel(effort, reasoning)) { page = PickerPage.Effort }
                            }
                            ChoiceGroup { NavigationRow(tr("Другие модели")) { page = PickerPage.Catalog } }
                            if (forMemory && model.isNotEmpty()) {
                                TextButton(onClick = { pickModel("") }) { Text(tr("Снять выбор")) }
                            }
                        }
                        PickerPage.Effort -> ReasoningPage(effort, reasoning, pickEffort)
                        PickerPage.Catalog -> Unit
                    }
                }
            }
        }
    }
}

/** The chosen level by name ("Высокий"); the picker shows it next to "Рассуждения". */
fun reasoningLabel(effort: String, reasoning: Reasoning): String = if (effort.isEmpty() && !reasoning.any) "" else effortName(effort)

@Composable
private fun ReasoningPage(effort: String, reasoning: Reasoning, pickEffort: (String) -> Unit) {
    ChoiceGroup {
        reasoning.levels.forEachIndexed { index, id ->
            if (index > 0) ChoiceDivider()
            val subtitle = when {
                id == "off" -> tr("Отвечает сразу, быстрее и дешевле")
                id == reasoning.defaultLevel -> tr("Уровень провайдера по умолчанию")
                else -> ""
            }
            OptionRow(effortName(id), id == effort, subtitle) { pickEffort(id) }
        }
    }
    Hint(tr("Уровни, которые Refuseless публикует для этой модели. Выбор запоминается для каждой модели; ") +
        tr("без выбора Kento просит высокий уровень. Рассуждения оплачиваются как ответ."))
}
