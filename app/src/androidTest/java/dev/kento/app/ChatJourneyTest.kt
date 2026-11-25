package dev.kento.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.kento.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import java.util.concurrent.TimeUnit

class ChatJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val server = MockWebServer()
    private val sent = "Проверка потока"
    private val prompt = "Ответь кратко"
    private fun text(value: String) = ui.onNodeWithText(value)
    private fun action(value: String) = ui.onNodeWithContentDescription(value)
    private fun awaitText(value: String) {
        try { ui.waitUntil(10000) { ui.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: Throwable) { throw AssertionError("Missing " + value + "\n" + ui.onAllNodes(isRoot()).printToString(6), e) }
    }

    @Before fun setup() {
        server.useHttps(app.serverTls.sslSocketFactory(), false)
        server.start()
        app.apiBase = server.url("/v1").toString()
    }
    @After fun finish() {
        dev.kento.data.tools.ToolLoop.refuse("test-chat", refused = false)
        server.shutdown()
    }

    @Test fun regeneratesLatestReplyWithoutDuplicatingInputOrLosingDraft() {
        val model = "regeneration-fixture"
        val question = "Generate a new answer for this message."
        val previous = runBlocking { app.settings.profiles.first() }
        fun enqueueAnswer(answer: String) = server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$answer\"}}]}\n\ndata: [DONE]\n\n"))
        runBlocking {
            app.settings.update { it.copy(apiKey = "", memoryModel = "",
                model = model, systemPrompt = "") }
        }
        action("Сообщение").performClick()
        awaitText(model)
        enqueueAnswer("Initial reply")
        action("Сообщение").performTextInput(question)
        action("Отправить").performClick()
        awaitText("Initial reply")
        val chat = runBlocking { checkNotNull(app.db.latest()) }
        try {
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("Остановить").fetchSemanticsNodes().isEmpty() }
            val first = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
            Assert.assertTrue(first.contains(question))
            action("Сообщение").performTextInput("Unsent draft")
            enqueueAnswer("Replacement reply")
            action("Повторить ответ").performClick()
            awaitText("Replacement reply")
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("Остановить").fetchSemanticsNodes().isEmpty() }
            text("Initial reply").assertDoesNotExist()
            text("Unsent draft").assertExists()
            val second = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
            Assert.assertEquals(first, second)
            val rows = runBlocking { app.db.recent(chat, 10).first() }
            Assert.assertEquals(listOf("assistant", "user"), rows.map { it.role })
            Assert.assertEquals("Replacement reply", rows.first().text)
        } finally {
            runBlocking {
                app.db.delete(chat)
                app.settings.updateBook { previous }
            }
        }
    }

    /**
     * There is no app-wide model lock any more. The memory analysis is held at its model until the
     * chat request arrives; with the old lock the reply waited for the analysis, and this journey timed out.
     */
    @Test fun chatReplyDoesNotWaitForTheMemoryAnalysis() {
        val previous = runBlocking { app.settings.profiles.first() }
        val work = androidx.work.WorkManager.getInstance(app)
        work.cancelUniqueWork("kento-memory").result.get()
        val analysing = java.util.concurrent.CountDownLatch(1)
        val chatArrived = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                if ("\"save_facts\"" in body) {
                    analysing.countDown()
                    if (chatArrived.await(20, TimeUnit.SECONDS)) released.set(true)
                    return RefuselessSse.call("save_facts", """{"operations":[{"op":"none","message_ids":[]}]}""")
                }
                chatArrived.countDown()
                return RefuselessSse.answer("Reply without waiting")
            }
        }
        val pending = java.util.UUID.randomUUID().toString()
        runBlocking {
            app.settings.update { it.copy(apiKey = "", model = "concurrency-chat", memoryModel = "concurrency-archivist", systemPrompt = "") }
            app.settings.updateBook { it.copy(memory = it.memory.copy(enabled = true, background = true)) }
            val reply = app.db.start(pending, "I live in Kazan.", "concurrency-chat")
            app.db.checkpoint(reply, "Noted.", "", "done")
        }
        var chat: String? = null
        try {
            dev.kento.data.memory.MemoryWorker.schedule(app, delaySeconds = 0)
            Assert.assertTrue("the analysis never asked its model", analysing.await(60, TimeUnit.SECONDS))
            action("Сообщение").performClick()
            awaitText("concurrency-chat")
            action("Сообщение").performTextInput("Hello while the memory works")
            action("Отправить").performClick()
            awaitText("Reply without waiting")
            chat = runBlocking { app.db.latest() }
            // The analysis got its answer because the chat request came, not because its wait ran out.
            ui.waitUntil(20_000) { released.get() }
        } finally {
            runBlocking {
                app.db.delete(pending)
                chat?.let { app.db.delete(it) }
                app.settings.updateBook { previous }
            }
            work.cancelUniqueWork("kento-memory").result.get()
        }
    }

    @Test fun settingsStreamHistoryAndCancellation() {
        val url = server.url("/v1").toString()
        action("История чатов").performClick()
        action("Настройки").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Подключение").fetchSemanticsNodes().isNotEmpty() }
        app.apiBase = url
        action("Подключение").performClick()
        awaitText("API-ключ")
        text("API-ключ").performTextReplacement("local-test-only")
        // No tool calling here: MockWebServer's throttleBody below also slows down reading the request, and tool
        // declarations would make this streaming check wait on request size instead of the reply.
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"test-chat","abliterated":true,"reasoning_efforts":["high","max"],"reasoning_default":"high"}]}"""))
        server.enqueue(MockResponse().setBody("""{"object":"credits","available":1.25,"key_spent":0,"unit":"usd"}"""))
        text("Проверить подключение").performScrollTo().performClick()
        awaitText("Каталог доступен · моделей: 1 · доступно $1.25")
        val catalogRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
        Assert.assertEquals("/v1/models", catalogRequest.path)
        Assert.assertEquals("Bearer local-test-only", catalogRequest.getHeader("Authorization"))
        Assert.assertEquals("/v1/credits", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        action("Назад").performClick()
        awaitText("Модели")
        text("Модели").performClick()
        text("Основная модель").performClick()
        awaitText("Выбор модели")
        text("test-chat").performClick()
        action("Назад").performClick()
        awaitText("Память")
        text("Память").performClick()
        text("Фоновая модель").performClick()
        awaitText("Модель для памяти")
        text("Другие модели").performClick()
        text("Название или ID модели").performTextInput("test-memory")
        text("Использовать этот ID").performClick()
        action("Назад").performClick()
        awaitText("Инструкции")
        text("Инструкции").performClick()
        // The persona block keeps the old systemPrompt field, so stored profiles carry over.
        text("Характер").performTextReplacement(prompt)
        text("Сохранить").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Сообщение").fetchSemanticsNodes().size == 1 }
        action("Сообщение").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Модель и рассуждения").fetchSemanticsNodes().isNotEmpty() }
        action("Модель и рассуждения").performClick()
        awaitText("Выбор модели")
        text("Рассуждения").performClick()
        awaitText("Максимальный")
        text("Средний").assertDoesNotExist()
        action("Назад").performClick()
        awaitText("Выбор модели")
        text("Рассуждения").performClick()
        text("Максимальный").performClick()
        ui.waitUntil { runBlocking { app.settings.settings.first().effort() == "max" } }
        val reopened = runBlocking { SettingsStore(app).settings.first() }
        Assert.assertEquals(prompt, reopened.systemPrompt)
        val stored = app.filesDir.resolve("datastore/connection.preferences_pb").readBytes().toString(Charsets.UTF_8)
        Assert.assertFalse(stored.contains(prompt))
        Assert.assertFalse(stored.contains("local-test-only"))

        // Refuseless streams thinking as delta.reasoning and moves <think> spans out of the content itself.
        val events = listOf(
            """{"choices":[{"index":0,"delta":{"reasoning":"clo"}}]}""",
            """{"choices":[{"index":0,"delta":{"reasoning":"sed"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"Готово"}}]}""",
            """{"choices":[{"delta":{"content":"\n\n```kotlin\nval result = 42\n```"}}]}""", "[DONE]"
        )
        // This journey is about streaming and stopping. MockWebServer's throttle also slows down reading the request,
        // and the web tools are declared in every request now, so the reply goes the plain path (as a model that
        // refuses tool declarations would) to keep the request within the client's read timeout.
        dev.kento.data.tools.ToolLoop.refuse("test-chat")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "data: $it\n\n" }).throttleBody(48, 80, TimeUnit.MILLISECONDS))
        action("Сообщение").performTextInput(sent)
        action("Отправить").performClick()
        awaitText("Готово")
        // The throttled stream shows "Готово" before the code block arrives; its copy button comes with the block.
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("Скопировать код").fetchSemanticsNodes().isNotEmpty() }
        action("Скопировать код").performClick()
        text("closed").assertDoesNotExist()
        text("Мысли модели").performClick()
        text("closed").assertExists()

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        Assert.assertEquals("/v1/chat/completions", request.path)
        val payload = request.body.readUtf8()
        listOf(prompt, sent, "test-chat", "\"reasoning_effort\":\"max\"", "\"stream\":true").forEach {
            Assert.assertTrue("Missing $it", payload.contains(it))
        }
        ui.activityRule.scenario.recreate()
        awaitText("Готово")
        action("Новый чат").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Доб", substring = true).fetchSemanticsNodes().isNotEmpty() }
        action("История чатов").performClick()
        action("Поиск по чатам").performClick()
        action("Найти чат").performTextInput("неизвестное")
        awaitText("Совпадений нет")
        action("Найти чат").performTextReplacement("потока")
        ui.onNode(hasText(sent) and hasClickAction()).performClick()
        awaitText("Готово")

        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Начало\"}}]}\n\n" + ": waiting\n\n".repeat(400))
            .throttleBody(80, 300, TimeUnit.MILLISECONDS))
        action("Сообщение").performTextInput("Ещё")
        action("Отправить").performClick()
        awaitText("Начало")
        action("Остановить").performClick()
        awaitText("Остановлено")
        runBlocking {
            val db = app.db
            val chat = db.latest()!!
            Assert.assertEquals("stopped", db.recent(chat, 1).first().single().state)
            Assert.assertEquals("test-memory", app.settings.settings.first().memoryModel)
        }
        action("Ещё").performClick()
        text("Удалить чат").performClick()
        text("Отмена").performClick()
        Assert.assertTrue(runBlocking { app.db.history().first().any { it.title == sent } })
        action("Ещё").performClick()
        text("Удалить чат").performClick()
        text("Удалить").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Доб", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10000) { runBlocking { app.db.history().first().none { it.title == sent } } }
    }
}

