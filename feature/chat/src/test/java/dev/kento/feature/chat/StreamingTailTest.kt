package dev.kento.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTailTest {
    @Test fun halfWrittenMarksAreClosedForRendering() {
        assertEquals("- **As of late 2026**", closeInline("- **As of late 2026"))
        assertEquals("see Wikipedia", closeInline("see [Wikipedia](https://en.wikipedia.org/wi"))
        assertEquals("code `x`", closeInline("code `x"))
        assertEquals("**done** and *open*", closeInline("**done** and *open"))
        assertEquals("plain text", closeInline("plain text"))
    }
}
