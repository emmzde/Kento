package dev.kento.data.memory

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Snowball stemmers instead of cutting two letters off. The samples are
 * every 40th word of the official snowball-data vocabularies (github.com/snowballstem/snowball-data) with the stem
 * the reference implementation gives. With SNOWBALL_DATA pointing at a folder of `russian-voc.txt`,
 * `russian-out.txt`, `english-voc.txt` and `english-out.txt`, the whole vocabularies are checked.
 */
class StemsTest {
    private fun pairs(lines: List<String>) = lines.filter(String::isNotBlank).map { it.trim().split(' ').let { p -> p[0] to p[1] } }

    private fun check(pairs: List<Pair<String, String>>, stem: (String) -> String) {
        val wrong = pairs.filter { stem(it.first) != it.second }.map { "${it.first}: ${stem(it.first)} != ${it.second}" }
        assertTrue("${wrong.size} of ${pairs.size} differ: ${wrong.take(20)}", wrong.isEmpty())
    }

    private fun sample(language: String) = pairs(javaClass.getResource("/snowball/$language.txt")!!.readText().lines())

    @Test fun russianMatchesTheReferenceStemmer() = check(sample("russian"), RussianStemmer::stem)

    @Test fun englishMatchesTheReferenceStemmer() = check(sample("english"), EnglishStemmer::stem)

    @Test fun wholeVocabulariesWhenGiven() {
        val dir = System.getenv("SNOWBALL_DATA")?.let(::File)?.takeIf { it.isDirectory } ?: return
        for ((language, stem) in listOf<Pair<String, (String) -> String>>("russian" to RussianStemmer::stem, "english" to EnglishStemmer::stem)) {
            val words = File(dir, "$language-voc.txt").readLines()
            val stems = File(dir, "$language-out.txt").readLines()
            check(words.zip(stems).filter { it.first.isNotBlank() }, stem)
        }
    }

    @Test fun verbAndNounShareTheirRoot() {
        assertEquals("бег", Stems.root("бегаю"))
        assertEquals(Stems.root("бег"), Stems.root("бегаю"))
        assertEquals(Stems.root("бегать"), Stems.root("бегом"))
        assertEquals(Stems.root("работаю"), Stems.root("работа"))
        assertEquals("кот", Stems.root("коту"))
        assertEquals("ветеринар", Stems.root("ветеринару"))
        assertEquals(Stems.root("running"), Stems.root("runs"))
        // Digits, mixed scripts and short stems stay as they are.
        assertEquals("v2", Stems.root("v2"))
        assertEquals("три", Stems.root("три"))
    }

    @Test fun termsAndSimilarityUseTheRoots() {
        assertEquals(listOf("ветеринар", "выписыв", "кот", "лекарств"), Recall.terms("Какое лекарство ветеринар выписывал моему коту?").sorted())
        assertEquals(FactSimilarity.stems("Я бегаю по утрам"), FactSimilarity.stems("Бег по утрам"))
    }
}
