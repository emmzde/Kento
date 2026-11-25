package dev.kento.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.kento.data.settings.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private fun text(value: String) = ui.onNodeWithText(value)
    private fun show(value: String) = text(value).performScrollTo()
    private fun awaitText(value: String) {
        ui.waitUntil(10000) { ui.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun keyCheckAndSearchProbeKeepOneConnection() {
        val old = runBlocking { app.settings.profiles.first() }
        MockWebServer().use { server ->
            app.apiBase = server.url("/v1").toString()
            val original = Settings(apiKey = "abl_first-qa", catalog = listOf(ModelOption("cached-one")), catalogLoaded = true).select("cached-one")
            try {
                runBlocking { app.settings.updateBook { ProfileBook(listOf(original), original.id) } }
                ui.onNodeWithContentDescription("История чатов").performClick()
                ui.onNodeWithContentDescription("Настройки").performClick()
                ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Подключение").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithContentDescription("Подключение").performClick()
                awaitText("API-ключ")
                text("Base URL").assertDoesNotExist()
                show("API-ключ").performTextReplacement("abl_second-qa")
                server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"glm-5.3-flash-abliterated","abliterated":true,
                    "reasoning_efforts":["low","high","max"],"reasoning_default":"low"}]}"""))
                server.enqueue(MockResponse().setBody("""{"object":"credits","balance":5,"reserved":0,"available":4.5,"key_spent":0.5,"unit":"usd"}"""))
                show("Проверить подключение").performClick()
                awaitText("Каталог доступен · моделей: 1 · доступно $4.50")
                assertEquals("/v1/models", server.takeRequest().path)
                val credits = server.takeRequest()
                assertEquals("/v1/credits", credits.path)
                assertEquals("Bearer abl_second-qa", credits.getHeader("Authorization"))
                ui.onNodeWithContentDescription("Назад").performClick()
                awaitText("Веб-поиск")
                text("Веб-поиск").performClick()
                awaitText("Поисковик")
                show("Поисковик").performClick()
                text("SearXNG").performClick()
                show("Адрес инстанса SearXNG").performTextReplacement(server.url("/").toString())
                server.enqueue(MockResponse().setBody("<html>challenge</html>"))
                show("Проверить инстанс").performClick()
                awaitText("Сервер не вернул JSON со списком results. Возможно, включена проверка браузера")
                server.enqueue(MockResponse().setBody("""{"results":[]}"""))
                show("Проверить инстанс").performClick()
                awaitText("JSON доступен · результатов: 0")
                repeat(2) {
                    val probe = server.takeRequest()
                    assertEquals("/search", probe.requestUrl!!.encodedPath)
                    assertNull(probe.getHeader("Authorization"))
                }
                text("Сохранить").performClick()
                ui.waitUntil(10000) { runBlocking { app.settings.profiles.first().active.apiKey == "abl_second-qa" } }
                val saved = runBlocking { SettingsStore(app).profiles.first() }
                assertEquals(1, saved.profiles.size)
                assertEquals(listOf("glm-5.3-flash-abliterated", "cached-one"), saved.active.modelIds())
            } finally {
                app.apiBase = null
                runBlocking { app.settings.updateBook { old } }
            }
        }
    }

    @Test fun dossierRequiresConfirmationAndRetainsEdits() {
        ui.onNodeWithContentDescription("История чатов").performClick()
        awaitText("Досье")
        text("Досье").performClick()
        ui.onNodeWithContentDescription("Добавить факт").performClick()
        text("Факт").performTextInput("Тест ручного факта")
        text("Сохранить").performClick()
        awaitText("Тест ручного факта")
        text("Найти факт").performTextReplacement("ФАК РУЧН")
        text("Тест ручного факта").assertExists()
        text("Найти факт").performTextReplacement("несуществующий")
        awaitText("Совпадений нет")
        text("Тест ручного факта").assertDoesNotExist()
        text("Найти факт").performTextReplacement("")
        text("Изменить").performClick()
        text("Факт").performTextReplacement("Исправленный факт")
        text("Сохранить").performClick()
        awaitText("Исправленный факт")
        ui.onNodeWithContentDescription("Удалить факт").performClick()
        text("Отмена").performClick()
        text("Исправленный факт").assertExists()
        ui.onNodeWithContentDescription("Удалить факт").performClick()
        text("Удалить").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Исправленный факт").fetchSemanticsNodes().isEmpty() }
    }
}
