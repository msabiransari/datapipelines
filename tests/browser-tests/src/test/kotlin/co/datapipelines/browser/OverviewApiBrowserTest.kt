package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #585 — the API home in real Chromium: the rail's API item lands on `/overview/api-console`
 * (no longer on the console), lights the API item and keeps the resource browser closed — the
 * API is not a tree family. A fresh workspace reads zeros and the two empty lines; a key created
 * through the Keys page's own REST verb then counts on the keys tile, is listed by NAME under
 * Recently created keys, and its value appears nowhere on the page. The MCP card is the console's
 * fragment: its tool chip reads the same on both pages. Copy prompt is the shared overview.mjs
 * path: "Copied", the text on the clipboard, and no request. Screenshots at 1512, 900 and 390 go
 * to build/browser-evidence for the #585 handback.
 */
class OverviewApiBrowserTest : RestTreeBrowserFixture() {
    private fun openHome() {
        page.navigate("$baseUrl/dashboard")
        page.click("nav.app-nav a[href='/overview/api-console']")
        page.waitForURL("**/overview/api-console")
        page.waitForSelector(".app-overview[data-overview='api-console']")
    }

    private fun count(key: String): String = page.locator("[data-count='$key'] .app-overview-count-v").innerText().trim()

    private fun toolChip(): String = page.locator("[data-mcp-connection] .app-chip").innerText().trim()

    @Test
    fun `the API item lands on the home with the workspace's numbers, the shared MCP card and no browser`() {
        startTrace()
        readyTree()
        openHome()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/api-console"
        page.locator(".app-nav-link.active").getAttribute("aria-current") shouldBe "page"
        page.locator("#app-crumbs .app-crumb-page").innerText().trim() shouldBe "API"
        page.locator("h1").innerText().trim() shouldBe "API"
        page.locator("#app-browser").isHidden shouldBe true
        page.locator("#browser-open").isHidden shouldBe true

        // A fresh workspace: every tile a zero, the two recent sections their empty lines, and
        // the three steps still there — the page teaches before there is anything to count.
        listOf("endpoints", "release", "draft", "keys").forEach { key -> count(key) shouldBe "0" }
        // textContent, not innerText: the tile's .ds-caption uppercases the label on screen.
        page.locator("[data-count='keys'] dt").textContent().trim() shouldBe "Workspace keys"
        val main = page.locator("main").innerText()
        main shouldContain "Nothing is published yet."
        main shouldContain "No keys yet."
        page.locator("[data-overview-access] ol li").count() shouldBe 3
        page.locator("[data-overview-manage-keys]").getAttribute("href") shouldBe "/api-keys"
        val homeChip = toolChip()
        page.locator("[data-mcp-connection] pre").innerText() shouldContain "\"DP-API-Key\": \"dpk_…\""

        // A key, through the Keys page's own verb: counted, listed by name, its value nowhere.
        val name = "home-agent-" + generatedPassword("k").take(6).lowercase()
        val created = api("POST", "/api/v1/auth/api-keys", """{"name": "$name", "kind": "mcp", "role": "author"}""")
        val secret = field(created, "key")
        openHome()
        count("keys") shouldBe "1"
        page.locator("[data-chart='key-kind'] [data-bar='mcp'] .app-overview-bar-v").innerText() shouldBe "1"
        page.locator("[data-overview-recent] .app-overview-recent li a").allInnerTexts() shouldBe listOf("$name · MCP key · live")
        // #home-ops: the keys panel's info button opens its own section, and the keys row leads to the Keys page.
        page.locator("[data-overview-recent] [data-task='keys'] a").getAttribute("href") shouldBe "/api-keys"
        page.click("[data-overview-panel='key-kind'] [data-open='key-kind']")
        page.waitForFunction("() => document.querySelector('dialog[data-overview-dialog]').open")
        page.locator("[data-overview-dialog-title]").innerText().trim() shouldBe "How keys are counted"
        page.locator("[data-dialog-section='key-kind']").isVisible shouldBe true
        page.locator("[data-dialog-section='endpoint-state']").isVisible shouldBe false
        page.keyboard().press("Escape")
        page.waitForFunction("() => !document.querySelector('dialog[data-overview-dialog]').open")
        val html = page.content()
        html shouldNotContain secret
        html shouldNotContain secret.substringBefore('.')
        shots()
        HomeOpsShots.take(page, "api-console", "key-kind")

        // The chip is the console's own fragment: one catalog size on both pages.
        page.click("[data-overview-view-all]")
        page.waitForURL { it.endsWith("/api-console") && !it.contains("/overview/") }
        toolChip() shouldBe homeChip
    }

    @Test
    fun `Copy prompt copies the publish prompt, says Copied and makes no request`() {
        startTrace()
        readyTree()
        openHome()
        page.click("[data-overview-agent]")
        val prompt = page.locator("#overview-prompt")
        prompt.inputValue() shouldNotContain "key"
        prompt.fill(prompt.inputValue().replace("[pipeline]", "example/served"))
        page.evaluate(
            """() => {
              window.__copied = null;
              Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
                writeText: (text) => Promise.resolve().then(() => { window.__copied = text; }),
              }});
            }""",
        )
        val requests = mutableListOf<String>()
        page.onRequest { requests += "${it.method()} ${it.url()}" }

        page.click("[data-overview-copy]")
        page.waitForFunction("() => window.__copied !== null")
        page.locator("[data-overview-copy]").innerText().trim() shouldBe "Copied"
        (page.evaluate("() => window.__copied") as String) shouldContain "example/served"
        requests shouldBe emptyList()
    }

    private fun shots() {
        val dir = Paths.get("build", "browser-evidence")
        Files.createDirectories(dir)
        // A fresh load at each width (a live resize keeps the wider layout's menu state), then the
        // top, the quick start and the MCP card — the access steps sit in How it works since
        // #home-ops: <main> scrolls inside the frame, so a full-page shot would show only the first
        // screen.
        SHOT_SIZES.forEach { (width, height) ->
            page.setViewportSize(width, height)
            page.reload()
            page.waitForSelector(".app-overview[data-overview='api-console']")
            val parts = listOf("top" to null, "quickstart" to "[data-overview-quickstart]", "mcp" to "[data-mcp-connection]")
            parts.forEach { (part, anchor) ->
                anchor?.let { page.locator(it).scrollIntoViewIfNeeded() }
                page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
                page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("585-api-home-$width-$part.png")))
            }
        }
        // Back to the fixture's viewport (readyTree's) for the rest of the case.
        page.setViewportSize(FIXTURE_WIDTH, FIXTURE_HEIGHT)
    }

    private companion object {
        /** The brief's three widths, each at a plausible window height for that width. */
        val SHOT_SIZES = listOf(1512 to 982, 900 to 900, 390 to 844)
        const val FIXTURE_WIDTH = 1440
        const val FIXTURE_HEIGHT = 900
    }
}

/**
 * #home-ops — the evidence set the three ops homes share (the home-frame lane's OverviewBrowserTest
 * exemplar): the page at 1512 and 390, light then dark, each as the viewport and as the overview
 * root's own shot (the shell scrolls an inner container, so a full-page shot stops at the
 * viewport), and the named panel's info dialog at both widths; leaves the light theme on and the
 * caller's viewport restored and reloaded. Files land in build/reports/home-ops-<family>.
 */
internal object HomeOpsShots {
    private const val WIDE = 1512
    private const val WIDE_H = 900
    private const val PHONE = 390
    private const val PHONE_H = 844
    private val SIZES = listOf(WIDE to WIDE_H, PHONE to PHONE_H)

    fun take(
        page: Page,
        family: String,
        panel: String,
    ) {
        val dir = Paths.get("build", "reports", "home-ops-$family")
        val viewport = page.viewportSize()
        Files.createDirectories(dir)
        listOf("light", "dark").forEach { mode ->
            themeOn(page, mode)
            SIZES.forEach { (width, height) -> shot(page, dir, "$family-$width-$mode", width, height) }
        }
        themeOn(page, "light")
        SIZES.forEach { (width, height) ->
            page.setViewportSize(width, height)
            page.reload()
            page.waitForSelector("[data-overview-summary]")
            page.click("[data-overview-panel='$panel'] [data-open='$panel']")
            page.waitForFunction("() => document.querySelector('dialog[data-overview-dialog]').open")
            page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$family-dialog-$panel-$width.png")))
            page.keyboard().press("Escape")
        }
        page.setViewportSize(viewport.width, viewport.height)
        page.reload()
        page.waitForSelector("[data-overview-summary]")
    }

    private fun shot(
        page: Page,
        dir: Path,
        name: String,
        width: Int,
        height: Int,
    ) {
        page.setViewportSize(width, height)
        page.reload()
        page.waitForSelector("[data-overview-summary]")
        page.evaluate("() => document.fonts.ready")
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$name.png")))
        page.locator("[data-overview]").screenshot(Locator.ScreenshotOptions().setPath(dir.resolve("$name-content.png")))
    }

    /** The user's stored theme wins over an emulated colour scheme, so the shots flip it through the top bar's toggle. */
    private fun themeOn(
        page: Page,
        mode: String,
    ) {
        val wanted = "/themes/$mode.css"
        val current = page.locator("#theme-link").first().getAttribute("href") ?: ""
        if (current.contains(wanted)) return
        page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
        page.waitForFunction("() => document.getElementById('theme-link').getAttribute('href').includes('$wanted')")
        page.locator("html[data-theme='$mode']").waitFor()
    }
}
