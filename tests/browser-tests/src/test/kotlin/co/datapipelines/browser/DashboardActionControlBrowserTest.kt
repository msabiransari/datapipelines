package co.datapipelines.browser

import com.google.gson.JsonParser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.SelectOption
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/** #543 — explicit controls on the product board: real evaluation, action gate, refresh wire and filters panel. */
class DashboardActionControlBrowserTest : DashboardBrowserSuite() {
    /** #554: these cases assert the filters column, which needs the desktop width (see [desktopBoard]). */
    @BeforeEach
    fun desktopWidth() {
        desktopBoard()
    }

    private val button = "#dp-board-filters [data-dp-action-control='apply']"
    private val limit = "#dp-board-filters [data-dp-parameter='limit'] input"
    private val region = "#dp-board-filters [data-dp-parameter='region'] select"
    private val lockedSentence = "Applying is paused while the parameters are checked."
    private val invalidSentence = "Fix the parameter errors to apply."

    /** #548 — what the runtime gate answers a programmatic press with: `resolved`, or the refusal code. */
    private fun programmaticRefresh(): Any? =
        page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'all' }).then(() => 'resolved', (e) => e.code)")

    /**
     * #548 — whether Apply's described note is SHOWN: Playwright's `isVisible` counts the 1px clipped box of a
     * visually-hidden element as visible, so the clip and the rendered width are read instead.
     */
    private fun noteShown(): Boolean =
        page.evaluate(
            """() => { const id = document.querySelector("$button").getAttribute('aria-describedby');
              const note = id && document.getElementById(id);
              if (!note) return false;
              const style = getComputedStyle(note);
              return style.clipPath === 'none' && note.getBoundingClientRect().width > 1 && style.visibility === 'visible'; }""",
        ) as Boolean

    /** #548 — Apply is available: no `aria-disabled`, no description. */
    private fun assertApplyAvailable() {
        val apply = page.locator(button)
        apply.getAttribute("aria-disabled") shouldBe null
        apply.getAttribute("aria-describedby") shouldBe null
        assertThat(apply).hasAccessibleDescription("")
    }

    private fun seedApplyBoard(root: String): String {
        val board = seedAcceptanceBoard(root)
        // Keep the acceptance board's other bindings; limit is deliberately UNBOUND.
        val controls =
            """[{"name":"apply_enabled","type":"action_control","action":"refresh_all","parameter":"enabled"},
               {"name":"apply_granularity","type":"action_control","action":"refresh_all","parameter":"granularity"},
               {"name":"apply","type":"action_control","action":"refresh_all","label":"Apply"}]"""
        placeApply(board, controls)
        return board
    }

    private fun placeApply(
        board: String,
        controls: String,
    ) {
        val groups = """[{"name":"controls","type":"group","members":["apply"]}]"""
        sql(
            "UPDATE dashboard_versions SET body_json = jsonb_set(jsonb_set(body_json, '{action_controls}', '$controls'::jsonb), " +
                "'{groups}', '$groups'::jsonb) WHERE dashboard_id = '$board'::uuid",
        )
    }

    private fun openBoard(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        page.evaluate("() => window.__dpPage.code") shouldBe null
        page.waitForFunction("() => window.__dpPage.notifications.some(n => n.code === 'refresh.completed')")
        // Immediate assertion: on the base this fails with 0 buttons, before any click or wire check.
        page.locator(button).count() shouldBe 1
    }

    private fun refreshBodies(): MutableList<String> {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        page.onRequest { request ->
            if (request.method() == "POST" && request.url().endsWith("/runtime/visualizations")) {
                request.postData()?.let(bodies::add)
            }
        }
        return bodies
    }

    @Test
    fun `Apply refreshes an unbound leaf with its committed wire value and survives re-render - both themes`() {
        startTrace()
        val board = seedApplyBoard(ready("dpapply"))
        val bodies = refreshBodies()
        for (theme in listOf("light", "dark")) {
            openBoard(board)
            ensureTheme(theme)
            page.waitForFunction(
                "() => document.querySelector('.dp-dashboard-card[data-dp-viz=acceptancechart] [data-dp-state=ready]') !== null",
            )
            page.locator(button).textContent() shouldBe "Apply"
            val seen = bodies.size
            page.fill(limit, "42")
            page.keyboard().press("Tab")
            // Unbound leaves stay in the composite until Apply; no evaluation runs here.
            page.waitForFunction("() => window.__dpPage.instance._adapter.readSelections().limit === 42 && !window.__dpPage.instance._lock")
            bodies.size shouldBe seen
            page.locator(button).count() shouldBe 1
            page.waitForFunction(
                """() => { const card = document.querySelector('.dp-dashboard-card[data-dp-viz=acceptancechart]');
                  return card.classList.contains('dp-dashboard-card--stale'); }""",
            )
            val shots = Paths.get("build", "reports", "543-screenshots").also { it.toFile().mkdirs() }
            page.locator(button).scrollIntoViewIfNeeded()
            page.locator("#dp-board-filters").screenshot(Locator.ScreenshotOptions().setPath(shots.resolve("apply-$theme.png")))
            page.screenshot(Page.ScreenshotOptions().setPath(shots.resolve("board-$theme.png")))
            val request = page.waitForRequest("**/runtime/visualizations") { page.locator(button).click() }
            val body = JsonParser.parseString(requireNotNull(request.postData())).asJsonObject
            val selections = body.getAsJsonObject("selections")
            val sentLimit = selections.get("limit").asJsonPrimitive
            sentLimit.isNumber shouldBe true
            sentLimit.asInt shouldBe 42
            body.get("scope").asString shouldBe "all"
            page.waitForFunction(
                """() => { const card = document.querySelector('.dp-dashboard-card[data-dp-viz=acceptancechart]');
                  return card.querySelector('[data-dp-state=ready]') && !card.classList.contains('dp-dashboard-card--stale'); }""",
            )
            bodies.size shouldBe seen + 1
            println("543-apply theme=$theme selections=$selections")
        }
    }

    @Test
    fun `Apply is unavailable and described during a held evaluation, the gate refuses a programmatic press, and no refresh crosses`() {
        startTrace()
        val board = seedApplyBoard(ready("dpapplylock"))
        val bodies = refreshBodies()
        openBoard(board)
        assertApplyAvailable()
        var held: Route? = null
        page.route("**/runtime/parameters") { route -> held = route }
        try {
            val seen = bodies.size
            page.selectOption(region, SelectOption().setLabel("United States"))
            page.waitForCondition { held != null }
            val apply = page.locator(button)
            assertThat(apply).hasAttribute("aria-disabled", "true")
            assertThat(apply).hasAccessibleDescription(lockedSentence)
            assertThat(apply).hasAccessibleName("Apply")
            // V3: the transient state is described for assistive technology, not shown.
            noteShown() shouldBe false
            programmaticRefresh() shouldBe "actions.locked"
            bodies.size shouldBe seen
            requireNotNull(held).resume()
            held = null
            page.waitForFunction("() => window.__dpPage.instance._parameters.values.region === 'US' && !window.__dpPage.instance._lock")
            assertApplyAvailable()
            bodies.size shouldBe seen
            println("548-locked described='$lockedSentence' refreshes=${bodies.size - seen}")
        } finally {
            held?.resume()
            page.unroute("**/runtime/parameters")
        }
    }

    @Test
    fun `invalid parameters make Apply unavailable with the visible sentence until a valid retry - no refresh crosses`() {
        startTrace()
        val board = seedApplyBoard(ready("dpapplyinvalid"))
        val bodies = refreshBodies()
        openBoard(board)
        val apply = page.locator(button)
        val seen = bodies.size
        // (a) an unbound leaf's commit starts no evaluation: Apply stays available.
        page.fill(limit, "500")
        page.keyboard().press("Tab")
        page.waitForFunction("() => window.__dpPage.instance._adapter.readSelections().limit === 500 && !window.__dpPage.instance._lock")
        assertApplyAvailable()
        // (b) a parent commit sends the full selections, limit = 500 included; (c) the server answers invalid.
        page.selectOption(region, SelectOption().setLabel("United States"))
        page.waitForFunction("() => window.__dpPage.instance._parameters.valid === false && !window.__dpPage.instance._lock")
        // (d) unavailable, the fixable state's sentence visible as its description, the gate refusing.
        assertThat(apply).hasAttribute("aria-disabled", "true")
        assertThat(apply).hasAccessibleDescription(invalidSentence)
        assertThat(apply).hasAccessibleName("Apply")
        val note = page.locator("#" + requireNotNull(apply.getAttribute("aria-describedby")))
        note.textContent() shouldBe invalidSentence
        note.isVisible shouldBe true
        noteShown() shouldBe true
        programmaticRefresh() shouldBe "parameters.invalid"
        // (e) from the invalid state any commit re-evaluates (the retry branch); (f) valid again.
        page.fill(limit, "42")
        page.keyboard().press("Tab")
        page.waitForFunction("() => window.__dpPage.instance._parameters.valid === true && !window.__dpPage.instance._lock")
        assertApplyAvailable()
        bodies.size shouldBe seen
        println("548-invalid described='$invalidSentence' visible=true refreshes=${bodies.size - seen}")
    }

    @Test
    fun `on a short window Enter on an unavailable Apply leaves the folded dock folded and sends nothing`() {
        startTrace()
        val board = seedApplyBoard(ready("dpapplyshort"))
        page.setViewportSize(SHORT_W, SHORT_H)
        val bodies = refreshBodies()
        openBoard(board)
        val dock = "#dp-board-dock[data-dp-dock-collapsed]"
        // (a) the short window folds the dock automatically.
        page.waitForSelector(dock)
        // (b) the filters are a hidden drawer at this width: open it first.
        page.locator("#dp-board-filters-trigger").click()
        page.waitForFunction("() => document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'true'")
        page.locator(button).isVisible shouldBe true
        var held: Route? = null
        page.route("**/runtime/parameters") { route -> held = route }
        try {
            val seen = bodies.size
            // (c) a held evaluation closes the gate.
            page.selectOption(region, SelectOption().setLabel("United States"))
            page.waitForCondition { held != null }
            // (d) keyboard focus on the unavailable button (the attribute, not the property: focus is kept).
            page.locator(button).focus()
            page.evaluate("() => document.activeElement && document.activeElement.getAttribute('data-dp-action-control')") shouldBe "apply"
            assertThat(page.locator(button)).hasAttribute("aria-disabled", "true")
            // (e) Enter dispatches a click; the composite stops it before the page's filters-panel listener.
            page.locator(dock).count() shouldBe 1
            page.keyboard().press("Enter")
            page.evaluate("() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
            page.locator(dock).count() shouldBe 1
            bodies.size shouldBe seen
            println("548-short ${SHORT_W}x$SHORT_H folded-after-enter=true refreshes=${bodies.size - seen}")
        } finally {
            // (f)
            held?.resume()
            page.unroute("**/runtime/parameters")
        }
    }

    @Test
    fun `a button-only board has a filters panel and a working drawer trigger without a parameter set`() {
        startTrace()
        val board = seedBoard(ready("dpapplyonly"))
        placeApply(board, """[{"name":"apply","type":"action_control","action":"refresh_all","label":"Apply"}]""")
        openBoard(board)
        page.locator("#dp-board-filters .dp-dashboard-parameter").count() shouldBe 0
        page.waitForFunction("() => document.querySelector('.dp-board-page').getAttribute('data-dp-filters') === 'some'")
        page.locator(button).isVisible shouldBe true
        page.setViewportSize(900, 900)
        page.locator("#dp-board-filters-trigger").click()
        page.waitForFunction("() => document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'true'")
        page.locator(button).isVisible shouldBe true
        documentOverflowsX() shouldBe false
    }

    private companion object {
        /** The short window of `DashboardDockResizeBrowserTest` (#800): the dock starts folded here. */
        const val SHORT_W = 1280
        const val SHORT_H = 720
    }
}
