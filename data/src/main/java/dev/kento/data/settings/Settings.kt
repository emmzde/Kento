package dev.kento.data.settings

import dev.kento.core.text.tr

import kotlinx.serialization.Serializable

/** The only text provider: chat, memory, titles and search all run on Refuseless. */
const val REFUSELESS_URL = "https://api.refuseless.com/v1"
/** The only image provider. */
const val OPENUNCENSORED_URL = "https://api.openuncensored.com/v1"

/** Reasoning levels Refuseless accepts, from none to the deepest; any other value is rejected with 400. */
val EFFORT_ORDER = listOf("off", "low", "high", "max")
/**
 * The level a chat request carries when the user has not picked one. Refuseless itself defaults to "low", which
 * left the models on their smallest thinking budget; a conversation deserves the model's upper tier.
 */
const val CHAT_EFFORT = "high"
/**
 * Output cap of a chat request when the user sets none, by reasoning level (see [Settings.maxTokens]). Refuseless
 * reserves prompt + max_tokens on the balance before each call; without a cap that is the model's whole output
 * window, and a small balance then refuses the request. Only the tokens the model writes are paid for, so the cap
 * only has to stay clear of real replies: on the conversation bench DeepSeek V4.1 Flash wrote 14 973
 * tokens (12 504 of them thinking) for one tangled question at "high" against the former 16K cap, and 6 249 in one
 * request at "low" against 8K. A reply cut by the cap is paid for and still fails, so the caps are twice that.
 */
val CHAT_MAX_TOKENS = mapOf("off" to 8_192, "low" to 16_384, "high" to 32_768, "max" to 65_536)
/** The chat memory setting: tokens of history per request; [HISTORY_CHOICES] are what "Advanced" offers. */
const val HISTORY_TOKENS = 100_000
val HISTORY_CHOICES = listOf(50_000, 100_000, 200_000)
/** After a compression this share of [Settings.historyTokens] stays word for word: 100K folds down to 50K. */
const val HISTORY_KEEP = 0.5
/** Bumped when the catalogue parser learns new fields; older saved catalogues are fetched again once. */
const val CATALOG_VERSION = 4
/** Prices change (weekly sale days); a catalogue older than this is fetched again quietly. */
const val CATALOG_MAX_AGE_MS = 24L * 60 * 60 * 1000
/** The format of [ProfileBook]: 2 is the single Refuseless connection. Older books are migrated by [decodeBook]. */
const val BOOK_VERSION = 2
/**
 * Background work (titles, facts, chat summaries) when the user picked no model: abliterated weights that accept
 * reasoning "off", $0.12 / $0.45 per 1M. The standard-weight deepseek-v4.1-flash-fast is 12 times cheaper but keeps
 * refusals, so it is a manual choice.
 */
val BACKGROUND_MODELS = listOf("deepseek-v4.1-flash-abliterated-fast", "deepseek-v4.1-flash-abliterated")

/** Sampling parameters sent with chat requests; null leaves the provider's own default. */
@Serializable
data class Sampling(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val repetitionPenalty: Double? = null,
    val seed: Long? = null
) {
    fun isDefault() = this == Sampling()
}

/** Rates above [threshold] prompt tokens; they then apply to the whole request, output included. */
@Serializable
data class LongContext(val threshold: Long, val priceIn: Double, val priceCached: Double, val priceOut: Double)

/** One model of the Refuseless catalogue (`GET /v1/models`). */
@Serializable
data class ModelOption(
    val id: String,
    /** Levels the model accepts, in [EFFORT_ORDER]; empty when the catalogue sets none. */
    val efforts: List<String> = emptyList(),
    val name: String = "",
    val contextLength: Int? = null,
    val vision: Boolean? = null,
    /** False for standard weights, which keep the base model's refusals (deepseek-v4.1-flash-fast, minimax). */
    val abliterated: Boolean = true,
    /** The level Refuseless applies when the request sets none. */
    val defaultEffort: String? = null,
    /** USD per million tokens for this account, discount already applied. */
    val priceIn: Double? = null,
    val priceOut: Double? = null,
    val priceCached: Double? = null,
    val discounted: Boolean = false,
    val longContext: LongContext? = null,
    val available: Boolean = true
) {
    fun supportsThinking() = efforts.any { it != "off" }
}

@Serializable
data class Settings(
    val apiKey: String = "",
    val model: String = "",
    /** The background model: chat titles, fact extraction and chat summaries. Cheap and without reasoning. */
    val memoryModel: String = "",
    /** Persona block of the system prompt: tone and character. Kept under its old name for stored profiles. */
    val systemPrompt: String = "",
    /** Role and format block: what Kento is for this user, answer length, Markdown habits. */
    val promptFormat: String = "",
    /** Values block: the user's principles and motivation that answers should respect. */
    val promptValues: String = "",
    /** Adds the current date and time to each request (at its end, so provider prompt caching keeps working). */
    val promptClock: Boolean = true,
    /** Reasoning level per model ID; a model without an entry thinks at [CHAT_EFFORT] (see [effort]). */
    val efforts: Map<String, String> = emptyMap(),
    val catalog: List<ModelOption> = emptyList(),
    val catalogLoaded: Boolean = false,
    val catalogVersion: Int = 0,
    /** When the catalogue was read, in epoch milliseconds; see [CATALOG_MAX_AGE_MS]. */
    val catalogAt: Long = 0,
    val id: String = "initial",
    val name: String = "",
    val visionOverrides: Map<String, Boolean> = emptyMap(),
    /** Sampling per model ID, so each model keeps the values that suit it. */
    val sampling: Map<String, Sampling> = emptyMap(),
    /**
     * Tokens of chat history (the summary of compressed messages plus the rest word for word) each request may carry.
     * Up to this the whole chat goes verbatim; past it the oldest part is folded into a summary (see [HISTORY_KEEP]).
     * Replaced the message count `historyLimit`, which a stored book still has and decoding ignores.
     */
    val historyTokens: Int = HISTORY_TOKENS
) {
    /** Every request goes to Refuseless; the address is not a setting any more. */
    val baseUrl: String get() = REFUSELESS_URL

    fun sampling(id: String = model) = sampling[id] ?: Sampling()
    fun withSampling(value: Sampling, id: String = model) =
        copy(sampling = if (value.isDefault()) sampling - id else sampling + (id to value))

    fun modelIds() = (catalog.map { it.id } + listOf(model, memoryModel)).filter(String::isNotBlank).distinct()
    fun option(id: String = model) = catalog.firstOrNull { it.id == id }

    /** Levels the catalogue publishes for the model, in [EFFORT_ORDER]; never invented. */
    fun levels(id: String = model): List<String> = option(id)?.efforts.orEmpty()
        .map { if (it == "none") "off" else it }.filter { it in EFFORT_ORDER }.distinct().sortedBy(EFFORT_ORDER::indexOf)

    /**
     * The level a chat request carries: the user's choice when the model offers it, else [CHAT_EFFORT], else the
     * model's own default, else its deepest level. Null only for a model the catalogue lists no levels for.
     */
    fun effort(id: String = model): String? {
        val levels = levels(id).ifEmpty { return null }
        efforts[id]?.takeIf { it in levels }?.let { return it }
        return CHAT_EFFORT.takeIf { it in levels } ?: option(id)?.defaultEffort?.takeIf { it in levels } ?: levels.last()
    }

    /** Background work (titles, facts, summaries): no reasoning where the model allows it, else the lightest level. */
    fun quietEffort(id: String = model): String? = levels(id).let { l ->
        when { l.isEmpty() -> null; "off" in l -> "off"; else -> l.first() }
    }

    /**
     * The output cap of a chat request at reasoning [level]: the user's own value from "Advanced" wins, else
     * [CHAT_MAX_TOKENS] for the level. A model without levels thinks at the provider's default "low".
     */
    fun maxTokens(level: String? = effort(), id: String = model): Int =
        sampling(id).maxTokens ?: CHAT_MAX_TOKENS[level] ?: CHAT_MAX_TOKENS.getValue("low")

    fun withEffort(level: String, id: String = model) = copy(efforts = efforts + (id to level))

    fun select(id: String, memory: Boolean = false): Settings = if (memory) copy(memoryModel = id) else copy(model = id)

    /**
     * The model for background work: the user's choice, else the first of [BACKGROUND_MODELS] in the catalogue
     * (abliterated, so a frank chat is still read, and able to switch reasoning off), else the chat model.
     */
    fun background(): String = memoryModel.ifBlank { BACKGROUND_MODELS.firstOrNull { option(it) != null } ?: model }

    /** A catalogue to read again: never loaded, saved by an older parser, or older than a day (prices move). */
    fun catalogStale(now: Long = System.currentTimeMillis()) =
        !catalogLoaded || catalogVersion < CATALOG_VERSION || now - catalogAt > CATALOG_MAX_AGE_MS
}

const val DUCKDUCKGO = "DuckDuckGo Lite"
const val AUTO_SEARCH = "Автоматически"

@Serializable
data class WebSettings(
    val engine: String = AUTO_SEARCH,
    /** Lets a model with tool support decide on its own when to search and which pages to read. */
    val modelDecides: Boolean = true,
    val instance: String = "",
    val tavilyKey: String = "",
    val braveKey: String = "",
    val serperKey: String = "",
    val exaKey: String = "",
    /**
     * Paid API searches (Tavily, Brave, Serper, Exa) one answer may make; 0 means no limit. Further searches of the
     * answer use DuckDuckGo or SearXNG and the full pages the paid search already returned.
     */
    val paidSearches: Int = 1
)

@Serializable
data class MemorySettings(
    /** The model sees the most relevant saved facts and may look up memories through a tool. */
    val enabled: Boolean = true,
    /**
     * Besides the core of the dossier (always there), at most this many facts that match the message go into each
     * request; the rest stay reachable through memory_search.
     */
    val factsInContext: Int = 6,
    /**
     * The background model reads each finished reply and saves the facts the user shared. It replaced the
     * memory_write tool of the chat model, whose every call cost one more full round at the chat's reasoning level.
     */
    val background: Boolean = true
)

/**
 * Cloud dictation through an OpenAI-compatible `audio/transcriptions` endpoint (Groq, OpenAI, a local whisper server).
 * Empty means the phone's own recognizer and the offline Whisper model are used, as before.
 */
@Serializable
data class SpeechEndpoint(val baseUrl: String = "", val apiKey: String = "", val model: String = "") {
    val configured get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/** Pictures are created and edited through OpenUncensored only; the key is all there is to set. */
@Serializable
data class ImageEndpoint(
    val apiKey: String = "",
    val catalog: List<dev.kento.data.net.ImageModel> = emptyList(),
    val generationModel: String = "",
    val editModel: String = ""
) {
    val baseUrl: String get() = OPENUNCENSORED_URL
    val configured get() = apiKey.isNotBlank()
    /** The selected model for a request with [references] photos, or the first that accepts them. */
    fun option(references: Int): dev.kento.data.net.ImageModel? {
        val selected = if (references == 0) generationModel else editModel
        val options = catalog.filter { it.accepts(references) }
        return options.find { it.id == selected } ?: options.firstOrNull()
    }
}

@Serializable
data class ProfileBook(
    /** Exactly one connection since [BOOK_VERSION] 2; the list is kept for the stored format and backups. */
    val profiles: List<Settings> = listOf(Settings()),
    val activeId: String = "initial",
    val web: WebSettings = WebSettings(),
    val memory: MemorySettings = MemorySettings(),
    /** Colour of the user's own chat bubbles; an ID from the interface palette, unknown values fall back to blue. */
    val bubble: String = "blue",
    val images: ImageEndpoint = ImageEndpoint(),
    val speech: SpeechEndpoint = SpeechEndpoint(),
    val version: Int = BOOK_VERSION
) {
    val active get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    fun replace(s: Settings) = copy(profiles = listOf(s), activeId = s.id)
}

private fun usd(value: Double): String =
    "$" + java.math.BigDecimal.valueOf(value).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString()

/** One line per model for pickers: vision, reasoning, refusals kept, price per million tokens and availability. */
fun Settings.modelDetails(): Map<String, String> = catalog.associate { m ->
    m.id to listOfNotNull(
        tr("фото").takeIf { m.vision == true },
        tr("рассуждения").takeIf { m.supportsThinking() },
        tr("с отказами").takeIf { !m.abliterated },
        if (m.priceIn != null && m.priceOut != null) tr("{0} / {1} за 1M", usd(m.priceIn), usd(m.priceOut)) else null,
        tr("скидка").takeIf { m.discounted },
        tr("недоступна").takeIf { !m.available }
    ).joinToString(" · ")
}
