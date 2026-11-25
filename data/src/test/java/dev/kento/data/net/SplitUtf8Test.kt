package dev.kento.data.net

import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Gateways that stream token bytes may split one UTF-8 character across two SSE events. */
class SplitUtf8Test {
    private fun stream(vararg pieces: ByteArray): List<Delta> {
        val buffer = Buffer()
        for (piece in pieces) {
            buffer.writeUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"")
            buffer.write(piece)
            buffer.writeUtf8("\"}}]}\n\n")
        }
        buffer.writeUtf8("data: [DONE]\n\n")
        return buildList { SseReader(Json { ignoreUnknownKeys = true }).read(buffer, ::add) }
    }

    @Test fun cyrillicLetterSplitBetweenEventsIsJoined() {
        val word = "встречила".toByteArray()
        // "ч" is D1 87: the first event ends after D1, the second starts with 87.
        val cut = "встре".toByteArray().size + 1
        val text = stream(word.copyOfRange(0, cut), word.copyOfRange(cut, word.size)).joinToString("") { it.text }
        assertEquals("встречила", text)
        assertFalse(text.contains('�'))
    }

    @Test fun threeAndFourByteCharactersSurviveAnySplit() {
        val sample = "a—«b»🎹ц".toByteArray()
        for (cut in 1 until sample.size) {
            val text = stream(sample.copyOfRange(0, cut), sample.copyOfRange(cut, sample.size)).joinToString("") { it.text }
            assertEquals("cut at $cut", "a—«b»🎹ц", text)
        }
    }

    @Test fun genuinelyBrokenBytesStillShowAsReplacement() {
        val text = stream(byteArrayOf('o'.code.toByte(), 0xD1.toByte()), "k".toByteArray()).joinToString("") { it.text }
        assertEquals("o�k", text)
    }

    @Test fun reasoningStreamIsMendedToo() {
        val bytes = "мысль".toByteArray()
        val buffer = Buffer()
        for (part in listOf(bytes.copyOfRange(0, 3), bytes.copyOfRange(3, bytes.size))) {
            buffer.writeUtf8("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"")
            buffer.write(part)
            buffer.writeUtf8("\"}}]}\n\n")
        }
        buffer.writeUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n")
        val deltas = buildList { SseReader(Json).read(buffer, ::add) }
        assertEquals("мысль", deltas.filter { it.thinking }.joinToString("") { it.text })
    }

    @Test fun ordinaryUtf8LinesDecodeAsBefore() {
        assertEquals("Привет, 世界 🎹", Utf8Lines.decode(okio.ByteString.Companion.run { "Привет, 世界 🎹".encodeUtf8() }))
    }
}
