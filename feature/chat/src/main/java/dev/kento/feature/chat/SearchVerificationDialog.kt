package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.data.web.DuckDuckGoSearch

@Composable
fun SearchVerificationDialog(browser: DuckDuckGoSearch, stop: () -> Unit) {
    val pending by browser.verification.collectAsStateWithLifecycle()
    val check = pending ?: return
    Dialog(onDismissRequest = stop, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Проверка DuckDuckGo"), Modifier.weight(1f))
                    TextButton(onClick = stop) { Text(tr("Стоп")) }
                }
                AndroidView(factory = { FrameLayout(it) }, modifier = Modifier.fillMaxWidth().weight(1f),
                    onRelease = { it.removeAllViews() },
                    update = { frame ->
                        if (check.view.parent !== frame) {
                            (check.view.parent as? ViewGroup)?.removeView(check.view)
                            frame.addView(check.view, FrameLayout.LayoutParams(-1, -1))
                        }
                    })
                TextButton(onClick = check.returnToSearch, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(tr("Вернуться к выдаче"))
                }
            }
        }
    }
}
