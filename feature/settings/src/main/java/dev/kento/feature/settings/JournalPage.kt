package dev.kento.feature.settings

import dev.kento.core.text.tr

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kento.core.ui.*
import dev.kento.data.log.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The local error journal: newest entries first, shared only when the user taps "Поделиться". */
@Composable
internal fun JournalPage() {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { text = withContext(Dispatchers.IO) { Journal.read() } }
    Spacer(Modifier.height(4.dp))
    Hint(tr("Ошибки ответов, поиска, изображений и падения приложения. Журнал хранится только на телефоне, ") +
        tr("API-ключи в него не попадают."))
    val body = text
    if (body == null) return
    if (body.isBlank()) {
        Hint(tr("Пока пусто — ошибок не было."))
        return
    }
    CardAction(tr("Поделиться журналом"), {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, tr("Kento: журнал ошибок"))
            .putExtra(Intent.EXTRA_TEXT, body.takeLast(60_000))
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    })
    CardAction(tr("Очистить"), { Journal.clear(); version++ })
    // Entries are appended; the newest block goes first, where the user looks.
    val entries = remember(body) { body.trimEnd().split(Regex("\n(?=\\d{4}-\\d{2}-\\d{2} )")).asReversed().take(200) }
    SelectionContainer {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.forEach { Text(it, color = Paper.copy(alpha = .85f), fontSize = 12.5.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}
