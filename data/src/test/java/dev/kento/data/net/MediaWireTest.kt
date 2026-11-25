package dev.kento.data.net

import dev.kento.data.settings.Settings
import kotlinx.serialization.json.*
import okio.*
import org.junit.Assert.*
import org.junit.Test

class MediaWireTest {
    @Test fun catalogueDistinguishesUnknownAndTextOnly() {
        val data = modelCatalog("""{"data":[{"id":"v","vision":true},{"id":"t","vision":false},{"id":"fallback"}]}""")
        assertEquals(listOf(true, false, null), data.map { it.vision })
    }
    @Test fun textAndTwentyImagesAreOneOrderedUserTurn() {
        val urls = (1..20).map { "data:image/jpeg;base64,$it" }
        val json = payload(Settings(), listOf(Turn("user", "Вопрос", urls)))
        val parts = json.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("Вопрос", parts.first().jsonObject.str("text"))
        assertEquals(urls, parts.drop(1).map { it.jsonObject.getValue("image_url").jsonObject.str("url") })
    }

    @Test fun sseWithOneByteReadsPreservesEveryScriptAndCombiningCharacter() {
        val fragments = listOf("Пере", "плёт ", "cafe\u0301", " 龙 ", "👩🏽‍💻")
        val bytes = Buffer()
        for (s in fragments) bytes.writeUtf8("data: " + """{"choices":[{"delta":{"content":"$s"}}]}""" + "\n\n")
        bytes.writeUtf8("data: [DONE]\n\n")
        val single = object : ForwardingSource(bytes) {
            override fun read(sink: Buffer, byteCount: Long) = super.read(sink, minOf(1, byteCount))
        }
        val deltas = buildList { SseReader(Json).read(single.buffer(), ::add) }
        assertEquals(fragments.joinToString(""), deltas.joinToString("") { it.text })
    }
}
