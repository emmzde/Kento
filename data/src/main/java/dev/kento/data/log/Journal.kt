package dev.kento.data.log

import dev.kento.core.text.tr

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A small local error journal: crashes, failed replies, search and image errors, unreadable settings. It stays on
 * the device (files/logs/journal.txt, at most ~256 KB, older half dropped) and leaves only when the user shares
 * it from settings. Entries never contain API keys: callers pass messages and exception types, and every line
 * goes through [mask] for anything that looks like a bearer token.
 */
object Journal {
    private const val LIMIT = 256 * 1024
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    @Volatile private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "journal.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { note("crash", tr("Падение в потоке {0}", thread.name), error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun note(area: String, message: String, error: Throwable? = null) {
        val target = file ?: return
        val line = buildString {
            append(LocalDateTime.now().format(stamp)).append(" [").append(area).append("] ").append(mask(message))
            if (error != null) {
                append("\n").append(mask(StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().lineSequence()
                    .take(24).joinToString("\n")))
            }
            append("\n")
        }
        synchronized(this) {
            runCatching {
                target.appendText(line)
                if (target.length() > LIMIT) target.writeText(target.readText().takeLast(LIMIT / 2).substringAfter('\n'))
            }
        }
    }

    fun read(): String = synchronized(this) { file?.takeIf { it.isFile }?.readText().orEmpty() }
    fun clear() = synchronized(this) { file?.delete(); Unit }

    private val secret = Regex("(?i)(bearer\\s+|sk-|key[=:]\\s*)[A-Za-z0-9._\\-]{8,}")
    fun mask(text: String) = secret.replace(text) { it.groupValues[1] + tr("[скрыто]") }
}
