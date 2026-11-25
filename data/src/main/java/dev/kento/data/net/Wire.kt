package dev.kento.data.net

import dev.kento.core.text.tr

import dev.kento.data.settings.EFFORT_ORDER
import dev.kento.data.settings.LongContext
import dev.kento.data.settings.ModelOption
import dev.kento.data.settings.Sampling
import dev.kento.data.settings.Settings
import kotlinx.serialization.json.*
import java.io.IOException

data class ToolCall(val id: String, val name: String, val arguments: String)
/** A piece of a streamed reply; [usage] arrives once, at the end, when the provider reports it. */
data class Delta(val text: String, val thinking: Boolean = false, val tool: ToolCall? = null, val usage: Reported? = null)
data class Turn(val role: String, val content: String, val images: List<String> = emptyList(),
    val calls: List<ToolCall> = emptyList(), val callId: String? = null,
    /** Thinking that preceded tool calls; reasoning models such as DeepSeek expect it back within the same turn. */
    val reasoning: String = "")

private fun Turn.wire() = buildJsonObject {
    put("role", role)
    if (images.isEmpty()) put("content", content) else putJsonArray("content") {
        add(buildJsonObject { put("type", "text"); put("text", content) })
        images.forEach { uri -> add(buildJsonObject {
            put("type", "image_url")
            putJsonObject("image_url") { put("url", uri) }
        }) }
    }
    callId?.let { put("tool_call_id", it) }
    if (role == "assistant" && calls.isNotEmpty() && reasoning.isNotBlank()) put("reasoning_content", reasoning)
    if (calls.isNotEmpty()) putJsonArray("tool_calls") {
        calls.forEach { call -> add(buildJsonObject {
            put("id", call.id); put("type", "function")
            putJsonObject("function") { put("name", call.name); put("arguments", call.arguments) }
        }) }
    }
}
open class ProviderIssue(message: String, val status: Int? = null, val retryAfterMs: Long? = null,
    /** Refuseless `error.code`, e.g. `model_warming`. */
    val code: String? = null): IOException(message)
/** The event stream ended without [DONE] or a finish reason; retried only when nothing had arrived yet. */
class StreamCut : ProviderIssue(tr("Соединение прервалось до конца ответа"))
/** `tool_choice` of a round that must answer: tools stay declared, so the prompt prefix stays cached. */
const val TOOL_CHOICE_NONE = "none"
internal val wireJson =Json { ignoreUnknownKeys = true; encodeDefaults = true }
internal fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/**
 * The Refuseless catalogue: `{"data":[{id, abliterated, vision, context_length, reasoning_efforts, reasoning_default,
 * pricing: {input_per_m, cached_input_per_m, output_per_m, discounted, long_context}}]}`.
 */
internal fun modelCatalog(raw: String): List<ModelOption> {
    val root = wireJson.parseToJsonElement(raw) as? JsonObject
    val items = root?.get("data") as? JsonArray ?: throw ProviderIssue(tr("Неверный формат каталога моделей"))
    return items.mapNotNull { item ->
        val m = item as? JsonObject ?: return@mapNotNull null
        val id = m.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val pricing = m["pricing"] as? JsonObject
        fun JsonObject.price(key: String) = str(key)?.toDoubleOrNull()?.takeIf { it >= 0 }
        val long = (pricing?.get("long_context") as? JsonObject)?.let { l ->
            val threshold = l.str("threshold")?.toLongOrNull() ?: return@let null
            val input = l.price("input_per_m") ?: return@let null
            LongContext(threshold, input, l.price("cached_input_per_m") ?: input, l.price("output_per_m") ?: return@let null)
        }
        val efforts = m.strings("reasoning_efforts").orEmpty().map { if (it == "none") "off" else it }
            .filter { it in EFFORT_ORDER }.distinct().sortedBy(EFFORT_ORDER::indexOf)
        ModelOption(id, efforts, m.str("name").orEmpty(), m.str("context_length")?.toIntOrNull(),
            m.str("vision")?.toBooleanStrictOrNull(), m.str("abliterated")?.toBooleanStrictOrNull() ?: true,
            m.str("reasoning_default")?.let { if (it == "none") "off" else it },
            pricing?.price("input_per_m"), pricing?.price("output_per_m"),
            pricing?.price("cached_input_per_m") ?: pricing?.price("input_per_m"),
            pricing?.str("discounted") == "true", long, m.str("available") != "false")
    }.distinctBy { it.id }
}

/**
 * One request body. [chat] marks a conversation request: it carries the user's sampling settings and reasoning level
 * for the model. Background requests (titles, memory, summaries) set their own values and think as little as the model
 * allows. [effort] overrides the level either way; a level the model does not list is never sent (Refuseless answers
 * it with 400).
 */
internal fun payload(s: Settings, messages: List<Turn>, tools: JsonArray? = null, temperature: Double? = null,
    toolChoice: String? = null, maxTokens: Int? = null, chat: Boolean = false, effort: String? = null): JsonObject = buildJsonObject {
    put("model", s.model)
    put("messages", JsonArray(messages.map { it.wire() }))
    put("stream", true)
    // Token counts of a streamed reply come only with include_usage.
    putJsonObject("stream_options") { put("include_usage", true) }
    val sampling = if (chat) s.sampling() else Sampling()
    val level = (effort ?: if (chat) s.effort() else s.quietEffort())?.takeIf { it in s.levels() }
    (temperature ?: sampling.temperature)?.let { put("temperature", it) }
    // Every request reserves prompt + max_tokens on the balance; a missing cap reserves the model's whole output.
    (maxTokens ?: if (chat) s.maxTokens(level) else null)?.let { put("max_tokens", it) }
    sampling.topP?.let { put("top_p", it) }
    sampling.topK?.let { put("top_k", it) }
    sampling.minP?.let { put("min_p", it) }
    sampling.frequencyPenalty?.let { put("frequency_penalty", it) }
    sampling.presencePenalty?.let { put("presence_penalty", it) }
    sampling.repetitionPenalty?.let { put("repetition_penalty", it) }
    sampling.seed?.let { put("seed", it) }
    level?.let { put("reasoning_effort", it) }
    if (tools != null && tools.isNotEmpty()) {
        put("tools", tools)
        // Standard OpenAI form: "auto" is the default, "none" keeps the declarations but forbids calls (the prompt and
        // its cached prefix stay the same, checked live 2026-11-24), a name forces that function in this request.
        when (toolChoice) {
            null -> {}
            TOOL_CHOICE_NONE -> put("tool_choice", toolChoice)
            else -> putJsonObject("tool_choice") {
                put("type", "function")
                putJsonObject("function") { put("name", toolChoice) }
            }
        }
    }
}
