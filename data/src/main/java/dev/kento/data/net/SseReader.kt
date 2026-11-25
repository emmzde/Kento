package dev.kento.data.net

import dev.kento.core.text.tr

import kotlinx.serialization.json.*
import okio.BufferedSource

/**
 * Reads a Refuseless chat stream (recorded live in data/src/test/resources/refuseless): `data:` events with
 * `choices[0].delta` holding `content`, thinking as `reasoning` (`reasoning_content` on Kimi) and indexed `tool_calls`
 * fragments; `finish_reason` `stop`, `tool_calls` or `length`; `usage`, growing in every chunk on some models and only
 * in the last one on others; an `error` event; then `[DONE]` (sent twice). The server already moves `<think>` spans
 * out of `content`. Kept as safeguards: UTF-8 characters split between events, events without the blank line
 * between them, and a stream that breaks off before `[DONE]`.
 */
internal class SseReader(private val json: Json) {
    fun read(source: BufferedSource, emit: (Delta) -> Unit) {
        // Bytes of a character split between events are joined here instead of becoming U+FFFD.
        val answerBytes = Utf8Mender()
        val thoughtBytes = Utf8Mender()
        val calls = ToolFragments()
        val lines = mutableListOf<String>()
        var finished = false
        var done = false
        var usage: Reported? = null
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun event() {
            if (lines.isEmpty()) return
            val raw = lines.joinToString("\n").trim()
            lines.clear()
            if (raw == "[DONE]") { done = true; return }
            if (raw.isEmpty()) return
            val packet = json.parseToJsonElement(raw).jsonObject
            (packet["error"] as? JsonObject)?.let { error ->
                val detail = error.str("message")
                val code = error.str("code")
                // Refuseless reports a busy, cold or missing model as an event of an otherwise successful stream;
                // the status lets the caller retry it when nothing has arrived yet, as it would an HTTP 429 or 503.
                val status = when (code) {
                    "model_busy", "model_warming", "rate_limit_exceeded" -> 429
                    "model_unavailable", "worker_unavailable", "upstream_error", "gateway_down", "gateway_error" -> 503
                    "insufficient_credits", "insufficient_credits_for_hold", "key_budget_exceeded", "key_daily_budget_exceeded" -> 402
                    else -> null
                }
                val reason = codeReason(code) ?: tr("Провайдер прервал генерацию")
                throw ProviderIssue(reason + (detail?.takeIf(String::isNotBlank)?.let { ": " + it.take(300) } ?: ""), status, code = code)
            }
            // The last usage of the stream counts: on some models every chunk repeats the growing total.
            reportedUsage(packet)?.let { usage = it }
            val choice = (packet["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
            (choice["delta"] as? JsonObject)?.let { delta ->
                val thought = thoughtBytes.accept(delta.text("reasoning").ifEmpty { delta.text("reasoning_content") })
                if (thought.isNotEmpty()) emit(Delta(thought, true))
                val answer = answerBytes.accept(delta.text("content"))
                if (answer.isNotEmpty()) emit(Delta(answer))
                (delta["tool_calls"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(calls::add) }
            }
            when (choice.str("finish_reason")) {
                null -> {}
                "length" -> throw ProviderIssue(tr("Достигнут лимит ответа"))
                else -> finished = true
            }
        }
        try {
            while (!done) {
                val line = Utf8Lines.read(source) ?: break
                when {
                    line.isBlank() -> event()
                    line.startsWith("data:") -> {
                        // An event that arrives without the blank line after the previous one still ends that one.
                        if (lines.isNotEmpty() && (lines.singleOrNull()?.trim() == "[DONE]" ||
                            runCatching { json.parseToJsonElement(lines.joinToString("\n")) }.isSuccess)) event()
                        if (!done) lines += line.substring(5).trimStart()
                    }
                    // Refuseless sends nothing but data lines; anything else (an SSE comment) carries no data.
                }
            }
            event()
            if (!done && !finished) throw StreamCut()
            calls.finish(emit)
            usage?.let { emit(Delta("", usage = it)) }
        } finally {
            thoughtBytes.finish().takeIf(String::isNotEmpty)?.let { emit(Delta(it, true)) }
            answerBytes.finish().takeIf(String::isNotEmpty)?.let { emit(Delta(it)) }
        }
    }
}
