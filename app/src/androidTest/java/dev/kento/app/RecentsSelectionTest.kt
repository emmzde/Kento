package dev.kento.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** The full chat list: select several chats and delete them in one go, with a confirmation and a cancel. */
class RecentsSelectionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun selectsSeveralChatsAndDeletesThemTogether() {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        val tag = UUID.randomUUID().toString().take(6)
        val titles = listOf("Пакет $tag один", "Пакет $tag два", "Пакет $tag три")
        val ids = titles.map { title ->
            UUID.randomUUID().toString().also { id -> runBlocking { app.db.start(id, title, "m").let { app.db.endActive(it, "done") } } }
        }
        try {
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("История чатов").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("История чатов").performClick()
            ui.onNodeWithText("Показать все").performClick()
            ui.onNodeWithText("Выбрать").performClick()
            ui.onNodeWithText("Выберите чаты").assertExists()
            ui.onNodeWithText(titles[0]).performClick()
            ui.onNodeWithText(titles[2]).performClick()
            ui.onNodeWithText("Выбрано: 2").assertExists()
            // Tapping a chosen chat again takes it out of the selection.
            ui.onNodeWithText(titles[2]).performClick()
            ui.onNodeWithText("Выбрано: 1").assertExists()
            ui.onNodeWithText(titles[2]).performClick()

            ui.onNodeWithContentDescription("Удалить выбранные чаты").performClick()
            ui.onNodeWithText("Удалить чаты: 2?").assertExists()
            ui.onNodeWithText("Отмена").performClick()
            assertEquals(3, runBlocking { app.db.history().first().count { it.id in ids } })

            ui.onNodeWithContentDescription("Удалить выбранные чаты").performClick()
            ui.onNodeWithText("Удалить").performClick()
            ui.waitUntil(10_000) { runBlocking { app.db.history().first().count { it.id in ids } == 1 } }
            assertEquals(titles[1], runBlocking { app.db.history().first().single { it.id in ids }.title })
            // Deleting leaves selection mode.
            ui.onNodeWithText("Чаты").assertExists()
        } finally {
            runBlocking { ids.forEach { app.db.delete(it) } }
        }
    }

    @Test fun longPressInTheSidePanelDeletesAChat() {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        val title = "Панель " + UUID.randomUUID().toString().take(6)
        val id = UUID.randomUUID().toString()
        runBlocking { app.db.endActive(app.db.start(id, title, "m"), "done") }
        try {
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("История чатов").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("История чатов").performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(title).performTouchInput { longClick() }
            ui.onNodeWithText("Удалить чат").performClick()
            ui.onNodeWithText("Удалить").performClick()
            ui.waitUntil(10_000) { runBlocking { app.db.history().first().none { it.id == id } } }
        } finally { runBlocking { app.db.delete(id) } }
    }
}
