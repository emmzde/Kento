package dev.kento.data.web

import dev.kento.data.memory.RuntimeContext
import dev.kento.data.memory.SystemPrompt
import dev.kento.data.memory.ToolNames
import dev.kento.data.memory.WebSearch
import dev.kento.data.net.Delta
import dev.kento.data.net.Provider
import dev.kento.data.net.ProviderIssue
import dev.kento.data.net.Turn
import dev.kento.data.net.UsageMeter
import dev.kento.data.settings.Settings
import dev.kento.data.settings.WebSettings
import dev.kento.data.tools.ChatTool
import dev.kento.data.tools.ToolLoop
import dev.kento.data.tools.ToolOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/**
 * The search tasks of the preview Bench (`decide`, `tavily`, `stream`) on the JVM, with the keys of the project's `.env`
 * (REFUSELESS_API_KEY, TAVILY_KEY, BRAVE_KEY, SERPER_KEY, EXA_KEY) instead of keys saved in the app. It runs the same
 * code as a chat reply: SystemPrompt, the runtime context, WebTools through WebAccess (one paid API, then DuckDuckGo
 * Lite over HTTP; there is no WebView here, so a bot check counts as a failed engine) and ToolLoop.
 *
 * Each variant switches a search-loop aid off: `steps` (RESEARCH_STEPS), `peek`
 * (page_excerpt of the top results), `all` (both). An earlier run also measured `nudge`
 * (announcesMore + CONTINUE) and `hold` (HOLD / looksLikeAnswer), and a later run measured page
 * digests on the background model (`nodigest`); all three were removed after them. `lastInput` is the input of the
 * last round, `roundInputs` of each.
 * It costs money, so it runs only with `KENTO_BENCH=1`:
 * `$env:KENTO_BENCH='1'; .\gradlew.bat :data:testDebugUnitTest --tests '*SearchBenchLiveTest*'`, with optional
 * KENTO_BENCH_MODELS, KENTO_BENCH_VARIANTS, KENTO_BENCH_TASKS (comma lists), KENTO_BENCH_OUT (folder) and
 * KENTO_BENCH_BUDGET (dollars at Refuseless, 0.30 by default).
 */
/** RESEARCH_STEPS of ToolLoop, which the bench lifts for the `steps` variant. */
private val RESEARCH_STEPS_DEFAULT = dev.kento.data.tools.RESEARCH_STEPS

class SearchBenchLiveTest {
    private val dotEnv: Map<String, String> = listOf(File(".env"), File("../.env")).firstOrNull(File::exists)?.readLines().orEmpty()
        .filter { '=' in it && !it.trimStart().startsWith("#") }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().split(Regex("\\s+")).first() }
    private fun env(name: String) = System.getenv(name)?.takeIf(String::isNotBlank) ?: dotEnv[name].orEmpty()
    private fun list(name: String, default: String) = (System.getenv(name)?.takeIf(String::isNotBlank) ?: default)
        .split(',').map(String::trim).filter(String::isNotEmpty)

    private val out = File(System.getenv("KENTO_BENCH_OUT") ?: "build/bench").apply { mkdirs() }
    private val api = Provider()

    /**
     * The whole run spends at most this many dollars at Refuseless (catalogue prices), $0.30 unless KENTO_BENCH_BUDGET
     * says otherwise. No new answer starts once 80% is spent: the answers already running (each $0.002-0.005, at most a
     * dozen at once) finish inside the rest.
     */
    private val budget = System.getenv("KENTO_BENCH_BUDGET")?.toDoubleOrNull() ?: 0.30
    private val spent = java.util.concurrent.atomic.AtomicLong()
    private fun spend(dollars: Double) { spent.addAndGet((dollars * 1_000_000).toLong()) }
    private fun over() = spent.get() / 1_000_000.0 >= budget * 0.8

    private val research = listOf(
        "Сравни последние флагманы Samsung и Apple: цены, камеры и автономность, с источниками.",
        "Кто такой рэпер Lucy Bedroque, какие у него альбомы и с кем он работал?",
        "Что нового в Kotlin 2.3 и Compose Multiplatform за последние месяцы?",
        "Какой сейчас курс биткоина к доллару и что на него повлияло на этой неделе?",
        "Какая последняя стабильная версия Android Studio и что в ней нового по сравнению с предыдущей?"
    )
    /** The questions of the stream task: a live figure and a release, both answered after a research. */
    private val streamed = listOf(
        "Какой сейчас курс биткоина и что на него влияет на этой неделе?",
        "Что нового в последней версии Kotlin и когда она вышла?"
    )

    private fun web() = WebSettings(tavilyKey = env("TAVILY_KEY"), braveKey = env("BRAVE_KEY"), serperKey = env("SERPER_KEY"),
        exaKey = env("EXA_KEY"), paidSearches = 1)

    /** A fresh WebAccess per variant, so no variant reads results or pages cached by another. */
    private fun access() = WebAccess(webHttp, publicPages, WebCache(), null, wikipedia = true,
        duckBrowser = { _, _ -> throw ProviderIssue("DuckDuckGo asked for a check; the bench has no WebView") })

    /** Research steps and peeked results of [variant]. */
    private fun aids(variant: String): Pair<Int, Int> {
        val base = variant.substringBefore("-")
        val off = if (base == "all") setOf("steps", "peek") else setOf(base)
        return (if ("steps" in off) Int.MAX_VALUE else RESEARCH_STEPS_DEFAULT) to (if ("peek" in off) 0 else 3)
    }

    private fun runtime(web: WebSearch) = RuntimeContext.build(ZonedDateTime.now(), emptyList(), emptyList(), web)

    @Test fun bench() = runBlocking {
        assumeTrue("set KENTO_BENCH=1 to run the paid bench", System.getenv("KENTO_BENCH") == "1")
        val key = env("REFUSELESS_API_KEY")
        assumeTrue("no REFUSELESS_API_KEY", key.isNotBlank())
        val catalog = api.models(Settings(apiKey = key))
        val tasks = list("KENTO_BENCH_TASKS", "decide,tavily,stream")
        // KENTO_BENCH_REPEAT runs each variant that many times at once, as "base-1", "base-2"…
        val repeat = System.getenv("KENTO_BENCH_REPEAT")?.toIntOrNull() ?: 1
        val variants = list("KENTO_BENCH_VARIANTS", "base,steps,peek,all").flatMap { v -> if (repeat <= 1) listOf(v) else (1..repeat).map { "$v-$it" } }
        // KENTO_BENCH_QUESTIONS keeps only the questions that contain one of its comma-separated parts.
        val only = list("KENTO_BENCH_QUESTIONS", "")
        val questions = ((if ("tavily" in tasks) research else emptyList()) + (if ("stream" in tasks) streamed else emptyList()))
            .filter { q -> only.isEmpty() || only.any { it in q } }
        // Every model and every variant at once: each variant asks its questions one by one with its own WebAccess, so
        // the variants share only the provider and the network, equally.
        coroutineScope {
            list("KENTO_BENCH_MODELS", "deepseek-v4.1-flash-abliterated").flatMap { model ->
                val s = Settings(apiKey = key, model = model, catalog = catalog, catalogLoaded = true)
                listOfNotNull(
                    if ("decide" in tasks) async(Dispatchers.IO) { decide(s) } else null,
                    if ("stream" in tasks) async(Dispatchers.IO) { plain(s) } else null) +
                    if (questions.isEmpty()) emptyList() else variants.map { variant -> async(Dispatchers.IO) { answers(s, variant, questions) } }
            }.awaitAll()
        }
        Log(File(out, "bench-spent.txt")).line("Refuseless spent by this run: \$${"%.4f".format(spent.get() / 1_000_000.0)} of the \$$budget budget")
    }

    private class Log(private val file: File) {
        init { file.writeText("") }
        fun line(text: String) { println(text); synchronized(this) { file.appendText(text + "\n") } }
    }

    /** Bench `decide`: the first round alone (no tool runs), so no aid of the loop can change it. */
    private suspend fun decide(s: Settings) {
        val log = Log(File(out, "bench-decide-${s.model}.txt"))
        log.line("=== decide model=${s.model} effort=${s.effort()} ${ZonedDateTime.now()}")
        val schemas = JsonArray(WebTools(access(), web(), 8_000).all().map { it.schema })
        val system = SystemPrompt.build(s, setOf(ToolNames.SEARCH, ToolNames.READ))
        val total = UsageMeter()
        suspend fun searches(q: String): Pair<Boolean, dev.kento.data.net.Usage> {
            val meter = UsageMeter()
            var called = false
            withContext(meter) {
                api.stream(s, listOf(Turn("system", system), Turn("user", runtime(WebSearch.ENABLED) + "\n\n" + q)), schemas, chat = true)
                    .collect { if (it.tool?.name == ToolNames.SEARCH) called = true }
            }
            total.add(meter.value)
            spend(meter.value.cost)
            return called to meter.value
        }
        val groups = listOf(
            "stable" to listOf("Объясни, как работает сортировка слиянием.", "Что такое фотосинтез?", "Напиши функцию на Kotlin, которая разворачивает строку.",
                "Переведи на английский: «Я опоздал на поезд».", "Сколько будет 17 * 23?", "Придумай короткое поздравление с днём рождения для коллеги.",
                "В чём разница между TCP и UDP?", "Кто написал «Войну и мир»?", "Как варить гречку?", "Посоветуй, как перестать откладывать дела.",
                "Что такое корутины в Kotlin?", "Объясни теорему Пифагора простыми словами."),
            "border" to listOf("Расскажи про Илона Маска.", "Что такое Jetpack Compose и зачем он нужен?", "Сколько калорий в банане?",
                "Как настроить VPN на роутере?", "Какие плюсы у Rust по сравнению с Go?", "Посоветуй хороший фильм на вечер.",
                "Как сделать резервную копию телефона на Android?", "Что такое RAG в нейросетях?", "Как работает ChatGPT?",
                "Чем полезен магний?", "Как выучить английский быстрее?", "Что посмотреть в Санкт-Петербурге за два дня?"),
            "live" to listOf("Какой сейчас курс биткоина?", "Какая погода сегодня в Москве?", "Кто выиграл последний матч Зенита?",
                "Какая последняя версия Kotlin?", "Что нового вышло в Android 17?", "Какие новости про OpenAI на этой неделе?",
                "Сколько сейчас стоит iPhone 18 Pro в России?", "Кто такой рэпер Lucy Bedroque?"))
        val counts = groups.map { (group, questions) ->
            group to questions.count { q ->
                if (over()) { log.line("   budget reached, skipped: $q"); return@count false }
                val (called, u) = searches(q)
                log.line("   ${group.padEnd(6)} ${if (called) "SEARCH" else "own   "} in=${u.input} out=${u.output} \$${"%.5f".format(u.cost)} $q")
                called
            } to questions.size
        }
        log.line("decide: " + counts.joinToString(", ") { (g, n) -> "${g.first} ${g.second}/$n" } +
            " (stable should be 0, live all); system ${system.length} chars")
        val u = total.value
        log.line("decide total: ${u.requests} requests, in ${u.input} (cached ${u.cached}) out ${u.output} " +
            "(reasoning ${u.reasoning}), \$${"%.4f".format(u.cost)}")
    }

    /** Bench `stream`, first part: a plain chat without tools, the reference for how evenly text arrives. */
    private suspend fun plain(s: Settings) {
        val log = Log(File(out, "bench-stream-plain-${s.model}.txt"))
        val stamps = mutableListOf<Pair<Long, Int>>()
        val start = System.currentTimeMillis()
        val meter = UsageMeter()
        withContext(meter) {
            api.stream(s, listOf(Turn("user", "Напиши подробно, абзацев на шесть, как устроен двигатель внутреннего сгорания.")), chat = true)
                .collect { d -> if (!d.thinking && d.text.isNotEmpty()) stamps += (System.currentTimeMillis() - start) to d.text.length }
        }
        spend(meter.value.cost)
        log.line("plain chat ${s.model}: " + smoothness(stamps) + ", \$${"%.4f".format(meter.value.cost)}")
    }

    private fun smoothness(stamps: List<Pair<Long, Int>>): String {
        if (stamps.isEmpty()) return "no text"
        val gaps = stamps.zipWithNext { a, b -> b.first - a.first }
        return "first text ${stamps.first().first} ms, ${stamps.size} pieces, ${stamps.sumOf { it.second }} chars, biggest piece " +
            "${stamps.maxOf { it.second }} chars, longest gap ${gaps.maxOrNull() ?: 0} ms, gaps>500ms ${gaps.count { it > 500 }}"
    }

    /** Bench `tavily` and the research part of `stream`: one answer per question through ToolLoop, with [variant]'s aids off. */
    private suspend fun answers(s: Settings, variant: String, questions: List<String>) {
        val log = Log(File(out, "bench-research-${s.model}-$variant.txt"))
        val lines = File(out, "bench-research-${s.model}-$variant.jsonl").apply { writeText("") }
        val (steps, peek) = aids(variant)
        val web = web()
        val access = access()
        val tools = Research(access)
        log.line("=== research model=${s.model} effort=${s.effort()} variant=$variant " +
            "steps=$steps peek=$peek tavily=${web.tavilyKey.isNotBlank()} brave=${web.braveKey.isNotBlank()} exa=${web.exaKey.isNotBlank()}")
        val total = UsageMeter()
        for (q in questions) {
            if (over()) { log.line("   budget reached (\$${"%.4f".format(spent.get() / 1_000_000.0)} of \$$budget), skipped: $q"); continue }
            val before = access.calls()
            val recorder = TrailRecorder({ 0 }) {}
            val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
            val readBytes = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val wrapped = tools.tools(web, s, recorder, peek = peek).all().map { tool -> object : ChatTool by tool {
                override suspend fun run(args: JsonObject): ToolOutput {
                    calls += tool.name
                    return tool.run(args).also { if (tool.name == ToolNames.READ) readBytes += it.text.toByteArray().size }
                }
            } }
            val system = SystemPrompt.build(s, setOf(ToolNames.SEARCH, ToolNames.READ))
            val turns = listOf(Turn("system", system), Turn("user", runtime(WebSearch.REQUIRED) + "\n\n" + q))
            val meter = UsageMeter()
            val answer = StringBuilder()
            val thoughts = StringBuilder()
            val stamps = mutableListOf<Pair<Long, Int>>()
            val started = System.currentTimeMillis()
            val failure = runCatching {
                withContext(meter) { ToolLoop(api, steps = steps).stream(s, turns, wrapped).collect { d: Delta ->
                    if (d.thinking) thoughts.append(d.text) else if (d.text.isNotEmpty()) {
                        stamps += (System.currentTimeMillis() - started) to d.text.length
                        answer.append(d.text)
                    }
                } }
            }.exceptionOrNull()
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            val delta = access.calls().mapValues { (k, v) -> v - (before[k] ?: 0) }.filterValues { it > 0 }
            val trail = recorder.finish()
            val u = meter.value
            val lastInput = meter.parts.lastOrNull()?.input ?: 0
            total.add(u)
            spend(u.cost)
            val links = Regex("""\]\(https?://""").findAll(answer).count()
            val searches = calls.count { it == ToolNames.SEARCH }
            val reads = calls.count { it == ToolNames.READ }
            val gaps = stamps.zipWithNext { a, b -> b.first - a.first }
            log.line("   '$q': rounds ${u.requests}, search_web $searches, read_webpage $reads, " +
                "engines $delta, ${"%.0f".format(seconds)}s, in ${u.input} (cached ${u.cached}) out ${u.output}, \$${"%.4f".format(u.cost)}, " +
                "answer ${answer.length} chars, links $links, last round in $lastInput, reads $readBytes bytes" +
                (failure?.let { ", FAILED $it" } ?: ""))
            log.line("      stream: " + smoothness(stamps))
            trail.queries.forEach { log.line("      query: $it") }
            log.line("      answer: ${answer.toString().replace('\n', ' ').take(300)}")
            lines.appendText(buildJsonObject {
                put("question", q); put("model", s.model); put("variant", variant)
                put("rounds", u.requests); put("searches", searches); put("reads", reads)
                put("paid", delta.filterKeys { it in setOf("Tavily", "Brave", "Serper", "Exa") }.values.sum())
                put("free", delta.filterKeys { it.startsWith("DuckDuckGo") || it == "SearXNG" }.values.sum())
                put("engines", delta.toString()); put("seconds", seconds)
                put("firstText", stamps.firstOrNull()?.first ?: -1); put("biggestPiece", stamps.maxOfOrNull { it.second } ?: 0)
                put("longestGap", gaps.maxOrNull() ?: 0); put("gaps500", gaps.count { it > 500 })
                put("input", u.input); put("cached", u.cached); put("output", u.output); put("cost", u.cost)
                put("lastInput", lastInput); put("roundInputs", meter.parts.joinToString(",") { it.input.toString() })
                put("readBytes", readBytes.joinToString(","))
                put("links", links); put("failed", failure?.toString()); put("answer", answer.toString())
                put("thoughtsTail", thoughts.takeLast(800).toString())
            }.toString() + "\n")
        }
        val u = total.value
        log.line("total ${s.model} $variant: ${u.requests} requests, in ${u.input} (cached ${u.cached}) out ${u.output}, \$${"%.4f".format(u.cost)}")
    }
}
