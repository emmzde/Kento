package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.media.SpeechPresets
import dev.kento.data.settings.SpeechEndpoint

/**
 * Cloud dictation: the same kind of recognition ChatGPT and Gemini use. Groq's Whisper large-v3-turbo has a
 * free tier and costs $0.04 per hour of speech after it; nothing is added to the app's size.
 */
@Composable
internal fun SpeechCloud(endpoint: SpeechEndpoint, vm: SettingsEditor) {
    var presets by remember { mutableStateOf(false) }
    val checking by vm.checkingSpeech.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val preset = SpeechPresets.all.firstOrNull { it.baseUrl == endpoint.baseUrl.trim().trimEnd('/') }
    SectionLabel(tr("Облачное распознавание"))
    Hint(tr("Как в ChatGPT и Gemini: фраза целиком уходит большой модели, она понимает контекст и сама расставляет ") +
        tr("знаки препинания. Работает при наличии сети и ничего не добавляет к размеру приложения."))
    ChoiceGroup(Card) {
        Box {
            NavigationRow(tr("Сервис"), preset?.name ?: if (endpoint.configured) tr("Свой сервер") else tr("Не задан"),
                trailing = Glyph.Down) { presets = true }
            SettingsMenu(presets, { presets = false }) {
                SpeechPresets.all.forEach { p -> DropdownMenuItem(text = { Text(p.name + " · " + p.model) }, onClick = {
                    presets = false; vm.editSpeech { it.copy(baseUrl = p.baseUrl, model = p.model) }
                }) }
                DropdownMenuItem(text = { Text(tr("Свой сервер")) }, onClick = { presets = false; vm.editSpeech { it.copy(baseUrl = "", model = "") } })
            }
        }
    }
    FieldCard("Base URL", endpoint.baseUrl, { v -> vm.editSpeech { it.copy(baseUrl = v) } }, placeholder = "https://…/v1",
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
    FieldCard(tr("Модель"), endpoint.model, { v -> vm.editSpeech { it.copy(model = v) } }, placeholder = "whisper-large-v3-turbo")
    SecretField(tr("API-ключ"), endpoint.apiKey, { v -> vm.editSpeech { it.copy(apiKey = v) } })
    CardAction(if (checking) tr("Проверяем…") else tr("Проверить"), vm::checkSpeech, enabled = !checking && endpoint.configured)
    if (preset != null && endpoint.apiKey.isBlank()) CardAction(tr("Получить ключ {0}", preset.name), { uri.openUri(preset.keyPage) })
    Hint(tr("Groq: бесплатный ключ без карты, около 2 часов речи в час; дальше $0.04 за час. Ключ хранится зашифрованным."))
    if (endpoint.configured) CardAction(tr("Отключить облачное распознавание"), { vm.editSpeech { SpeechEndpoint() } })
}
