package dev.kento.app

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.kento.data.memory.RuntimeContext
import dev.kento.data.memory.SystemPrompt
import dev.kento.data.memory.ToolNames
import dev.kento.data.memory.WebSearch
import dev.kento.data.net.Turn
import dev.kento.data.net.UsageMeter
import dev.kento.data.tools.ToolLoop
import dev.kento.data.web.AndroidPageRenderer
import dev.kento.data.web.Research
import dev.kento.data.web.TrailRecorder
import dev.kento.data.web.WebAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * Search quality on the user's own path: the model of the QA app's settings
 * researches through WebTools in ToolLoop, with the device's WebAccess (WebView for bot checks and script pages),
 * exactly as a chat reply with the globe pressed. It replaced the old pipeline (Research.collectSources) that rewrote
 * the question and ranked pages for it, which the app no longer used.
 *
 * Paid and needs a key saved in the QA app: `-e searchBenchmark true [-e searchBenchmarkLimit N]`. Per question it logs
 * whether the answer states the reference fact, whether a reference site was opened or cited, rounds and tokens.
 */
class SearchQualityBenchmark {
    private data class Case(val question: String, val answer: String, val sources: String) {
        private val pattern = Regex(answer, RegexOption.IGNORE_CASE)
        private val domains = sources.split(' ')

        fun reference(url: String): Boolean {
            val host = url.toHttpUrlOrNull()?.host ?: return false
            return domains.any { host == it || host.endsWith(".$it") }
        }

        fun states(answer: String) = pattern.containsMatchIn(answer)
    }

    @Test fun measuresSearchQuality() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("searchBenchmark") == "true")
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        val book = app.settings.profiles.first()
        val s = book.active
        assertTrue("Выберите рабочую модель в настройках QA-приложения", s.model.isNotBlank())
        val research = Research(WebAccess(app.searchBrowser, renderer = AndroidPageRenderer(app)))
        val system = SystemPrompt.build(s, setOf(ToolNames.SEARCH, ToolNames.READ))
        val count = args.getString("searchBenchmarkLimit")?.toIntOrNull()?.coerceIn(1, cases.size) ?: cases.size
        var stated = 0
        var referenced = 0
        val total = UsageMeter()
        for ((index, case) in cases.take(count).withIndex()) {
            val recorder = TrailRecorder()
            val tools = research.tools(book.web, s, recorder)
            val runtime = RuntimeContext.build(ZonedDateTime.now(), emptyList(), emptyList(), WebSearch.REQUIRED)
            val turns = listOf(Turn("system", system), Turn("user", runtime + "\n\n" + case.question))
            val meter = UsageMeter()
            val answer = StringBuilder()
            val started = System.currentTimeMillis()
            val failure = runCatching {
                withContext(meter) {
                    ToolLoop(app.api).stream(s, turns, tools.all()).collect { if (!it.thinking) answer.append(it.text) }
                }
            }.exceptionOrNull()
            val trail = recorder.finish()
            val sources = tools.sources(answer.toString()).map { it.url } + trail.read.map { it.url }
            val states = case.states(answer.toString())
            val onReference = sources.any(case::reference)
            if (states) stated++
            if (onReference) referenced++
            val u = meter.value
            total.add(u)
            Log.i("KentoSearchBenchmark", "${index + 1}/$count states=$states reference=$onReference rounds=${u.requests} " +
                "in=${u.input} out=${u.output} cost=${"%.4f".format(u.cost)} ${(System.currentTimeMillis() - started) / 1000}s " +
                "queries=${trail.queries} read=${trail.read.map { it.host }}${failure?.let { " FAILED $it" } ?: ""} question=${case.question}")
        }
        val u = total.value
        Log.i("KentoSearchBenchmark", "questions=$count stated=$stated/$count referenceSource=$referenced/$count " +
            "requests=${u.requests} in=${u.input} out=${u.output} cost=${"%.4f".format(u.cost)}")
    }

    private val cases = listOf(
        Case("What HTTP status means Too Many Requests?", "429", "rfc-editor.org ietf.org developer.mozilla.org"),
        Case("Какой статус HTTP означает постоянное перенаправление с сохранением метода?", "308", "rfc-editor.org ietf.org developer.mozilla.org"),
        Case("What is the reserved IPv4 block for documentation TEST-NET-1?", "192\\.0\\.2\\.0/24", "rfc-editor.org ietf.org iana.org"),
        Case("Для чего IANA резервирует example.com и example.org?", "(documentation|документац)", "iana.org rfc-editor.org ietf.org"),
        Case("How many bits are in an IPv6 address?", "128", "rfc-editor.org ietf.org icann.org"),
        Case("Какой HTTP заголовок сообщает, когда повторить запрос после 503?", "Retry-After", "rfc-editor.org ietf.org developer.mozilla.org"),
        Case("Which robots.txt directive marks a crawler path as forbidden?", "disallow", "rfc-editor.org ietf.org developers.google.com"),
        Case("As of which Android API level does an app need notification channels?", "(API level |Android |API )26", "developer.android.com"),
        Case("В какой версии Android появилось разрешение POST_NOTIFICATIONS?", "(Android 13|API level 33|API 33)", "developer.android.com"),
        Case("Which Android permission is used to record microphone audio?", "RECORD_AUDIO", "developer.android.com"),
        Case("Как обратиться к loopback хоста из стандартного Android Emulator?", "10\\.0\\.2\\.2", "developer.android.com"),
        Case("Does Android Room allow database access on the main thread by default?", "main thread", "developer.android.com"),
        Case("What Kotlin operator supplies a fallback for a null value?", "Elvis", "kotlinlang.org"),
        Case("Как в Kotlin объявить функцию расширения для String?", "fun String\\.", "kotlinlang.org"),
        Case("Kotlin suspend coroutine cancellation exception name", "CancellationException", "kotlinlang.org kotlinx.coroutines.github.io"),
        Case("What Python keyword creates a generator value?", "yield", "docs.python.org peps.python.org"),
        Case("С какого Python порядок вставки словаря гарантирован спецификацией?", "3\\.7", "docs.python.org peps.python.org"),
        Case("What does Python's len() return for a sequence?", "number of items", "docs.python.org"),
        Case("Which Python exception signals a missing dictionary key?", "KeyError", "docs.python.org"),
        Case("Как включить проверку внешних ключей в SQLite?", "PRAGMA foreign_keys", "sqlite.org"),
        Case("What SQLite mode writes changes to a write-ahead log?", "\\bWAL\\b", "sqlite.org"),
        Case("What port is reserved for PostgreSQL by default?", "5432", "postgresql.org iana.org"),
        Case("Для чего используется CSS свойство box-sizing border-box?", "padding.{0,40}border", "developer.mozilla.org w3.org"),
        Case("Which JavaScript method waits for all promises and rejects on the first rejection?", "Promise\\.all", "developer.mozilla.org tc39.es"),
        Case("Какой элемент HTML создаёт многострочное поле для текста?", "textarea", "developer.mozilla.org whatwg.org"),
        Case("What Cargo command checks a Rust project without producing an executable?", "cargo check", "rust-lang.org"),
        Case("Which Git command records the current contents of the index in a new commit?", "git commit", "git-scm.com"),
        Case("Как добавить в git изменение только по отдельным фрагментам файла?", "(git add -p|--patch)", "git-scm.com"),
        Case("What HTTP response header carries the path of a newly created resource?", "Location", "developer.mozilla.org rfc-editor.org ietf.org"),
        Case("Find the ISO 8601 UTC suffix in RFC 3339 on rfc-editor.org", "\\bZ\\b", "rfc-editor.org"),
        Case("Найди на developer.mozilla.org разницу между HTTP 401 и 403", "401", "developer.mozilla.org"),
        Case("На sqlite.org как запустить проверку целостности базы SQLite?", "integrity_check", "sqlite.org")
    )
}
