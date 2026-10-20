package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.Consumer

/** #622: both pages copy the shared card's placeholder config without a request; CSP is collected by BrowserSuite. */
class McpConnectionCardBrowserTest : RestTreeBrowserFixture() {
    @Test
    fun `both pages copy exactly the config and say Config copied without a request`() {
        startTrace()
        readyTree()
        listOf("/api-console", "/overview/api-console").forEach { path ->
            page.navigate("$baseUrl$path")
            page.locator("[data-mcp-copy]").count() shouldBe 1
            page.waitForFunction("() => !!window.DatapipelinesMcpConnection")
            page.locator("[data-mcp-connection]").count() shouldBe 1
            val config = page.locator("[data-mcp-connection] pre").textContent()
            config shouldContain "\"DP-API-Key\": \"dpk_…\""
            Regex("dpk_[^\\\"\\s]*").findAll(config).map { it.value }.toList() shouldBe listOf("dpk_…")
            page.evaluate(
                """() => {
                  window.__copied = null;
                  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
                    writeText: (text) => Promise.resolve().then(() => { window.__copied = text; }),
                  }});
                }""",
            )
            val requests = mutableListOf<String>()
            val listener = Consumer<com.microsoft.playwright.Request> { requests += "${it.method()} ${it.url()}" }
            page.onRequest(listener)
            page.click("[data-mcp-copy]")
            page.waitForFunction("() => window.__copied !== null")
            page.locator("[data-mcp-copy]").textContent() shouldBe "Copied"
            page.evaluate("() => window.__copied") shouldBe config
            page.locator("[data-mcp-copy-status]").textContent() shouldBe "Config copied to the clipboard."
            requests shouldBe emptyList()
            println("MCP config copy: page=$path clipboard=pre requests=${requests.size} placeholderKeys=1")
            page.offRequest(listener)
        }
    }

    @Test
    fun `boosted navigation loads the fragment copier once and uses the current card`() {
        startTrace()
        readyTree()
        page.click("nav.app-nav a[href='/overview/api-console']")
        page.waitForURL("**/overview/api-console")
        page.waitForFunction("() => !!window.DatapipelinesMcpConnection")
        page.evaluate(
            """() => {
              window.__writes = [];
              Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
                writeText: async text => { window.__writes.push(text); },
              }});
            }""",
        )
        page.click("[data-overview-view-all]")
        page.waitForURL { it.endsWith("/api-console") && !it.contains("/overview/") }
        page.click("[data-mcp-copy]")
        page.waitForFunction("() => window.__writes.length > 0")
        page.evaluate("() => window.__writes") shouldBe listOf(page.locator("[data-mcp-connection] pre").textContent())
        page.locator("[data-mcp-copy-status]").textContent() shouldBe "Config copied to the clipboard."
    }

    @Test
    fun `an unavailable clipboard selects the config and explains manual copying`() {
        startTrace()
        readyTree()
        page.navigate("$baseUrl/api-console")
        page.waitForFunction("() => !!window.DatapipelinesMcpConnection")
        page.evaluate("() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })")
        page.click("[data-mcp-copy]")
        page.locator("[data-mcp-copy]").textContent() shouldBe "Select and copy"
        page.evaluate("() => getSelection().toString()") shouldBe page.locator("[data-mcp-connection] pre").textContent()
        page.locator("[data-mcp-copy-status]").textContent() shouldBe
            "Copy is not available here. The config is selected: press Ctrl+C (⌘C on a Mac) to copy it."
    }

    @Test
    fun `the console card at 1512 in light and dark`() {
        startTrace()
        readyTree()
        page.setViewportSize(SHOT_WIDTH, SHOT_HEIGHT)
        val dir = Paths.get("build", "browser-evidence")
        Files.createDirectories(dir)
        listOf("light", "dark").forEach { theme ->
            page.navigate("$baseUrl/api-console")
            page.waitForFunction("() => !!window.DatapipelinesMcpConnection")
            val wanted = "/themes/$theme.css"
            val current = page.locator("#theme-link").first().getAttribute("href") ?: ""
            if (!current.contains(wanted)) {
                page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
            }
            page.waitForFunction("() => document.getElementById('theme-link').getAttribute('href').includes('$wanted')")
            page.evaluate("() => document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(resolve)))")
            page.locator("[data-mcp-connection]").scrollIntoViewIfNeeded()
            page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("622-console-$theme-1512.png")))
        }
    }

    private companion object {
        const val SHOT_WIDTH = 1512
        const val SHOT_HEIGHT = 982
    }
}
