package dev.kento.feature.chat

import org.junit.Assert.*
import org.junit.Test

class MathTextTest {
    @Test fun formulasAreLiftedOutOfMarkdown() {
        val lifted = liftMath("Площадь круга \$S = \\pi r^2\$, а корни:\n\n\$\$x = \\frac{-b \\pm \\sqrt{D}}{2a}\$\$\n\nИ ещё \\(a_1\\) и \\[e^{i\\pi}\\]")
        assertEquals(listOf("S = \\pi r^2" to false, "x = \\frac{-b \\pm \\sqrt{D}}{2a}" to true, "a_1" to false, "e^{i\\pi}" to true),
            lifted.formulas.map { it.latex to it.display })
        assertFalse(lifted.text.contains("\\frac"))
        assertEquals(4, mathMark.findAll(lifted.text).count())
    }

    @Test fun moneyAndCodeStayText() {
        for (text in listOf("Цена от $5 до $10 в месяц", "Курс $58,237, вчера $60k", "Сумма: $ 100 и $ 200",
            "Экранированный \\$5 и \\$6")) {
            assertTrue(text, liftMath(text).formulas.isEmpty())
        }
        val code = "Пример `echo \$HOME \$PATH` и\n```bash\nexport A=\$B\$C\n```\nи \$x\$"
        val lifted = liftMath(code)
        assertEquals(listOf("x"), lifted.formulas.map { it.latex })
        assertTrue(lifted.text.contains("`echo \$HOME \$PATH`"))
        assertTrue(lifted.text.contains("export A=\$B\$C"))
    }

    @Test fun unparsableLatexReadsAsText() {
        assertEquals("(a+b)/(2) ≤ √(ab)", plainMath("\\frac{a+b}{2} \\le \\sqrt{ab}"))
        assertEquals("x² + y₁ · α", plainMath("x^2 + y_1 \\cdot \\alpha"))
        assertEquals("скорость v", plainMath("\\text{скорость } v"))
    }

    @Test fun streamingHoldsBackAnOpenFormula() {
        assertEquals(6, unclosedMath("Итого $$\\frac{1}{"))
        assertNull(unclosedMath("Итого \$\$x\$\$ готово"))
        assertEquals(3, unclosedMath("Да \\[x"))
    }
}
