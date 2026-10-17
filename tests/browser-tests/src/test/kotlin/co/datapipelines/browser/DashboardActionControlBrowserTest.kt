package co.datapipelines.browser

import com.google.gson.JsonParser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
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
    fun `Apply during a held evaluation announces actions locked in the dock and sends no refresh`() {
        startTrace()
        val board = seedApplyBoard(ready("dpapplylock"))
        val bodies = refreshBodies()
        openBoard(board)
        var held: Route? = null
        page.route("**/runtime/parameters") { route -> held = route }
        try {
            val seen = bodies.size
            page.selectOption("#dp-board-filters [data-dp-parameter='region'] select", SelectOption().setLabel("United States"))
            page.waitForCondition { held != null }
            page.locator(button).click()
            page.waitForFunction("() => window.__dpPage.notifications.some(n => n.code === 'actions.locked')")
            val refusedLine =
                page.locator("#dp-board-dock-pane-events .dp-board-event-text:text-is('the action could not start a refresh.')")
            refusedLine.isVisible shouldBe true
            bodies.size shouldBe seen
            requireNotNull(held).resume()
            held = null
            page.waitForFunction("() => window.__dpPage.instance._parameters.values.region === 'US' && !window.__dpPage.instance._lock")
            bodies.size shouldBe seen
        } finally {
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
}
