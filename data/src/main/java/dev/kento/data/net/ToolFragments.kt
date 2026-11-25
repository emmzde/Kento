package dev.kento.data.net

import dev.kento.core.text.tr

import kotlinx.serialization.json.*

/**
 * Tool calls of one Refuseless stream: every fragment carries the `index` of its call, the first one also its `id`
 * and `function.name`, the rest pieces of `function.arguments` to append (recorded live, see SseReader).
 */
internal class ToolFragments {
    private class Parts(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
    private val parts = sortedMapOf<Int, Parts>()

    fun add(fragment: JsonObject) {
        val index = fragment.str("index")?.toIntOrNull() ?: return
        val p = parts.getOrPut(index) { Parts() }
        fragment.str("id")?.takeIf(String::isNotEmpty)?.let { p.id = it }
        val function = fragment["function"] as? JsonObject ?: return
        function.str("name")?.takeIf(String::isNotEmpty)?.let { p.name = it }
        function.str("arguments")?.let { p.args.append(it) }
    }

    /**
     * Hands over every named call. Arguments cut short still go to the tool loop, which tells the model they were not
     * JSON so it can try again; only a stream of nothing but nameless calls fails the reply.
     */
    fun finish(emit: (Delta) -> Unit) {
        var emitted = 0
        for ((index, p) in parts) {
            if (p.name.isBlank()) continue
            val args = Utf8Mender().let { it.accept(p.args.toString()) + it.finish() }
            emit(Delta("", tool = ToolCall(p.id.ifEmpty { "call_$index" }, p.name, args.ifBlank { "{}" })))
            emitted++
        }
        if (parts.isNotEmpty() && emitted == 0) throw ProviderIssue(tr("Неполный вызов инструмента"))
    }
}
