package dev.kento.core.text

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class I18nTest {
    @After fun russian() = I18n.force(I18n.RU)

    @Test fun argumentsFillThePattern() {
        assertEquals("Выбрано: 3", tr("Выбрано: {0}", 3))
        I18n.force(I18n.EN)
        assertEquals("Selected: 3", tr("Выбрано: {0}", 3))
        assertEquals("Delete 2 chats?", tr("Удалить чаты: {0}?", 2))
        // No entry: the Russian original rather than an empty label.
        assertEquals("Нет такого ключа", tr("Нет такого ключа"))
        assertEquals("{x} {9}", tr("{x} {9}", "a"))
    }

    /** Every tr("…") in the app has an English entry, so the English interface never falls back to Russian. */
    @Test fun everyInterfaceStringIsTranslated() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val call = Regex("""\btr\("((?:[^"\\]|\\.)*)"""")
        val missing = mutableSetOf<String>()
        // Hidden folders (VCS, IDE, nested worktrees) and build outputs are not this source tree.
        root.walkTopDown().onEnter { !it.name.startsWith(".") && it.name != "build" }
            .filter { it.extension == "kt" && "${File.separator}src${File.separator}main${File.separator}" in it.path }
            .forEach { file ->
                call.findAll(file.readText()).forEach { match ->
                    val key = match.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").replace("\\$", "$").replace("\\\\", "\\")
                    if (key !in English.strings) missing += "${file.name}: $key"
                }
            }
        assertTrue("No English for:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /**
     * The reverse: every English entry belongs to a string the app still has, so removed screens and providers do not
     * leave their translations behind. A key passed to tr() through a constant (search engines, attachment statuses)
     * is a string literal somewhere in the sources too.
     */
    @Test fun everyTranslationBelongsToAStringOfTheApp() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val literal = Regex(""""((?:[^"\\\n]|\\.)*)"""")
        val strings = mutableSetOf<String>()
        root.walkTopDown().onEnter { !it.name.startsWith(".") && it.name != "build" }
            .filter { it.extension == "kt" && it.name != "English.kt" && "${File.separator}src${File.separator}main${File.separator}" in it.path }
            .forEach { file ->
                literal.findAll(file.readText()).forEach { match ->
                    strings += match.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").replace("\\$", "$").replace("\\\\", "\\")
                }
            }
        val orphans = English.strings.keys.filter { it !in strings }
        assertTrue("English entries without a string in the app:\n" + orphans.joinToString("\n"), orphans.isEmpty())
    }
}
