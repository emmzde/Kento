package dev.kento.core.ui

import dev.kento.core.text.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogList(ids: List<String>, selected: String, details: Map<String, String>, pick: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val id = query.trim()
    val matches = remember(ids, id) { ids.filter { it.contains(id, true) || modelName(it).contains(id, true) } }
    SearchField(query, { query = it }, tr("Название или ID модели"))
    Spacer(Modifier.height(10.dp))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
        items(matches, key = { it }) { item ->
            OptionRow(modelName(item), item == selected, details[item].orEmpty()) { pick(item) }
            ChoiceDivider()
        }
        if (id.isNotEmpty() && id.none(Char::isWhitespace) && id !in ids) item {
            OptionRow(tr("Использовать этот ID"), false, id) { pick(id) }
        }
        if (matches.isEmpty() && (id.isEmpty() || id.any(Char::isWhitespace))) item {
            Text(if (id.isEmpty()) tr("Введи ID модели") else tr("Совпадений нет"),
                Modifier.padding(12.dp), color = Muted)
        }
    }
}
