package dev.kento.core.text

import android.content.Context
import java.util.Locale

/**
 * Interface language. Russian is the source language of every string in the code; English comes from [English].
 * "auto" follows the phone: Russian when the system language is Russian (or Ukrainian, Belarusian, Kazakh),
 * English otherwise. The choice lives in plain preferences (it is not secret) and is read once at start, so a
 * change restarts the app and every screen, menu and message is drawn in the new language.
 */
object I18n {
    const val AUTO = "auto"
    const val RU = "ru"
    const val EN = "en"
    private const val PREFS = "kento.ui"
    private const val KEY = "language"

    @Volatile var english = false
        private set

    fun init(context: Context) {
        val chosen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO
        english = resolve(chosen) == EN
    }

    fun choice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    /** Saves the choice; the caller restarts the app so it takes effect everywhere. */
    fun choose(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).commit()
    }

    /** Tests pin the language so their Russian expectations do not depend on the emulator locale. */
    fun force(language: String) { english = resolve(language) == EN }

    internal fun resolve(choice: String): String = when (choice) {
        RU, EN -> choice
        else -> if (Locale.getDefault().language in setOf("ru", "uk", "be", "kk")) RU else EN
    }

    internal fun format(pattern: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return pattern
        val out = StringBuilder(pattern.length + 16)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '{') {
                val close = pattern.indexOf('}', i + 1)
                val index = if (close > i + 1) pattern.substring(i + 1, close).toIntOrNull() else null
                if (index != null && index in args.indices) {
                    out.append(args[index])
                    i = close + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}

/**
 * The text in the interface language. [ru] is the Russian original and the key; `{0}`, `{1}`… take [args].
 * A string without an English entry stays Russian rather than going blank.
 */
fun tr(ru: String, vararg args: Any?): String =
    I18n.format(if (I18n.english) English.strings[ru] ?: ru else ru, args)
