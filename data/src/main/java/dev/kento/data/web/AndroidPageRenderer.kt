package dev.kento.data.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

class AndroidPageRenderer(context: Context) : PageRenderer {
    private val app = context.applicationContext

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun render(html: String): String? {
        val offlineHtml = withContext(Dispatchers.Default) {
            val doc = Jsoup.parse(html)
            doc.select("base, link, iframe, frame, object, embed, meta[http-equiv]").remove()
            doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
                "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; " +
                    "connect-src 'none'; form-action 'none'; base-uri 'none'")
            doc.outerHtml()
        }
        return withContext(Dispatchers.Main.immediate) {
            val view = try { WebView(app) } catch (_: RuntimeException) { return@withContext null }
            val loaded = CompletableDeferred<Boolean>()
            var lost = false
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String) { loaded.complete(true) }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    lost = true
                    loaded.complete(false)
                    view.destroy()
                    return true
                }
            }
            try {
                withTimeoutOrNull(2_500) {
                    view.loadDataWithBaseURL("https://kento.invalid/", offlineHtml, "text/html", "utf-8", null)
                    if (!loaded.await()) return@withTimeoutOrNull null
                    delay(400)
                    if (lost) return@withTimeoutOrNull null
                    val rendered = CompletableDeferred<String?>()
                    view.evaluateJavascript("document.documentElement.outerHTML.slice(0, 2097152)") { encoded ->
                        rendered.complete(runCatching { Json.decodeFromString<String?>(encoded) }.getOrNull())
                    }
                    rendered.await()
                }
            } finally {
                if (!lost) {
                    view.stopLoading()
                    view.destroy()
                }
            }
        }
    }
}
