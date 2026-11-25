package dev.kento.data.net

import dev.kento.data.db.Attachment
import dev.kento.data.db.MemoryEntry
import dev.kento.data.db.Message
import dev.kento.data.media.MEDIA_TOOLS
import dev.kento.data.memory.ContextRequest
import dev.kento.data.memory.MemoryTools
import dev.kento.data.memory.WebSearch
import dev.kento.data.memory.assemble
import dev.kento.data.settings.ModelOption
import dev.kento.data.settings.Settings
import dev.kento.data.settings.WebSettings
import dev.kento.data.web.ReadPage
import dev.kento.data.web.SearchAccess
import dev.kento.data.web.SearchHit
import dev.kento.data.web.WebTools
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/**
 * Live requests to Refuseless with the key from `REFUSELESS_API_KEY` or the project's `.env`. They cost a little, so
 * they run only with `KENTO_LIVE=1`:
 * `$env:KENTO_LIVE='1'; .\gradlew.bat :data:testDebugUnitTest --tests '*RefuselessLiveTest*'`.
 */
class RefuselessLiveTest {
    private val key = System.getenv("REFUSELESS_API_KEY")?.takeIf(String::isNotBlank) ?: listOf(File(".env"), File("../.env"))
        .firstOrNull(File::exists)?.readLines()?.firstOrNull { it.startsWith("REFUSELESS_API_KEY=") }
        ?.substringAfter('=')?.trim()?.split(Regex("\\s+"))?.firstOrNull()

    private val noWeb = object : SearchAccess {
        override suspend fun search(settings: WebSettings, query: String) = emptyList<SearchHit>()
        override suspend fun read(hit: SearchHit, question: String) = ReadPage("", hit.url, "")
    }

    /**
     * The second turn of a chat, and the first turn of another chat, read the start of the request from the
     * provider's prefix cache, although the clock, the chosen notes, the attachments and the web_search flag changed.
     *
     * Refuseless serves a model from several machines and the cache lives on each one, so a request that lands on
     * another machine misses even with an identical prefix (seen 2026-11-24: whole runs with 0 cached, the shared tools
     * prefix included). The chat is therefore tried up to three times with a fresh salt; one hit proves the layout,
     * and every attempt is printed.
     */
    @Test fun secondTurnHitsThePrefixCache() = runBlocking {
        assumeTrue("set KENTO_LIVE=1 to run live requests", System.getenv("KENTO_LIVE") == "1")
        assumeTrue("no REFUSELESS_API_KEY", key != null)
        val model = System.getenv("KENTO_LIVE_MODEL") ?: "deepseek-v4.1-flash-abliterated"
        val schemas = JsonArray(WebTools(noWeb, WebSettings(), 8_000).all().map { it.schema } +
            MemoryTools.SCHEMAS + MEDIA_TOOLS.map { it.schema })
        val names = schemas.map { it.toString().substringAfter("\"name\":\"").substringBefore('"') }.toSet()
        val api = Provider()
        fun fact(id: Long, text: String) = MemoryEntry(id, "fact", text, "f$id", topic = "work", createdAt = System.currentTimeMillis())
        val file = Attachment("6f1c2a9e-live", "one", 1, "notes.pdf", "application/pdf", 48_213, "h")
        val hits = mutableListOf<Boolean>()
        for (attempt in 1..3) {
            // A fresh salt keeps earlier runs out of the cache, so the chat starts cold.
            val salt = java.util.UUID.randomUUID().toString().take(8)
            val s = Settings(apiKey = key!!, model = model, efforts = mapOf(model to "low"),
                promptFormat = "Answer in the language of the user. Use Markdown where it helps. (run $salt)",
                systemPrompt = "You are Kento, a calm and precise assistant who explains things to a software developer.",
                promptValues = "Honesty over comfort; say when you are unsure.",
                catalog = listOf(ModelOption(model, efforts = listOf("off", "low", "high", "max"), contextLength = 1_048_576)))

            suspend fun send(rows: List<Message>, facts: List<MemoryEntry>, web: WebSearch, files: List<Attachment>): Pair<Usage, String> {
                val turns = assemble(rows.asReversed(), rows.size, facts, s,
                    ContextRequest(tools = names, now = ZonedDateTime.now(), web = web, files = files)).turns
                System.getenv("KENTO_LIVE_DUMP")?.let { dir ->
                    File(dir, "request-${rows.first().chatId}-${rows.size}.json").writeText(payload(s, turns, schemas, chat = true).toString())
                }
                val meter = UsageMeter()
                val text = StringBuilder()
                withContext(meter) { api.stream(s, turns, schemas, chat = true).collect { if (!it.thinking) text.append(it.text) } }
                return meter.value to text.toString()
            }

            val chat = mutableListOf(Message(1, "one", "user", "In two sentences: what is a closure in programming?"))
            val (first, answer) = send(chat, listOf(fact(1, "The user writes Android apps in Kotlin.")), WebSearch.ENABLED, emptyList())
            chat += Message(2, "one", "assistant", answer.ifBlank { "A closure is a function that keeps its surrounding variables." })
            chat += Message(3, "one", "user", "And how does it differ from a lambda? Two sentences.")
            val (second, _) = send(chat, listOf(fact(2, "The user prefers short examples."), fact(3, "The user is learning Rust.")),
                WebSearch.DISABLED, listOf(file))
            val (next, _) = send(listOf(Message(1, "two", "user", "Name three prime numbers.")), emptyList(), WebSearch.REQUIRED, emptyList())
            println("live cache $model, attempt $attempt: turn 1 in ${first.input} cached ${first.cached}; " +
                "turn 2 in ${second.input} cached ${second.cached}; other chat in ${next.input} cached ${next.cached}")
            // Turn 2 repeats tools, system and turn 1 up to its newest message: all of that can come from the cache.
            hits += second.cached >= first.input * 3 / 4
            if (hits.last()) break
        }
        assertTrue("turn 2 missed the cache in all ${hits.size} attempts", hits.any { it })
    }

    /**
     * A picture returned by image_edit travels inside the `role: tool` result, and the model reads it
     * there. The number exists only in the picture, so the answer cannot be guessed from the text.
     */
    @Test fun pictureInsideAToolResultReachesTheModel() = runBlocking {
        assumeTrue("set KENTO_LIVE=1 to run live requests", System.getenv("KENTO_LIVE") == "1")
        assumeTrue("no REFUSELESS_API_KEY", key != null)
        val model = System.getenv("KENTO_LIVE_MODEL") ?: "deepseek-v4.1-flash-abliterated"
        // The number is drawn only in this picture (data/src/test/resources/refuseless); the request text never names it.
        val number = "4826"
        val jpeg = checkNotNull(javaClass.getResource("/refuseless/number-$number.jpg")).readBytes()
        val picture = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg)
        val edit = object : dev.kento.data.tools.ChatTool {
            override val name = "image_edit"
            override val schema = MEDIA_TOOLS.single { it.name == "image_edit" }.schema
            override fun status(args: kotlinx.serialization.json.JsonObject) = ""
            override suspend fun run(args: kotlinx.serialization.json.JsonObject) =
                dev.kento.data.tools.ToolOutput("Edited image 1 is attached to this result.", listOf(picture))
        }
        val s = Settings(apiKey = key!!, model = model, efforts = mapOf(model to "low"),
            catalog = listOf(ModelOption(model, efforts = listOf("off", "low", "high", "max"), vision = true, contextLength = 1_048_576)))
        val answer = dev.kento.data.tools.ToolLoop(Provider()).stream(s,
            listOf(Turn("user", "Attachment 1 has id a1. Crop it with image_edit, then tell me the number written on it.")),
            listOf(edit), force = "image_edit").toList().filter { !it.thinking }.joinToString("") { it.text }
        println("live tool picture $model: expected $number, answer: ${answer.take(300)}")
        assertTrue(answer, answer.contains(number))
    }

    /**
     * The default background model, forced to call save_facts, turns "I moved to Sber" into an
     * update of the saved job note (or outdates it and adds the new one), and saves nothing from a plain question.
     */
    @Test fun backgroundReaderUpdatesTheOldFactThroughSaveFacts() = runBlocking {
        assumeTrue("set KENTO_LIVE=1 to run live requests", System.getenv("KENTO_LIVE") == "1")
        assumeTrue("no REFUSELESS_API_KEY", key != null)
        val s = Settings(apiKey = key!!, model = "glm-5.3-flash-abliterated", catalog = listOf(
            ModelOption("deepseek-v4.1-flash-abliterated-fast", efforts = listOf("off", "low", "high", "max"))))
        val model = s.copy(model = s.background())
        val job = MemoryEntry(12, "fact", "Пользователь работает в Яндексе.", "k12", topic = "работа", importance = 3,
            createdAt = System.currentTimeMillis() - 200L * 86_400_000)
        val notes = dev.kento.data.memory.notesInput(listOf(job))
        fun messages(vararg texts: Pair<Long, String>) = kotlinx.serialization.json.buildJsonArray {
            texts.forEach { (id, text) -> add(kotlinx.serialization.json.buildJsonObject { put("id", id); put("text", text) }) }
        }
        val moved = dev.kento.data.memory.askSaveFacts(Provider(), model, notes,
            messages(41L to "Кстати, я с прошлой недели перешёл в Сбер, теперь пишу там бэкенд на Go.", 43L to "Подскажи, как настроить gRPC?"))
        val changes = dev.kento.data.memory.factChanges(moved, listOf(job), setOf(41L, 43L))
        println("live save_facts move: $moved -> $changes")
        assertTrue(changes.toString(), changes.any { it.target == 12L && it.op != dev.kento.data.db.FactChange.Op.ADD })
        assertTrue(changes.toString(), changes.none { it.op == dev.kento.data.db.FactChange.Op.ADD && "gRPC" in it.text && 43L in it.sources && it.sources.size == 1 })
        val nothing = dev.kento.data.memory.askSaveFacts(Provider(), model, notes, messages(50L to "Какая погода будет в выходные в Казани?"))
        println("live save_facts none: $nothing")
        assertTrue(nothing.toString(), dev.kento.data.memory.factChanges(nothing, listOf(job), setOf(50L)).isEmpty())
    }
}
