package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.db.*
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp

private val factDay = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.forLanguageTag("ru"))
private fun factDate(millis: Long) = if (millis <= 0) "" else
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).format(factDay)

@Composable
fun DossierScreen(dao: MemoryDao, back: () -> Unit) {
    BackHandler(onBack = back)
    val facts by remember(dao) { dao.facts() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var fileStatus by rememberSaveable { mutableStateOf("") }
    val visibleFacts = remember(facts, query) {
        val terms = query.split(Regex("\\s+")).filter(String::isNotEmpty)
        facts.filter { fact -> terms.all { term -> fact.text.contains(term, ignoreCase = true) } }
    }
    Screen(Modifier.fillMaxSize(), Night) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ScreenBar(tr("Досье"), back) { MemoryFileActions(dao) { fileStatus = it } }
        if (fileStatus.isNotBlank()) Text(fileStatus, Modifier.padding(horizontal = 16.dp), color = Accent)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text(tr("Найти факт")) }, singleLine = true)
            IconButton(onClick = { editId = 0; draft = "" }, Modifier.padding(start = 6.dp)) {
                Symbol(Glyph.Plus, tr("Добавить факт"), Paper)
            }
        }
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (facts.isEmpty()) item { Text(tr("Пока пусто"), Modifier.padding(4.dp), color = Muted) }
            else if (visibleFacts.isEmpty()) item { Text(tr("Совпадений нет"), color = Muted) }
            items(visibleFacts, key = { it.id }) { fact ->
                ChoiceGroup {
                    Text(fact.text, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp))
                    Text(listOf(fact.topic, factDate(fact.createdAt)).filter(String::isNotBlank).joinToString(" · "),
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp), color = Muted, fontSize = 12.5.sp)
                    Row {
                        if (!fact.identity.startsWith("manual:")) TextButton({ sourceId = fact.id }) { Text(tr("Источник")) }
                        TextButton({ editId = fact.id; draft = fact.text }) { Text(tr("Изменить")) }
                        ToolButton(Glyph.Trash, tr("Удалить факт"), { deleteId = fact.id })
                    }
                }
            }
        }
    }
    }
    editId?.let { id -> AlertDialog(onDismissRequest = { editId = null },
        title = { Text(if (id == 0L) tr("Новый факт") else tr("Изменить факт")) },
        text = { OutlinedTextField(draft, { draft = it }, label = { Text(tr("Факт")) }) },
        confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = {
            val text = draft.trim()
            scope.launch { if (id == 0L) dao.addFact(text) else dao.amend(id, text); editId = null }
        }) { Text(tr("Сохранить")) } }, dismissButton = { TextButton({ editId = null }) { Text(tr("Отмена")) } }) }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(tr("Удалить факт из досье?")) },
        confirmButton = { TextButton({ scope.launch { dao.forget(id); deleteId = null } }) { Text(tr("Удалить")) } },
        dismissButton = { TextButton({ deleteId = null }) { Text(tr("Отмена")) } }) }
    sourceId?.let { id ->
        val sources by produceState<List<Message>?>(null, id) { value = dao.sources(id) }
        AlertDialog(onDismissRequest = { sourceId = null }, title = { Text(tr("Исходные сообщения")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                sources?.let { rows ->
                    if (rows.isEmpty()) Text(tr("Исходная переписка удалена."))
                    for (m in rows) Text("#" + m.id + " · " + m.text, Modifier.padding(bottom = 10.dp))
                }
            }
        }, confirmButton = { TextButton({ sourceId = null }) { Text(tr("Закрыть")) } })
    }
}
