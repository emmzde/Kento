package dev.kento.data.web

import okhttp3.HttpUrl

internal fun robotsAllows(text: String, url: HttpUrl): Boolean {
    data class Rule(val pattern: String, val allow: Boolean)
    val specific = mutableListOf<Rule>()
    val wildcard = mutableListOf<Rule>()
    val agents = mutableSetOf<String>()
    var startedRules = false
    var useSpecific = false
    for (line in text.removePrefix("\uFEFF").lineSequence()) {
        val fields = line.substringBefore('#').split(':', limit = 2)
        if (fields.size != 2) continue
        val value = fields[1].trim()
        when (fields[0].trim().lowercase()) {
            "user-agent" -> {
                if (startedRules) agents.clear()
                startedRules = false
                agents += value.lowercase()
                if (value.equals("kento", ignoreCase = true)) useSpecific = true
            }
            "allow", "disallow" -> {
                startedRules = true
                if (value.startsWith('/')) {
                    val rule = Rule(normalizeRobots(value), fields[0].trim().equals("allow", true))
                    if ("kento" in agents) specific += rule
                    if ("*" in agents) wildcard += rule
                }
            }
        }
    }
    val target = normalizeRobots(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))
    return (if (useSpecific) specific else wildcard).asSequence().filter { matches(it.pattern, target) }
        .maxWithOrNull(compareBy<Rule> { it.pattern.count { char -> char != '*' } - if (it.pattern.endsWith('$')) 1 else 0 }
            .thenBy { it.allow })?.allow ?: true
}

private fun normalizeRobots(text: String): String = buildString {
    val bytes = text.toByteArray(Charsets.UTF_8)
    var index = 0
    while (index < bytes.size) {
        val byte = bytes[index++].toInt() and 255
        if (byte == 37 && index + 1 < bytes.size) {
            val first = bytes[index].toInt().toChar().digitToIntOrNull(16)
            val second = bytes[index + 1].toInt().toChar().digitToIntOrNull(16)
            if (first != null && second != null) {
                index += 2
                val decoded = (first * 16 + second).toChar()
                if (decoded in 'a'..'z' || decoded in 'A'..'Z' || decoded in '0'..'9' || decoded in "-._~")
                    append(decoded)
                else append("%%%02X".format(decoded.code))
                continue
            }
        }
        if (byte >= 128 || byte == 32) append("%%%02X".format(byte)) else append(byte.toChar())
    }
}

private fun matches(pattern: String, target: String): Boolean {
    val anchored = pattern.endsWith('$')
    val parts = (if (anchored) pattern.dropLast(1) else pattern).split('*')
    val prefix = parts.first()
    if (!target.startsWith(prefix)) return false
    if (parts.size == 1) return !anchored || target == prefix
    val suffix = if (anchored) parts.last() else ""
    if (!target.endsWith(suffix)) return false
    val end = target.length - suffix.length
    var position = prefix.length
    for (part in parts.subList(1, if (anchored) parts.lastIndex else parts.size)) {
        if (part.isEmpty()) continue
        val next = target.indexOf(part, position)
        if (next < 0) return false
        position = next + part.length
        if (position > end) return false
    }
    return position <= end
}
