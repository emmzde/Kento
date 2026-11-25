package dev.kento.feature.settings

import dev.kento.core.text.tr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kento.core.ui.*
import dev.kento.data.settings.WebSettings
import dev.kento.data.settings.DUCKDUCKGO
import dev.kento.data.settings.AUTO_SEARCH

@Composable
internal fun WebOptions(web: WebSettings, vm: SettingsEditor) {
    var menu by remember { mutableStateOf(false) }
    val probe by vm.probe.collectAsStateWithLifecycle()
    Spacer(Modifier.height(4.dp))
    ChoiceGroup(Card) {
        SwitchRow(tr("Модель ищет сама"), web.modelDecides, tr("Любая модель сама решает, когда искать и какие страницы читать, в том числе по просьбе «посмотри в сети». ") +
            tr("Кнопка глобуса в чате заставляет искать всегда"), Glyph.Globe) { on ->
            vm.editWeb { it.copy(modelDecides = on) }
        }
    }
    SectionLabel(tr("Источник"))
    ChoiceGroup(Card) {
        Box {
            NavigationRow(tr("Поисковик"), tr(web.engine), trailing = Glyph.Down) { menu = true }
            SettingsMenu(menu, { menu = false }) {
                listOf(AUTO_SEARCH, DUCKDUCKGO, "SearXNG", "Tavily").forEach { engine -> DropdownMenuItem(text = { Text(tr(engine)) }, onClick = {
                    menu = false; vm.editWeb { it.copy(engine = engine) }
                }, trailingIcon = if (engine == web.engine) { { Symbol(Glyph.Check, color = Accent, size = 18.dp) } } else null) }
            }
        }
    }
    when (web.engine) {
        AUTO_SEARCH -> {
            Hint(tr("Один запрос идёт в один API с ключом ниже: Tavily, Brave, Serper, Exa по порядку. Следующий — только если первый не ответил или выдача пуста. Без ключей Kento ищет через DuckDuckGo."))
            SectionLabel(tr("Ключи API"))
            SecretField(tr("Ключ Brave Search"), web.braveKey, { value -> vm.editWeb { it.copy(braveKey = value) } }, revealable = false)
            SecretField(tr("Ключ Serper"), web.serperKey, { value -> vm.editWeb { it.copy(serperKey = value) } }, revealable = false)
            SecretField(tr("Ключ Exa"), web.exaKey, { value -> vm.editWeb { it.copy(exaKey = value) } }, revealable = false)
            SecretField(tr("Ключ Tavily"), web.tavilyKey, { value -> vm.editWeb { it.copy(tavilyKey = value) } }, revealable = false)
            Hint(tr("Свежесть и точность поиска зависят от индекса. Brave и Serper (выдача Google) дают самые свежие ") +
                tr("результаты и фильтр по дате; одного ключа уже достаточно."))
            ChoiceGroup(Card) {
                SwitchRow(tr("Один платный запрос на ответ"), web.paidSearches == 1,
                    tr("Первый поиск идёт в API и сразу приносит до 20 страниц целиком, модель читает их без новых запросов. Остальные поиски ответа — через DuckDuckGo и уже полученные страницы")) { on ->
                    vm.editWeb { it.copy(paidSearches = if (on) 1 else 0) }
                }
            }
        }
        "SearXNG" -> {
            SectionLabel(tr("Инстанс"))
            FieldCard(tr("Адрес инстанса SearXNG"), web.instance, { value -> vm.editWeb { it.copy(instance = value) } }, placeholder = "https://…")
            CardAction(tr("Проверить инстанс"), vm::testInstance, enabled = !probe.startsWith(tr("Проверяем")))
            if (probe.isNotBlank()) {
                val good = probe.startsWith(tr("JSON доступен"))
                Notice(probe, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), tone = if (good) Tone.Info else Tone.Problem,
                    pulse = probe.startsWith(tr("Проверяем")))
            }
        }
        "Tavily" -> {
            SectionLabel(tr("Ключ API"))
            SecretField(tr("Ключ Tavily"), web.tavilyKey, { value -> vm.editWeb { it.copy(tavilyKey = value) } }, revealable = false)
            ChoiceGroup(Card) {
                SwitchRow(tr("Один платный запрос на ответ"), web.paidSearches == 1,
                    tr("Первый поиск идёт в API и сразу приносит до 20 страниц целиком, модель читает их без новых запросов. Остальные поиски ответа — через DuckDuckGo и уже полученные страницы")) { on ->
                    vm.editWeb { it.copy(paidSearches = if (on) 1 else 0) }
                }
            }
        }
        else -> Hint(tr("DuckDuckGo работает без ключа. Если сайт попросит проверку, Kento откроет её во встроенном браузере и покажет в чате."))
    }
}
