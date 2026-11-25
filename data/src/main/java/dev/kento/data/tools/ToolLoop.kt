package dev.kento.data.tools

import dev.kento.core.text.tr

import dev.kento.data.net.*
import dev.kento.data.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * What a tool hands back: the text of its `role: tool` message and, for image tools, pictures that travel in that same
 * message as `image_url` parts. Refuseless passes them to the model as images (checked live on every vision model of
 * the catalogue, 2026-11-24), so no message of the user's has to be made up for them.
 */
class ToolOutput(val text: String, val images: List<String> = emptyList())

/** A function the model may call. [schema] is the complete OpenAI `tools[]` entry. */
interface ChatTool {
    val name: String
    val schema: JsonObject
    /** Upper bound for one call; a slower call answers with an error and the model carries on. */
    val timeoutMs: Long get() = 30_000
    /** Safe to run at the same time as other calls of the same turn (network lookups); others run one by one. */
    val parallel: Boolean get() = false
    /** A short line for the progress row while the call runs, e.g. "Ищу: android 16 release date". */
    fun status(args: JsonObject): String
    suspend fun run(args: JsonObject): ToolOutput
}

/** Builds the standard `{"type":"function","function":{name, description, parameters}}` declaration. */
fun functionSchema(name: String, description: String, required: List<String> = emptyList(),
    properties: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", name)
        put("description", description)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties", properties)
            putJsonArray("required") { required.forEach { add(it) } }
            put("additionalProperties", false)
        }
    }
}

/** The model always gets an answer to its call: a failure becomes `{"error": "..."}`, the fact and nothing more. */
fun toolError(what: String): String = buildJsonObject { put("error", what) }.toString()

/**
 * Runs a chat turn in which the model may call tools: stream, execute the calls, send the results back with their
 * `tool_call_id`, repeat. The last round keeps the same declarations with `tool_choice: "none"`, so the model has to
 * answer while the request prefix (tools are rendered first) stays cached; dropping the tools made the biggest round
 * of a research pay full price. A tool that throws or times out returns an error message instead of breaking the reply.
 */
class ToolLoop(private val api: Provider, private val maxRounds: Int = 12,
    /** Rounds with web lookups before the answer; the bench lifts it to measure the limit (see [RESEARCH_STEPS]). */
    private val steps: Int = RESEARCH_STEPS) {
    companion object {
        /** Endpoints that rejected a request with `tools` in this process; they get plain requests from then on. */
        private val rejected = ConcurrentHashMap.newKeySet<List<String>>()
        private fun key(s: Settings) = listOf(s.model)

        /** Every Refuseless model runs tools (checked live, 2026-11-23); a rejection is still remembered. */
        fun supports(s: Settings): Boolean = key(s) !in rejected

        /** Marks [model] as refusing tool declarations, as a 400 to them would, or clears that; journeys use it for the plain path. */
        fun refuse(model: String, refused: Boolean = true) { if (refused) rejected += listOf(model) else rejected -= listOf(model) }
    }

    /**
     * [force] names a tool the model must call in the first round (the search button in the composer).
     * [fallback] replaces the whole loop when the provider refuses tool declarations outright.
     */
    fun stream(s: Settings, initial: List<Turn>, tools: List<ChatTool>, status: (String) -> Unit = {},
        force: String? = null, fallback: (() -> Flow<Delta>)? = null,
        /** The user pressed "skip": calls still running return what they have and the next round must answer. */
        skip: kotlinx.coroutines.flow.StateFlow<Boolean>? = null): Flow<Delta> = flow {
        val turns = initial.toMutableList()
        val declarations = JsonArray(tools.map { it.schema })
        val byName = tools.associateBy { it.name }
        var spoke = false
        // Rounds that ran web lookups; see RESEARCH_STEPS.
        var researched = 0
        // The input of the first round is the conversation itself; what later rounds send on top of it is research.
        var conversation = 0L
        var research = 0L
        for (round in 0 until maxRounds) {
            val final = round == maxRounds - 1 || skip?.value == true || research > RESEARCH_TOKENS || researched >= steps
            val calls = mutableListOf<ToolCall>()
            val text = StringBuilder()
            val thought = StringBuilder()
            var separated = !spoke
            var received = false
            suspend fun FlowCollector<Delta>.show(piece: String) {
                if (piece.isEmpty()) return
                if (!separated) { emit(Delta("\n\n")); separated = true }
                if (piece.isNotBlank()) spoke = true
                emit(Delta(piece))
            }
            try {
                // The last round may not call tools; after a research the model is told so.
                val request = if (final && round > 0) turns + Turn("user", ANSWER_NOW) else turns
                val choice = if (final) TOOL_CHOICE_NONE else force.takeIf { round == 0 }
                val before = meter()?.value?.input ?: 0
                api.stream(s, request, declarations, toolChoice = choice, chat = true).onCompletion {
                    val input = (meter()?.value?.input ?: 0) - before
                    if (round == 0) conversation = input else research += (input - conversation).coerceAtLeast(0)
                }.collect { delta ->
                    received = true
                    when {
                        delta.tool != null -> calls += delta.tool
                        delta.thinking -> { thought.append(delta.text); emit(delta) }
                        delta.text.isEmpty() -> emit(delta)
                        else -> {
                            text.append(delta.text)
                            show(delta.text)
                        }
                    }
                }
            } catch (e: ProviderIssue) {
                // Open question: any 400/422 here switches tools off for the model
                // until restart, although every Refuseless model takes tools and the 400 usually means something else.
                if (round == 0 && !received && e.status in setOf(400, 422)) {
                    rejected += key(s)
                    emitAll(fallback?.invoke() ?: api.stream(s, initial, chat = true))
                    return@flow
                }
                throw e
            }
            // A round without calls is the answer. A call in the last round is ignored: tool_choice "none" forbids it,
            // and the reply has to end here.
            if (calls.isEmpty() || final) return@flow
            if (calls.any { it.name == dev.kento.data.memory.ToolNames.SEARCH || it.name == dev.kento.data.memory.ToolNames.READ }) researched++
            turns += Turn("assistant", text.toString(), calls = calls, reasoning = thought.toString())
            // Several searches or page reads of one turn run together, as a research assistant would open tabs;
            // the results still go back in call order.
            val together = calls.size > 1 && calls.take(8).all { byName[it.name]?.parallel == true }
            val skipped = ToolOutput(toolError("Skipped by the user"))
            val outputs = if (together) coroutineScope {
                val jobs = calls.mapIndexed { index, call -> async {
                    if (index >= 8) ToolOutput(toolError("Too many calls in one turn")) else execute(byName[call.name], call, status)
                } }
                val watcher = skip?.let { flag -> launch { flag.first { it }; jobs.forEach { it.cancel() } } }
                jobs.map { job -> try { job.await() } catch (e: CancellationException) { if (skip?.value == true) skipped else throw e } }
                    .also { watcher?.cancel() }
            } else calls.mapIndexed { index, call ->
                when {
                    skip?.value == true -> skipped
                    index >= 8 -> ToolOutput(toolError("Too many calls in one turn"))
                    else -> coroutineScope {
                        val job = async { execute(byName[call.name], call, status) }
                        val watcher = skip?.let { flag -> launch { flag.first { it }; job.cancel() } }
                        (try { job.await() } catch (e: CancellationException) { if (skip?.value == true) skipped else throw e })
                            .also { watcher?.cancel() }
                    }
                }
            }
            for ((call, output) in calls.zip(outputs)) turns += Turn("tool", output.text, output.images, callId = call.id)
            status("")
        }
    }

    private suspend fun execute(tool: ChatTool?, call: ToolCall, status: (String) -> Unit): ToolOutput {
        if (tool == null) return ToolOutput(toolError("Unknown tool ${call.name}"))
        val args = runCatching { wireJson.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return ToolOutput(toolError("Arguments are not a JSON object"))
        status(tool.status(args))
        return try {
            withTimeoutOrNull(tool.timeoutMs) { tool.run(args) } ?: ToolOutput(toolError("${tool.name} timed out"))
        } catch (e: CancellationException) { throw e }
        catch (e: ToolRefusal) { ToolOutput(toolError(e.message.orEmpty())) }
        catch (e: IOException) {
            dev.kento.data.log.Journal.note("tool", "${tool.name}: ${friendlyError(e)}")
            // The interface wording is localized; the model gets the status or the kind of failure in English.
            ToolOutput(toolError("${tool.name} failed (" + ((e as? ProviderIssue)?.status?.let { "HTTP $it" } ?: "I/O error") + ")"))
        }
        catch (e: Exception) {
            dev.kento.data.log.Journal.note("tool", tr("{0} упал", tool.name), e)
            ToolOutput(toolError("${tool.name} failed (internal error)"))
        }
    }
}

/**
 * Input tokens one reply may spend on research before the next round has to answer: what the tool results add to
 * every later round, summed over the rounds. The conversation itself is not counted (the whole chat travels now, up to
 * Settings.historyTokens, and it is the cached part of each round), so the research budget is the same in a new chat
 * and in a long one, whatever the model's window.
 */
internal const val RESEARCH_TOKENS = 40_000L

/**
 * Research happens in a burst, the way fast assistants do it: one wide step with all searches at once, at most one
 * more for gaps, then a single answer. Each step costs the model a full read of the conversation and its
 * own thinking, so this is where the minutes went, not in the searches.
 */
internal const val RESEARCH_STEPS = 2

/** The note of the last round: the tools are closed, the reply comes from what was found. Nothing about how to answer. */
internal const val ANSWER_NOW = "[Kento: tools are no longer available for this reply. Answer from what you found.]"

/** A tool's own, model-facing explanation of why it could not help (bad argument, unknown file). */
class ToolRefusal(message: String) : Exception(message)

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
