package dev.kento.data.net

import dev.kento.data.settings.ImageEndpoint
import dev.kento.data.settings.REFUSELESS_URL
import dev.kento.data.settings.Settings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** What a new request can still spend, in the provider's currency; [spent] is what this key has used, where told. */
data class Balance(val amount: Double, val currency: String = "USD", val spent: Double? = null)

/**
 * The two balances Kento can show: Refuseless `GET /v1/credits` (free, no hold) and OpenUncensored `GET /v1/credits`.
 * A few seconds each, only when the user opens the spending sheet. Null when the key is missing or the call fails.
 */
class BalanceProbe(client: OkHttpClient, private val textBase: () -> String = { REFUSELESS_URL },
    private val imageBase: () -> String = { dev.kento.data.settings.OPENUNCENSORED_URL }) {
    private val quick = client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()

    suspend fun text(s: Settings): Balance? = if (s.apiKey.isBlank()) null else fetch(textBase() + "/credits", s.apiKey)
    suspend fun images(endpoint: ImageEndpoint): Balance? =
        if (!endpoint.configured) null else fetch(imageBase() + "/credits", endpoint.apiKey)

    private suspend fun fetch(address: String, key: String): Balance? {
        val target = address.toHttpUrlOrNull() ?: return null
        val call = quick.newCall(Request.Builder().url(target).header("Accept", "application/json").apply { authorize(this, key) }.build())
        val body = suspendCancellableCoroutine<String?> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(null)
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(runCatching { response.use { if (it.isSuccessful) it.peekBody(65_536).string() else null } }.getOrNull())
                }
            })
        } ?: return null
        return runCatching { readBalance(wireJson.parseToJsonElement(body)) }.getOrNull()
    }
}

private fun JsonElement?.amount(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

/**
 * Refuseless `{"object":"credits","available":41.3,"balance":42.5,"key_spent":3.18,"unit":"usd"}` (available is what
 * a new request can reserve; balance also counts the holds of requests in flight) and OpenUncensored
 * `{"object":"credits","data":{"remaining_usd":2.04}}`.
 */
internal fun readBalance(root: JsonElement): Balance? {
    val top = root as? JsonObject ?: return null
    top["available"].amount()?.let { return Balance(it, (top["unit"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "USD", top["key_spent"].amount()) }
    val data = top["data"] as? JsonObject
    (data?.get("remaining_usd") ?: top["remaining_usd"]).amount()?.let { return Balance(it) }
    return null
}
