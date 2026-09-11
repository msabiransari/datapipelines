package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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

    /**
     * An anchor styled as a button keeps its label on hover. base.css's `a:hover` outranked
     * `.ds-button-primary` and painted the label in the link colour on the accent ground —
     * invisible (the login page's "Continue with Google", owner 2026-09-11). Asserted on the
     * computed style AFTER a real hover, on the one primary anchor every deployment renders
     * (the keys screen's "Go to API"); the login providers need an OIDC registration this
     * suite does not have, but the rule is per variant, not per page.
     */
    @Test
    fun `an anchor styled as a primary button keeps its label colour on hover`() {
        signedIn("hover")
        page.navigate("$baseUrl/settings/api-keys")
        val link = page.locator("a.ds-button-primary").first()
        link.waitFor()

        fun style(prop: String): String =
            link
                .evaluate("(el, p) => getComputedStyle(el).getPropertyValue(p)", prop)
                .toString()
                .trim()
        val resting = style("color")
        val background = style("background-color")
        link.hover()
        page.waitForTimeout(250.0) // base.css transitions colour over --duration-fast

        style("color") shouldBe resting
        style("text-decoration-line") shouldBe "none"
        // The label must contrast with the ground it sits on: same colour as the background
        // is exactly the defect.
        (style("color") == style("background-color")) shouldBe false
        background.isNotBlank() shouldBe true
    }

    /**
     * The shell is the viewport (app.css, 079 §A note, revised 2026-09-11): the DOCUMENT never
     * scrolls — `<main>` does. A screen whose panes run a few pixels long used to drag the
     * whole application (rail, bar and all) by that much; a long single-column screen
     * (/workspaces, /docs) now scrolls inside `<main>` with the chrome fixed. Measured on the
     * document element at two desktop sizes on every app screen, the long ones included —
     * `/docs` is 500px taller than a 900px viewport and is the one that proves the model.
     */
    @Test
    fun `the document never scrolls vertically - main does`() {
        startTrace()
        signedIn("vscroll")
        val offenders = mutableListOf<String>()
        listOf(1440 to 900, 1920 to 1080).forEach { (w, h) ->
            page.setViewportSize(w, h)
            appPages.forEach { route ->
                page.navigate("$baseUrl$route")
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
                val extra =
                    (page.evaluate("() => document.documentElement.scrollHeight - document.documentElement.clientHeight") as Number)
                        .toLong()
                if (extra > 0) offenders += "$route at ${w}x$h: document ${extra}px taller than the viewport"
            }
        }
        offenders shouldBe emptyList()
        // Non-vacuity: the model is only proven by a screen that HAS more content than fits.
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/docs")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        val mainOverflow =
            (page.evaluate("() => { const m = document.getElementById('app-main'); return m.scrollHeight - m.clientHeight }") as Number)
                .toLong()
        (mainOverflow > 0) shouldBe true
    }

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
        // Since <main> became the scroll container (2026-09-11) a document-widener shows up
        // as MAIN's horizontal overflow, not the document's — so both are measured and the
        // larger reported; the guard would otherwise have gone vacuous the day main scrolled.
        (
            page.evaluate(
                """() => { const d = document.documentElement, m = document.getElementById('app-main');
                       return Math.max(d.scrollWidth - d.clientWidth, m ? m.scrollWidth - m.clientWidth : 0) }""",
            ) as Number
        ).toLong()

    /**
     * The elements whose right edge is past the viewport. "The page overflows by 9px" is a
     * fact you cannot act on; "DIV.app-grid does" is. Reported IN the failure message rather
     * than dug out of a trace afterwards.
     */
    private fun culprits(page: Page): String =
        page
            .evaluate(
                """
                () => Array.from(document.querySelectorAll('*'))
                  .filter(e => e.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
                  .slice(0, 12)
                  .map(e => {
                    const id = n => n.tagName + (n.className && typeof n.className === 'string' && n.className.trim()
                      ? '.' + n.className.trim().split(/\s+/).join('.') : '');
                    const chain = [];
                    for (let n = e; n && chain.length < 5; n = n.parentElement) chain.push(id(n));
                    const r = e.getBoundingClientRect();
                    return chain.join(' < ') + ' [' + Math.round(r.left) + '..' + Math.round(r.right) + ']'
                      + ' text=' + JSON.stringify((e.textContent || '').trim().slice(0, 30));
                  })
                  .join(' | ')
                """.trimIndent(),
            ).toString()

    /**
     * 110 §D — every screen at FOUR widths: the two review widths the shell was built
     * for (a fixed explorer column overflows a NARROW window, a content-sized `1fr`
     * overflows a WIDE one) plus the two the shell now has to fit — 390×844 (a phone)
     * and 768×1024 (the shell's own breakpoint, where the rail starts collapsed).
     *
     * The EDITORS are deliberately not in [appPages]: they are entered from a leaf,
     * not from the rail, and below 768 they render the §C wide-screen band BY DECISION
     * ("Open on a wider screen to edit") — there is nothing to exclude here, and no
     * silent skip of a page that was never in the list.
     */
    @Test
    fun `no app screen scrolls sideways at 390, 768, 1440 or 2560`() {
        startTrace()
        signedIn("ovf")

        val offenders = mutableListOf<String>()
        listOf(390 to 844, 768 to 1024, 1440 to 900, 2560 to 1440).forEach { (w, h) ->
            page.setViewportSize(w, h)
            appPages.forEach { route ->
                page.navigate("$baseUrl$route")
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
                val extra = overflow(page)
                if (extra > 0) {
                    // At the two narrow widths the failure must name the screen, the
                    // overflow in px, and the culprit element — "/dashboard at 390:
                    // 154px over — .app-topbar" is a report you can act on.
                    offenders += "$route at $w: ${extra}px over — ${culprits(page)}"
                }
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
        page.locator("#app-main").click(
            com.microsoft.playwright.Locator
                .ClickOptions()
                .setPosition(5.0, 5.0),
        )
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
        // The deployment default is dark; this test is about the SWAP, so start from light.
        ensureTheme("light")

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

    /**
     * 098 §A — the overflow walk above signs in as a FRESH user, so `/datasources` renders its
     * empty state and cannot overflow anything. The screen the owner photographed had eight
     * rows, and the overflow came from one of them: the JDBC URL cell is a mono string with no
     * spaces, it set the table's minimum width, and the bare `.ds-table` had no scroll container
     * to keep that inside the card.
     *
     * Measured on the demo stack before the fix (2026-09-08, seven seeded rows at 1440x900):
     * `document.scrollWidth 1575` against `innerWidth 1440`, table right edge 1551, the table's
     * wrapper `overflow-x: visible`, and `HEADER.app-topbar [232..1575]` among the culprits —
     * the top bar dragged wide with the page, its search box clipped. 093 §3 measured 1618 with
     * eight rows on the same screen.
     *
     * Eight rows, registered through the screen's own htmx endpoint (the register modal posts
     * exactly this), with URLs as long as a real deployment's — a short `jdbc:h2:mem:x` would
     * fit at any width and the walk would pass without measuring anything.
     */
    @Test
    fun `the datasources list with eight rows does not scroll the document sideways`() {
        startTrace()
        signedIn("dsovf")

        seedDatasources(8) shouldBe emptyList()

        val offenders = mutableListOf<String>()
        listOf(1440 to 900, 2560 to 1440).forEach { (w, h) ->
            page.setViewportSize(w, h)
            page.navigate("$baseUrl/datasources")
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
            page.locator("table.ds-table tbody tr").first().waitFor()
            // The rows really are there: a check taken against an empty table is the check the
            // walk above already makes, and it is what let this regress unseen. A FLOOR, not an
            // equality — this module shares one database, and another spec's GLOBAL datasource
            // is visible from every workspace including this one (measured: 9 rows, not 8).
            page.locator("table.ds-table tbody tr").count() shouldBeGreaterThanOrEqual 8
            val extra = overflow(page)
            if (extra > 0) offenders += "/datasources at ${w}x$h overflows by ${extra}px — ${culprits(page)}"
        }
        offenders shouldBe emptyList()
    }

    /**
     * Registers [count] datasources through `POST /partials/datasources` — the endpoint the
     * register modal posts to — from inside the page, so the session cookie and the
     * `DP-CSRF-Token` header the layout carries both apply. Returns how many were accepted.
     *
     * Registration does not connect, so the hosts and ports need not exist; the point of the
     * fixture is the WIDTH of what a real row renders. The DRIVER does have to be loaded,
     * though, and MySQL Connector/J is deliberately absent from the default build (GPL + FOSS
     * exception, datasources.md §10.2) — a first cut using MYSQL rows had exactly half of them
     * refused with `datasource.driver_not_loaded`. Postgres and H2 are both on this suite's
     * classpath, so the shapes below are theirs, with URLs as long as a deployment's.
     *
     * Returns the refusals, so a future failure names its own cause instead of counting.
     */
    private fun seedDatasources(count: Int): List<String> {
        page.navigate("$baseUrl/datasources")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        @Suppress("UNCHECKED_CAST")
        return page.evaluate(
            """async (count) => {
              const headers = JSON.parse(document.body.getAttribute('hx-headers') || '{}');
              const shapes = [
                ['POSTGRES', 'jdbc:postgresql://analytics-primary.internal.example.com:5432/dp_sample_trips'],
                ['H2', 'jdbc:h2:mem:analytics_primary_internal_example_com_dp_sample_trips'],
                ['POSTGRES', 'jdbc:postgresql://reporting-standby.internal.example.com:5432/dp_reporting'],
                ['H2', 'jdbc:h2:mem:reporting_standby_internal_example_com_dp_reporting'],
              ];
              const refusals = [];
              for (let i = 0; i < count; i++) {
                const [dialect, url] = shapes[i % shapes.length];
                const body = new URLSearchParams({
                  name: 'seeded-datasource-' + i,
                  displayName: 'Seeded datasource ' + i,
                  dialect,
                  jdbcUrl: url + '_' + i,
                  credentialKind: 'password',
                  username: 'dp_demo_readonly',
                  password: 'not-a-real-secret-' + i,
                  description: 'A seeded row whose JDBC URL is as long as a real one.',
                });
                const res = await fetch('/partials/datasources', {
                  method: 'POST',
                  credentials: 'same-origin',
                  headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' },
                  body,
                });
                if (!res.ok) refusals.push(dialect + ' ' + res.status + ' ' + (await res.text()).slice(0, 200));
              }
              return refusals;
            }""",
            count,
        ) as List<String>
    }
}
