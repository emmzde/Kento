package dev.kento.data.net

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse

/**
 * Mock replies shaped like the Refuseless streams recorded in data/src/test/resources/refuseless: deltas with index,
 * `finish_reason` in its own chunk, usage in a chunk without choices, then `[DONE]`.
 */
object RefuselessSse {
    private fun chunk(delta: JsonObject, finish: String? = null) = buildJsonObject {
        put("object", "chat.completion.chunk")
        putJsonArray("choices") { add(buildJsonObject {
            put("index", 0); put("delta", delta); put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
        }) }
    }

    /** The whole event stream: [deltas] in order, then the finish chunk, the usage chunk and [DONE]. */
    fun body(deltas: List<JsonObject>, finish: String, prompt: Int = 0, completion: Int = 0): String = buildString {
        (listOf(buildJsonObject { put("role", "assistant"); put("content", "") }) + deltas)
            .forEach { append("data: ").append(chunk(it)).append("\n\n") }
        append("data: ").append(chunk(buildJsonObject {}, finish)).append("\n\n")
        if (prompt > 0 || completion > 0) append("data: ").append(buildJsonObject {
            putJsonArray("choices") {}
            putJsonObject("usage") { put("prompt_tokens", prompt); put("completion_tokens", completion); put("total_tokens", prompt + completion) }
        }).append("\n\n")
        append("data: [DONE]\n\n")
    }

    fun answerBody(text: String, reasoning: String = "", prompt: Int = 0): String = body(buildList {
        if (reasoning.isNotEmpty()) add(buildJsonObject { put("reasoning", reasoning) })
        add(buildJsonObject { put("content", text) })
    }, "stop", prompt, if (prompt > 0) 1 else 0)

    /** Tool calls as Refuseless streams them: id and name first, then the arguments, each fragment with its index. */
    fun callsBody(vararg calls: Triple<String, String, String>, text: String = "", prompt: Int = 0): String = body(buildList {
        if (text.isNotEmpty()) add(buildJsonObject { put("content", text) })
        calls.forEachIndexed { index, (id, name, args) ->
            add(buildJsonObject { putJsonArray("tool_calls") { add(buildJsonObject {
                put("id", id); put("type", "function"); put("index", index); putJsonObject("function") { put("name", name) }
            }) } })
            add(buildJsonObject { putJsonArray("tool_calls") { add(buildJsonObject {
                put("index", index); putJsonObject("function") { put("arguments", args) }
            }) } })
        }
    }, "tool_calls", prompt, if (prompt > 0) 1 else 0)

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    fun answer(text: String, reasoning: String = "", prompt: Int = 0): MockResponse = response(answerBody(text, reasoning, prompt))
    fun calls(vararg calls: Triple<String, String, String>, text: String = "", prompt: Int = 0): MockResponse =
        response(callsBody(*calls, text = text, prompt = prompt))
    fun call(name: String, args: String, id: String = "call_1", prompt: Int = 0): MockResponse = calls(Triple(id, name, args), prompt = prompt)
}
