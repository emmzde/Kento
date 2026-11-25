package dev.kento.data.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A page the research came across: [read] when Kento opened it, otherwise a search result the model saw. */
@Serializable
data class Visit(val url: String, val title: String = "", val read: Boolean = false) {
    val host: String get() = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url.take(40)
}

/**
 * The research behind one reply, for the "searched N sites" line and the sources sheet: queries in order, every page
 * met, [at] the length of the reply text when the first search began (the preamble before it stays above the line)
 * and how long the research took.
 */
@Serializable
data class Trail(val queries: List<String> = emptyList(), val visits: List<Visit> = emptyList(), val at: Int = -1,
    val seconds: Int = 0) {
    val sites: List<String> get() = visits.map { it.host }.distinct()
    val read: List<Visit> get() = visits.filter { it.read }
    val isEmpty get() = queries.isEmpty() && visits.isEmpty()
    fun encode(): String = if (isEmpty) "" else trailJson.encodeToString(this)

    companion object {
        fun decode(raw: String): Trail? = raw.takeIf(String::isNotBlank)?.let { runCatching { trailJson.decodeFromString<Trail>(it) }.getOrNull() }
        /** Answers saved before 0.6 kept only their sources; they show as read pages. */
        fun ofSources(sources: List<dev.kento.data.db.Source>) = Trail(visits = sources.map { Visit(it.url, it.title, read = true) })
    }
}

private val trailJson = Json { ignoreUnknownKeys = true }

/**
 * Collects a [Trail] while tools run, possibly from several parallel calls. [mark] reports how much reply text exists
 * when the first query starts.
 */
class TrailRecorder(private val mark: () -> Int = { 0 }, private val changed: (Trail) -> Unit = {}) {
    val state = MutableStateFlow(Trail())
    private val started = System.currentTimeMillis()

    private fun update(change: (Trail) -> Trail) { state.update(change); changed(state.value) }

    fun query(text: String) = update { t ->
        t.copy(queries = (t.queries + text.trim()).distinct().takeLast(40), at = if (t.at < 0) mark() else t.at)
    }

    fun found(hits: List<SearchHit>) = update { t ->
        val known = t.visits.map { it.url }.toSet()
        t.copy(visits = (t.visits + hits.filter { it.url !in known }.map { Visit(it.url, it.title) }).take(120))
    }

    fun read(url: String, title: String) = update { t ->
        val existing = t.visits.indexOfFirst { it.url == url }
        val visit = Visit(url, title.ifBlank { t.visits.getOrNull(existing)?.title.orEmpty() }, read = true)
        t.copy(visits = if (existing >= 0) t.visits.toMutableList().also { it[existing] = visit } else (t.visits + visit).take(120),
            at = if (t.at < 0) mark() else t.at)
    }

    fun finish(): Trail = state.value.copy(seconds = ((System.currentTimeMillis() - started) / 1000).toInt())
}
