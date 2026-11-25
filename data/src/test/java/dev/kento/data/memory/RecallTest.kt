package dev.kento.data.memory

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecallTest {
    /** A `matchinfo(…, 'pcnalx')` blob for one column: p, c, n, a, l, then (tf, total, df) per phrase. */
    private fun info(rows: Int, avg: Int, length: Int, vararg phrases: Triple<Int, Int, Int>): ByteArray {
        val ints = listOf(phrases.size, 1, rows, avg, length) + phrases.flatMap { listOf(it.first, it.second, it.third) }
        return ByteBuffer.allocate(ints.size * 4).order(ByteOrder.nativeOrder()).apply { ints.forEach { putInt(it) } }.array()
    }

    @Test fun rareCoveredTermsBeatManyCommonOnes() {
        // "кот" is everywhere, "синулокс" appears once: the message with both outranks one repeating the cat thrice.
        val needle = info(20_000, 120, 110, Triple(1, 4_000, 3_000), Triple(1, 1, 1))
        val noise = info(20_000, 120, 110, Triple(3, 4_000, 3_000), Triple(0, 1, 1))
        assertTrue(Recall.rank(needle, 2, 300.0) > Recall.rank(noise, 2, 0.0))
        assertEquals(2, Recall.bm25(needle).second)
        assertEquals(0.0, Recall.rank(info(10, 10, 10, Triple(0, 0, 0)), 1, 0.0), 0.0)
    }

    @Test fun recencyOnlyBreaksTies() {
        val same = info(1_000, 100, 100, Triple(1, 50, 40), Triple(1, 30, 20))
        assertTrue(Recall.rank(same, 2, 1.0) > Recall.rank(same, 2, 200.0))
        val better = info(1_000, 100, 100, Triple(2, 50, 40), Triple(1, 30, 20))
        assertTrue(Recall.rank(better, 2, 365.0) > Recall.rank(same, 2, 0.0))
    }

    @Test fun termsDropStopWordsAndEndings() {
        assertEquals(listOf("ветеринар", "выписыв", "кот", "лекарств"), Recall.terms("Какое лекарство ветеринар выписывал моему коту?").sorted())
        assertFalse("пользователь" in Recall.terms("Пользователь живёт в Москве"))
    }

    @Test fun restatedFactsAreTheSameAndOtherFactsAreNot() {
        fun similar(a: String, b: String) = FactSimilarity.jaccard(FactSimilarity.stems(a), FactSimilarity.stems(b))
        val base = "Пользователь бегает по утрам три раза в неделю."
        assertTrue(similar(base, "Пользователь бегает по утрам три раза в неделю уже несколько лет.") >= FactSimilarity.SAME)
        assertTrue(similar(base, "Пользователь пишет Android-приложения на Kotlin.") < 0.25)
    }

    @Test fun profileKeepsOneSlotPerFact() {
        val now = System.currentTimeMillis()
        fun fact(id: Long, text: String) = dev.kento.data.db.MemoryEntry(id = id, kind = "fact", text = text, identity = "k$id",
            importance = 3, createdAt = now)
        val facts = listOf(fact(1, "Пользователь бегает по утрам три раза в неделю."), fact(2, "Пользователь бегает по утрам три раза в неделю и любит это."),
            fact(3, "Пользователь бегает по утрам три раза в неделю — это важно."), fact(4, "Пользователь пишет на Kotlin."),
            fact(5, "Пользователя зовут Эмиль."))
        val chosen = MemoryProfile.core(facts)
        assertEquals(3, chosen.size)
        assertEquals(1, chosen.count { "бегает" in it.text })
    }
}
