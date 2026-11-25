package dev.kento.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dev.kento.data.net.Provider
import dev.kento.data.web.*
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class KentoTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestApp::class.java.name, context)
}
class TestApp : KentoApp() {
    override fun onCreate() {
        super.onCreate()
        // Journeys check Russian texts whatever the emulator's locale.
        dev.kento.core.text.I18n.force(dev.kento.core.text.I18n.RU)
        // The one-time notification question is a system dialog that would cover the screen under test.
        getSharedPreferences("kento.ui", MODE_PRIVATE).edit().putBoolean("askedNotifications", true).commit()
    }
    /** Background fact reading would take mock replies queued for the chat; the memory journey schedules it itself. */
    override fun scheduleMemory() {}
    /** Each test starts from a fresh screen; a reply left running by the previous test must not leak into it. */
    override val keepRepliesAfterScreen = false
    /** Journeys start in the chat; the welcome screen has its own check. */
    override val showWelcome = false
    /** Journeys queue exact mock replies; an extra title request after the first answer would take one of them. */
    override val nameChat: suspend (dev.kento.data.settings.Settings, String) -> String? = { _, _ -> null }
    @Volatile var searchHtml: String? = null
    @Volatile var fixturePage: ReadPage? = null
    override val web: SearchAccess by lazy {
        // DuckDuckGo is asked over HTTP first; the fixture page answers there as well, so a bot-check fixture still
        // sends the search on to the WebView tab, as a real check does.
        val network = WebAccess(searchBrowser, duckHttp = { q, period -> searchHtml ?: dev.kento.data.web.duckDuckGoLite(q, period) })
        object : SearchAccess by network {
            override suspend fun read(hit: SearchHit, question: String) =
                fixturePage?.takeIf { it.url == hit.url } ?: network.read(hit, question)
            override suspend fun peek(hit: SearchHit) = fixturePage?.takeIf { it.url == hit.url } ?: network.peek(hit)
        }
    }
    override val searchBrowser by lazy { DuckDuckGoSearch(this) { view, url ->
        val fixture = searchHtml
        if (fixture == null) view.loadUrl(url)
        else view.loadDataWithBaseURL(url, fixture, "text/html", "utf-8", null)
    } }
    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    /**
     * The mock server of the running journey, e.g. `server.url("/v1")`. Kento talks to Refuseless and OpenUncensored
     * only, so the address is not a setting any more; tests redirect both here. Null reaches the real services.
     */
    @Volatile var apiBase: String? = null
    @Volatile var imageBase: String? = null
    override val api by lazy {
        val tls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        Provider(OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).build(),
            base = { apiBase ?: dev.kento.data.settings.REFUSELESS_URL },
            imageBase = { imageBase ?: apiBase ?: dev.kento.data.settings.OPENUNCENSORED_URL })
    }
}

