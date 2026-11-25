package dev.kento.data.net

import dev.kento.data.settings.ImageEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * One live picture through OpenUncensored with the key from `OPENUNCENSORED_API_KEY` or the project's `.env`: the
 * catalogue, a generation on the cheapest model ($0.01 at the time of writing) and the balance before and after.
 * It spends money, so it runs only with `KENTO_LIVE=1` and `KENTO_LIVE_IMAGES=1`.
 */
class OpenUncensoredLiveTest {
    private val key = System.getenv("OPENUNCENSORED_API_KEY")?.takeIf(String::isNotBlank) ?: listOf(File(".env"), File("../.env"))
        .firstOrNull(File::exists)?.readLines()?.firstOrNull { it.startsWith("OPENUNCENSORED_API_KEY=") }
        ?.substringAfter('=')?.trim()?.split(Regex("\\s+"))?.firstOrNull()

    @Test fun cheapestModelDrawsAPictureAndReportsItsPrice(): Unit = runBlocking {
        assumeTrue("set KENTO_LIVE=1 and KENTO_LIVE_IMAGES=1", System.getenv("KENTO_LIVE") == "1" && System.getenv("KENTO_LIVE_IMAGES") == "1")
        assumeTrue("no OPENUNCENSORED_API_KEY", key != null)
        val api = Provider()
        val endpoint = ImageEndpoint(apiKey = key!!)
        val catalog = api.images.models(endpoint)
        val model = catalog.filter { it.generate && it.priceUsd != null }.minBy { it.priceUsd!! }
        val before = api.balances.images(endpoint)
        val file = File.createTempFile("kento-live", ".img")
        val started = System.currentTimeMillis()
        val billed = api.images.create(endpoint, model, "A small red paper boat on calm water, soft morning light", emptyList(), file)
        val after = api.balances.images(endpoint)
        println("live image ${model.id}: ${file.length()} bytes in ${System.currentTimeMillis() - started} ms, billed $billed, " +
            "listed ${model.priceUsd}, balance ${before?.amount} -> ${after?.amount}, catalogue ${catalog.size} models")
        assertTrue("empty picture", file.length() > 10_000)
        file.delete()
    }
}
