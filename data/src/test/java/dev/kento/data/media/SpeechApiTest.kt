package dev.kento.data.media

import dev.kento.data.net.ProviderIssue
import dev.kento.data.settings.SpeechEndpoint
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechApiTest {
    @Test fun sendsWavWithModelAndPromptAndReadsText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":" Привет, это проверка. "}"""))
            val endpoint = SpeechEndpoint(server.url("/openai/v1/").toString(), "test-key", "whisper-large-v3-turbo")
            val text = SpeechApi().transcribe(endpoint, FloatArray(16_000) { (it % 40 - 20) / 40f }, prompt = "Ранее сказано.")
            assertEquals("Привет, это проверка.", text)
            val request = server.takeRequest()
            assertEquals("/openai/v1/audio/transcriptions", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = request.body.readByteArray()
            val form = String(body, Charsets.ISO_8859_1)
            assertTrue(form.contains("whisper-large-v3-turbo"))
            assertTrue(String(body, Charsets.UTF_8).contains("Ранее сказано."))
            assertTrue(form.contains("RIFF") && form.contains("WAVE"))
            // 16 000 samples of 16-bit audio plus the 44-byte header.
            assertTrue(body.size > 32_044)
        }
    }

    @Test fun explainsAMissingEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Not found"}}"""))
            val error = runCatching { SpeechApi().transcribe(SpeechEndpoint(server.url("/v1").toString(), "", "m"), FloatArray(800)) }
                .exceptionOrNull() as ProviderIssue
            assertEquals(404, error.status)
            assertTrue(error.message!!.contains("audio/transcriptions"))
        }
    }

    @Test fun wavHeaderDescribes16kMono() {
        val bytes = wav(FloatArray(10) { 1f })
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bytes.size)
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(20, header.getInt(40))
        assertEquals(32767, header.getShort(44).toInt())
    }
}
