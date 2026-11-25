package dev.kento.feature.chat

import dev.kento.core.text.tr

import android.os.SystemClock
import dev.kento.data.web.Research
import dev.kento.data.memory.ChatContext
import dev.kento.data.memory.ToolNames
import dev.kento.data.memory.WebSearch
import dev.kento.data.media.IMAGE_LIMIT
import dev.kento.data.media.seesImages
import dev.kento.data.tools.ChatTool
import dev.kento.data.tools.ToolLoop
import dev.kento.data.db.ChatDao
import dev.kento.data.net.Provider
import dev.kento.data.net.friendlyError
import dev.kento.data.settings.ProfileBook
import dev.kento.data.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

data class DraftReply(val id: Long, val text: String, val thoughts: String)
/** The research of reply [id] while it is being written. */
data class LiveTrail(val id: Long, val trail: dev.kento.data.web.Trail)

internal class Generation(private val chats: ChatDao, private val api: Provider, private val contextBuilder: ChatContext,
    private val research: Research, private val media: dev.kento.data.media.LocalMedia, private val scheduleMemory: () -> Unit,
    /** Outlives a stopped reply, so the chat still gets its name. */
    private val background: CoroutineScope, private val nameChat: suspend (Settings, String) -> String?) {
    private val attachments = dev.kento.data.media.MediaRequest(media)
    private val loop = ToolLoop(api)
    val live = MutableStateFlow<DraftReply?>(null)

    val progress = MutableStateFlow("")
    /** The web research of the reply being written, for the "searching N sites" line. */
    val trail = MutableStateFlow<LiveTrail?>(null)
    /** Set by "skip": the running searches stop and the model answers with what it has. */
    val skip = MutableStateFlow(false)

    suspend fun image(chat: String, input: String, endpoint: dev.kento.data.settings.ImageEndpoint, model: dev.kento.data.net.ImageModel, retry: Long?) {
        val reply = withContext(NonCancellable) {
            if (retry == null) chats.start(chat, input, model.id, imageGeneration = true)
            else { check(chats.retry(chat, retry, model.id)); retry }
        }
        try {
            val refs = media.dao.sent(chats.promptFor(chat, reply).id).map {
                if (!it.mime.startsWith("image/")) throw dev.kento.data.net.ProviderIssue(tr("Для референса выберите изображение"))
                dev.kento.data.net.ImageReference(media.file(it), it.mime)
            }
            var billed: Double? = null
            // No app-wide lock: OpenUncensored takes 32 parallel requests per key, and a picture of up to four minutes
            // no longer holds back the memory analysis. One reply at a time is the composer's `sending` flag.
            media.storeGenerated(chat, reply) { file -> billed = api.images.create(endpoint, model, input, refs, file) }
            // OpenUncensored reports what the picture cost; the catalogue price (plus the per-reference surcharge) is the
            // fallback when it does not.
            val listed = model.priceUsd?.let { it + (model.priceInputUsd ?: 0.0) * refs.size }
            val usage = billed?.let { dev.kento.data.net.Usage(charged = it, requests = 1, exact = 1) }
                ?: dev.kento.data.net.Usage(estimated = listed ?: 0.0, requests = 1, unpriced = if (listed == null) 1 else 0)
            chats.addUsage(reply, usage)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { chats.endActive(reply, "stopped") }
            throw e
        } catch (e: Exception) {
            dev.kento.data.log.Journal.note("image", "${model.id}: " + friendlyError(e), e.takeUnless { it is dev.kento.data.net.ProviderIssue })
            chats.endActive(reply, "failed", friendlyError(e))
        }
    }

    /**
     * One reply. Web search, memory and attachment tools travel with the request and the model decides when to use
     * them. The declared set depends only on settings and on whether the chat has attachments, never on the turn, so
     * the request prefix stays cached; what the turn allows is the web_search field of the runtime context:
     * "required" with [search] (the composer's globe), "disabled" when the model may not search on its own.
     */
    suspend fun run(chatId: String, input: String, s: Settings, book: ProfileBook, search: Boolean, retry: Long? = null) {
        val id = withContext(NonCancellable) {
            if (retry == null) chats.start(chatId, input, s.model)
            else {
                check(chats.retry(chatId, retry, s.model))
                retry
            }
        }
        // Named once the first reply is done: never competes with it on providers that allow one request at a time.
        val fresh = retry == null && input.isNotBlank() && chats.questions(chatId) == 1
        val answer = StringBuilder()
        val thoughts = StringBuilder()
        var state = "done"
        var issue = ""
        val meter = dev.kento.data.net.UsageMeter()
        // The provider's count of the first request against Kento's estimate of it; see Message.promptTokens.
        val probe = dev.kento.data.net.PromptProbe()
        var estimate = 0
        skip.value = false
        live.value = null
        // The model decides when it knows enough; only a runaway research is stopped, five minutes after the first
        // query, the same way as "skip" (the model then answers with what it found). Tokens are capped separately by
        // the reading budget of WebTools.
        val fuse = java.util.concurrent.atomic.AtomicBoolean(false)
        var fuseJob: kotlinx.coroutines.Job? = null
        val recorder = dev.kento.data.web.TrailRecorder({ answer.length }) {
            trail.value = LiveTrail(id, it)
            if (fuse.compareAndSet(false, true)) fuseJob = background.launch { kotlinx.coroutines.delay(5 * 60_000L); skip.value = true }
        }
        // A busy or cold model is waited for by the provider client; the status line says so meanwhile.
        var waiting: String? = null
        val notice = dev.kento.data.net.ProviderNotice { waiting = it; progress.value = it }
        try { withContext(meter + notice + probe) {
            val recent = chats.context(chatId)
            val latest = recent.first()
            val rows = media.dao.sent(latest.id)
            val batch = if (rows.isEmpty()) null else attachments.prepare(s, rows) { progress.value = it }
            val prior = if (batch == null && recent.getOrNull(1)?.role == "assistant") {
                val prompt = recent.getOrNull(2)?.takeIf { it.role == "user" }
                prompt?.let { message ->
                    val files = media.dao.sent(message.id)
                    if (files.isEmpty()) null else message.id to attachments.prepare(s, files) { progress.value = it }
                }
            } else null
            val native = ToolLoop.supports(s)
            val flag = WebSearch.of(search, book.web.modelDecides)
            // Declared even when this message may not search: the tool refuses then, and the prefix stays the same.
            val web = research.tools(book.web, s, recorder, allowed = flag.allowed)
            // From the chat's first sent attachment on, the whole attachment set is declared and stays.
            val sent = media.dao.observe(chatId).first().filter { it.messageId != null }
            // Pictures of the messages in the request; image_edit adds its own only up to IMAGE_LIMIT.
            var images = 0
            var tools = if (!native) emptyList() else buildList<ChatTool> {
                addAll(web.all())
                if (book.memory.enabled) addAll(contextBuilder.memoryTools(chatId))
                if (sent.isNotEmpty()) addAll(attachments.tools(sent, s.seesImages() != false) { IMAGE_LIMIT - images })
            }
            suspend fun context(withTools: Boolean): List<dev.kento.data.net.Turn> {
                val names = if (withTools) tools.map { it.name }.toSet() else emptySet()
                val bytes = if (withTools && tools.isNotEmpty()) JsonArray(tools.map { it.schema }).toString().toByteArray().size else 0
                // Schemas are plain ASCII JSON: about a third of their bytes in tokens, still a safe overestimate.
                val prepared = contextBuilder.prepare(chatId, s, media = batch, previousMedia = prior,
                    memorySettings = book.memory, tools = names, toolBytes = bytes / 3,
                    web = flag.takeIf { ToolNames.SEARCH in names },
                    files = if (names.any { it == "read_media_queue" }) sent else emptyList(),
                    compressing = { progress.value = tr("Сжимаю историю чата…") })
                if (progress.value == tr("Сжимаю историю чата…")) progress.value = ""
                images = prepared.turns.sumOf { it.images.size }
                // Images are counted as a flat guess; such a request would mislead the token scale of the chat.
                estimate = if (images > 0) 0 else prepared.estimate
                return prepared.turns
            }
            val turns = if (tools.isNotEmpty()) try { context(withTools = true) } catch (e: dev.kento.data.net.ProviderIssue) {
                // A message that only fits without tool schemas and rules still gets sent, as a plain request.
                tools = emptyList()
                context(withTools = false)
            } else context(withTools = false)
            val status: (String) -> Unit = {
                progress.value = it
                // Tools start once the model stops writing: the words that came within the last redraw interval
                // would otherwise wait on screen for the whole search.
                live.value = DraftReply(id, answer.toString(), thoughts.toString())
            }
            var lastDraw = 0L
            var lastWrite = SystemClock.elapsedRealtime()
            val stream = if (tools.isNotEmpty()) loop.stream(s, turns, tools, status, skip = skip, fallback = {
                // The provider refused tool declarations: the same question as a plain request.
                flow { emitAll(api.stream(s, context(withTools = false), chat = true)) }
            }) else api.stream(s, turns, chat = true)
            stream.collect { delta ->
                // The model answered after a wait: the "starting" line goes away with the first piece.
                if (waiting != null && progress.value == waiting) { progress.value = ""; waiting = null }
                if (delta.text.isEmpty()) return@collect
                (if (delta.thinking) thoughts else answer).append(delta.text)
                val now = SystemClock.elapsedRealtime()
                // Long replies redraw less often, so the screen stays smooth while they grow.
                if (now - lastDraw >= (if (answer.length + thoughts.length < 4_000) 48 else 110)) {
                    live.value = DraftReply(id, answer.toString(), thoughts.toString())
                    lastDraw = now
                }
                if (now - lastWrite >= 1200) {
                    chats.checkpoint(id, answer.toString(), thoughts.toString(), "active")
                    lastWrite = now
                }
            }
            if (answer.isBlank()) {
                state = "failed"
                issue = tr("Модель не вернула текст ответа")
            }
            web.sources(answer.toString()).takeIf { it.isNotEmpty() }?.let {
                chats.setSources(id, dev.kento.data.db.encodeSources(it))
            }
        } } catch (e: CancellationException) {
            state = "stopped"
            throw e
        } catch (e: Exception) {
            state = "failed"
            issue = friendlyError(e)
            dev.kento.data.log.Journal.note("chat", "${s.model}: $issue",
                e.takeUnless { it is dev.kento.data.net.ProviderIssue })
        } finally {
            withContext(NonCancellable) {
                fuseJob?.cancel()
                // `adb shell setprop log.tag.KentoSearch DEBUG` shows how a reply researched: queries, pages, time, tokens.
                if (android.util.Log.isLoggable("KentoSearch", android.util.Log.DEBUG)) recorder.finish().let { t ->
                    val u = meter.value
                    android.util.Log.d("KentoSearch", "reply $state in ${t.seconds}s, ${u.requests} requests, in ${u.input} " +
                        "(cached ${u.cached}) out ${u.output}, sites ${t.sites.size}, read ${t.read.size}")
                    t.queries.forEach { android.util.Log.d("KentoSearch", "query: $it") }
                    t.read.forEach { android.util.Log.d("KentoSearch", "read: ${it.url}") }
                    android.util.Log.d("KentoSearch", "answer: " + answer.toString().replace('\n', ' ').take(3500))
                }
                // The final text first, so the last words never wait for the database to report the finished row.
                live.value = DraftReply(id, answer.toString(), thoughts.toString())
                chats.checkpoint(id, answer.toString().trim(), thoughts.toString(), state, issue)
                recorder.finish().takeUnless { it.isEmpty }?.let { chats.setResearch(id, it.encode()) }
                runCatching { chats.addUsage(id, meter.value) }
                probe.tokens?.takeIf { estimate > 0 }?.let { runCatching { chats.setPrompt(id, it.toInt(), estimate) } }
                // The draft stays until the next reply: the row ignores it once Room reports it finished, and clearing it
                // here showed the last saved checkpoint (up to a second older) for a frame, so the text shrank and grew.
                trail.value = null
                progress.value = ""
                if (book.memory.enabled && book.memory.background) scheduleMemory()
                // A chat that just outgrew its history budget gets its summary now, not when the next message waits.
                if (state == "done") background.launch(Dispatchers.IO) {
                    val folding = dev.kento.data.net.UsageMeter()
                    withContext(folding) { runCatching { contextBuilder.compressAhead(chatId, s) } }
                    runCatching { chats.addUsage(id, folding.value) }
                }
                // The title request is part of what this chat cost, so it lands on the first reply.
                if (fresh && state == "done") background.launch(Dispatchers.IO) {
                    val titles = dev.kento.data.net.UsageMeter()
                    withContext(titles) { nameChat(s, input) }?.let { chats.rename(chatId, it) }
                    runCatching { chats.addUsage(id, titles.value) }
                }
            }
        }
    }
}
