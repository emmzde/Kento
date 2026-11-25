package dev.kento.data.net

import dev.kento.data.settings.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Stored settings of earlier versions; the stream format itself is covered by StreamReaderTest. */
class CompatibilityTest {
    @Test fun migratesTheSingleProfileOfVersion011() {
        val book = decodeBook("""{"apiKey":"old","systemPrompt":"style","memoryModel":"background"}""")
        val migrated = book.active
        assertEquals(1, book.profiles.size)
        assertEquals(BOOK_VERSION, book.version)
        assertEquals("old", migrated.apiKey)
        assertEquals("deepseek-v4.1-flash-abliterated", migrated.model)
        assertEquals("background", migrated.memoryModel)
        assertEquals("style", migrated.systemPrompt)
        // A 0.1.1 profile pointed at a local server keeps its prompts but no foreign key.
        val local = decodeBook("""{"model":"custom","apiKey":"lm","baseUrl":"http://127.0.0.1:1234/v1","promptFormat":"short"}""").active
        assertEquals("", local.apiKey)
        assertEquals("short", local.promptFormat)
    }
    @Test fun currentBookWithTheOldMessageCountGetsTheDefaultChatMemory() {
        val book = decodeBook("""{"version":$BOOK_VERSION,"activeId":"refuseless","profiles":[{"id":"refuseless","model":"m","historyLimit":8}]}""")
        assertEquals(dev.kento.data.settings.HISTORY_TOKENS, book.active.historyTokens)
        assertEquals(100_000, dev.kento.data.settings.HISTORY_TOKENS)
        assertEquals(listOf(50_000, 100_000, 200_000), dev.kento.data.settings.HISTORY_CHOICES)
    }
    @Test fun collapsesSeveralConnectionsIntoRefuselessAndOpenUncensored() {
        val old = """{"activeId":"ou","bubble":"violet",
            "profiles":[
              {"id":"ou","name":"OU","baseUrl":"https://api.openuncensored.com/v1","apiKey":"ou-chat","model":"glm-5.3-flash-uncensored",
               "systemPrompt":"persona","promptFormat":"format","promptValues":"values","promptClock":false,"effort":"","adapter":"auto",
               "catalog":[{"id":"glm-5.3-flash-uncensored","efforts":null,"parameters":["tools"]}],"generationModel":"z-image-turbo",
               "sampling":{"glm-5.3-flash-uncensored":{"temperature":0.7}},"historyLimit":40,"toolSupport":{"x":false}},
              {"id":"rl","baseUrl":"https://api.refuseless.com/v1","apiKey":"abl_key","model":"glm-5.3-flash-abliterated",
               "memoryModel":"deepseek-v4.1-flash-fast","effort":"max","sampling":{"glm-5.3-flash-abliterated":{"topP":0.9}}},
              {"id":"or","baseUrl":"https://openrouter.ai/api/v1","apiKey":"sk-or","model":"x"}],
            "images":{"baseUrl":"https://api.runware.ai/v1","apiKey":"rw","adapter":"runware","catalog":[{"id":"runware:1","external":true}]},
            "memory":{"enabled":true,"factsInContext":10,"background":false},
            "web":{"engine":"Tavily","tavilyKey":"tv"}}"""
        val book = decodeBook(old)
        val s = book.active
        assertEquals(1, book.profiles.size)
        assertEquals(BOOK_VERSION, book.version)
        // Key, models and level from the Refuseless profile; the user's own words and habits from the active one.
        assertEquals("abl_key", s.apiKey)
        assertEquals("glm-5.3-flash-abliterated", s.model)
        assertEquals("deepseek-v4.1-flash-fast", s.memoryModel)
        assertEquals(mapOf("glm-5.3-flash-abliterated" to "max"), s.efforts)
        assertEquals("persona", s.systemPrompt)
        assertEquals("format", s.promptFormat)
        assertEquals("values", s.promptValues)
        assertFalse(s.promptClock)
        // The old message count is replaced by the token budget of the chat memory.
        assertEquals(dev.kento.data.settings.HISTORY_TOKENS, s.historyTokens)
        assertEquals(0.9, s.sampling("glm-5.3-flash-abliterated").topP!!, 1e-9)
        assertTrue(s.catalog.isEmpty() && !s.catalogLoaded)
        // Pictures: the old image API was Runware, so the OpenUncensored chat key moves over.
        assertEquals("ou-chat", book.images.apiKey)
        assertTrue(book.images.catalog.isEmpty())
        assertEquals("z-image-turbo", book.images.generationModel)
        assertEquals("violet", book.bubble)
        assertEquals(10, book.memory.factsInContext)
        assertTrue(book.memory.background)
        assertEquals("tv", book.web.tavilyKey)
        // A migrated book reads back unchanged.
        val again = decodeBook(Json { encodeDefaults = true }.encodeToString(book))
        assertEquals(book, again)
    }
    @Test fun keepsTheOpenUncensoredImageApi() {
        val book = decodeBook("""{"activeId":"a","profiles":[{"id":"a","baseUrl":"https://api.refuseless.com/v1","apiKey":"abl"}],
            "images":{"baseUrl":"https://api.openuncensored.com/v1","apiKey":"ou-img","catalog":[{"id":"z-image-turbo","priceUsd":0.01}]}}""")
        assertEquals("ou-img", book.images.apiKey)
        assertEquals(listOf("z-image-turbo"), book.images.catalog.map { it.id })
        assertTrue(legacyBook("""{"profiles":[]}"""))
        assertFalse(legacyBook(Json { encodeDefaults = true }.encodeToString(book)))
    }
    /** SearXNG and a Whisper server may still live on the local network. */
    @Test fun localHttpIsScoped() {
        assertTrue(validBase("http://10.0.2.2:11434/v1"))
        assertTrue(validBase("http://192.168.1.10:1234/v1"))
        assertFalse(validBase("http://192.169.1.10:1234/v1"))
    }
}
