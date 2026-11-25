package dev.kento.data.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepPageTest {
    /** A page of thousands of nested wrappers used to overflow the stack and crash the app during a search. */
    @Test fun deeplyNestedPageIsReadWithoutOverflow() {
        val html = "<html><body>" + "<div><span>".repeat(4000) + "Deep fact" + "</span></div>".repeat(4000) + "<p>Top fact</p></body></html>"
        val page = extractPage(Jsoup.parse(html), "https://example.org/".toHttpUrl())
        assertTrue(page.text.contains("Deep fact"))
        assertTrue(page.text.contains("Top fact"))
    }
}
