package dev.kento.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import dev.kento.data.backup.Backup
import dev.kento.data.db.Attachment
import dev.kento.data.db.Source
import dev.kento.data.db.encodeSources
import dev.kento.data.settings.Settings
import dev.kento.feature.chat.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Release 0.5.0: editing a sent message, searching inside chats, tables and formulas, the full backup. */
class ReleaseFeaturesTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val server = MockWebServer()
    private lateinit var vm: ChatViewModel

    @Before fun setup() {
        server.start()
        app.apiBase = server.url("/v1").toString()
        runBlocking { app.settings.update { Settings(id = it.id, model = "release-fixture", promptClock = false) } }
        vm = ui.runOnIdle { ViewModelProvider(ui.activity)[ChatViewModel::class.java] }
        ui.waitUntil(15000) { vm.ready.value && vm.settings.value?.model == "release-fixture" }
        ui.runOnIdle { vm.newChat() }
    }
    @After fun finish() { server.shutdown() }

    private fun event(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":${JsonPrimitive(text)}}}]}\n\n"
    private fun answer(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event(text) + "data: [DONE]\n\n")
    private fun awaitText(text: String, substring: Boolean = false) =
        ui.waitUntil(15000) { ui.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitIdle() = ui.waitUntil(15000) { ui.onAllNodesWithContentDescription("Остановить").fetchSemanticsNodes().isEmpty() }
    private fun send(text: String) {
        ui.onNodeWithContentDescription("Сообщение").performTextReplacement(text)
        ui.onNodeWithContentDescription("Отправить").performClick()
    }
    private fun lastUser() = Json.parseToJsonElement(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
        .getValue("messages").jsonArray.last { it.jsonObject.getValue("role").jsonPrimitive.content == "user" }
        .jsonObject.getValue("content").jsonPrimitive.content

    @Test fun editingAMessageReplacesTheAnswersAfterIt() {
        server.enqueue(answer("Первый ответ"))
        send("Сколько планет в системе?")
        awaitText("Первый ответ"); awaitIdle()
        assertTrue(lastUser().endsWith("Сколько планет в системе?"))

        // The chat title repeats the first line; the bubble is the clickable one.
        ui.onNode(hasText("Сколько планет в системе?") and hasClickAction()).performClick()
        ui.onNodeWithContentDescription("Изменить сообщение").performClick()
        awaitText("Изменение сообщения", substring = true)
        server.enqueue(answer("Исправленный ответ"))
        send("Сколько планет в Солнечной системе?")
        awaitText("Исправленный ответ"); awaitIdle()
        assertTrue(lastUser().endsWith("Сколько планет в Солнечной системе?"))
        ui.onNodeWithText("Первый ответ").assertDoesNotExist()
        val rows = runBlocking { app.db.recent(vm.activeId.value, 10).first() }
        assertEquals(listOf("user" to "Сколько планет в Солнечной системе?", "assistant" to "Исправленный ответ"),
            rows.asReversed().map { it.role to it.text })
    }

    @Test fun tablesAndFormulasAreDrawnNotPrintedRaw() {
        server.enqueue(answer("| Планета | Луны |\n|---|---:|\n| Земля | 1 |\n| Марс | 2 |\n\nКорни:\n\n\$\$x = \\frac{-b \\pm \\sqrt{D}}{2a}\$\$\n\nУсловие \$a \\neq 0\$ и **шаг 1:** \$\$ax^2 + bx + c = 0, \\quad a \\neq 0\$\$ внутри строки.\n\nИ ~~старое~~ значение"))
        send("Покажи таблицу и формулу")
        awaitText("Марс"); awaitIdle()
        ui.onNodeWithText("Планета").assertExists()
        ui.onAllNodesWithText("|", substring = true).assertCountEquals(0)
        ui.onNodeWithContentDescription("x = \\frac{-b \\pm \\sqrt{D}}{2a}").assertExists()
        ui.onAllNodesWithText("\\frac", substring = true).assertCountEquals(0)
        ui.onAllNodesWithText("\$\$", substring = true).assertCountEquals(0)
        // Formulas inside a line are drawn too: the text underneath is the readable form, never LaTeX source.
        ui.onAllNodesWithText("\\neq", substring = true).assertCountEquals(0)
        ui.onAllNodesWithText("\\quad", substring = true).assertCountEquals(0)
        awaitText("a ≠ 0", substring = true)
    }

    @Test fun chatListFindsChatsByWhatWasSaid() {
        val tag = UUID.randomUUID().toString().take(6)
        val chat = UUID.randomUUID().toString()
        runBlocking {
            val reply = app.db.start(chat, "Общий вопрос", "m")
            app.db.checkpoint(reply, "Ответ упоминает квазар$tag и его яркость", "", "done")
        }
        try {
            ui.onNodeWithContentDescription("История чатов").performClick()
            ui.onNodeWithContentDescription("Поиск по чатам").performClick()
            ui.onNodeWithContentDescription("Найти чат").performTextInput("квазар$tag")
            awaitText("Общий вопрос")
            awaitText("квазар$tag", substring = true)
        } finally { runBlocking { app.db.delete(chat) } }
    }

    @Test fun backupKeepsEverythingButKeysAndRestoresIt() = runBlocking {
        val chat = UUID.randomUUID().toString()
        val reply = app.db.start(chat, "Вопрос для копии", "m")
        app.db.checkpoint(reply, "Ответ для копии", "мысли", "done")
        app.db.setSources(reply, encodeSources(listOf(Source("Источник", "https://example.org/a"))))
        val fileId = UUID.randomUUID().toString()
        File(app.media.directory, fileId).writeBytes(byteArrayOf(1, 2, 3, 4))
        app.media.dao.add(listOf(Attachment(fileId, chat, reply, "note.bin", "application/octet-stream", 4, "h")))
        app.settings.update { it.copy(apiKey = "sk-backup-secret-123456") }
        val backup = Backup(app.database, app.media, app.settings)
        try {
            val bytes = ByteArrayOutputStream().also { out -> backup.save { out } }.toByteArray()
            val names = mutableListOf<String>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names += entry.name
                    assertFalse(entry.name, zip.readBytes().decodeToString().contains("sk-backup-secret"))
                }
            }
            assertTrue(names.containsAll(listOf("manifest.json", "chats.json", "memory.json", "settings.json", "media/$fileId")))

            app.db.delete(chat)
            app.media.recover()
            assertFalse(File(app.media.directory, fileId).exists())
            val restored = backup.restore { bytes.inputStream() }
            assertTrue(restored.chats >= 1)
            val rows = app.db.recent(chat, 10).first().asReversed()
            assertEquals(listOf("Вопрос для копии", "Ответ для копии"), rows.map { it.text })
            assertEquals("https://example.org/a", rows.last().sourceList().single().url)
            assertTrue(File(app.media.directory, fileId).isFile)
            // The key typed on this device survives a restore of a key-less copy.
            assertEquals("sk-backup-secret-123456", app.settings.settings.first().apiKey)
            // A second restore of the same copy adds nothing.
            assertEquals(0, backup.restore { bytes.inputStream() }.chats)
        } finally {
            app.db.delete(chat)
            app.media.recover()
        }
    }
}
