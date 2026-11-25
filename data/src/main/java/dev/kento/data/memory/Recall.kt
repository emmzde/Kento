package dev.kento.data.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/**
 * Long-term recall that holds up after a year of chats.
 *
 * The first version asked SQLite for the 30 newest messages that shared any word with the query. With thousands of
 * chats every common word ("кот", "проект", "отель") matches hundreds of recent messages, so an old but exact answer
 * never made it into those 30: a synthetic year (2 600 chats, 23 000 messages) found 0 of 12 planted details.
 * Now every match is ranked with Okapi BM25 from FTS4 `matchinfo` (the measure Lucene and SQLite FTS5 use), rewarded
 * for covering more of the query words and only lightly for being recent. Same year, same queries: see Bench.kt.
 */
internal object Recall {
    /** Words that carry no meaning in a memory query; "пользователь" opens nearly every saved fact. */
    private val stop = setOf(
        "и", "в", "во", "на", "с", "со", "по", "за", "из", "к", "ко", "о", "об", "от", "до", "для", "что", "как", "это", "мой", "моя",
        "моё", "мое", "мои", "моего", "моей", "моих", "моему", "моим", "мою", "свой", "свою", "своего", "мне", "меня", "мы", "нас", "наш", "наша", "я", "ты", "он", "она", "они",
        "был", "была", "были", "было", "есть", "или", "но", "не", "ли", "же", "бы", "то", "так", "уже", "ещё", "еще", "какой", "какая",
        "какое", "какие", "когда", "где", "кто", "чем", "про", "пользователь", "пользователя", "пользователю",
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "my", "me", "is", "was", "are", "what", "when", "which",
        "who", "how", "did", "do", "user", "users"
    )

    /** Query words as FTS prefixes of their Snowball roots ([Stems]): "ветеринару" also finds "ветеринар". */
    fun terms(text: String, limit: Int = 10): List<String> =
        Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase()).map { it.value.take(48) }
            .filter { it !in stop }
            .map { Stems.root(it) }
            .distinct().take(limit).toList()

    fun match(terms: List<String>) = terms.joinToString(" OR ") { "$it*" }

    /**
     * BM25 score of one row from `matchinfo(table, 'pcnalx')` and how many query phrases it contains. The blob is
     * 32-bit unsigned integers in the native byte order: p, c, n, a[c], l[c], then hits-this-row, hits-all-rows and
     * rows-with-hits for each phrase and column.
     */
    fun bm25(info: ByteArray, k1: Double = 1.2, b: Double = 0.75): Pair<Double, Int> {
        val ints = ByteBuffer.wrap(info).order(ByteOrder.nativeOrder()).asIntBuffer()
        if (ints.remaining() < 3) return 0.0 to 0
        val p = ints[0]
        val c = ints[1]
        val n = ints[2].toDouble().coerceAtLeast(1.0)
        var score = 0.0
        var covered = 0
        for (phrase in 0 until p) {
            var found = false
            for (col in 0 until c) {
                val avg = ints[3 + col].toDouble().coerceAtLeast(1.0)
                val length = ints[3 + c + col].toDouble()
                val base = 3 + 2 * c + 3 * (phrase * c + col)
                val tf = ints[base].toDouble()
                val df = ints[base + 2].toDouble()
                if (tf <= 0) continue
                found = true
                val idf = ln((n - df + .5) / (df + .5) + 1)
                score += idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * length / avg))
            }
            if (found) covered++
        }
        return score to covered
    }

    /**
     * Final order of candidates: BM25, multiplied by the share of query words the row covers (a message that mentions
     * the cat, the vet and the medicine beats ten that only mention the cat), with a gentle recency bonus that
     * never outweighs a better match.
     */
    fun rank(info: ByteArray, phrases: Int, ageDays: Double): Double {
        val (score, covered) = bm25(info)
        if (covered == 0) return 0.0
        val coverage = covered.toDouble() / phrases.coerceAtLeast(1)
        return score * (0.35 + 0.65 * coverage * coverage) * (1 + 0.15 / (1 + ageDays / 30))
    }
}

/**
 * Near-duplicate check for saved facts. Two notes that share most of their meaningful word stems are the same fact told
 * twice ("бегает по утрам три раза в неделю" and "… уже несколько лет"); a year of background extraction otherwise
 * fills the profile with copies and pushes everything else out of the prompt.
 */
internal object FactSimilarity {
    fun stems(text: String): Set<String> = Recall.terms(text, limit = 40).toSet()

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }

    /**
     * What stays of a fact the user deleted: a short hash of each word root, sorted. [jaccard] of two prints is the
     * [jaccard] of the roots, so a restatement of the deleted fact is still recognised while its words are gone.
     */
    fun print(text: String): String = hashed(stems(text)).sorted().joinToString(" ")

    fun hashed(stems: Set<String>): Set<String> = stems.mapTo(mutableSetOf()) { root ->
        java.security.MessageDigest.getInstance("SHA-256").digest(root.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    }

    /** At or above this the newer note replaces the older one instead of being stored next to it. */
    const val SAME = 0.6
}
