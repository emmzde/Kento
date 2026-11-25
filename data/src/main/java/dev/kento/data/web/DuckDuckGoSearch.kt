package dev.kento.data.web

import dev.kento.core.text.tr

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.*
import dev.kento.data.net.ProviderIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SearchVerification internal constructor(val view: WebView, val returnToSearch: () -> Unit)

class DuckDuckGoSearch(context: Context, private val navigate: (WebView, String) -> Unit = WebView::loadUrl) {
    private val app = context.applicationContext
    private val pending = MutableStateFlow<SearchVerification?>(null)
    val verification = pending.asStateFlow()
    private val searches = Mutex()
    private var tab: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun browser(): WebView {
        tab?.let { return it }
        val view = try { WebView(app) } catch (e: RuntimeException) {
            throw ProviderIssue(tr("Системный Android WebView недоступен. Проверьте его установку или выберите другой поисковик."))
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        tab = view
        return view
    }

    private fun discard(view: WebView) {
        if (tab === view) tab = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    /** [period] is DuckDuckGo's `df` filter: d, w, m or y for the last day, week, month or year. */
    suspend fun search(query: String, period: String? = null): List<SearchHit> = searches.withLock {
        withContext(Dispatchers.Main.immediate) { searchInTab(query, period) }
    }

    private suspend fun searchInTab(query: String, period: String?): List<SearchHit> {
        val url = "https://lite.duckduckgo.com/lite/".toHttpUrl().newBuilder().addQueryParameter("q", query)
            .apply { if (period != null) addQueryParameter("df", period) }.build().toString()
        val view = browser()
        val pages = Channel<Pair<String, String>>(Channel.CONFLATED)
        return try {
            view.onResume()
            view.webViewClient = ResultClient(pages, url, ::discard)
            navigate(view, url)
            var hits: List<SearchHit>? = null
            while (hits == null) {
                val verifying = pending.value?.view === view
                val (base, html) = if (verifying) pages.receive() else withTimeoutOrNull(25_000) { pages.receive() }
                    ?: throw ProviderIssue(tr("DuckDuckGo не ответил за 25 секунд"))
                when (val page = withContext(Dispatchers.Default) { duckDuckGoPage(html, base) }) {
                    is DuckPage.Results -> hits = page.hits
                    DuckPage.Verification -> if (!verifying) pending.value = SearchVerification(view) {
                        if (pending.value?.view === view) navigate(view, url)
                    }
                    DuckPage.Unknown -> if (!verifying) throw ProviderIssue(tr("DuckDuckGo вернул страницу без распознаваемой выдачи"))
                }
            }
            hits
        } finally {
            if (pending.value?.view === view) pending.value = null
            pages.cancel()
            if (tab === view) {
                (view.parent as? ViewGroup)?.removeView(view)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        discard(view)
                        return true
                    }
                }
                view.stopLoading()
                view.onPause()
            }
            withContext(NonCancellable + Dispatchers.IO) { CookieManager.getInstance().flush() }
        }
    }

    private class ResultClient(private val pages: Channel<Pair<String, String>>, initialUrl: String,
        private val discard: (WebView) -> Unit) : WebViewClient() {
        private val query = initialUrl.toHttpUrl().queryParameter("q")
        private var started = false
        private fun fail(message: String) { pages.close(ProviderIssue(message)) }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (url.toHttpUrlOrNull()?.queryParameter("q") == query) started = true
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val allowed = request.url.toString().toHttpUrlOrNull()?.isDuckDuckGo() == true
            if (!allowed && request.isForMainFrame) fail(tr("DuckDuckGo перенаправил поиск на другой сайт"))
            return !allowed
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) fail(tr("DuckDuckGo вернул HTTP {0}. Попробуйте позже или выберите другой поисковик.", response.statusCode))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) fail(tr("Не удалось открыть DuckDuckGo в WebView ({0})", error.errorCode))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            fail(tr("Android остановил WebView во время поиска"))
            discard(view)
            return true
        }

        override fun onPageFinished(view: WebView, location: String) {
            val address = location.toHttpUrlOrNull()
            val returnedQuery = address?.queryParameter("q")
            if (returnedQuery != query && (!started || returnedQuery != null)) return
            if (address?.isDuckDuckGo() != true) {
                fail(tr("Неожиданный адрес страницы DuckDuckGo"))
                return
            }
            view.evaluateJavascript("document.documentElement.outerHTML.slice(0, 1048576)") { encoded ->
                val html = runCatching { Json.decodeFromString<String>(encoded) }.getOrNull()
                if (html.isNullOrBlank()) fail(tr("WebView не вернул HTML выдачи DuckDuckGo"))
                else pages.trySend(location to html)
            }
        }
    }
}
