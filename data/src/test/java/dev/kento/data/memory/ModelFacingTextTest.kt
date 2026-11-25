package dev.kento.data.memory

import dev.kento.data.db.Attachment
import dev.kento.data.db.MemoryEntry
import dev.kento.data.media.MEDIA_TOOLS
import dev.kento.data.media.attachmentLabel
import dev.kento.data.net.Provider
import dev.kento.data.net.ProviderIssue
import dev.kento.data.settings.Settings
import dev.kento.data.settings.WebSettings
import dev.kento.data.tools.ANSWER_NOW
import dev.kento.data.tools.toolError
import dev.kento.data.web.DISABLED
import dev.kento.data.web.ReadPage
import dev.kento.data.web.SearchAccess
import dev.kento.data.web.SearchHit
import dev.kento.data.web.WebTools
import dev.kento.data.web.failure
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Kento tells the model only what is functional (what a tool does, when it
 * applies, what the runtime context fields mean). Tone, length, form and structure of answers come from the user's
 * prompt blocks alone, which are not part of this check. Every text Kento itself writes for a model is in English.
 */
class ModelFacingTextTest {
    private val access = object : SearchAccess {
        override suspend fun search(settings: WebSettings, query: String) = emptyList<SearchHit>()
        override suspend fun read(hit: SearchHit, question: String) = ReadPage("", hit.url, "")
    }
    private val allTools = setOf(ToolNames.SEARCH, ToolNames.READ, ToolNames.RECALL) + MEDIA_TOOLS.map { it.name }

    /** What a chat model reads besides the user's own blocks and messages. */
    private fun chatTexts(): Map<String, String> {
        val file = Attachment("3f2a", "c", 1, "scan.jpg", "image/jpeg", 2048, "h")
        val fact = MemoryEntry(7, "fact", "Works on Kento.", "k", topic = "work", createdAt = 1_795_000_000_000L)
        return mapOf(
            "system rules" to SystemPrompt.build(Settings(promptClock = true), allTools),
            "runtime context" to RuntimeContext.build(ZonedDateTime.of(2026, 11, 23, 14, 5, 0, 0, ZoneOffset.UTC), listOf(fact),
                listOf(file), WebSearch.DISABLED) + RuntimeContext.build(null, emptyList(), emptyList(), WebSearch.REQUIRED) +
                RuntimeContext.build(null, emptyList(), emptyList(), WebSearch.ENABLED),
            "web tools" to JsonArray(WebTools(access, WebSettings(), 8_000).all().map { it.schema }).toString(),
            "memory tools" to JsonArray(MemoryTools.SCHEMAS).toString(),
            "attachment tools" to JsonArray(MEDIA_TOOLS.map { it.schema }).toString(),
            "chat summary header" to SUMMARY_HEADER,
            "last round" to ANSWER_NOW,
            "tool errors" to listOf(toolError("search_web timed out"), toolError(DISABLED), toolError(dev.kento.data.web.UNAVAILABLE),
                failure(ProviderIssue("Модель занята", status = 503)), failure(java.io.IOException("Нет сети"))).joinToString("\n"),
            "attachment labels" to listOf(
                attachmentLabel(0, file, "Captured: 2026:11:20 10:00:00; Camera: Pixel", "Total 12.50"),
                attachmentLabel(1, file, "", "", seen = false),
                attachmentLabel(10, file, "", "Page 11", overLimit = true),
                attachmentLabel(2, file.copy(mime = "application/pdf"), content = "PDF text:\nbody"),
                attachmentLabel(3, file.copy(mime = "audio/ogg"), content = "Audio transcript:\nhello"),
            ).joinToString("\n\n"),
        )
    }

    /** Background requests: their output format is functional (parsed by Kento), so only the language is checked. */
    private fun backgroundTexts() = mapOf(
        "memory analysis" to memoryPrompt + SAVE_FACTS.toString(),
        "chat summary" to SUMMARY_PROMPT + ChatSummaries(Provider()).request("", "").joinToString("\n") { it.content },
        "chat title" to ChatTitles(Provider()).titleRequest("Что посмотреть в Казани?").replace("Что посмотреть в Казани?", ""),
    )

    private val styleWords = Regex("""(?i)\b(compact|concise|brief|briefly|short|shorter|table|filler|tone|length|lengthy|style|format|formatting|verbose|succinct|bullet|paragraph|heading|polite|friendly|naturally|think|thinking|emoji|restat\w*|plainly)\b""")

    @Test fun chatFacingTextsSayNothingAboutStyle() {
        for ((what, text) in chatTexts()) {
            val found = styleWords.findAll(text).map { it.value }.toList()
            assertTrue("$what mentions answer style: $found", found.isEmpty())
        }
    }

    @Test fun everyModelFacingTextIsEnglish() {
        for ((what, text) in chatTexts() + backgroundTexts()) {
            val cyrillic = Regex("[\\u0400-\\u04FF]+").findAll(text).map { it.value }.toList()
            assertTrue("$what is not English: $cyrillic", cyrillic.isEmpty())
        }
    }

    @Test fun toolErrorsStateOnlyTheFailure() {
        assertEquals("""{"error":"search_web timed out"}""", toolError("search_web timed out"))
        assertEquals("HTTP 503", failure(ProviderIssue("Модель занята", status = 503)))
    }

    @Test fun rulesStayFewAndAvoidTheOldStyleHints() {
        val rules = SystemPrompt.build(Settings(promptClock = true), allTools)
        // Rules ride along with every request: ~5 KB is roughly 1.2 thousand tokens, cached after the first turn.
        assertTrue("rules grew to ${rules.length} chars", rules.length < 5_500)
        for (old in listOf("one short sentence", "write nothing", "Think briefly", "no filler", "short sections", "stays compact",
            "Use them naturally", "proceed without it", "say plainly")) {
            assertFalse(old, rules.contains(old, ignoreCase = true) || chatTexts().values.any { it.contains(old, ignoreCase = true) })
        }
    }
}
