package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Paths

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
        // The editors too: they are the screens whose panes are SIZED to the viewport, so
        // they are where a few pixels of arithmetic drift shows first (the owner's report of
        // 2026-09-13 was the pipeline editor). Seeded through the page's own session.
        val fixture = "vscroll_" + generatedPassword("f").take(8).lowercase()
        postJson(
            "/api/v1/templates",
            """{"id":"test/$fixture","type":"sql","dialect":"POSTGRES",""" +
                """"display_name":"$fixture","description":"vscroll fixture","body":"SELECT 1"}""",
        )
        postJson(
            "/api/v1/pipelines",
            """{"name":"test/$fixture","display_name":"$fixture","description":"vscroll fixture",""" +
                """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )
        val routes = appPages + listOf("/pipelines/${pipelineId("test/$fixture")}/editor", "/templates/editor?name=test/$fixture")
        val offenders = mutableListOf<String>()
        // Two desktop sizes and one tall, narrow-ish window (the owner's portrait monitor).
        listOf(1440 to 900, 1920 to 1080, 1636 to 1850).forEach { (w, h) ->
            page.setViewportSize(w, h)
            routes.forEach { route ->
                page.navigate("$baseUrl$route")
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
                val extra =
                    (page.evaluate("() => document.documentElement.scrollHeight - document.documentElement.clientHeight") as Number)
                        .toLong()
                if (extra > 0) offenders += "$route at ${w}x$h: document ${extra}px taller than the viewport; ${overflowChain()}"
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

    /** Diagnostic for a failing run: every element (to depth 6) whose box ends below the viewport. */
    private fun overflowChain(): String {
        val chain =
            page.evaluate(
                """() => {
              const ch = document.documentElement.clientHeight; const out = [];
              const walk = (el, d) => {
                if (d > 6) return;
                const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
                if (r.bottom > ch + 0.5 && r.height > 0) out.push(el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : '') + '[' + cs.position + ' top=' + Math.round(r.top) + ' bottom=' + Math.round(r.bottom) + ' h=' + Math.round(r.height) + ']');
                for (const c of el.children) walk(c, d + 1);
              };
              walk(document.body, 0);
              return out.join(' > ');
            }""",
            )
        return chain.toString()
    }

    /** POSTs JSON through the page's own session (the 106 fixture pattern), asserting 201. */
    private fun postJson(
        url: String,
        body: String,
    ) {
        val status =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch(args.url, {
                    method: 'POST', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json',
                              'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''},
                    body: args.body,
                  });
                  return res.status;
                }""",
                mapOf("url" to url, "body" to body),
            )
        (status as Number).toInt() shouldBe 201
    }

    /** The UUID of the named pipeline, read from the metadata DB (the seedLocalUser seam). */
    private fun pipelineId(name: String): String =
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection
                    .prepareStatement("SELECT p.id FROM pipelines p WHERE p.name = ? ORDER BY p.created_at DESC LIMIT 1")
                    .use { statement ->
                        statement.setString(1, name)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
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

    /**
     * #237 — the content column is the window's, not the top bar's. The shell's second track was
     * a bare `1fr`, which is `minmax(auto, 1fr)`: the column could not be narrower than the top
     * bar's min-content (crumbs, search, mode toggle, MCP-key chip, avatar). At 1100 that was
     * 911px in an 868px column on every screen whose crumb has a group ("Build / Templates"), and
     * `<main>` — the same column — ran 43px past the window (29px on the pipelines screens),
     * clipped by the shell. Only the dashboard, with the shortest crumb, fit (measured on
     * a3e79706). The sideways test above cannot see this: the shell clips, so neither the
     * document nor `<main>` ever reports a scroll width; the fact is `<main>`'s right EDGE.
     *
     * Asserted at the three desktop widths on every screen and both editors: `<main>` and the bar
     * end inside the window, the bar's visible controls end inside the bar without overlapping
     * each other (a bar that fits by stacking its search on its chip is not a fix), and the
     * crumb's page name is whole (nor is one that fits by cutting "Templates" to "Te…", which
     * the first cut of the fix did: the search held its width and the crumb took the squeeze).
     * Then the track on its own terms ([widenedBarOffenders]): it must hold against a bar wider
     * than the window, so the guard outlives the MCP-key chip that makes today's bar too wide.
     * The dashboard, the sql template editor and the pipeline editor are photographed light and
     * dark at each width; the transform editor is TransformFaceBrowserTest's, which asserts the
     * same edge.
     */
    @Test
    fun `main and the top bar end inside the window at 1100, 1440 and 1920 - every screen and both editors`() {
        startTrace()
        signedIn("edge")
        val fixture = "edge_" + generatedPassword("f").take(8).lowercase()
        postJson(
            "/api/v1/templates",
            """{"id":"test/$fixture","type":"sql","dialect":"POSTGRES",""" +
                """"display_name":"$fixture","description":"#237 fixture","body":"SELECT 1"}""",
        )
        postJson(
            "/api/v1/pipelines",
            """{"name":"test/$fixture","display_name":"$fixture","description":"#237 fixture",""" +
                """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )
        val photographed =
            mapOf(
                "dashboard" to "/dashboard",
                "template-editor-sql" to "/templates/editor?name=test/$fixture",
                "pipeline-editor" to "/pipelines/${pipelineId("test/$fixture")}/editor",
            )
        val routes = appPages + photographed.values.drop(1)

        val offenders = mutableListOf<String>()
        page.navigate("$baseUrl/dashboard")
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            // Every route in the first theme; the photographed three again in the second.
            val walked = if (theme == "light") routes else photographed.values.toList()
            DESKTOP_WIDTHS.forEach { width -> offenders += edgeWalk(walked, width, theme, photographed) }
        }
        offenders += widenedBarOffenders()
        offenders shouldBe emptyList()
    }

    /**
     * The track itself, whatever the bar holds today. The walk above goes red only while some
     * screen's bar is wider than its column, and the MCP-key chip — 344px of the 911 — is most
     * of why one is at 1100 now; 233c (keys v2) removes it, after which reverting the track to a
     * bare `1fr` would leave the whole walk green. So an unshrinkable control wider than the
     * window is appended to the bar on the dashboard at 1100 (the one screen the base already
     * fit): the column must stay the window's, `<main>` ending inside it. The control overflows
     * the BAR — that is not the question; only `<main>`'s edge is. Set through the CSSOM, not a
     * style attribute, so the CSP has nothing to refuse.
     */
    private fun widenedBarOffenders(): List<String> {
        val width = DESKTOP_WIDTHS.first()
        page.setViewportSize(width, 900)
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        val mainRight =
            (
                page.evaluate(
                    """
                    () => {
                      const wide = document.createElement('div');
                      wide.style.flex = 'none';
                      wide.style.width = '2000px';
                      document.querySelector('header.app-topbar').append(wide);
                      return document.getElementById('app-main').getBoundingClientRect().right;
                    }
                    """.trimIndent(),
                ) as Number
            ).toDouble()
        println("#237 /dashboard at $width, a 2000px control appended to the bar: main ..$mainRight")
        return if (mainRight > width + 0.5) listOf("a 2000px control in the top bar pushed main to $mainRight at $width") else emptyList()
    }

    /** [routes] at [width]: each one's [edgeOffenders], and a shot of the ones in [photographed] (name → route). */
    private fun edgeWalk(
        routes: List<String>,
        width: Int,
        theme: String,
        photographed: Map<String, String>,
    ): List<String> {
        page.setViewportSize(width, 900)
        return routes.flatMap { route ->
            page.navigate("$baseUrl$route")
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
            photographed.entries.firstOrNull { it.value == route }?.let { shot("${it.key}-$width-$theme") }
            edgeOffenders("$route at $width ($theme)")
        }
    }

    /**
     * What ends past where it must, on the page as it stands: `<main>` and the bar past the
     * window, a bar control past the bar's content edge, a control past the next one's left edge.
     * Named with both edges — "main 1143 > 1100" is the report the issue itself was.
     */
    @Suppress("UNCHECKED_CAST")
    private fun edgeOffenders(where: String): List<String> =
        (
            page.evaluate(
                """
                () => {
                  const out = [], edges = [], w = window.innerWidth, slack = 0.5;
                  const name = e => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '')
                    + (typeof e.className === 'string' && e.className.trim() ? '.' + e.className.trim().split(/\s+/)[0] : '');
                  const main = document.getElementById('app-main').getBoundingClientRect();
                  if (main.right > w + slack) out.push('main ends at ' + Math.round(main.right) + ' > window ' + w);
                  const bar = document.querySelector('header.app-topbar');
                  const b = bar.getBoundingClientRect();
                  if (b.right > w + slack) out.push('the top bar ends at ' + Math.round(b.right) + ' > window ' + w);
                  const inner = b.right - parseFloat(getComputedStyle(bar).paddingRight);
                  const leaf = bar.querySelector('.app-crumb-page');
                  if (leaf.scrollWidth > leaf.clientWidth + 1) // +1: both are rounded; a cut name is far more
                    out.push('the crumb "' + leaf.textContent.trim() + '" is cut to ' + leaf.clientWidth + ' of its ' + leaf.scrollWidth + 'px');
                  const kids = [...bar.children].filter(k => k.getBoundingClientRect().width > 0);
                  kids.forEach((k, i) => {
                    const r = k.getBoundingClientRect(), right = Math.round(r.right);
                    edges.push(name(k) + ' ' + Math.round(r.left) + '..' + right);
                    if (r.right > inner + slack) out.push(name(k) + ' ends at ' + right + ' past the bar\'s ' + Math.round(inner));
                    const next = kids[i + 1], nextLeft = next ? next.getBoundingClientRect().left : Infinity;
                    if (r.right > nextLeft + slack) out.push(name(k) + ' (..' + right + ') overlaps ' + name(next) + ' (' + Math.round(nextLeft) + '..)');
                  });
                  return { out, edges: 'main ..' + Math.round(main.right) + ', bar ..' + Math.round(b.right) + ' | ' + edges.join(', ') };
                }
                """.trimIndent(),
            ) as Map<String, Any?>
        ).let { measured ->
            // The measured edges, pass or fail: the handback's before/after table (SiteRhythm's precedent).
            println("#237 $where: ${measured["edges"]}")
            (measured["out"] as List<String>).map { "$where: $it" }
        }

    /** A viewport shot (the shell is the viewport — the document never scrolls) under build/reports/240-screenshots. */
    private fun shot(name: String) {
        // Any visible toast is dismissed through its own × first (the 7d/7e shot convention), so
        // the picture shows the screen and not the theme switch that preceded it.
        page.locator("#toast .ds-toast-close").all().forEach { close -> if (close.isVisible) close.click() }
        page.waitForFunction("() => document.querySelectorAll('#toast .ds-toast').length === 0")
        val dir = Paths.get("build", "reports", "240-screenshots").also { it.toFile().mkdirs() }
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("240-$name.png")))
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
    fun `the avatar menu opens, closes on Escape, on an outside click and on choosing an item`() {
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

        // Choosing an item closes it (owner, 2026-09-13: "does not go away when we select
        // an option"). A mode button is the case that stayed open the longest: its PATCH
        // swaps nothing, so nothing else on the page ever moved. The choice still lands —
        // the theme the button names is the theme the document wears afterwards.
        page.locator("#app-avatar").click()
        menu.waitFor()
        val chosen = if (page.locator("html").getAttribute("data-theme") == "dark") "light" else "dark"
        page.locator("#app-appearance [data-mode=$chosen]").click()
        awaitHidden()
        page.locator("html[data-theme=$chosen]").waitFor()
        page.locator("#app-avatar").getAttribute("aria-expanded") shouldBe "false"

        // Settings is a boosted navigation: only #app-main is swapped, so the menu in the
        // top bar survived the trip and sat open over the new screen.
        page.locator("#app-avatar").click()
        menu.waitFor()
        page.locator("#app-user-menu a[href='/settings']").click()
        page.waitForURL("**/settings")
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

    /**
     * 127 §C — "Report a problem" in the avatar menu: the fourth `menuitem`, in arrow-key
     * order between API keys and Log out, opening the bug form in a NEW tab (the app tab
     * never navigates) and closing the menu under the v1.42 close-on-choice rule.
     *
     * The item COUNT is the guard's falsification leg: drop the `menuitem` role from the
     * new link and this goes red. Arrow-key order matters because shell.js walks the
     * `[role="menuitem"]` set — End must land on Log out and ArrowUp on the new link.
     */
    @Test
    fun `Report a problem opens the bug form in a new tab and closes the menu`() {
        startTrace()
        signedIn("report")
        page.navigate("$baseUrl/dashboard")

        page.locator("#app-avatar").click()
        page.locator("#app-user-menu").waitFor()

        val items = page.locator("#app-user-menu [role='menuitem']")
        items.count() shouldBe 4
        items.nth(0).innerText() shouldContain "Settings"
        items.nth(1).innerText() shouldContain "API keys"
        items.nth(2).innerText() shouldContain "Report a problem"
        items.nth(3).innerText() shouldContain "Log out"

        page.keyboard().press("End")
        page.evaluate("() => document.activeElement.textContent").toString() shouldContain "Log out"
        page.keyboard().press("ArrowUp")
        page.evaluate("() => document.activeElement.textContent").toString() shouldContain "Report a problem"

        val report = page.locator("#app-user-menu a", Page.LocatorOptions().setHasText("Report a problem"))
        report.getAttribute("href") shouldBe "https://github.com/msabiransari/datapipelines/issues/new?template=bug_report.yml"

        val popup = page.waitForPopup { report.click() }
        // The DESTINATION is asserted, not the load: github.com answers an anonymous
        // issues/new with a login bounce whose return_to encodes the form's URL — decode
        // before comparing, so the check holds on-line (redirect) and off (about:blank
        // would fail here, as it should).
        val landed = java.net.URLDecoder.decode(popup.url(), Charsets.UTF_8)
        landed shouldContain "github.com"
        landed shouldContain "/msabiransari/datapipelines/issues/new"
        landed shouldContain "template=bug_report.yml"
        popup.close()

        awaitHidden()
        page.locator("#app-avatar").getAttribute("aria-expanded") shouldBe "false"
        page.url() shouldContain "/dashboard"
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
        // `net::ERR_NETWORK_CHANGED` is Chromium's transport layer, not the app: on a Linux host
        // with IPv6 enabled every Docker container start adds a veth with a link-local IPv6
        // address, Chromium's NetworkChangeNotifier reports an IP-address change, and any load
        // in flight is aborted (docker/for-linux#914; measured here 2026-09-12 with forked test
        // JVMs starting containers beside this test). DEVELOPMENT.md §9.5 has the sysctl that
        // removes the cause; this assertion is about the APP's console, so that one message is
        // set aside by name — every other error line still fails it.
        page.onConsoleMessage { message ->
            if (message.type() == "error" && !message.text().contains("net::ERR_NETWORK_CHANGED")) errors += message.text()
        }
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

    /**
     * 188 (#188): the CSP is ENFORCED, and the suite's collector sees a refusal. The
     * suite-wide zero-violation rule (BrowserSuite.closePage) would hold vacuously against
     * a report-only policy, a policy that was never sent, or a listener that never
     * attached — so this test provokes one: an inline `<script>` appended to a live app
     * page (through CDP, which the policy does not govern) must be REFUSED by the browser,
     * and the refusal must reach the collector naming the policy. Drained afterwards so the
     * deliberate violation is not charged to this test. Every real page is then held to
     * zero by the same collector — this is what makes that zero mean something.
     */
    @Test
    fun `the content security policy is enforced - an injected inline script is refused and seen`() {
        startTrace()
        signedIn("csp")
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        drainCspViolations() shouldBe emptyList()

        val ran =
            page.evaluate(
                """
                () => {
                  window.__dpCspProbe = false;
                  const s = document.createElement('script');
                  s.textContent = 'window.__dpCspProbe = true';
                  document.body.appendChild(s);
                  return window.__dpCspProbe;
                }
                """.trimIndent(),
            )
        ran shouldBe false
        val seen = drainCspViolations()
        seen.size shouldBeGreaterThanOrEqual 1
        seen.first() shouldContain "Content Security Policy"
        seen.first() shouldContain "script-src"
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

    private companion object {
        /** The explorer's desktop widths (#237's acceptance): the stacked breakpoint and two desktops. */
        val DESKTOP_WIDTHS = listOf(1100, 1440, 1920)
    }
}
