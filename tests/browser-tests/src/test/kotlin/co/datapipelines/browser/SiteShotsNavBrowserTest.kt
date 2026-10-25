package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.net.URI

/** Exercises the screenshot driver's actual click helper against plain and fragment-bearing links. */
class SiteShotsNavBrowserTest : BrowserSuite() {
    @Test
    fun `the driver follows a fragment destination and skips an absent section`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body =
                """<html><body><a class="app-nav-link" data-nav-section="/admin" href="/target#workspace-members">Members</a>
                <div id="workspace-members">Members destination</div></body></html>""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        // Supply the CLI configuration without invoking the screenshot/login workflow.
        val driverUrl = SiteShotsMain::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
        val priorUrl = driverUrl.get(null)
        try {
            page.setDefaultTimeout(NAV_TIMEOUT_MS)
            page.setDefaultNavigationTimeout(NAV_TIMEOUT_MS)
            val root = "http://127.0.0.1:${server.address.port}"
            driverUrl.set(null, root)
            page.navigate(root)
            page.evaluate("() => document.querySelector('a').setAttribute('href', '/target')")
            click("/admin") shouldBe true
            URI(page.url()).path shouldBe "/target"
            URI(page.url()).fragment shouldBe null

            page.navigate(root)
            click("/admin") shouldBe true
            URI(page.url()).path shouldBe "/target"
            URI(page.url()).fragment shouldBe "workspace-members"
            val before = page.url()
            click("/absent") shouldBe false
            page.url() shouldBe before
        } finally {
            driverUrl.set(null, priorUrl)
            server.stop(0)
        }
    }

    /** Call the driver itself: a copied URL predicate would only test the copy. */
    private fun click(section: String): Boolean {
        val method = SiteShotsMain::class.java.getDeclaredMethod("clickNavSection", Page::class.java, String::class.java)
        method.isAccessible = true
        return try {
            method.invoke(SiteShotsMain, page, section) as Boolean
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private companion object {
        const val NAV_TIMEOUT_MS = 5_000.0
    }
}
