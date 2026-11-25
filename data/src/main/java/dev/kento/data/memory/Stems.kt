package dev.kento.data.memory

/**
 * Word roots for memory: the Snowball stemmers for Russian and English (snowballstem.org, `russian.sbl` and
 * `english.sbl`, ported by hand; offline, nothing to download), so "коту" finds "кот", "ветеринару" finds
 * "ветеринар" and "running" finds "run". They replaced cutting two letters off long words.
 *
 * [root] goes one step past Snowball for Russian: a verb keeps its stem vowel ("бегаю", "бегать" → "бега") while
 * the noun has none ("бег"), so one trailing vowel of a Cyrillic stem longer than three letters is dropped and both
 * become "бег". Words in other scripts, with digits or mixed scripts stay as they are.
 */
internal object Stems {
    private val cyrillic = Regex("[а-яё]+")
    private val latin = Regex("[a-z']+")

    /** The root of one lowercase word. */
    fun root(word: String): String = when {
        cyrillic.matches(word) -> RussianStemmer.stem(word).let {
            if (it.length > 3 && it.last() in RussianStemmer.VOWELS) it.dropLast(1) else it
        }
        latin.matches(word) -> EnglishStemmer.stem(word)
        else -> word
    }
}

/** Snowball Russian, as `russian.sbl`: endings are removed only inside RV, the part after the first vowel. */
internal object RussianStemmer {
    const val VOWELS = "аеиоуыэюя"

    private val gerund1 = listOf("в", "вши", "вшись")
    private val gerund2 = listOf("ив", "ивши", "ившись", "ыв", "ывши", "ывшись")
    private val adjective = listOf("ее", "ие", "ые", "ое", "ими", "ыми", "ей", "ий", "ый", "ой", "ем", "им", "ым", "ом", "его",
        "ого", "ему", "ому", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею")
    private val participle1 = listOf("ем", "нн", "вш", "ющ", "щ")
    private val participle2 = listOf("ивш", "ывш", "ующ")
    private val reflexive = listOf("ся", "сь")
    private val verb1 = listOf("ла", "на", "ете", "йте", "ли", "й", "л", "ем", "н", "ло", "но", "ет", "ют", "ны", "ть", "ешь", "нно")
    private val verb2 = listOf("ила", "ыла", "ена", "ейте", "уйте", "ите", "или", "ыли", "ей", "уй", "ил", "ыл", "им", "ым", "ен",
        "ило", "ыло", "ено", "ят", "ует", "уют", "ит", "ыт", "ены", "ить", "ыть", "ишь", "ую", "ю")
    private val noun = listOf("а", "ев", "ов", "ие", "ье", "е", "иями", "ями", "ами", "еи", "ии", "и", "ией", "ей", "ой", "ий", "й",
        "иям", "ям", "ием", "ем", "ам", "ом", "о", "у", "ах", "иях", "ях", "ы", "ь", "ию", "ью", "ю", "ия", "ья", "я")
    private val derivational = listOf("ост", "ость")
    private val tidy = listOf("ейш", "ейше", "н", "ь")

    private fun vowel(c: Char) = c in VOWELS

    fun stem(word: String): String {
        val w = StringBuilder(word.replace('ё', 'е'))
        val n = w.length
        // RV starts after the first vowel; R2 after the second vowel-consonant pair.
        /** Snowball `gopast`: the position right after the first letter from [from] that is (not) a vowel. */
        fun past(from: Int?, isVowel: Boolean): Int? {
            var i = from ?: return null
            while (i < n && vowel(w[i]) != isVowel) i++
            return if (i < n) i + 1 else null
        }
        val rv = past(0, true) ?: n
        val r2 = past(past(past(rv, false), true), false) ?: n
        if (!w.cut(rv, gerund2, afterA = gerund1)) {
            w.cut(rv, reflexive)
            val adjectival = w.cut(rv, adjective).also { if (it) w.cut(rv, participle2, afterA = participle1) }
            if (!adjectival && !w.cut(rv, verb2, afterA = verb1)) w.cut(rv, noun)
        }
        if (w.length > rv && w.last() == 'и') w.setLength(w.length - 1)
        longest(w, r2, derivational)?.let { w.setLength(w.length - it.length) }
        when (val end = longest(w, rv, tidy)) {
            "ейш", "ейше" -> {
                w.setLength(w.length - end.length)
                if (w.length - 2 >= rv && w.endsWith("нн")) w.setLength(w.length - 1)
            }
            "н" -> if (w.length - 2 >= rv && w[w.length - 2] == 'н') w.setLength(w.length - 1)
            "ь" -> w.setLength(w.length - 1)
        }
        return w.toString()
    }

    /** The longest of [endings] that [w] ends with, lying wholly at or after [from]. */
    private fun longest(w: CharSequence, from: Int, endings: List<String>): String? =
        endings.filter { w.endsWith(it) && w.length - it.length >= from }.maxByOrNull { it.length }

    /**
     * Snowball `among`: the longest ending of both groups wins; one of [afterA] needs "а" or "я" (inside RV) right
     * before it and goes only when that holds, with no retry of a shorter ending.
     */
    private fun StringBuilder.cut(rv: Int, plain: List<String>, afterA: List<String> = emptyList()): Boolean {
        val a = longest(this, rv, afterA)
        val b = longest(this, rv, plain)
        val end = listOfNotNull(a, b).maxByOrNull { it.length } ?: return false
        val start = length - end.length
        if (end == a && (start - 1 < rv || this[start - 1] !in "ая")) return false
        setLength(start)
        return true
    }
}

/** Snowball English (Porter2), as `english.sbl`. */
internal object EnglishStemmer {
    private val exceptions = mapOf("skis" to "ski", "skies" to "sky", "idly" to "idl", "gently" to "gentl", "ugly" to "ugli",
        "early" to "earli", "only" to "onli", "singly" to "singl", "sky" to "sky", "news" to "news", "howe" to "howe",
        "atlas" to "atlas", "cosmos" to "cosmos", "bias" to "bias", "andes" to "andes")
    private val prefixes = listOf("gener", "commun", "arsen", "past", "univers", "later", "emerg", "organ", "inter")
    private val step2 = listOf("tional" to "tion", "enci" to "ence", "anci" to "ance", "abli" to "able", "entli" to "ent",
        "izer" to "ize", "ization" to "ize", "ational" to "ate", "ation" to "ate", "ator" to "ate", "alism" to "al",
        "aliti" to "al", "alli" to "al", "fulness" to "ful", "ousli" to "ous", "ousness" to "ous", "iveness" to "ive",
        "iviti" to "ive", "biliti" to "ble", "bli" to "ble", "ogist" to "og", "ogi" to "og", "fulli" to "ful", "lessli" to "less",
        "li" to "")
    private val step3 = listOf("tional" to "tion", "ational" to "ate", "alize" to "al", "icate" to "ic", "iciti" to "ic",
        "ical" to "ic", "ful" to "", "ness" to "", "ative" to "")
    private val step4 = listOf("al", "ance", "ence", "er", "ic", "able", "ible", "ant", "ement", "ment", "ent", "ism", "ate",
        "iti", "ous", "ive", "ize", "ion")
    private val doubles = listOf("bb", "dd", "ff", "gg", "mm", "nn", "pp", "rr", "tt")

    private fun vowel(c: Char) = c in "aeiouy"

    fun stem(word: String): String {
        exceptions[word]?.let { return it }
        if (word.length < 3) return word
        val w = StringBuilder(word)
        if (w.startsWith("'")) w.deleteCharAt(0)
        if (w.startsWith("y")) w.setCharAt(0, 'Y')
        for (i in 1 until w.length) if (w[i] == 'y' && vowel(w[i - 1])) w.setCharAt(i, 'Y')
        val p1 = prefixes.filter { w.startsWith(it) }.maxByOrNull { it.length }?.length ?: region(w, 0)
        val p2 = region(w, p1)
        step1a(w)
        step1b(w, p1)
        step1c(w)
        replace(w, p1, step2) { end, start ->
            when (end) {
                "ogi" -> start > 0 && w[start - 1] == 'l'
                "li" -> start > 0 && w[start - 1] in "cdeghkmnrt"
                else -> true
            }
        }
        replace(w, p1, step3) { end, start -> end != "ative" || start >= p2 }
        longest(w, step4)?.let { end ->
            val start = w.length - end.length
            if (start >= p2 && (end != "ion" || start > 0 && w[start - 1] in "st")) w.setLength(start)
        }
        if (w.endsWith("e")) {
            val start = w.length - 1
            if (start >= p2 || start >= p1 && !shortSyllable(w, start)) w.setLength(start)
        } else if (w.endsWith("l")) {
            val start = w.length - 1
            if (start >= p2 && start > 0 && w[start - 1] == 'l') w.setLength(start)
        }
        return w.toString().replace('Y', 'y')
    }

    /** Where R1 (from 0) or R2 (from R1) begins: after the first non-vowel that follows a vowel. */
    private fun region(w: CharSequence, from: Int): Int {
        var i = from
        while (i < w.length && !vowel(w[i])) i++
        while (i < w.length && vowel(w[i])) i++
        return if (i < w.length) i + 1 else w.length
    }

    private fun longest(w: CharSequence, endings: List<String>): String? =
        endings.filter { w.endsWith(it) }.maxByOrNull { it.length }

    /** The longest ending of [rules] in R1; it is replaced only when [allowed]; a shorter one is never tried. */
    private fun replace(w: StringBuilder, p1: Int, rules: List<Pair<String, String>>, allowed: (String, Int) -> Boolean) {
        val rule = rules.filter { w.endsWith(it.first) }.maxByOrNull { it.first.length } ?: return
        val start = w.length - rule.first.length
        if (start >= p1 && allowed(rule.first, start)) w.replace(start, w.length, rule.second)
    }

    /** A short syllable ends at [end]: consonant, vowel, consonant other than w, x or Y; or vowel, consonant at the start. */
    private fun shortSyllable(w: CharSequence, end: Int): Boolean =
        end >= 3 && w[end - 1] !in "aeiouywxY" && vowel(w[end - 2]) && !vowel(w[end - 3]) ||
            end == 2 && !vowel(w[1]) && vowel(w[0]) ||
            w.subSequence(0, end).endsWith("past")

    private fun step1a(w: StringBuilder) {
        longest(w, listOf("'", "'s", "'s'"))?.let { w.setLength(w.length - it.length) }
        when (val end = longest(w, listOf("sses", "ied", "ies", "s", "us", "ss"))) {
            "sses" -> w.setLength(w.length - 2)
            "ied", "ies" -> w.replace(w.length - 3, w.length, if (w.length - 3 >= 2) "i" else "ie")
            "s" -> if ((0..w.length - 3).any { vowel(w[it]) }) w.setLength(w.length - 1)
            else -> Unit
        }
    }

    private fun step1b(w: StringBuilder, p1: Int) {
        val end = longest(w, listOf("eed", "eedly", "ed", "edly", "ing", "ingly")) ?: return
        val start = w.length - end.length
        val stem = w.substring(0, start)
        when (end) {
            "eed", "eedly" -> {
                if (start >= p1 && stem !in setOf("proc", "exc", "succ")) w.replace(start, w.length, "ee")
                return
            }
            "ing" -> when {
                stem.endsWith("y") && stem.length == 2 && !vowel(stem[0]) -> { w.replace(start - 1, w.length, "ie"); return }
                stem in setOf("inn", "out", "cann", "herr", "earr", "even") -> return
            }
        }
        if (stem.none(::vowel)) return
        w.setLength(start)
        when {
            listOf("at", "bl", "iz").any { w.endsWith(it) } -> w.append('e')
            doubles.any { w.endsWith(it) } -> if (!(w.length == 3 && w[0] in "aeo")) w.setLength(w.length - 1)
            w.length == p1 && shortSyllable(w, w.length) -> w.append('e')
        }
    }

    private fun step1c(w: StringBuilder) {
        val n = w.length
        if (n > 2 && (w[n - 1] == 'y' || w[n - 1] == 'Y') && !vowel(w[n - 2])) w.setCharAt(n - 1, 'i')
    }
}
