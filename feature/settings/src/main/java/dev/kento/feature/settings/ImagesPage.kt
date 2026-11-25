package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.settings.ImageEndpoint

/** Pictures are created and edited through OpenUncensored; the key is all there is to set here. */
@Composable
internal fun ImagesPage(endpoint: ImageEndpoint, vm: SettingsEditor) {
    val checking by vm.checkingImages.collectAsStateWithLifecycle()
    Spacer(Modifier.height(4.dp))
    SectionLabel("OpenUncensored")
    SecretField(tr("API-ключ"), endpoint.apiKey, { v -> vm.editImages { it.copy(apiKey = v) } })
    CardAction(if (checking) tr("Проверяем…") else tr("Проверить и загрузить модели"), vm::checkImages,
        enabled = !checking && endpoint.configured)
    if (endpoint.catalog.isNotEmpty()) {
        val edits = endpoint.catalog.count { it.edit }
        Hint(tr("Моделей: {0}, из них с редактированием по фото: {1}. ", endpoint.catalog.size, edits) +
            endpoint.catalog.take(6).joinToString(", ") { it.name } + if (endpoint.catalog.size > 6) "…" else "")
    }
    Hint(tr("Картинки создаются и редактируются через api.openuncensored.com, оплата за каждое изображение по цене каталога. ") +
        tr("Ключ начинается с ou- и хранится зашифрованным."))
    if (endpoint.configured) CardAction(tr("Удалить ключ"), { vm.editImages { ImageEndpoint() } })
}
