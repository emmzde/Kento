package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import dev.kento.core.ui.Glyph
import dev.kento.core.ui.Paper
import dev.kento.core.ui.Symbol
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dev.kento.data.db.MemoryDao
import dev.kento.data.memory.MemoryArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MemoryFileActions(memory: MemoryDao, report: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val archive = remember(memory) { MemoryArchive(memory) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun perform(success: String, failure: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
                report(success)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(failure) }
            finally { busy = false }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) perform(tr("Память экспортирована"), tr("Не удалось экспортировать память")) {
            archive.save { checkNotNull(resolver.openOutputStream(uri, "wt")) }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform(tr("Память восстановлена"), tr("Не удалось импортировать память. Проверьте файл резервной копии.")) {
            archive.restore { checkNotNull(resolver.openInputStream(uri)) }
        }
    }
    Box {
        IconButton(onClick = { menu = true }, enabled = !busy) { Symbol(Glyph.More, tr("Действия"), Paper) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(tr("Экспорт памяти")) }, onClick = {
                menu = false
                export.launch("Kento-memory.json")
            })
            DropdownMenuItem(text = { Text(tr("Импорт памяти")) }, onClick = {
                menu = false
                import.launch(arrayOf("application/json"))
            })
            DropdownMenuItem(text = { Text(tr("Очистить память")) }, onClick = {
                menu = false
                confirmClear = true
            })
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(tr("Очистить память?")) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            perform(tr("Память очищена"), tr("Не удалось очистить память")) { memory.clear() }
        }) { Text(tr("Очистить")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Отмена")) } })
}
