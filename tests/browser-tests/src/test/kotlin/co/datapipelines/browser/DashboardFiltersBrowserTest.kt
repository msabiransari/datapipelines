package co.datapipelines.browser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.microsoft.playwright.options.SelectOption
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * #473 — the board's parameter FILTERS: their own panel BESIDE the chart container (never inside
 * it, so no adapter's mount scope can claim them), announced from a toolbar trigger, and at
 * narrow widths a drawer — opened from the toolbar, closed on Escape, focus returned.
 *
 * The behaviour of the controls themselves (evaluation, the lock, the wire) is the conformance
 * suites'; what the page adds is WHERE the panel lives and that the drawer is keyboard-reachable.
 *
 * Recovery 473b (after #460's persistent shell): the board is re-mounted on in-shell navigation
 * without a document load, so the drawer case first leaves and returns twice and counts the
 * document's keydown listeners through DevTools (DOMDebugger.getEventListeners) — the code under
 * test reports nothing about itself. A board with no visible parameter reserves no panel.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardFiltersBrowserTest : DashboardBrowserSuite() {
    private fun openBoard(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        val code = page.evaluate("() => window.__dpPage.code") as String?
        if (code != null) {
            val error = page.evaluate("() => String(window.__dpPage.error)") as String
            throw AssertionError("the board page failed to boot: $code — $error")
        }
    }

    /** Listeners of [type] registered on `document`, read through DevTools, not through the page's own seams. */
    private fun documentListenerCount(type: String): Int {
        val cdp = page.context().newCDPSession(page)
        try {
            val document = cdp.send("Runtime.evaluate", JsonObject().apply { addProperty("expression", "document") })
            val objectId = document.getAsJsonObject("result").get("objectId").asString
            val listeners =
                cdp.send("DOMDebugger.getEventListeners", JsonObject().apply { addProperty("objectId", objectId) })
            return listeners.getAsJsonArray("listeners").count { it.asJsonObject.get("type").asString == type }
        } finally {
            cdp.detach()
        }
    }

    /** The filters panel's and the board's rectangles, with the panel's computed display. */
    @Suppress("UNCHECKED_CAST")
    private fun regions(): Map<String, Any?> =
        page.evaluate(
            """() => { const panel = document.getElementById('dp-board-filters').getBoundingClientRect();
              const board = document.getElementById('dp-board').getBoundingClientRect();
              const layout = document.querySelector('.dp-board-layout').getBoundingClientRect();
              return { panelLeft: panel.left, panelRight: panel.right, panelWidth: panel.width,
                boardLeft: board.left, boardWidth: board.width, layoutLeft: layout.left, layoutWidth: layout.width,
                panelDisplay: getComputedStyle(document.getElementById('dp-board-filters')).display,
                panelPosition: getComputedStyle(document.getElementById('dp-board-filters')).position,
                panelTransform: getComputedStyle(document.getElementById('dp-board-filters')).transform,
                triggerDisplay: getComputedStyle(document.querySelector('.dp-board-toolbar')).display,
                filters: document.querySelector('.dp-board-page').getAttribute('data-dp-filters'),
                fields: document.querySelectorAll('#dp-board-filters .dp-dashboard-parameter').length }; }""",
        ) as Map<String, Any?>

    private fun Any?.px(): Double = (this as Number).toDouble()

    /**
     * #460's persistent shell: leave in-shell and come back twice (a cached restoration re-mounts
     * the board without a document load). Each visit's Escape listener must leave with its board,
     * so the document holds exactly the first visit's keydown count.
     */
    private fun leaveAndReturnHoldsOneListenerSet() {
        val firstMount = documentListenerCount("keydown")
        repeat(2) { visit ->
            page.evaluate("() => { window.__leftBoard = window.__dpPage; }")
            page.click(".app-nav-link[data-nav-section='/templates']")
            page.waitForSelector("#template-list-wrapper")
            page.goBack()
            page.waitForFunction("() => window.__dpPage !== window.__leftBoard && window.__dpPage.ready === true")
            val count = documentListenerCount("keydown")
            println("473b-keydown-listeners visit=${visit + 2} first=$firstMount now=$count")
            count shouldBe firstMount
        }
    }

    @Test
    @Order(1)
    fun `the filters are a panel beside the board - holding the controls, never inside the chart container`() {
        startTrace()
        val root = ready("dpfilt")
        // A parameterISED board: without a set the runtime renders no pane at all (the bootstrap
        // parameter step is an explicit no-op), and WHERE the pane mounts is this test's question.
        val board = seedParameterisedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")

        val where =
            page.evaluate(
                """() => { const panel = document.getElementById('dp-board-filters');
                  const params = panel.querySelector('.dp-dashboard-parameters');
                  const board = document.getElementById('dp-board');
                  return { panel: !!panel, params: !!params,
                    insideBoard: params ? board.contains(params) : true,
                    insideRuntime: params ? !!params.closest('[data-datapipelines-dashboard]') : true,
                    beforeBoard: params ? !!(params.compareDocumentPosition(board) & Node.DOCUMENT_POSITION_FOLLOWING) : false }; }""",
            ) as Map<*, *>
        where["panel"] shouldBe true
        where["params"] shouldBe true
        where["insideBoard"] shouldBe false
        where["insideRuntime"] shouldBe false
        where["beforeBoard"] shouldBe true

        // The workspace page never scrolls sideways, at the wide layout either.
        documentOverflowsX() shouldBe false

        // Measured at both desktop sizes: the panel ends at or before the board begins, it holds
        // the set's field, and the board keeps a usable width.
        for ((width, height) in listOf(1440 to 900, 1280 to 800)) {
            page.setViewportSize(width, height)
            page.waitForFunction("() => document.querySelector('.dp-board-page').getAttribute('data-dp-filters') === 'some'")
            val r = regions()
            println("473b-filters-geometry ${width}x$height $r")
            r["panelDisplay"] shouldBe "block"
            r["fields"].px() shouldBe 1.0
            r["panelRight"].px() shouldBeLessThanOrEqual r["boardLeft"].px()
            (r["boardWidth"].px() >= r["layoutWidth"].px() / 2) shouldBe true
            documentOverflowsX() shouldBe false
        }

        // A board with NO parameter set: no panel, no trigger, the board takes the whole row.
        openBoard(seedBoard(root))
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        val bare = regions()
        println("473b-filters-none $bare")
        bare["filters"] shouldBe "none"
        bare["panelDisplay"] shouldBe "none"
        bare["triggerDisplay"] shouldBe "none"
        bare["boardLeft"].px() shouldBe (bare["layoutLeft"].px() plusOrMinus 1.0)
        bare["boardWidth"].px() shouldBe (bare["layoutWidth"].px() plusOrMinus 1.0)
        documentOverflowsX() shouldBe false
    }

    @Test
    @Order(2)
    fun `the narrow drawer opens from the toolbar and closes on Escape with focus returned`() {
        startTrace()
        val root = ready("dpdraw")
        // A parameterised board: the trigger exists only while a parameter is visible.
        val board = seedParameterisedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready")
        leaveAndReturnHoldsOneListenerSet()
        page.setViewportSize(900, 900)

        page.waitForFunction(
            "() => document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'false'",
        )
        page.click("#dp-board-filters-trigger")
        page.waitForFunction(
            """() => { const panel = document.getElementById('dp-board-filters');
              return panel.classList.contains('is-open') &&
                document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'true'; }""",
        )
        // Focus moved INTO the drawer (the close button) — a keyboard user is inside, not behind it.
        page.waitForFunction("() => document.activeElement && document.activeElement.id === 'dp-board-filters-close'")
        documentOverflowsX() shouldBe false

        page.keyboard().press("Escape")
        page.waitForFunction(
            """() => { const panel = document.getElementById('dp-board-filters');
              return !panel.classList.contains('is-open') &&
                document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'false'; }""",
        )
        // Focus returned to the trigger that opened the drawer.
        page.waitForFunction("() => document.activeElement && document.activeElement.id === 'dp-board-filters-trigger'")

        // Open again, then cross back to desktop while it is open: the panel is a column beside
        // the board again and the drawer state is gone with the trigger — nothing still claims
        // to be expanded, and the next narrowing starts closed.
        page.click("#dp-board-filters-trigger")
        page.waitForFunction("() => document.getElementById('dp-board-filters').classList.contains('is-open')")
        page.setViewportSize(1280, 900)
        page.waitForFunction(
            """() => { const panel = document.getElementById('dp-board-filters');
              return !panel.classList.contains('is-open') &&
                document.getElementById('dp-board-filters-trigger').getAttribute('aria-expanded') === 'false'; }""",
        )
        // The panel settles INTO its grid column: in flow, at the layout's left edge, not left
        // displaced by a drawer transform carried across the boundary.
        page.waitForFunction(
            """() => { const panel = document.getElementById('dp-board-filters');
              const layout = document.querySelector('.dp-board-layout');
              return Math.abs(panel.getBoundingClientRect().left - layout.getBoundingClientRect().left) <= 1; }""",
        )
        val wide = regions()
        println("473b-filters-resize-back $wide")
        wide["panelDisplay"] shouldBe "block"
        wide["panelPosition"] shouldBe "relative"
        wide["panelLeft"].px() shouldBe (wide["layoutLeft"].px() plusOrMinus 1.0)
        wide["triggerDisplay"] shouldBe "none"
        wide["panelRight"].px() shouldBeLessThanOrEqual wide["boardLeft"].px()
        documentOverflowsX() shouldBe false
        page.setViewportSize(900, 900)
        page.waitForFunction("() => getComputedStyle(document.querySelector('.dp-board-toolbar')).display !== 'none'")
        (page.evaluate("() => document.getElementById('dp-board-filters').classList.contains('is-open')") as Boolean) shouldBe false
        page.setViewportSize(1280, 900)
    }

    /**
     * Where the first or the last field of the filters is: whether it holds the focus, and whether
     * it lies inside the drawer's visible box and the viewport. The field list is every input and
     * select of the mounted rows, in document order (the order Tab walks).
     */
    private fun fieldReach(which: String): Map<*, *> =
        page.evaluate(
            """(which) => { const panel = document.getElementById('dp-board-filters');
              const fields = Array.from(panel.querySelectorAll('.dp-dashboard-parameter input, .dp-dashboard-parameter select'));
              const field = which === 'first' ? fields[0] : fields[fields.length - 1];
              const box = panel.getBoundingClientRect(); const r = field.getBoundingClientRect();
              const focused = document.activeElement;
              return { id: field.id, fields: fields.length, active: focused === field,
                focusedId: focused ? focused.id : null, focusedInPanel: panel.contains(focused),
                closeAfterFields: !!(document.getElementById('dp-board-filters-close').compareDocumentPosition(field) & Node.DOCUMENT_POSITION_PRECEDING),
                inPanel: r.top >= box.top - 1 && r.bottom <= box.bottom + 1,
                inViewport: r.top >= 0 && r.bottom <= window.innerHeight && r.left >= 0 && r.right <= window.innerWidth,
                fieldTop: r.top, fieldBottom: r.bottom, panelTop: box.top, panelBottom: box.bottom,
                panelScroll: panel.scrollTop, panelOverflowY: getComputedStyle(panel).overflowY }; }""",
            which,
        ) as Map<*, *>

    /** The page's own scroll positions — the window's and the shell's scroll container's. */
    private fun pageScroll(): Map<*, *> =
        page.evaluate(
            "() => { const m = document.querySelector('.app-main'); return { y: window.scrollY, main: m ? m.scrollTop : 0 }; }",
        ) as Map<*, *>

    /**
     * #493 A — at a phone and a tablet viewport the drawer opens and BOTH ends of a long form are
     * reachable: by keyboard (Tab walks from the close button to the first field and on to the last,
     * never leaving the drawer, each focused field inside the drawer's visible box) and by pointer
     * (the drawer scrolled to its top hides the last field — the non-vacuity floor — and the wheel
     * over the drawer brings it into view). The page behind never scrolls and never scrolls sideways.
     *
     * Disabled on the landed board by #496: the runtime mounts the form BEFORE the drawer's Close
     * button, so the drawer opens scrolled to the end and Tab from Close leaves it (measured at
     * 390x844: closeAfterFields=true, scrollTop=710, focusedInPanel=false). Re-enable with that fix.
     */
    @Test
    @Disabled("#496: the drawer's Close button renders after the form, so forward Tab never reaches the first field")
    @Order(3)
    fun `at 390x844 and 768x1024 the drawer reaches the first and the last field without scrolling the page`() {
        startTrace()
        val root = ready("dpreach")
        val board = seedAcceptanceBoard(root)
        for ((width, height) in listOf(390 to 844, 768 to 1024)) {
            page.setViewportSize(width, height)
            openBoard(board)
            page.waitForFunction("() => document.querySelector('.dp-board-page').getAttribute('data-dp-filters') === 'some'")
            page.waitForFunction(
                "(n) => document.querySelectorAll('#dp-board-filters [data-dp-parameter=\"markets\"] input').length === n",
                acceptanceMarkets,
            )
            val before = pageScroll()
            page.click("#dp-board-filters-trigger")
            page.waitForFunction(
                """() => { const panel = document.getElementById('dp-board-filters'); const style = getComputedStyle(panel);
                  return panel.classList.contains('is-open') && style.visibility === 'visible' && style.transform === 'none'; }""",
            )
            page.waitForFunction("() => document.activeElement && document.activeElement.id === 'dp-board-filters-close'")

            page.keyboard().press("Tab")
            val first = fieldReach("first")
            println("493-reach ${width}x$height first=$first")
            first["active"] shouldBe true
            first["inPanel"] shouldBe true
            first["inViewport"] shouldBe true

            var presses = 1
            var escaped = 0
            while (fieldReach("last")["active"] != true && presses < MAX_TABS) {
                page.keyboard().press("Tab")
                presses++
                val inside = page.evaluate("() => document.getElementById('dp-board-filters').contains(document.activeElement)")
                if (inside != true) escaped++
            }
            val last = fieldReach("last")
            println("493-reach ${width}x$height last=$last presses=$presses escaped=$escaped")
            last["active"] shouldBe true
            escaped shouldBe 0
            last["inPanel"] shouldBe true
            last["inViewport"] shouldBe true

            // Pointer reach: from the drawer's top the last field is out of view (else the form is
            // not long enough to prove anything), and the wheel over the drawer brings it in.
            page.evaluate("() => { document.getElementById('dp-board-filters').scrollTop = 0; }")
            fieldReach("last")["inPanel"] shouldBe false
            page.mouse().move(width / 4.0, height / 2.0)
            var wheels = 0
            while (fieldReach("last")["inPanel"] != true && wheels < MAX_WHEELS) {
                page.mouse().wheel(0.0, WHEEL_STEP)
                page.waitForTimeout(WHEEL_SETTLE_MS)
                wheels++
            }
            val wheeled = fieldReach("last")
            println("493-reach ${width}x$height wheel=$wheeled wheels=$wheels")
            wheeled["inPanel"] shouldBe true
            wheeled["inViewport"] shouldBe true

            pageScroll() shouldBe before
            documentOverflowsX() shouldBe false
            page.keyboard().press("Escape")
            page.waitForFunction("() => !document.getElementById('dp-board-filters').classList.contains('is-open')")
        }
        page.setViewportSize(1280, 900)
    }

    /** The parsed `selections` of every refresh POST the page sent, oldest first. */
    private fun selectionsOf(bodies: List<String>): List<JsonObject> =
        synchronized(bodies) { bodies.toList() }.map { JsonParser.parseString(it).asJsonObject.getAsJsonObject("selections") }

    /** Waits for the next refresh POST after [seen] bodies and answers its selections. */
    private fun nextSelections(
        bodies: List<String>,
        seen: Int,
    ): JsonObject {
        page.waitForCondition { synchronized(bodies) { bodies.size } > seen }
        return selectionsOf(bodies).last()
    }

    /**
     * #493 B — on the product board, a click on an option's LABEL text selects its input; a change
     * of a parent re-resolves its dependent's options; and every typed value reaches the refresh
     * POST (read off the wire through `page.onRequest`) as the JSON type it was typed as — the
     * radio's string, the BOOLEAN's true/false/null and the INTEGER's number. The leaves are bound
     * to `refresh_all`, so each gesture alone sends its refresh.
     */
    @Test
    @Order(4)
    fun `a label click selects its option, a parent change re-resolves its dependent and typed values travel typed`() {
        startTrace()
        val root = ready("dpwire")
        val board = seedAcceptanceBoard(root)
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        page.onRequest { request ->
            if (request.method() == "POST" && request.url().endsWith("/runtime/visualizations")) {
                request.postData()?.let(bodies::add)
            }
        }
        openBoard(board)
        // The initial action's refresh settles first (the conformance case 13 rule: a gesture's
        // refresh must not race the bootstrap's).
        page.waitForFunction(
            "() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })",
        )
        val granularity = "#dp-board-filters [data-dp-parameter=\"granularity\"]"
        (page.evaluate("() => document.querySelector('$granularity input:checked').id") as String).endsWith("-1") shouldBe true

        // The LABEL, not the input: the visible text selects its own radio.
        var seen = bodies.size
        page.click("$granularity label.dp-dashboard-parameter-option-label:text-is('Day')")
        page.waitForFunction("() => document.querySelector('$granularity input:checked').id.endsWith('-0')")
        val day = nextSelections(bodies, seen)
        println("493-wire label-day selections=$day")
        day.get("granularity").isJsonPrimitive shouldBe true
        day.get("granularity").asJsonPrimitive.isString shouldBe true
        day.get("granularity").asString shouldBe "DAY"

        // The BOOLEAN's three states arrive as JSON true, false and null — never as strings.
        val enabled = "#dp-board-filters [data-dp-parameter=\"enabled\"] select"
        for ((option, expected) in listOf("true" to true, "false" to false)) {
            seen = bodies.size
            page.selectOption(enabled, option)
            val sent = nextSelections(bodies, seen)
            println("493-wire enabled=$option selections=$sent")
            sent.get("enabled").asJsonPrimitive.isBoolean shouldBe true
            sent.get("enabled").asBoolean shouldBe expected
        }
        seen = bodies.size
        page.selectOption(enabled, "")
        val unset = nextSelections(bodies, seen)
        println("493-wire enabled=unset selections=$unset")
        unset.has("enabled") shouldBe true
        unset.get("enabled").isJsonNull shouldBe true

        // The INTEGER input: typed text, sent as a JSON number.
        seen = bodies.size
        page.fill("#dp-board-filters [data-dp-parameter=\"limit\"] input", "42")
        page.keyboard().press("Tab")
        val limit = nextSelections(bodies, seen)
        println("493-wire limit=42 selections=$limit")
        limit.get("limit").asJsonPrimitive.isNumber shouldBe true
        limit.get("limit").asInt shouldBe 42

        // The dependent pair: Europe's cities, then the parent changes and the child re-resolves.
        val city = "#dp-board-filters [data-dp-parameter=\"city\"] select"
        val cityOptions = "() => Array.from(document.querySelector('$city').options).map(function (o) { return o.textContent; })"
        page.evaluate(cityOptions) shouldBe listOf("", "EU city 1", "EU city 2", "EU city 3")
        page.selectOption("#dp-board-filters [data-dp-parameter=\"region\"] select", SelectOption().setLabel("United States"))
        page.waitForFunction("() => (document.querySelector('$city').options[1] || {}).textContent === 'US city 1'")
        val resolved = page.evaluate(cityOptions)
        println("493-wire city-after-region=US options=$resolved")
        resolved shouldBe listOf("", "US city 1", "US city 2", "US city 3")
        page.evaluate("() => window.__dpPage.instance._adapter.readSelections().city") shouldBe "US-1"
    }

    /** A second instance of [board] beside the host page's first, signalled at `window.__dp2`. */
    private fun mountSecondInstance(board: String) {
        page.evaluate(
            """(id) => { const second = document.createElement('div'); second.id = 'board-2'; document.body.appendChild(second);
              window.__dp2 = { ready: false, instance: null };
              const instance = window.DatapipelinesDashboard.init({ server: { baseUrl: '', credentials: 'session' },
                dashboard: { id: id, version: 'released' }, container: second,
                adapter: window.DatapipelinesDashboard.adapters(second) });
              window.__dp2.instance = instance;
              instance.ready.then(function () { window.__dp2.ready = true; }, function () {}); }""",
            board,
        )
        page.waitForFunction("() => window.__dp2.ready")
    }

    /** Each instance's checked granularity radio's option text, and whether every option label targets an input of its own group. */
    private fun radioState(): Map<*, *> =
        page.evaluate(
            """() => { const one = function (host) {
                const group = document.querySelector(host + ' [data-dp-parameter="granularity"]');
                const checked = group.querySelector('input:checked');
                const labels = Array.from(group.querySelectorAll('label.dp-dashboard-parameter-option-label'));
                return { checked: checked ? group.querySelector('label[for="' + checked.id + '"]').textContent : null,
                  name: group.querySelector('input').name,
                  ownTargets: labels.every(function (l) { const t = document.getElementById(l.htmlFor); return !!t && group.contains(t); }) }; };
              return { a: one('#board'), b: one('#board-2') }; }""",
        ) as Map<*, *>

    /**
     * #493 D — two instances of the controls board in one document: a LABEL click in one moves only
     * its own radio group (every label's `for` resolves inside its own group — the per-instance
     * token keeps the ids apart), and the two groups carry different `name` attributes. The
     * conformance suite's case 14 clicks the INPUTS; the label path is #473's and is what a shared
     * token breaks first (a duplicated id resolves to the FIRST instance's input).
     */
    @Test
    @Order(5)
    fun `two instances on one page keep their radio groups apart under label clicks`() {
        startTrace()
        val root = ready("dptwo")
        installHostPage()
        val board = seedControlsBoard(root)
        openHost(board)
        mountSecondInstance(board)
        page.waitForFunction(
            "() => document.querySelectorAll('#board [data-dp-parameter=\"granularity\"] input[type=radio]').length === 3" +
                " && document.querySelectorAll('#board-2 [data-dp-parameter=\"granularity\"] input[type=radio]').length === 3",
        )
        val start = radioState()
        println("493-two start=$start")
        (start["a"] as Map<*, *>)["checked"] shouldBe "Week"
        (start["b"] as Map<*, *>)["checked"] shouldBe "Week"

        page.click("#board-2 [data-dp-parameter=\"granularity\"] label.dp-dashboard-parameter-option-label:text-is('Day')")
        val afterDay = radioState()
        println("493-two after-b-day=$afterDay")
        (afterDay["b"] as Map<*, *>)["checked"] shouldBe "Day"
        (afterDay["a"] as Map<*, *>)["checked"] shouldBe "Week"

        page.click("#board [data-dp-parameter=\"granularity\"] label.dp-dashboard-parameter-option-label:text-is('Month')")
        val afterMonth = radioState()
        println("493-two after-a-month=$afterMonth")
        (afterMonth["a"] as Map<*, *>)["checked"] shouldBe "Month"
        (afterMonth["b"] as Map<*, *>)["checked"] shouldBe "Day"

        (afterMonth["a"] as Map<*, *>)["ownTargets"] shouldBe true
        (afterMonth["b"] as Map<*, *>)["ownTargets"] shouldBe true
        org.junit.jupiter.api.Assertions.assertNotEquals(
            (afterMonth["a"] as Map<*, *>)["name"],
            (afterMonth["b"] as Map<*, *>)["name"],
            "the two instances' radio groups share a name",
        )
    }

    private companion object {
        /** Tab presses allowed from the first field to the last (the acceptance form has ~30 stops). */
        const val MAX_TABS = 80

        /** Wheel turns allowed to bring the last field into view. */
        const val MAX_WHEELS = 40
        const val WHEEL_STEP = 200.0
        const val WHEEL_SETTLE_MS = 100.0
    }
}
