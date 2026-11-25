package dev.kento.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.Room
import dev.kento.data.db.KentoDb
import dev.kento.data.memory.ChatContext
import dev.kento.data.memory.MemoryTools
import dev.kento.data.memory.RuntimeContext
import dev.kento.data.memory.SystemPrompt
import dev.kento.data.memory.ToolNames
import dev.kento.data.memory.WebSearch
import dev.kento.data.net.Turn
import dev.kento.data.net.UsageMeter
import dev.kento.data.settings.MemorySettings
import dev.kento.data.settings.Settings
import dev.kento.data.tools.ChatTool
import dev.kento.data.tools.ToolLoop
import dev.kento.data.tools.ToolOutput
import dev.kento.data.web.SearchStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Developer benchmark of the preview build, run against the API key already saved in the app (Kento never prints it):
 * `adb shell am broadcast -n dev.kento.app/.BenchReceiver --es task check|memory|decide|tavily|stream [--es model ID]
 * [--es off steps,peek] [--es tag NAME]`. `off` lifts the research step limit or the peeked excerpts for tavily and stream.
 * The same tasks run on the JVM with the keys of .env: SearchBenchLiveTest.
 * Only the shell (DUMP permission) can send it. Results go to logcat (KentoBench), files/bench-<task><tag>.txt and,
 * for tavily, one JSON line per answer in files/bench-<task><tag>.jsonl.
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as KentoApp
        val task = intent.getStringExtra("task") ?: "memory"
        val model = intent.getStringExtra("model") ?: "glm-5.3-flash-uncensored"
        val tag = intent.getStringExtra("tag") ?: ""
        val off = intent.getStringExtra("off").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        Bench.scope.launch { Bench(app, model, task + tag, off).run(task) }
    }
}

private class Bench(private val app: KentoApp, private val model: String, private val name: String,
    /** Search-loop aids switched off for this run: steps, peek. */
    private val off: Set<String> = emptySet()) {
    private fun loop() = if ("steps" in off) ToolLoop(app.api, steps = Int.MAX_VALUE) else ToolLoop(app.api)
    private val peek = if ("peek" in off) 0 else 3
    private val lines = StringBuilder()
    companion object { val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val out = StringBuilder()
    private fun log(line: String) { Log.i("KentoBench", line); out.appendLine(line) }

    suspend fun run(task: String) {
        log("=== $task model=$model off=${off.sorted()} ${ZonedDateTime.now()}")
        try {
            when (task) {
                "check" -> check()
                "memory" -> memory(live = true)
                "memoryOffline" -> memory(live = false)
                "decide" -> decide(legacy = false)
                "decideOld" -> decide(legacy = true)
                "tavily" -> tavily()
                "stream" -> stream()
            }
        } catch (e: Throwable) { log("FAILED: $e"); Log.e("KentoBench", "failed", e) }
        log("=== done")
        File(app.filesDir, "bench-$name.txt").writeText(out.toString())
        if (lines.isNotEmpty()) File(app.filesDir, "bench-$name.jsonl").writeText(lines.toString())
    }

    /** What the bench would run with, without spending anything: the model, its level and which keys are saved. */
    private suspend fun check() {
        val (s, book) = settings()
        val w = book.web
        log("model ${s.model} in catalogue ${s.catalog.any { it.id == s.model }}, effort ${s.effort()}, key ${s.apiKey.isNotBlank()}")
        log("web: engine=${w.engine} paidSearches=${w.paidSearches} tavily=${w.tavilyKey.isNotBlank()} brave=${w.braveKey.isNotBlank()} " +
            "serper=${w.serperKey.isNotBlank()} exa=${w.exaKey.isNotBlank()} searxng=${w.instance.isNotBlank()}")
    }

    private suspend fun settings(): Pair<Settings, dev.kento.data.settings.ProfileBook> {
        val book = app.settings.profiles.first()
        return book.active.select(model) to book
    }

    // ---------------------------------------------------------------- memory: a synthetic year of chats

    private data class Needle(val fact: String, val question: String, val answer: Regex, val query: String)
    private val needles = listOf(
        Needle("Ветеринар выписал коту Барсику антибиотик Синулокс на неделю, давать с едой.", "Какое лекарство ветеринар выписывал моему коту?", Regex("(?i)синулокс"), "кот ветеринар лекарство"),
        Needle("Мы с женой забронировали отель Kalimera Bay на Крите на август.", "Как назывался отель, который мы бронировали на Крите?", Regex("(?i)kalimera"), "отель Крит бронь"),
        Needle("Мой научный руководитель — профессор Заславский с кафедры матанализа.", "Как фамилия моего научного руководителя?", Regex("(?i)заславск"), "научный руководитель"),
        Needle("Для бэкапов я купил NAS Synology DS923+ и поставил на него restic.", "Какую модель NAS я себе купил?", Regex("(?i)ds923"), "NAS бэкап"),
        Needle("Сестра Алина выходит замуж 14 сентября в Казани.", "Когда свадьба у моей сестры?", Regex("(?i)14 сентября|14\\.09"), "сестра свадьба"),
        Needle("Мой бюджет на новый ноутбук — 180 тысяч рублей, не больше.", "Какой бюджет на ноутбук я называл?", Regex("180"), "бюджет ноутбук"),
        Needle("Тренер поставил мне цель пробежать полумарафон за 1:45.", "Какое целевое время на полумарафон мне ставил тренер?", Regex("1[:.]45|час сорок пять"), "полумарафон тренер цель"),
        Needle("У меня аллергия на пенициллин, это важно помнить.", "На какой антибиотик у меня аллергия?", Regex("(?i)пеницил"), "аллергия антибиотик"),
        Needle("Рабочий проект называется «Орион», это CRM для логистов.", "Как назывался мой рабочий CRM-проект?", Regex("(?i)орион"), "рабочий проект CRM"),
        Needle("Я купил гитару Fender Player Stratocaster цвета Tidepool.", "Какого цвета гитару я купил?", Regex("(?i)tidepool"), "гитара цвет"),
        Needle("Дочка ходит в детский сад номер 247 на Ломоносовском проспекте.", "В какой детский сад ходит моя дочка?", Regex("247"), "дочка детский сад"),
        Needle("Мой арендодатель Геннадий поднимает аренду до 65 тысяч с марта.", "До скольки арендодатель поднимает аренду?", Regex("65"), "аренда арендодатель")
    )

    private val topics = listOf(
        "кот" to "кот корм ветеринар прививка лоток игрушка шерсть лекарство таблетки",
        "работа" to "работа проект созвон дедлайн задача менеджер релиз CRM спринт ревью",
        "код" to "Kotlin Compose корутины WorkManager Room ошибка стек gradle билд тест",
        "отпуск" to "отпуск отель билеты море Крит Турция виза бронь чемодан пляж",
        "спорт" to "бег тренировка пульс полумарафон тренер кроссовки темп интервалы",
        "здоровье" to "врач анализы аллергия таблетки антибиотик давление сон витамины",
        "семья" to "сестра мама дочка садик свадьба подарок праздник родители",
        "покупки" to "ноутбук бюджет телефон NAS гитара наушники скидка магазин доставка",
        "учёба" to "диссертация руководитель статья кафедра конференция дедлайн рецензия",
        "дом" to "аренда квартира ремонт соседи коммуналка арендодатель мебель кухня"
    )

    private fun sentence(rnd: Random, words: List<String>): String {
        val filler = listOf("я думаю", "вообще", "кстати", "получается", "если честно", "в итоге", "наверное", "надо бы", "вчера", "сегодня")
        val n = rnd.nextInt(6, 14)
        return (List(n) { if (rnd.nextInt(3) == 0) filler.random(rnd) else words.random(rnd) }.joinToString(" ") + ".")
            .replaceFirstChar { it.uppercase() }
    }

    private fun seedYear(db: KentoDb, chats: Int, rnd: Random): Map<String, Long> {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val sql = db.openHelper.writableDatabase
        val planted = mutableMapOf<String, Long>()
        sql.beginTransaction()
        try {
            var needle = 0
            for (c in 0 until chats) {
                // Oldest first: ids grow with time as in a real database.
                val at = now - (365L * day) + c * (365L * day / chats)
                val (topic, vocab) = topics[rnd.nextInt(topics.size)]
                val words = vocab.split(' ')
                val id = "bench-$c"
                sql.execSQL("INSERT INTO chats(id,title,updatedAt,pinned) VALUES(?,?,?,0)", arrayOf(id, "$topic ${c}", at))
                val turns = rnd.nextInt(3, 7)
                for (t in 0 until turns) {
                    var user = List(rnd.nextInt(1, 4)) { sentence(rnd, words) }.joinToString(" ")
                    // Needles live in the first 9 months, the noise keeps mentioning the same words until today.
                    if (t == 1 && needle < needles.size && c == (needle + 1) * (chats * 3 / 4) / (needles.size + 1)) {
                        user = needles[needle].fact + " " + user
                        planted[needles[needle].fact] = at
                        needle++
                    }
                    val answer = List(rnd.nextInt(4, 12)) { sentence(rnd, words) }.joinToString(" ")
                    sql.execSQL("INSERT INTO messages(chatId,role,text,thoughts,state,model,issue,imageGeneration,createdAt,sources,usage,research) VALUES(?,?,?,?,?,?,?,0,?,'','','')",
                        arrayOf(id, "user", user, "", "done", "bench", "", at + t * 60_000))
                    sql.execSQL("INSERT INTO messages(chatId,role,text,thoughts,state,model,issue,imageGeneration,createdAt,sources,usage,research) VALUES(?,?,?,?,?,?,?,0,?,'','','')",
                        arrayOf(id, "assistant", answer, "", "done", "bench", "", at + t * 60_000 + 20_000))
                }
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        return planted
    }

    /** A year of fact writes (MemoryDao.remember, the guard of the background reader): core facts restated many times in other words, updates, and one-off details. */
    private suspend fun seedFacts(db: KentoDb, rnd: Random) {
        val memory = db.memory()
        val core = listOf(
            "Пользователь пишет Android-приложения на Kotlin" to "стек",
            "Пользователя зовут Эмиль" to "имя",
            "Пользователь живёт в Москве" to "город",
            "У пользователя есть кот Барсик" to "питомцы",
            "Пользователь предпочитает короткие ответы без воды" to "стиль",
            "Пользователь бегает по утрам три раза в неделю" to "спорт",
            "Пользователь работает бэкенд-разработчиком в логистической компании" to "работа",
            "У пользователя есть дочь дошкольного возраста" to "семья"
        )
        val variants = listOf("", " и любит это", " уже несколько лет", " (подтвердил ещё раз)", " — это важно", " в последнее время")
        repeat(52) { week ->
            // Each week the model restates some core facts in slightly different words, as a real year of chats does.
            core.shuffled(rnd).take(3).forEach { (text, topic) ->
                memory.remember(text + variants.random(rnd) + ".", topic, 3, null)
            }
            repeat(18) {
                val (topic, vocab) = topics.random(rnd)
                memory.remember("Пользователь упоминал: " + sentence(rnd, vocab.split(' ')), topic, rnd.nextInt(1, 3), null)
            }
            if (week == 30) memory.remember("Пользователь переехал из Москвы в Санкт-Петербург.", "город", 3, null)
        }
    }

    private suspend fun memory(live: Boolean) {
        val (s, book) = settings()
        val db = Room.inMemoryDatabaseBuilder(app, KentoDb::class.java).build()
        try {
            val rnd = Random(7)
            var t = System.nanoTime()
            val planted = seedYear(db, chats = 2_600, rnd = rnd)
            seedFacts(db, rnd)
            val chats = db.chats()
            val memory = db.memory()
            val messages = db.openHelper.readableDatabase.query("SELECT COUNT(*), SUM(LENGTH(text)) FROM messages").use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }
            val facts = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM memory_entries WHERE deleted=0").use { it.moveToFirst(); it.getLong(0) }
            log("seeded: ${messages.first} messages, ${messages.second / 1024} KB text, $facts facts, planted ${planted.size} needles in ${(System.nanoTime() - t) / 1_000_000} ms")

            // 1. What one request costs after a year: the system prompt and the context of a new chat.
            val context = ChatContext(chats, memory)
            val tools = setOf(ToolNames.RECALL)
            chats.start("fresh", "Привет! Помоги спланировать неделю тренировок.", s.model)
            t = System.nanoTime()
            val prepared = context.prepare("fresh", s, memorySettings = book.memory, tools = tools)
            val system = prepared.turns.first().content
            log("new chat request: ${prepared.usage.estimated} tokens est., system ${system.length} chars, prepare ${(System.nanoTime() - t) / 1_000_000} ms")
            prepared.turns.last().content.lines().filter { it.startsWith("- [Date:") }.forEach { log("   fact in runtime context: ${it.take(140)}") }

            // 2. memory_search: speed, answer size and whether the planted detail comes back.
            val search = MemoryTools(memory).all().first { it.name == ToolNames.RECALL }
            var hits = 0
            val times = mutableListOf<Long>()
            val sizes = mutableListOf<Int>()
            for (n in needles) {
                t = System.nanoTime()
                val result = search.run(buildJsonObject { put("query", n.query) }).text
                times += (System.nanoTime() - t) / 1_000_000
                sizes += result.length
                val found = n.answer.containsMatchIn(result)
                if (found) hits++
                log("   search '${n.query}': ${if (found) "HIT" else "miss"} ${result.length} chars ${times.last()} ms")
            }
            times.sort()
            log("memory_search: hits $hits/${needles.size}, p50 ${times[times.size / 2]} ms, max ${times.last()} ms, avg ${sizes.average().toInt()} chars (~${sizes.average().toInt() / 3} tokens)")

            if (!live) return
            // 3. The live model with only memory tools, asked about each planted detail in a new chat.
            var correct = 0
            val total = UsageMeter()
            for ((i, n) in needles.withIndex()) {
                val chat = "ask-$i"
                chats.start(chat, n.question, s.model)
                val turns = context.prepare(chat, s, memorySettings = book.memory, tools = tools).turns
                val meter = UsageMeter()
                val answer = StringBuilder()
                val calls = mutableListOf<String>()
                withContext(meter) {
                    val wrapped = MemoryTools(memory, shown = { chat to 0L }).all().map { tool -> object : ChatTool by tool {
                        override suspend fun run(args: JsonObject): ToolOutput { calls += "${tool.name}(${args})"; return tool.run(args) }
                    } }
                    ToolLoop(app.api).stream(s, turns, wrapped).collect { if (!it.thinking) answer.append(it.text) }
                }
                total.add(meter.value)
                val ok = n.answer.containsMatchIn(answer)
                if (ok) correct++
                log("   Q$i ${if (ok) "OK " else "BAD"} in=${meter.value.input} out=${meter.value.output} calls=$calls answer=${answer.toString().replace('\n', ' ').take(160)}")
            }
            val u = total.value
            log("live recall: $correct/${needles.size} correct, ${u.requests} requests, in ${u.input} (cached ${u.cached}) out ${u.output}, cost \$${"%.4f".format(u.cost)}")
        } finally { db.close() }
    }

    // ---------------------------------------------------------------- decide: does the model search only when needed

    /** The search rules and tool description before 0.5.3, to measure the same questions against them. */
    private val oldRules = "- You can search the web in this chat. Never claim you have no internet access: search instead.\n" +
        "- Search when the answer depends on facts that change or that you may not know reliably: news, prices and markets, releases and " +
        "versions, current docs, people, artists, companies, brands, laws, schedules, lyrics, slang and niche names. Answer stable knowledge, " +
        "reasoning and coding yourself."
    private val oldTool = "Search the web. Returns title"

    /** The runtime context a chat message of the app carries: the clock and the web_search flag. */
    private fun runtime(web: WebSearch) = RuntimeContext.build(java.time.ZonedDateTime.now(), emptyList(), emptyList(), web)

    private suspend fun decide(legacy: Boolean) {
        val (s, book) = settings()
        val stable = listOf(
            "Объясни, как работает сортировка слиянием.", "Что такое фотосинтез?", "Напиши функцию на Kotlin, которая разворачивает строку.",
            "Переведи на английский: «Я опоздал на поезд».", "Сколько будет 17 * 23?", "Придумай короткое поздравление с днём рождения для коллеги.",
            "В чём разница между TCP и UDP?", "Кто написал «Войну и мир»?", "Как варить гречку?", "Посоветуй, как перестать откладывать дела.",
            "Что такое корутины в Kotlin?", "Объясни теорему Пифагора простыми словами."
        )
        val live = listOf(
            "Какой сейчас курс биткоина?", "Какая погода сегодня в Москве?", "Кто выиграл последний матч Зенита?",
            "Какая последняя версия Kotlin?", "Что нового вышло в Android 17?", "Какие новости про OpenAI на этой неделе?",
            "Сколько сейчас стоит iPhone 18 Pro в России?", "Кто такой рэпер Lucy Bedroque?"
        )
        val web = app.chatEngine.let { dev.kento.data.web.Research(app.web).tools(book.web, s) }
        var schemas = JsonArray(web.all().map { it.schema })
        var system = SystemPrompt.build(s, setOf(ToolNames.SEARCH, ToolNames.READ))
        if (legacy) {
            // The first rule (when to search) replaced by the old, eager one.
            val start = system.indexOf("- search_web and read_webpage reach")
            val end = system.indexOf("- Follow the web_search field")
            system = system.substring(0, start) + oldRules + "\n" + system.substring(end)
            schemas = Json.parseToJsonElement(schemas.toString().replace(Regex("""Search the web, only for[^.]*\)\. Returns title"""), oldTool)).jsonArray
        }
        log("search rules: " + system.substringAfter("# Web research").take(300).replace('\n', ' '))
        suspend fun searches(q: String): Pair<Boolean, Long> {
            val meter = UsageMeter()
            var called = false
            withContext(meter) {
                app.api.stream(s, listOf(Turn("system", system), Turn("user", runtime(WebSearch.ENABLED) + "\n\n" + q)), schemas, chat = true)
                    .collect { if (it.tool?.name == ToolNames.SEARCH) called = true }
            }
            return called to meter.value.input
        }
        // Questions a model knows well enough but that mention products, people or practical topics: the eager kind.
        val borderline = listOf(
            "Расскажи про Илона Маска.", "Что такое Jetpack Compose и зачем он нужен?", "Сколько калорий в банане?",
            "Как настроить VPN на роутере?", "Какие плюсы у Rust по сравнению с Go?", "Посоветуй хороший фильм на вечер.",
            "Как сделать резервную копию телефона на Android?", "Что такое RAG в нейросетях?", "Как работает ChatGPT?",
            "Чем полезен магний?", "Как выучить английский быстрее?", "Что посмотреть в Санкт-Петербурге за два дня?"
        )
        var stableSearched = 0
        var liveSearched = 0
        var borderSearched = 0
        for (q in stable) { val (c, inTok) = searches(q); if (c) stableSearched++; log("   stable ${if (c) "SEARCH" else "own   "} in=$inTok $q") }
        for (q in borderline) { val (c, inTok) = searches(q); if (c) borderSearched++; log("   border ${if (c) "SEARCH" else "own   "} in=$inTok $q") }
        for (q in live) { val (c, inTok) = searches(q); if (c) liveSearched++; log("   live   ${if (c) "SEARCH" else "own   "} in=$inTok $q") }
        log("decide: searched $stableSearched/${stable.size} stable (should be 0), $borderSearched/${borderline.size} borderline (fewer is better), " +
            "$liveSearched/${live.size} live (should be all); system ${system.length} chars")
    }

    // ---------------------------------------------------------------- tavily: paid search calls per answer

    private suspend fun tavily() {
        val (s, book) = settings()
        log("web: engine=${book.web.engine} tavily=${book.web.tavilyKey.isNotBlank()} brave=${book.web.braveKey.isNotBlank()} serper=${book.web.serperKey.isNotBlank()} exa=${book.web.exaKey.isNotBlank()}")
        log("effort ${s.effort()}, aids: steps=${"steps" !in off} peek=$peek")
        val questions = listOf(
            "Сравни последние флагманы Samsung и Apple: цены, камеры и автономность, с источниками.",
            "Кто такой рэпер Lucy Bedroque, какие у него альбомы и с кем он работал?",
            "Что нового в Kotlin 2.3 и Compose Multiplatform за последние месяцы?",
            "Какой сейчас курс биткоина к доллару и что на него повлияло на этой неделе?",
            "Какая последняя стабильная версия Android Studio и что в ней нового по сравнению с предыдущей?"
        )
        val research = dev.kento.data.web.Research(app.web)
        val total = UsageMeter()
        for (q in questions) {
            val before = SearchStats.snapshot()
            val recorder = dev.kento.data.web.TrailRecorder({ 0 }) {}
            val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
            val tools = research.tools(book.web, s, recorder, peek = peek).all().map { tool -> object : ChatTool by tool {
                override suspend fun run(args: JsonObject): ToolOutput { calls += tool.name; return tool.run(args) }
            } }
            val system = SystemPrompt.build(s, setOf(ToolNames.SEARCH, ToolNames.READ))
            val turns = listOf(Turn("system", system), Turn("user", runtime(WebSearch.REQUIRED) + "\n\n" + q))
            val meter = UsageMeter()
            val answer = StringBuilder()
            val thoughts = StringBuilder()
            val started = System.currentTimeMillis()
            var firstText = -1L
            val failure = runCatching {
                withContext(meter) { loop().stream(s, turns, tools).collect {
                    if (it.thinking) thoughts.append(it.text) else {
                        if (firstText < 0 && it.text.isNotBlank()) firstText = System.currentTimeMillis() - started
                        answer.append(it.text)
                    }
                } }
            }.exceptionOrNull()
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            val delta = SearchStats.snapshot().mapValues { (k, v) -> v - (before[k] ?: 0) }.filterValues { it > 0 }
            val trail = recorder.finish()
            val u = meter.value
            total.add(u)
            val links = Regex("""\]\(https?://""").findAll(answer).count()
            val searches = calls.count { it == ToolNames.SEARCH }
            val reads = calls.count { it == ToolNames.READ }
            log("   '$q': rounds ${u.requests}, search_web $searches, read_webpage $reads, " +
                "engines $delta, ${"%.0f".format(seconds)}s (first text ${firstText / 1000.0}s), in ${u.input} (cached ${u.cached}) out ${u.output}, " +
                "\$${"%.4f".format(u.cost)}, answer ${answer.length} chars, links $links${failure?.let { ", FAILED $it" } ?: ""}")
            trail.queries.forEach { log("      query: $it") }
            log("      answer: ${answer.toString().replace('\n', ' ').take(400)}")
            lines.appendLine(buildJsonObject {
                put("question", q); put("model", s.model); put("off", off.sorted().joinToString(","))
                put("rounds", u.requests); put("searches", searches); put("reads", reads)
                put("paid", delta.filterKeys { it in setOf("Tavily", "Brave", "Serper", "Exa") }.values.sum())
                put("free", delta.filterKeys { it.startsWith("DuckDuckGo") || it == "SearXNG" }.values.sum())
                put("engines", delta.toString())
                put("seconds", seconds); put("firstText", firstText / 1000.0)
                put("input", u.input); put("cached", u.cached); put("output", u.output); put("cost", u.cost)
                put("links", links); put("failed", failure?.toString()); put("answer", answer.toString())
                put("thoughtsTail", thoughts.takeLast(600).toString())
            }.toString())
        }
        val u = total.value
        log("tavily total: ${u.requests} requests, in ${u.input} (cached ${u.cached}) out ${u.output}, \$${"%.4f".format(u.cost)}")
    }

    // ---------------------------------------------------------------- stream: how evenly text reaches the screen

    private suspend fun stream() {
        val (s, book) = settings()
        suspend fun measure(label: String, block: suspend ((dev.kento.data.net.Delta) -> Unit) -> Unit) {
            val stamps = mutableListOf<Pair<Long, Int>>()
            val start = System.currentTimeMillis()
            block { d -> if (!d.thinking && d.text.isNotEmpty()) stamps += (System.currentTimeMillis() - start) to d.text.length }
            if (stamps.isEmpty()) { log("$label: no text"); return }
            val gaps = stamps.zipWithNext { a, b -> b.first - a.first }
            val chars = stamps.sumOf { it.second }
            log("$label: first text ${stamps.first().first} ms, ${stamps.size} pieces, $chars chars, biggest piece ${stamps.maxOf { it.second }} chars, " +
                "longest gap ${gaps.maxOrNull() ?: 0} ms, gaps>500ms ${gaps.count { it > 500 }}, total ${stamps.last().first} ms")
        }
        val plain = listOf(Turn("user", "Напиши подробно, абзацев на шесть, как устроен двигатель внутреннего сгорания."))
        measure("plain chat") { sink -> app.api.stream(s, plain, chat = true).collect(sink) }
        val research = dev.kento.data.web.Research(app.web)
        val system = SystemPrompt.build(s, setOf(ToolNames.SEARCH, ToolNames.READ))
        for ((i, question) in listOf("Какой сейчас курс биткоина и что на него влияет на этой неделе?",
            "Что нового в последней версии Kotlin и когда она вышла?").withIndex()) {
            val tools = research.tools(book.web, s, peek = peek)
            val turns = listOf(Turn("system", system), Turn("user", runtime(WebSearch.REQUIRED) + "\n\n" + question))
            measure("after research ${i + 1}") { sink -> loop().stream(s, turns, tools.all()).collect(sink) }
        }
    }
}
