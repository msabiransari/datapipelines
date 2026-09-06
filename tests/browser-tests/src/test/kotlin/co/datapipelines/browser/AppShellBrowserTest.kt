package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * 079 §A/§B/§F — the shell, in a real browser.
 *
 * The unit and render tests pin the pieces; this pins the things only a live page can show:
 * that the rail's collapsed state survives a navigation without a flash, that the theme
 * swaps without a reload, that a section click is ONE boosted GET rather than a document
 * load, and — the check the owner asked for by name — that no app screen scrolls sideways.
 *
 * ## The horizontal-overflow check
 *
 * The owner saw the top nav clipped on a ~3000px window after opening a pipeline in the
 * explorer (orchestrator walk 2026-09-05, unconfirmed at the time). `scrollWidth ==
 * clientWidth` on `<html>` is the mechanical statement of "nothing pokes out of the
 * viewport" — it is true of a correct page at every width and false the moment one grid
 * column, one `min-width`, or one unwrapped table forces the document wider than the window.
 * Both review widths are checked because the failure is width-dependent in both directions:
 * a fixed 320px explorer column overflows a NARROW window, and a `1fr` that resolves against
 * content overflows a WIDE one.
 */
class AppShellBrowserTest : BrowserSuite() {
    /** Every top-level screen, in rail order — the same list siteShots photographs. */
    private val appPages =
        listOf(
            "/dashboard",
            "/pipelines",
            "/datasources",
            "/templates",
            "/executions",
            "/api-console",
            "/promotion",
            "/workspaces",
            "/settings",
            "/docs",
            "/admin/users",
        )

    private fun signedIn(slug: String): String {
        val user = seedLocalUser(uniqueEmail("$slug-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("$slug" + generatedPassword("w").take(8).lowercase())
        return user.email
    }

    /**
     * `<html>`'s own scroll extent against its visible width. Read AFTER the page has
     * settled — an htmx partial that has not landed yet cannot overflow anything, so a
     * check taken too early is a check of an empty page (the "two OBSERVED events" rule:
     * measure the state you actually mean).
     */
    private fun overflow(page: Page): Long =
        // Playwright hands back an Integer for a small whole number and a Long for a big one,
        // so the result is read as a Number and normalised — a direct `as Long` throws
        // ClassCastException on every page that does NOT overflow, which is the pass case.
        (page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toLong()

    @Test
    fun `no app screen scrolls sideways at 1440 or 2560`() {
        startTrace()
        signedIn("ovf")

        val offenders = mutableListOf<String>()
        listOf(1440 to 900, 2560 to 1440).forEach { (w, h) ->
            page.setViewportSize(w, h)
            appPages.forEach { route ->
                page.navigate("$baseUrl$route")
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
                val extra = overflow(page)
                if (extra > 0) offenders += "$route at ${w}x$h overflows by ${extra}px"
            }
        }
        offenders shouldBe emptyList()
    }

    @Test
    fun `the pipelines explorer does not overflow once its detail pane has loaded`() {
        startTrace()
        signedIn("ovfx")

        // The owner's actual report was about the state AFTER opening a pipeline in the
        // explorer, which is when the second (detail) grid column gains content. An empty
        // explorer cannot reproduce it, so this navigates and settles, then measures at the
        // wide width where the clipping was seen.
        listOf(1440 to 900, 2560 to 1440).forEach { (w, h) ->
            page.setViewportSize(w, h)
            page.navigate("$baseUrl/pipelines")
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
            overflow(page) shouldBe 0L
        }
    }

    @Test
    fun `a rail click is one boosted GET, not a document load`() {
        startTrace()
        signedIn("boost")

        val documents = mutableListOf<String>()
        page.onRequest { request ->
            if (request.resourceType() == "document") documents += request.url()
        }
        val before = documents.size

        page.locator("nav.app-nav a[href='/executions']").first().click()
        page.waitForURL("**/executions")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)

        // hx-boost issues an XHR, not a navigation: the browser records NO new document
        // request. That is the whole point of 076 §B and this round did not change it.
        documents.size shouldBe before
        // The rail itself was never re-rendered, so its highlight had to be mirrored
        // client-side by shell.js.
        page.locator("nav.app-nav a[href='/executions']").first().getAttribute("class") shouldContain "active"
        page.locator("#app-crumbs .app-crumb-page").innerText() shouldBe "Executions"
        page.locator("#app-crumbs .app-crumb-group").innerText() shouldBe "Operate"
    }

    @Test
    fun `the rail collapses, persists across a navigation, and applies before first paint`() {
        startTrace()
        signedIn("rail")

        page.navigate("$baseUrl/pipelines")
        page.locator("#rail-collapse").click()
        page.locator("html.rail-collapsed").waitFor()
        page.locator("#rail-collapse").getAttribute("aria-expanded") shouldBe "false"

        // A FULL page load, not a boosted swap: the collapsed class has to be on <html>
        // before the first paint, which only the layout's inline script can do.
        page.navigate("$baseUrl/executions")
        page.locator("html.rail-collapsed").count() shouldBe 1
        page.evaluate("() => localStorage.getItem('dp-rail')") shouldBe "1"

        page.locator("#rail-collapse").click()
        page.locator("html:not(.rail-collapsed)").waitFor()
    }

    @Test
    fun `the avatar menu opens, closes on Escape and on an outside click`() {
        startTrace()
        signedIn("menu")
        page.navigate("$baseUrl/dashboard")

        val menu = page.locator("#app-user-menu")
        menu.isVisible() shouldBe false

        page.locator("#app-avatar").click()
        menu.waitFor()
        page.locator("#app-avatar").getAttribute("aria-expanded") shouldBe "true"

        page.keyboard().press("Escape")
        // ATTACHED, not the default VISIBLE: a `[hidden]` element is by definition never
        // visible, so a default wait on it can only time out.
        awaitHidden()

        page.locator("#app-avatar").click()
        menu.waitFor()
        page.locator("#app-main").click(com.microsoft.playwright.Locator.ClickOptions().setPosition(5.0, 5.0))
        awaitHidden()
    }

    /** Waits for the avatar menu to carry the `hidden` attribute again. */
    private fun awaitHidden() {
        page
            .locator("#app-user-menu[hidden]")
            .waitFor(
                com.microsoft.playwright.Locator
                    .WaitForOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED),
            )
    }

    @Test
    fun `the mode toggle swaps the theme stylesheet and data-theme without a reload`() {
        startTrace()
        signedIn("mode")
        page.navigate("$baseUrl/dashboard")

        val documents = mutableListOf<String>()
        page.onRequest { request -> if (request.resourceType() == "document") documents += request.url() }
        val before = documents.size

        page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
        // The href CHANGING is the completion signal — the PATCH answers with an
        // out-of-band swap of #theme-link, and nothing else in the response says so.
        page.waitForFunction("() => document.getElementById('theme-link').getAttribute('href').includes('/themes/dark.css')")
        page.locator("html[data-theme='dark']").waitFor()
        // No reload: the point of §B.
        documents.size shouldBe before

        page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
        page.waitForFunction("() => document.getElementById('theme-link').getAttribute('href').includes('/themes/light.css')")
    }

    @Test
    fun `the API section is reachable, signed-in, and redirects an anonymous request to login`() {
        startTrace()
        signedIn("api")

        page.locator("nav.app-nav a[href='/api-console']").first().click()
        page.waitForURL("**/api-console")
        page.locator("h1").first().innerText() shouldBe "API"
        page.content() shouldContain "Published endpoints"
        page.content() shouldContain "MCP server"
        // The tool count is rendered from McpToolCatalog.NAMES.size at request time; the
        // page must state a real number, never the template's placeholder.
        page.locator(".app-chip", Page.LocatorOptions().setHasText("tools")).first().innerText() shouldContain " tools"

        val anonymous = newSession()
        try {
            anonymous.page.navigate("$baseUrl/api-console")
            anonymous.page.waitForURL("**/login")
        } finally {
            anonymous.close()
        }
    }

    @Test
    fun `the console renders no font over the network and no console errors`() {
        startTrace()
        signedIn("console")

        val errors = mutableListOf<String>()
        page.onConsoleMessage { message -> if (message.type() == "error") errors += message.text() }
        val externalFonts = mutableListOf<String>()
        page.onRequest { request ->
            if (request.url().contains("fonts.googleapis") || request.url().contains("fonts.gstatic")) {
                externalFonts += request.url()
            }
        }

        appPages.forEach { route ->
            page.navigate("$baseUrl$route")
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        }

        // §G: the vendored faces are served from this origin. A request to a font CDN would
        // mean a stylesheet or template reintroduced one — the audit's live counterpart.
        externalFonts shouldBe emptyList()
        errors shouldBe emptyList()
    }

    @Test
    fun `the vendored webfonts are served and actually used`() {
        startTrace()
        signedIn("fonts")
        page.navigate("$baseUrl/dashboard")

        val fontRequests = mutableListOf<String>()
        page.onResponse { response ->
            if (response.url().endsWith(".woff2")) fontRequests += "${response.status()} ${response.url()}"
        }
        page.reload()
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        page.evaluate("() => document.fonts.ready")

        // Both preloaded faces must be fetched AND answered 200 — a 404 here is the exact
        // silent failure the audit exists for: the page falls back and just looks slightly
        // different, which nobody reports as a bug.
        fontRequests.size shouldBeGreaterThan 0
        fontRequests.map { it.substringBefore(" ") }.toSet() shouldContain "200"
        fontRequests.any { it.contains("InterVariable.woff2") } shouldBe true
    }
}
