package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import java.time.LocalDate

/** Save everything to one file and bring it back on another phone; API keys stay out of the file. */
@Composable
internal fun BackupPage(vm: SettingsEditor) {
    val resolver = LocalContext.current.contentResolver
    val state by vm.backupState.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.saveBackup { checkNotNull(resolver.openOutputStream(uri, "wt")) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreBackup { checkNotNull(resolver.openInputStream(uri)) }
    }
    val busy = state.startsWith("…")
    Spacer(Modifier.height(4.dp))
    Hint(tr("Один файл со всеми чатами, вложениями, досье и настройками. Сохраните его в облако или на компьютер, ") +
        tr("а на новом телефоне восстановите отсюда же."))
    CardAction(tr("Сохранить копию"), { save.launch("Kento-${LocalDate.now()}.zip") }, enabled = !busy)
    CardAction(tr("Восстановить из копии"), { open.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy)
    if (state.isNotBlank()) Notice(state.removePrefix("…"), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        tone = if (state.startsWith("!")) Tone.Problem else Tone.Info, pulse = busy)
    Hint(tr("API-ключи подключений, поиска и изображений в копию не входят: после восстановления введите их заново. ") +
        tr("Чаты, которые уже есть на телефоне, не перезаписываются."))
}
