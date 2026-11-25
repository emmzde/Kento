package dev.kento.data.net

import okio.BufferedSource
import okio.ByteString

/**
 * Some gateways stream token bytes rather than characters: a two-byte Cyrillic letter or a three-byte symbol can be
 * split across two SSE events, each carrying half of it as raw bytes inside its JSON string. Decoding every line
 * on its own turned each half into U+FFFD ("встре��ила").
 *
 * Lines are therefore decoded here with every byte that is not part of a complete UTF-8 sequence kept as a marker
 * char (U+DC80..U+DCFF, the lone low surrogates Python calls "surrogateescape"). JSON parsing leaves them in place,
 * and [Utf8Mender] joins the bytes with the next event's continuation before turning them into text.
 */
internal object Utf8Lines {
    private const val ESCAPE = 0xDC00

    fun read(source: BufferedSource): String? {
        if (source.exhausted()) return null
        val newline = source.indexOf('\n'.code.toByte())
        val bytes = if (newline < 0) source.readByteString() else source.readByteString(newline).also { source.skip(1) }
        val end = if (bytes.size > 0 && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return decode(bytes, end)
    }

    internal fun decode(bytes: ByteString, end: Int = bytes.size): String {
        val out = StringBuilder(end)
        var i = 0
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            val need = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> -1
            }
            if (need == 0) { out.append(b.toChar()); i++; continue }
            if (need < 0 || i + need >= end) {
                out.append((ESCAPE + b).toChar()); i++; continue
            }
            var cp = b and (0x3F shr need)
            var ok = true
            for (k in 1..need) {
                val c = bytes[i + k].toInt() and 0xFF
                if (c and 0xC0 != 0x80) { ok = false; break }
                cp = (cp shl 6) or (c and 0x3F)
            }
            val minimum = when (need) { 1 -> 0x80; 2 -> 0x800; else -> 0x10000 }
            if (!ok || cp < minimum || cp > 0x10FFFF || cp in 0xD800..0xDFFF) { out.append((ESCAPE + b).toChar()); i++; continue }
            out.appendCodePoint(cp)
            i += need + 1
        }
        return out.toString()
    }

    fun isEscape(c: Char) = c.code in 0xDC80..0xDCFF
    fun byteOf(c: Char) = (c.code - ESCAPE).toByte()
}

/**
 * Reassembles one text stream (answer or reasoning) from deltas that may end or start in the middle of a UTF-8
 * sequence. Complete characters pass straight through; only a dangling prefix waits for the next delta.
 */
internal class Utf8Mender {
    private val pending = java.io.ByteArrayOutputStream()

    fun accept(chunk: String): String {
        // A low surrogate right after a high one is half of an ordinary emoji, not an escaped byte.
        fun escaped(i: Int) = Utf8Lines.isEscape(chunk[i]) && (i == 0 || !chunk[i - 1].isHighSurrogate())
        if (pending.size() == 0 && chunk.indices.none(::escaped)) return chunk
        val out = StringBuilder(chunk.length)
        for (i in chunk.indices) {
            val c = chunk[i]
            if (escaped(i)) { pending.write(Utf8Lines.byteOf(c).toInt()); continue }
            drain(out, final = true)
            out.append(c)
        }
        drain(out, final = false)
        return out.toString()
    }

    /** Whatever is still waiting when the stream ends is genuinely broken and shown as U+FFFD. */
    fun finish(): String = StringBuilder().also { drain(it, final = true) }.toString()

    private fun drain(out: StringBuilder, final: Boolean) {
        if (pending.size() == 0) return
        val bytes = pending.toByteArray()
        val keep = if (final) 0 else incompleteTail(bytes)
        out.append(String(bytes, 0, bytes.size - keep, Charsets.UTF_8))
        pending.reset()
        if (keep > 0) pending.write(bytes, bytes.size - keep, keep)
    }

    /** Length of a trailing, still valid but unfinished UTF-8 sequence. */
    private fun incompleteTail(bytes: ByteArray): Int {
        for (back in 1..minOf(3, bytes.size)) {
            val b = bytes[bytes.size - back].toInt() and 0xFF
            if (b and 0xC0 == 0x80) continue
            val need = when { b in 0xC2..0xDF -> 2; b in 0xE0..0xEF -> 3; b in 0xF0..0xF4 -> 4; else -> 0 }
            return if (need > back) back else 0
        }
        return 0
    }
}
