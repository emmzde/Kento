package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.settings.*

private val MenuShape = RoundedCornerShape(18.dp)

/** Drop-down menus of the settings screens share the raised card look. */
@Composable
internal fun SettingsMenu(expanded: Boolean, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    DropdownMenu(expanded, dismiss, shape = MenuShape, containerColor = menuColor, content = content)

/** The Refuseless connection: the key is the only thing to set, the address is fixed. */
@Composable
internal fun Connections(book: ProfileBook, vm: SettingsEditor) {
    val checking by vm.checking.collectAsStateWithLifecycle()
    val active = book.active
    SectionLabel("Refuseless")
    SecretField(tr("API-ключ"), active.apiKey, { v -> vm.edit { it.copy(apiKey = v) } })
    CardAction(if (checking) tr("Проверяем…") else tr("Проверить подключение"), vm::check, enabled = !checking)
    Hint(tr("Чат, память, названия чатов и веб-поиск работают через Refuseless (api.refuseless.com). Ключ начинается с abl_ ") +
        tr("и хранится зашифрованным. Каталог моделей открыт и без ключа, с ключом в нём цены вашего аккаунта."))

    if (active.model.isNotBlank()) {
        SectionLabel(tr("Ответы"))
        ChoiceGroup(Card) {
            var open by remember { mutableStateOf(false) }
            val override = active.visionOverrides[active.model]
            val detected = when (active.option()?.vision) { true -> tr("зрение"); false -> "OCR"; null -> tr("каталог не загружен") }
            Box {
                NavigationRow(tr("Изображения"), when (override) { true -> tr("Зрение"); false -> tr("Локальный OCR"); null -> tr("Авто · {0}", detected) },
                    subtitle = active.model, trailing = Glyph.Down) { open = true }
                SettingsMenu(open, { open = false }) {
                    listOf(null to tr("Авто"), true to tr("Зрение"), false to tr("Локальный OCR")).forEach { (mode, label) ->
                        DropdownMenuItem({ Text(label) }, {
                            open = false
                            vm.edit { it.copy(visionOverrides = if (mode == null) it.visionOverrides - it.model else it.visionOverrides + (it.model to mode)) }
                        })
                    }
                }
            }
        }
        Hint(tr("«Изображения» решает, как модель получит фото: сама посмотрит на него или прочитает распознанный текст."))
    }
}
