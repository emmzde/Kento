package dev.kento.data.memory

import dev.kento.data.db.Message
import dev.kento.data.net.Turn
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemoryBudgetTest {
    @Test fun quoteIncludesMatchNearEndOfOldMessage() {
        val text = "предисловие ".repeat(400) + "Место встречи — вокзал."
        val quote = excerpt(text, listOf("вокзал"), 200)
        assertTrue(quote.contains("вокзал"))
        assertTrue(cost(quote) <= 200)
    }

    @Test fun longEscapedUnicodeMessageFitsEveryRequestWithoutLosingItsTail() {
        val text = "Кавычки \" и строка\n🎹\t".repeat(180) + "последний факт"
        val row = Message(id = 37, chatId = "test", role = "user", text = text)
        val chunks = messageSegments(row, 200).toList()
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString("") { it.getValue("text").jsonPrimitive.content })
        for (chunk in chunks) {
            assertEquals(37, chunk.getValue("id").jsonPrimitive.int)
            assertTrue(cost(JsonArray(listOf(chunk)).toString()) <= 200)
            assertFalse(chunk.getValue("text").jsonPrimitive.content.last().isHighSurrogate())
        }
    }

    private fun chat(exchanges: Int, filler: String) = (0 until exchanges).flatMap { n ->
        listOf(Message(2L * n + 1, "c", "user", "Filler $n $filler"), Message(2L * n + 2, "c", "assistant", "Continue $n."))
    }

    /** A small window still cuts the history by whole messages, and a message that cannot fit is refused. */
    @Test fun aSmallWindowBoundsTheRequestWhateverTheHistoryBudget() {
        val s = dev.kento.data.settings.Settings(model = "small", systemPrompt = "Persona.",
            catalog = listOf(dev.kento.data.settings.ModelOption("small", contextLength = 4096)))
        val rows = chat(14, "unrelated background ".repeat(55)) + Message(29, "c", "user", "Recall the tag.")
        val prepared = assemble(rows.asReversed(), rows.size, emptyList(), s, ContextRequest(memory = false))
        assertTrue(prepared.usage.shortened)
        assertTrue(prepared.turns.size < 10)
        assertTrue(prepared.turns.sumOf { cost(it.content) } <= inputBudget(s))
        assertTrue(prepared.turns.last().content.endsWith("Recall the tag."))
        val huge = listOf(Message(1, "c", "user", "Too large. ".repeat(480)))
        val refused = runCatching { assemble(huge, 1, emptyList(), s, ContextRequest(memory = false)) }.exceptionOrNull()
        assertEquals("Сообщение и системный промпт превышают бюджет контекста", refused?.message)
    }

    /** On a million-token model the history budget, not the window, decides; the summary is part of that budget. */
    @Test fun theHistoryBudgetCountsTokensAndIncludesTheSummary() {
        val s = dev.kento.data.settings.Settings(model = "big", historyTokens = 10_000,
            catalog = listOf(dev.kento.data.settings.ModelOption("big", contextLength = 1_048_576)))
        val rows = chat(150, "background words ".repeat(20)) + Message(301, "c", "user", "Next.")
        val history = { r: ContextRequest -> assemble(rows.asReversed(), rows.size, emptyList(), s, r).turns.dropLast(1)
            .filter { it.role != "system" }.sumOf { TokenScale().of(it.content) } }
        val plain = history(ContextRequest(memory = false))
        assertTrue("$plain", plain in 9_000..10_000)
        val summary = "Earlier decisions. ".repeat(800)
        val withSummary = assemble(rows.asReversed(), rows.size, emptyList(), s, ContextRequest(memory = false, summary = summary))
        assertEquals(Turn("system", SUMMARY_HEADER + summary), withSummary.turns.first())
        val verbatim = history(ContextRequest(memory = false, summary = summary))
        assertTrue("$verbatim", verbatim + TokenScale().of(SUMMARY_HEADER + summary) <= 10_000)
        // The byte estimate cuts this chat; measured at half of it by the provider, the whole chat fits.
        assertTrue(assemble(rows.asReversed(), rows.size, emptyList(), s, ContextRequest(memory = false)).turns.size < rows.size)
        assertEquals(rows.size, assemble(rows.asReversed(), rows.size, emptyList(), s, ContextRequest(memory = false, scale = TokenScale(0.5))).turns.size)
        // The memory reader's batches keep their 128K window on this model.
        assertEquals(131_072 - 8_192, backgroundBudget(s))
    }
}
