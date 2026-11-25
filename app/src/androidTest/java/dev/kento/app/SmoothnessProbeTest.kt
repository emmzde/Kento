package dev.kento.app

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.kento.data.settings.*
import dev.kento.feature.chat.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Not an assertion suite: measures how smooth the chat is with `dumpsys gfxinfo` while a long chat full of Markdown
 * scrolls, while a reply streams in with its reasoning at the pace of a real model (~40 tokens a second), and while
 * the history drawer opens and closes. Each part writes its frame statistics to the QA app's external files
 * (`shots/perf-*.txt`) and to the log (tag KentoPerf). Run with `-e perf true`. The QA build is debuggable and the
 * emulator renders on the host, so the numbers are a pessimistic bound for a release build on a phone.
 */
class SmoothnessProbeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private lateinit var before: ProfileBook
    private lateinit var vm: ChatViewModel
    private val chats = mutableListOf<String>()
    private val dir get() = File(app.getExternalFilesDir(null), "shots").apply { mkdirs() }

    @Before fun setup() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("perf") == "true")
        server.start()
        before = runBlocking { app.settings.profiles.first() }
        ui.waitForIdle()
        vm = ui.runOnIdle { ViewModelProvider(ui.activity)[ChatViewModel::class.java] }
        ui.waitUntil(15000) { vm.ready.value }
        runBlocking { app.settings.updateBook { book -> book.replace(Settings(id = book.activeId, model = "kento-chat",
            catalog = listOf(ModelOption("kento-chat", name = "Kento Chat", efforts = listOf("off", "low", "high", "max"))),
            catalogLoaded = true, catalogVersion = CATALOG_VERSION, catalogAt = System.currentTimeMillis())) } }
        ui.waitUntil(10000) { vm.settings.value?.model == "kento-chat" }
        app.apiBase = server.url("/v1").toString()
        // The throttled reply would make MockWebServer read a request with tool schemas just as slowly.
        dev.kento.data.tools.ToolLoop.refuse("kento-chat")
    }

    @After fun finish() {
        if (!::before.isInitialized) return
        dev.kento.data.tools.ToolLoop.refuse("kento-chat", refused = false)
        try { runBlocking { chats.forEach { app.db.delete(it) }; app.settings.updateBook { before } } } finally { server.shutdown() }
    }

    private fun shell(command: String): String =
        instrument.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    private fun reset() { shell("dumpsys gfxinfo ${app.packageName} reset") }

    /** The frame statistics since [reset], saved as `shots/perf-[name].txt`; the summary lines go to the log. */
    private fun record(name: String) {
        val dump = shell("dumpsys gfxinfo ${app.packageName}")
        File(dir, "perf-$name.txt").writeText(dump)
        dump.lines().map(String::trim).filter { line ->
            listOf("Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "95th percentile", "99th percentile",
                "Number Missed Vsync", "Number Slow UI thread", "Number Frame deadline missed").any { line.startsWith(it) }
        }.take(9).forEach { Log.i("KentoPerf", "$name: $it") }
    }

    private fun answer(n: Int) = buildString {
        appendLine("## Разбор варианта $n")
        appendLine()
        appendLine("Короче, **главное** здесь — не путать срочное с важным. Вот что я вижу по шагам, и почему именно так, а не иначе:")
        appendLine()
        repeat(5) { appendLine("- Пункт ${it + 1}: сначала *разговор*, потом цифры; [ссылка](https://example.org/$n/$it) на источник, если нужен.") }
        appendLine()
        appendLine("| Кто | Что хочет | Срок |")
        appendLine("|---|---|---|")
        repeat(4) { appendLine("| Человек ${it + 1} | денег или внимания | до ${it + 10} декабря |") }
        appendLine()
        appendLine("```kotlin")
        appendLine("fun plan(days: Int) = (1..days).map { day -> \"День \$day: шаг\" }")
        appendLine("```")
        appendLine()
        repeat(3) {
            appendLine("Абзац ${it + 1}. Длинный текст ответа, чтобы строка переносилась несколько раз: модель рассуждает о людях, " +
                "сроках, деньгах и о том, кому и что сказать первым, чтобы никого не подставить и не сжечь мосты раньше времени.")
            appendLine()
        }
    }.trim()

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
    private fun piece(field: String, text: String) = """{"choices":[{"delta":{"$field":${JsonPrimitive(text)}}}]}"""

    @Test fun scrollingStreamingAndTheDrawerStaySmooth() {
        // 1. A long chat: 40 exchanges, each answer about 2 KB of Markdown with a list, a table and code.
        val chat = "perf-" + java.util.UUID.randomUUID()
        chats += chat
        runBlocking {
            repeat(40) { n ->
                val reply = app.db.start(chat, "Вопрос номер $n: как мне быть с этой ситуацией?", "kento-chat")
                app.db.checkpoint(reply, answer(n), "Мысли модели к ответу $n.", "done")
            }
        }
        ui.runOnIdle { vm.select(chat) }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Разбор варианта 39").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        val metrics = app.resources.displayMetrics
        val x = metrics.widthPixels / 2
        val top = metrics.heightPixels / 4
        val bottom = metrics.heightPixels * 3 / 4
        reset()
        repeat(12) { shell("input swipe $x $top $x $bottom 180") }
        repeat(12) { shell("input swipe $x $bottom $x $top 180") }
        ui.waitForIdle()
        record("scroll")

        // 2. A reply streams in: reasoning first, then a long Markdown answer, in small pieces as Refuseless sends them.
        val thinking = "Пользователь спрашивает про план. Нужно учесть сроки, деньги и людей. ".repeat(20)
        val text = answer(99) + "\n\n" + answer(100)
        val events = thinking.chunked(6).map { piece("reasoning", it) } + text.chunked(5).map { piece("content", it) }
        // About 40 events a second, the pace of a flash model on Refuseless.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(*events.toTypedArray()))
            .throttleBody(4_800, 1, TimeUnit.SECONDS))
        ui.onNodeWithContentDescription("Сообщение").performClick()
        ui.onNodeWithContentDescription("Сообщение").performTextInput("И что теперь?")
        reset()
        ui.onNodeWithContentDescription("Отправить").performClick()
        ui.waitUntil(120_000) { ui.onAllNodesWithContentDescription("Остановить").fetchSemanticsNodes().isEmpty() &&
            ui.onAllNodesWithText("Разбор варианта 100").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        record("stream")

        // 3. The history drawer, opened by its button and closed by the scrim, five times.
        reset()
        repeat(5) {
            ui.onNodeWithContentDescription("История чатов").performClick()
            ui.waitForIdle()
            shell("input tap ${metrics.widthPixels - 20} $bottom")
            ui.waitForIdle()
        }
        record("drawer")
    }
}

