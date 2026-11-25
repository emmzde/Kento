package dev.kento.data.net

import dev.kento.data.settings.ModelOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What requests cost. [charged] is money the provider itself reported (OpenUncensored `cost_usd` for a picture);
 * [estimated] is the catalogue price applied to the token counts Refuseless reported. [exact] of the [requests] had a
 * provider figure; [guessed] reported no tokens either, so Kento counted their text itself.
 */
@Serializable
data class Usage(
    val input: Long = 0, val output: Long = 0, val cached: Long = 0, val reasoning: Long = 0,
    val charged: Double = 0.0, val estimated: Double = 0.0,
    val requests: Int = 0, val exact: Int = 0, val unpriced: Int = 0, val guessed: Int = 0
) {
    operator fun plus(o: Usage) = Usage(input + o.input, output + o.output, cached + o.cached, reasoning + o.reasoning,
        charged + o.charged, estimated + o.estimated, requests + o.requests, exact + o.exact, unpriced + o.unpriced, guessed + o.guessed)
    val cost get() = charged + estimated
    /** Every request carried the provider's own figure: this is what left the key, not an estimate. */
    val precise get() = requests > 0 && exact == requests
    fun encode(): String = if (requests == 0) "" else usageJson.encodeToString(this)

    companion object {
        fun decode(raw: String): Usage = if (raw.isBlank()) Usage() else runCatching { usageJson.decodeFromString<Usage>(raw) }.getOrDefault(Usage())
    }
}

private val usageJson = Json { ignoreUnknownKeys = true }

/** Token counts one response reported, before the price list is applied. */
data class Reported(val input: Long, val output: Long, val cached: Long, val reasoning: Long)

private fun JsonObject.count(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

/**
 * The `usage` of a Refuseless chunk: `prompt_tokens`, `completion_tokens`, `prompt_tokens_details.cached_tokens` and
 * `completion_tokens_details.reasoning_tokens`. It carries no money; the price comes from the catalogue.
 */
internal fun reportedUsage(packet: JsonObject): Reported? {
    val usage = packet["usage"] as? JsonObject ?: return null
    val input = usage.count("prompt_tokens") ?: 0
    val output = usage.count("completion_tokens") ?: 0
    if (input == 0L && output == 0L) return null
    val cached = (usage["prompt_tokens_details"] as? JsonObject)?.count("cached_tokens") ?: 0
    val reasoning = (usage["completion_tokens_details"] as? JsonObject)?.count("reasoning_tokens") ?: 0
    return Reported(input, output, cached, reasoning)
}

/** One chat request as [Usage]: the catalogue price of the tokens Refuseless reported, or of Kento's own count. */
internal fun priced(r: Reported?, option: ModelOption?, guessIn: Long, guessOut: Long): Usage {
    val input = r?.input ?: guessIn
    val output = r?.output ?: guessOut
    val cached = (r?.cached ?: 0).coerceAtMost(input)
    // Above the long-context threshold its rates apply to the whole request, output included (Refuseless pricing).
    val long = option?.longContext?.takeIf { input > it.threshold }
    val priceIn = long?.priceIn ?: option?.priceIn
    val priceOut = long?.priceOut ?: option?.priceOut
    val priceCached = long?.priceCached ?: option?.priceCached ?: priceIn
    val money = if (priceIn != null && priceOut != null && priceCached != null)
        ((input - cached) * priceIn + cached * priceCached + output * priceOut) / 1_000_000.0 else null
    return Usage(input, output, cached, r?.reasoning ?: 0, estimated = money ?: 0.0, requests = 1,
        unpriced = if (money == null) 1 else 0, guessed = if (r == null) 1 else 0)
}

/**
 * Collects the usage of every request made inside a coroutine: a reply's tool rounds, its search query rewrites and the
 * chat title all land in the meter of the reply that caused them.
 */
class UsageMeter : AbstractCoroutineContextElement(UsageMeter) {
    companion object Key : CoroutineContext.Key<UsageMeter>
    private val total = AtomicReference(Usage())
    private val each = java.util.concurrent.ConcurrentLinkedQueue<Usage>()
    fun add(usage: Usage) { total.updateAndGet { it + usage }; each += usage }
    val value: Usage get() = total.get()
    /** Every request on its own, in the order they finished; the bench reads the input of the last round from it. */
    val parts: List<Usage> get() = each.toList()
}

internal suspend fun meter(): UsageMeter? = currentCoroutineContext()[UsageMeter]

/**
 * Catches `usage.prompt_tokens` of the first chat request made inside a coroutine: the request that carried the
 * bare history, before any tool round added results. The chat keeps it to count its history in the provider's tokens.
 */
class PromptProbe : AbstractCoroutineContextElement(PromptProbe) {
    companion object Key : CoroutineContext.Key<PromptProbe>
    private val first = AtomicReference<Long?>(null)
    fun offer(tokens: Long) { if (tokens > 0) first.compareAndSet(null, tokens) }
    val tokens: Long? get() = first.get()
}
