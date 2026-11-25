package dev.kento.data.memory

import dev.kento.data.db.MemoryEntry
import dev.kento.data.settings.Settings
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MemoryContextTest {
    @Test fun excerptKeepsWholeSentencesAroundTheMatch() {
        val text = "Первое предложение про погоду. Второе тоже про погоду. Мы договорились встретиться на вокзале в семь. " +
            "Потом обсуждали кино. Последнее предложение."
        val quote = excerpt(text, listOf("вокзал"), 130)
        assertTrue(quote.contains("Мы договорились встретиться на вокзале в семь."))
        assertTrue(cost(quote) <= 130)
        assertFalse(quote.contains("…"))
        // Starts and ends on sentence boundaries.
        assertTrue(quote.first().isUpperCase())
        assertTrue(quote.trimEnd().last() in ".!?")
    }

    @Test fun excerptReturnsShortTextUntouched() {
        assertEquals("Короткий текст.", excerpt("Короткий текст.", listOf("x"), 500))
    }

    @Test fun relevantFactsFillTheSlotsLeftBesideTheCore() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        val facts = listOf(
            MemoryEntry(1, "fact", "Любит чай.", "a", importance = 1, createdAt = now - 400 * day),
            MemoryEntry(2, "fact", "Зовут Эмиль.", "b", importance = 3, createdAt = now - 300 * day),
            MemoryEntry(3, "fact", "Пишет Android-приложение Kento на Kotlin.", "c", topic = "проекты", importance = 2, createdAt = now - 10 * day),
            MemoryEntry(4, "fact", "Был в Баку летом.", "d", importance = 1, createdAt = now - 5 * day),
        )
        val core = MemoryProfile.core(facts)
        assertEquals(listOf(2L), core.map { it.id })
        // Only facts that share a root with the message, and none of the core again.
        assertEquals(listOf(3L), MemoryProfile.select(facts, "Как ускорить Kotlin корутины в Android?", 2, now, taken = core).map { it.id })
        assertEquals(listOf(1L), MemoryProfile.select(facts, "Какой чай заварить?", 2, now, taken = core).map { it.id })
        assertTrue(MemoryProfile.select(facts, "Привет", 6, now, taken = core).isEmpty())
        assertTrue(MemoryProfile.select(facts, "Kotlin", 0, now).isEmpty())
    }

    /** The core goes with every message, the topical notes follow it. */
    @Test fun coreIsInEveryRuntimeContextAndTopicalNotesFollowTheMessage() {
        val now = 1_800_000_000_000L
        val marathon = MemoryEntry(1, "fact", "Готовлюсь к марафону, бегаю 4 раза в неделю.", "manual:1", edited = true, importance = 3, createdAt = now)
        val cat = MemoryEntry(2, "fact", "У пользователя кот Барсик.", "b", topic = "питомцы", importance = 2, createdAt = now)
        val kotlin = MemoryEntry(3, "fact", "Пользователь пишет на Kotlin.", "c", topic = "стек", importance = 2, createdAt = now)
        val facts = listOf(marathon, cat, kotlin)
        fun runtime(message: String): String {
            val core = MemoryProfile.core(facts)
            val chosen = core + MemoryProfile.select(facts, message, 6, now, taken = core)
            return RuntimeContext.build(null, chosen, emptyList(), null, ZoneOffset.UTC)
        }
        for (message in listOf("Чем кормить кота?", "Как собрать Kotlin-проект?", "Привет")) {
            assertTrue(message, runtime(message).contains("Content: Готовлюсь к марафону"))
        }
        assertTrue(runtime("Чем кормить кота?").contains("Барсик"))
        assertFalse(runtime("Чем кормить кота?").contains("Kotlin"))
        assertTrue(runtime("Как собрать Kotlin-проект?").contains("пишет на Kotlin"))
        assertFalse(runtime("Как собрать Kotlin-проект?").contains("Барсик"))
        // "Бегаю" in the note, "бег" in the message: one root.
        assertEquals(listOf(1L), MemoryProfile.select(facts.map { it.copy(edited = false, importance = 2) }, "План на бег", 6, now).map { it.id })
    }

    @Test fun coreHoldsTenLinesAndAboutThreeHundredTokensUserNotesFirst() {
        val now = 1_800_000_000_000L
        val own = listOf("Готовлюсь к марафону.", "Не ем мясо.", "Живу в Казани.", "Работаю врачом.").mapIndexed { i, text ->
            MemoryEntry(i + 1L, "fact", text, "manual:$i", edited = true, importance = 2, createdAt = now - i) }
        val important = (10L..30L).map { MemoryEntry(it, "fact", "Важный факт $it о предмете ${it * 7}.", "f$it", importance = 3,
            createdAt = now - it * 1000) }
        val detail = MemoryEntry(99, "fact", "Мелкая деталь.", "d", importance = 2, createdAt = now)
        val core = MemoryProfile.core(own + important + detail)
        assertEquals(MemoryProfile.CORE_LINES, core.size)
        // The user's own notes first, then the freshest important facts; plain details never.
        assertEquals(own.map { it.id }, core.take(4).map { it.id })
        assertEquals((10L..15L).toList(), core.drop(4).map { it.id })
        assertFalse(detail in core)
        val long = (1L..6L).map { MemoryEntry(it, "fact", "Факт номер ${it * 11}: " + "текст$it ".repeat(30), "l$it", importance = 3, createdAt = now - it) }
        val limited = MemoryProfile.core(long)
        assertTrue(limited.sumOf { it.text.length } <= MemoryProfile.CORE_CHARS)
        assertTrue(limited.size in 1..4)
    }

    @Test fun factLinesCarryDateTopicAndContent() {
        val fact = MemoryEntry(12, "fact", "Пишет на Kotlin.", "k", topic = "стек", createdAt = 1_795_000_000_000L)
        // No id: the chat model has no tool that takes one.
        assertEquals("[Date: 2026-11-18] Topic: стек Content: Пишет на Kotlin.", factLine(fact, ZoneOffset.UTC))
    }

    @Test fun rulesSayThatSavingNeedsNoToolAndThatThePresentWins() {
        val rules = SystemPrompt.build(Settings(), setOf(ToolNames.RECALL))
        assertTrue(rules.contains("a request to remember something needs no tool call"))
        assertTrue(rules.contains("When a note contradicts what the user says now, what the user says now is current"))
        assertFalse(rules.contains("memory_write"))
        assertEquals(listOf(ToolNames.RECALL), MemoryTools.SCHEMAS.map { it.getValue("function").jsonObject.getValue("name").jsonPrimitive.content })
    }

    @Test fun emptySetupSendsNoSystemPrompt() {
        assertEquals("", SystemPrompt.build(Settings(), emptySet()))
    }

    @Test fun blocksAndToolRulesAppearOnlyWhenPresent() {
        val prompt = SystemPrompt.build(Settings(promptFormat = "Кратко.", systemPrompt = "Прямо."),
            setOf(ToolNames.SEARCH, ToolNames.READ))
        assertTrue(prompt.startsWith("# Role and format\nКратко."))
        assertTrue(prompt.contains("# Persona\nПрямо."))
        assertFalse(prompt.contains("# Values"))
        assertTrue(prompt.contains("# Runtime context"))
        assertTrue(prompt.contains("# Web research"))
        assertFalse(prompt.contains("# Memory"))
    }

    @Test fun runtimeTimeNamesDayAndOffset() {
        val now = ZonedDateTime.of(2026, 11, 20, 21, 14, 0, 0, ZoneOffset.ofHours(3))
        assertEquals("2026-11-20 21:14 Friday UTC+03:00 (2026-11-20 18:14 UTC)", RuntimeContext.time(now))
        assertEquals("2026-11-20 18:14 Friday UTC+00:00 (2026-11-20 18:14 UTC)", RuntimeContext.time(now.withZoneSameInstant(ZoneOffset.UTC)))
        // Past midnight in UTC the date differs too.
        assertEquals("2026-11-21 01:30 Saturday UTC+03:00 (2026-11-20 22:30 UTC)",
            RuntimeContext.time(ZonedDateTime.of(2026, 11, 21, 1, 30, 0, 0, ZoneOffset.ofHours(3))))
    }

    @Test fun titleCleanupStripsQuotesAndPunctuation() {
        val titles = ChatTitles(dev.kento.data.net.Provider())
        assertEquals("Ускорение корутин в Android", titles.clean("«Ускорение корутин в Android».\n"))
        assertEquals("Kotlin flows", titles.clean("Title: \"Kotlin flows\""))
        assertNull(titles.clean("   "))
    }

    @Test fun titlesUseTheBackgroundModelAndRoomForThinkers() {
        val titles = ChatTitles(dev.kento.data.net.Provider())
        val catalog = listOf(dev.kento.data.settings.ModelOption("deepseek-v4.1-flash-abliterated-fast", efforts = listOf("off", "low", "high", "max")),
            dev.kento.data.settings.ModelOption("glm", efforts = listOf("low", "high", "max")))
        assertEquals("deepseek-v4.1-flash-abliterated-fast", titles.titleModel(Settings(model = "glm", catalog = catalog)))
        assertEquals("cheap", titles.titleModel(Settings(model = "glm", memoryModel = "cheap", catalog = catalog)))
        // A model that can switch reasoning off gets a 24-token title; GLM thinks at "low" anyway and needs room.
        assertFalse(titles.thinks(Settings(model = "deepseek-v4.1-flash-abliterated-fast", catalog = catalog)))
        assertTrue(titles.thinks(Settings(model = "glm", catalog = catalog)))
        assertFalse(titles.thinks(Settings(model = "unknown")))
    }

    @Test fun searchedAnswersKeepTheirSourcesInHistory() {
        val sources = dev.kento.data.db.encodeSources(listOf(dev.kento.data.db.Source("CoinGecko", "https://coingecko.com/btc"),
            dev.kento.data.db.Source("Cited", "https://cited.example/a")))
        val answer = dev.kento.data.db.Message(chatId = "c", role = "assistant", text = "About 58k, see [x](https://cited.example/a).", sources = sources)
        assertEquals("About 58k, see [x](https://cited.example/a).\n\nSources: [CoinGecko](https://coingecko.com/btc)", historyText(answer))
        assertEquals("plain", historyText(dev.kento.data.db.Message(chatId = "c", role = "assistant", text = "plain")))
    }
}
