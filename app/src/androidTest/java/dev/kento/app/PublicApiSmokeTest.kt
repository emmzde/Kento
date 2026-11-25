package dev.kento.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import dev.kento.data.net.Provider
import dev.kento.data.settings.Settings
import dev.kento.data.settings.WebSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PublicApiSmokeTest {
    @Test fun publicCatalogAndSearchFromDevice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicApis") == "true")
        // The Refuseless catalogue is public: every model there publishes its reasoning levels.
        val catalog = Provider().models(Settings())
        assertTrue(catalog.isNotEmpty())
        assertTrue(catalog.all { it.efforts.isNotEmpty() })
        assertTrue(Provider().images.models(dev.kento.data.settings.ImageEndpoint()).any { it.accepts(0) })

        // Without a single key the search still finds and reads a page (DuckDuckGo Lite
        // over HTTP, Wikipedia next to it; the WebView tab only if DuckDuckGo asks for a check).
        val web = ApplicationProvider.getApplicationContext<TestApp>().web
        val hits = web.search(WebSettings(), "Android operating system")
        assertTrue("Поиск без ключей вернул пустую выдачу", hits.isNotEmpty())
        val page = hits.take(3).firstNotNullOfOrNull { hit -> runCatching { web.read(hit, "Android") }.getOrNull()?.takeIf { it.text.length >= 50 } }
        assertNotNull("Ни одна из трёх найденных страниц не прочиталась", page)
    }
}
