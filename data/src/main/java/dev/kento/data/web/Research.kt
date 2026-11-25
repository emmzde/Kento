package dev.kento.data.web

import dev.kento.data.memory.backgroundBudget
import dev.kento.data.settings.Settings
import dev.kento.data.settings.WebSettings

/**
 * Bytes one page read may return and bytes the whole research of one answer may add. Fixed caps: a million-token
 * window does not make the pages of one answer larger, since every later round of the answer sends them again.
 */
internal fun webBudgets(s: Settings): Pair<Int, Int> =
    (backgroundBudget(s) / 4).coerceIn(4_000, 12_000) to (backgroundBudget(s) * 2).coerceIn(24_000, 48_000)

/**
 * Makes the web tools of one answer. The model researches itself through search_web and read_webpage in ToolLoop;
 * the old pipeline that rewrote the question and ranked pages for it (collectSources) is gone.
 */
class Research(private val access: SearchAccess) {
    /**
     * search_web and read_webpage; [WebTools.sources] lists what the answer was built on. One read returns at most
     * 4-12 KB cut to the model's focus. [allowed] is false while web_search is "disabled": the tools are declared all
     * the same and refuse.
     */
    fun tools(web: WebSettings, s: Settings, trail: TrailRecorder? = null, allowed: Boolean = true, peek: Int = 3): WebTools =
        // The whole research of one answer may add 24-48 KB (about 7-14 thousand tokens): enough for several searches and
        // a few page reads, while every later round of the answer sends it again.
        webBudgets(s).let { (page, total) ->
            WebTools(access, web, page, totalBytes = total, trail = trail, peek = peek, allowed = allowed)
        }
}
