package dev.kento.data.media

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class PhrasesTest {
    private fun tone(seconds: Double) = FloatArray((16000 * seconds).toInt()) { (0.3 * sin(it * 0.2)).toFloat() }
    private fun silence(seconds: Double) = FloatArray((16000 * seconds).toInt())
    private fun join(vararg parts: FloatArray) = parts.reduce { a, b -> a + b }
    private fun seconds(parts: List<FloatArray>) = parts.map { Math.round(it.size / 1600.0) / 10.0 }

    @Test fun pausesSeparatePhrasesSoEachGetsItsOwnLanguage() {
        val parts = OfflineSpeech.phrases(join(silence(0.5), tone(4.0), silence(0.8), tone(6.0), silence(0.8), tone(3.0), silence(1.0)))
        assertEquals(3, parts.size)
        assertEquals(listOf(4.4, 6.4, 3.4), seconds(parts))
    }

    @Test fun shortFragmentsJoinTheirNeighbour() {
        val parts = OfflineSpeech.phrases(join(tone(3.0), silence(0.6), tone(0.5), silence(0.6), tone(3.0)))
        assertEquals(2, parts.size)
    }

    @Test fun longSpeechIsSplitWithinTheWhisperWindow() {
        val parts = OfflineSpeech.phrases(tone(60.0))
        assertEquals(3, parts.size)
        assertEquals(true, parts.all { it.size <= 16000 * 28 })
    }

    @Test fun silenceOnlyIsPassedThrough() {
        assertEquals(1, OfflineSpeech.phrases(silence(5.0)).size)
    }
}
