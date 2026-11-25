package dev.kento.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.kento.data.settings.*
import dev.kento.feature.chat.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Not an assertion suite: fills a chat with a reply, its reasoning, a network failure and draft attachments, then
 * saves screenshots of each redesigned surface to the QA app's external files ("shots/"). Run with `-e showcase true`.
 */
class DesignShowcaseTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private lateinit var before: ProfileBook
    private lateinit var vm: ChatViewModel
    private val files = mutableListOf<File>()
    private val chats = mutableListOf<String>()

    @Before fun setup() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("showcase") == "true")
        server.start()
        before = runBlocking { app.settings.profiles.first() }
        ui.waitForIdle()
        vm = ui.runOnIdle { ViewModelProvider(ui.activity)[ChatViewModel::class.java] }
        ui.waitUntil(15000) { vm.ready.value }
        ui.runOnIdle { vm.newChat() }
        runBlocking { app.settings.updateBook { book -> book.replace(Settings(id = book.activeId, model = "kento-chat",
            catalog = listOf(ModelOption("kento-chat", name = "Kento Chat", vision = true, efforts = listOf("off", "low", "high", "max"))),
            catalogLoaded = true, catalogVersion = dev.kento.data.settings.CATALOG_VERSION, catalogAt = System.currentTimeMillis()))
            .copy(bubble = "blue", images = dev.kento.data.settings.ImageEndpoint(apiKey = "ou-showcase")) } }
        ui.waitUntil(10000) { vm.settings.value?.model == "kento-chat" }
        // Only now, with a fresh catalogue saved: a catalogue refresh for the settings left by an earlier suite would
        // otherwise wait in the mock queue and take the first chat reply.
        app.apiBase = server.url("/v1").toString()
        // The thinking shot throttles the reply, and MockWebServer then reads the request just as slowly: tool
        // schemas would make it wait on the request. Screens look the same on the plain path.
        dev.kento.data.tools.ToolLoop.refuse("kento-chat")
    }

    @After fun finish() {
        if (!::before.isInitialized) return
        dev.kento.data.tools.ToolLoop.refuse("kento-chat", refused = false)
        try {
            runBlocking { (chats + vm.activeId.value).distinct().forEach { app.db.delete(it) }; app.media.recover(); app.settings.updateBook { before } }
            files.forEach { it.delete() }
        } finally { server.shutdown() }
    }

    private fun shot(name: String) {
        ui.waitForIdle()
        Thread.sleep(700)
        val bitmap = instrument.uiAutomation.takeScreenshot() ?: return
        val dir = File(app.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun stream(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun ask(text: String) {
        ui.onNodeWithContentDescription("Сообщение").performClick()
        ui.onNodeWithContentDescription("Сообщение").performTextInput(text)
        ui.onNodeWithContentDescription("Отправить").performClick()
        hideKeyboard()
    }

    private fun hideKeyboard() = ui.runOnUiThread {
        ui.activity.currentFocus?.clearFocus()
        ui.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())
    }

    private fun idle() = ui.waitUntil(20000) { ui.onAllNodesWithContentDescription("Остановить").fetchSemanticsNodes().isEmpty() }

    private fun fixture(name: String) = File(app.media.cameraTarget().parentFile, name).also { files += it }

    /** Image mode against a local Images API that answers after a pause, to film the waiting card and the reveal. */
    private fun paintSunset() {
        val art = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        Canvas(art).apply {
            drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 1024f, intArrayOf(0xFF241E4E.toInt(), 0xFFB5577A.toInt(), 0xFFF6A86B.toInt()), null, Shader.TileMode.CLAMP) })
            drawCircle(512f, 640f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE3B0.toInt() })
            drawRect(0f, 700f, 1024f, 1024f, Paint().apply { shader = LinearGradient(0f, 700f, 0f, 1024f, 0xFF2B3A67.toInt(), 0xFF0E1530.toInt(), Shader.TileMode.CLAMP) })
            val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFE3B0; strokeWidth = 6f }
            for (i in 0..6) drawLine(512f - 140 + i * 12, 730f + i * 34, 512f + 140 - i * 12, 730f + i * 34, glint)
        }
        val png = java.io.ByteArrayOutputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        art.recycle()
        val b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.endsWith("/images/models") -> MockResponse().setBody("""{"data":[{"id":"sunset-painter","name":"Sunset Painter","operations":["generate"]}]}""")
                request.path!!.endsWith("/images/generations") -> MockResponse().setBodyDelay(6, TimeUnit.SECONDS).setBody("""{"data":[{"b64_json":"$b64"}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        chats += vm.activeId.value
        ui.runOnIdle { vm.newChat(); vm.setImageMode(true) }
        ui.waitUntil(15000) { vm.images.value.catalog.isNotEmpty() }
        ask("Нарисуй закат над морем")
        ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("Kento создаёт изображение").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(3500)
        shot("11-image-painting")
        // Drive the Compose clock by hand from here, so the 1.8 s development can be caught midway.
        ui.mainClock.autoAdvance = false
        val deadline = System.currentTimeMillis() + 20000
        while (ui.onAllNodesWithContentDescription("Открыть изображение Kento").fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "Image never arrived" }
            ui.mainClock.advanceTimeByFrame()
            Thread.sleep(30)
        }
        repeat(40) { ui.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
        Thread.sleep(250)
        val mid = instrument.uiAutomation.takeScreenshot()
        File(File(app.getExternalFilesDir(null), "shots").apply { mkdirs() }, "12-image-reveal.png").outputStream().use { mid.compress(Bitmap.CompressFormat.PNG, 100, it) }
        mid.recycle()
        ui.mainClock.autoAdvance = true
        idle()
        shot("13-image-done")
        ui.runOnIdle { vm.setImageMode(false) }
        server.dispatcher = QueueDispatcher()
    }

    @Test fun captureRedesign() {
        val thinking = "Пользователь просит коротко. Нужно объяснить, почему чёрный цвет на OLED экономит энергию.\\nКаждый пиксель светится сам, значит чёрный — это выключенный пиксель."
        val answer = "**OLED** — экран, в котором каждый пиксель светится сам.\\n\\nПоэтому чёрный цвет на нём — это просто выключенные пиксели:\\n\\n- глубокий чёрный без подсветки;\\n- меньше расход батареи на тёмных темах;\\n- высокая контрастность."
        server.enqueue(stream("""{"choices":[{"delta":{"reasoning_content":"$thinking"}}]}""", """{"choices":[{"delta":{"content":"$answer"}}]}"""))
        ask("Привет! Коротко: почему тёмная тема экономит батарею на OLED?")
        idle()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Мысли модели").fetchSemanticsNodes().isNotEmpty() }
        // Three refusals: the first try and both automatic retries.
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        ask("А на LCD так же?")
        idle()
        hideKeyboard()
        shot("01-chat")
        ui.onNodeWithText("Мысли модели").performScrollTo().performClick()
        shot("02-thoughts-open")

        // A reply that keeps thinking for a while shows the shimmering label and the newest line of thought.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Сравниваю подсветку LCD и пиксели OLED.\\nНа LCD подсветка горит всегда, даже на чёрном.\"}}]}\n\n" +
                ":" + " ".repeat(4000) + "\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"На LCD подсветка горит всегда.\"}}]}\n\ndata: [DONE]\n\n")
            .throttleBody(400, 1, TimeUnit.SECONDS))
        ask("Проверь ещё раз")
        ui.waitUntil(10000) { ui.onAllNodesWithText("Думает…").fetchSemanticsNodes().isNotEmpty() }
        shot("03-thinking")
        idle()

        ui.onNodeWithContentDescription("Прикрепить").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("Открыть камеру").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(600)
        shot("04-attach-sheet")
        androidx.test.uiautomator.UiDevice.getInstance(instrument).pressBack()
        ui.waitUntil(5000) { ui.onAllNodesWithText("Открыть камеру").fetchSemanticsNodes().isEmpty() }

        val photo = fixture("Закат.jpg")
        val bmp = Bitmap.createBitmap(900, 1100, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 1100f, intArrayOf(0xFF1D2B64.toInt(), 0xFFF8846B.toInt(), 0xFFFBD38D.toInt()), null, Shader.TileMode.CLAMP) })
            drawCircle(450f, 700f, 170f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF1C9.toInt() })
            drawRect(0f, 820f, 900f, 1100f, Paint().apply { color = 0xFF14213D.toInt() })
        }
        photo.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        val audio = fixture("fallin in luv - 2 star, ambrr.wav")
        instrument.context.assets.open("russian-speech.wav").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val pdf = fixture("Конспект лекции.pdf")
        PdfDocument().apply {
            val page = startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawText("Лекция 4", 40f, 60f, Paint().apply { textSize = 22f })
            finishPage(page)
            pdf.outputStream().use { writeTo(it) }
            close()
        }
        runBlocking { app.media.import(vm.activeId.value, listOf(photo, audio, pdf).map { FileProvider.getUriForFile(app, app.packageName + ".capture", it) }) }
        ui.waitUntil(20000) { ui.onAllNodesWithContentDescription("Конспект лекции.pdf").fetchSemanticsNodes().isNotEmpty() }
        hideKeyboard()
        shot("05-attachments")
        runBlocking { app.media.dao.observe(vm.activeId.value).first().forEach { app.media.remove(it) } }

        runBlocking { app.settings.updateBook { it.copy(bubble = "violet") } }
        ui.waitUntil(5000) { vm.bubble.value == "violet" }
        shot("06-violet-bubble")
        runBlocking { app.settings.updateBook { it.copy(bubble = "blue") } }

        paintSunset()

        ui.onNodeWithContentDescription("История чатов").performClick()
        ui.onNodeWithContentDescription("Настройки").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Цвет сообщений").fetchSemanticsNodes().isNotEmpty() }
        shot("07-settings")
        ui.onNodeWithText("Цвет сообщений").performScrollTo().performClick()
        shot("08-message-colour")
        androidx.test.uiautomator.UiDevice.getInstance(instrument).pressBack()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("Подключение").performScrollTo().performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("API-ключ").fetchSemanticsNodes().isNotEmpty() }
        shot("09-connection")
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.onNodeWithText("Веб-поиск").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("Поисковик").fetchSemanticsNodes().isNotEmpty() }
        shot("10-web-search")
    }
}
