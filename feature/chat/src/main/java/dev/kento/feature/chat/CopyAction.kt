package dev.kento.feature.chat

import android.content.ClipData
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import dev.kento.core.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CopyAction(text: String, label: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    IconButton(onClick = { scope.launch {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Kento", text)))
        copied = true
    } }, modifier = Modifier.size(40.dp)) {
        Symbol(if (copied) Glyph.Check else Glyph.Copy, label, if (copied) Accent else Muted, 18.dp)
    }
}
