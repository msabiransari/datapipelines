package co.datapipelines.browser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.SelectOption
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Paths

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
        // #492: this viewport also narrows the board region below its 820 px container boundary.
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
     * Opens the drawer from its trigger, waits until it is shown and Close holds the focus, and
     * answers [fieldReach] of the first field read at that moment (before any Tab), printed as the
     * #496 `496-drawer-open` line: the drawer's `scrollTop` on open and whether Close follows the form.
     */
    private fun openDrawer(viewport: String): Map<*, *> {
        page.click("#dp-board-filters-trigger")
        page.waitForFunction(
            """() => { const panel = document.getElementById('dp-board-filters'); const style = getComputedStyle(panel);
              return panel.classList.contains('is-open') && style.visibility === 'visible' && style.transform === 'none'; }""",
        )
        page.waitForFunction("() => document.activeElement && document.activeElement.id === 'dp-board-filters-close'")
        val opened = fieldReach("first")
        println("496-drawer-open $viewport scrollTop=${opened["panelScroll"]} closeAfterFields=${opened["closeAfterFields"]}")
        return opened
    }

    /**
     * #493 A — at a phone and a tablet viewport the drawer opens and BOTH ends of a long form are
     * reachable: by keyboard (Tab walks from the close button to the first field and on to the last,
     * never leaving the drawer, each focused field inside the drawer's visible box) and by pointer
     * (the drawer scrolled to its top hides the last field — the non-vacuity floor — and the wheel
     * over the drawer brings it into view). The page behind never scrolls and never scrolls sideways.
     *
     * #496: the pane mounts AFTER the drawer's Close button, so the drawer opens at its top (read
     * before the first Tab, asserted after the first-field reach) and Tab from Close enters the form.
     * Before the fix the form came first (measured at 390x844: closeAfterFields=true,
     * focusedInPanel=false, scrollTop=710 at #493 and 251 after #498's one-row options).
     */
    @Test
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
            val opened = openDrawer("${width}x$height")

            page.keyboard().press("Tab")
            val first = fieldReach("first")
            println("493-reach ${width}x$height first=$first")
            first["active"] shouldBe true
            first["inPanel"] shouldBe true
            first["inViewport"] shouldBe true
            opened["closeAfterFields"] shouldBe false
            opened["panelScroll"].px() shouldBe 0.0

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
        assertParentChangeStale(board)
    }

    /** A committed parent change leaves the retained chart visibly stale in both themes. */
    private fun assertParentChangeStale(board: String) {
        try {
            for (theme in listOf("light", "dark")) {
                ensureTheme(theme)
                openBoard(board)
                page.waitForFunction("() => window.__dpPage.notifications.some(n => n.code === 'refresh.completed')")
                val city = "#dp-board-filters [data-dp-parameter=\"city\"] select"
                val cityOptions = "() => Array.from(document.querySelector('$city').options).map(o => o.textContent)"
                page.evaluate(cityOptions) shouldBe listOf("", "EU city 1", "EU city 2", "EU city 3")
                val card = "article.dp-dashboard-card[data-dp-viz='acceptancechart']"
                page.evaluate("s => document.querySelector(s).classList.contains('dp-dashboard-card--stale')", card) shouldBe false
                page.selectOption("#dp-board-filters [data-dp-parameter=\"region\"] select", SelectOption().setLabel("United States"))
                page.waitForFunction(
                    "() => !window.__dpPage.instance._lock && window.__dpPage.instance._adapter.readSelections().city === 'US-1'",
                )
                page.evaluate(cityOptions) shouldBe listOf("", "US city 1", "US city 2", "US city 3")
                // #542: this is a real parent commit, after its dependent's new state applied.
                page.evaluate("s => document.querySelector(s).classList.contains('dp-dashboard-card--stale')", card) shouldBe true
                page.waitForFunction(STALE_BORDER, card)
                val border = page.evaluate(STALE_BORDER, card)
                println("542-stale-border $theme " + page.evaluate("() => window.__dp542Border"))
                border shouldBe true
                val dir = Paths.get("build", "reports", "542-screenshots").also { it.toFile().mkdirs() }
                page.locator(card).screenshot(Locator.ScreenshotOptions().setPath(dir.resolve("542-$theme-parent-stale.png")))
            }
        } finally {
            ensureTheme("light")
        }
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

    /**
     * #497 option (a), owner ruling 2026-10-09, refined 2026-10-10 — a typed value past `limit`'s
     * declared maximum stays in its field beside its error, and the board keeps the LAST VALID value:
     * the rejected commit starts no refresh (the gate refuses it while the state is invalid), a
     * change of ANOTHER control re-evaluates with 10 and its bound refresh carries 10 while the field
     * still shows `500` and its alert, and correcting the field to 50 clears the alert and refreshes
     * with 50. Every refresh body is read off the wire. The acceptance board's granularity defaults
     * to Week, so the other control's gesture is Month.
     */
    @Test
    @Order(6)
    fun `a rejected typed value stays in its field beside its error while the refreshes carry the last valid value`() {
        startTrace()
        val root = ready("dpkeep")
        val board = seedAcceptanceBoard(root)
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        page.onRequest { request ->
            if (request.method() == "POST" && request.url().endsWith("/runtime/visualizations")) {
                request.postData()?.let(bodies::add)
            }
        }
        openBoard(board)
        page.waitForFunction(
            "() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })",
        )
        val limit = "#dp-board-filters [data-dp-parameter=\"limit\"]"
        val alert = "$limit .dp-dashboard-parameter-error[role=alert]"
        val granularity = "#dp-board-filters [data-dp-parameter=\"granularity\"]"

        // 500 is past the maximum: the alert names it, the field keeps it, and no refresh leaves —
        // the bound refresh is refused aloud by the gate (parameters.invalid) instead.
        var seen = bodies.size
        page.fill("$limit input", "500")
        page.keyboard().press("Tab")
        page.waitForSelector(alert)
        page.waitForFunction(
            "() => window.__dpPage.notifications.some(function (n) { return n.code === 'parameters.invalid'; })",
        )
        val rejectedText = page.textContent(alert)
        val rejectedShown = page.inputValue("$limit input")
        println("497-keep rejected alert=$rejectedText shown=$rejectedShown refreshes-after=${bodies.size - seen}")
        rejectedShown shouldBe "500"
        rejectedText.contains("above the declared maximum 100") shouldBe true
        bodies.size shouldBe seen

        // Another control's change: the re-evaluation carries 10, its bound refresh carries 10, and
        // the field still shows the person's 500 beside its error.
        seen = bodies.size
        page.click("$granularity label.dp-dashboard-parameter-option-label:text-is('Month')")
        val month = nextSelections(bodies, seen)
        println("497-keep month selections=$month shown=${page.inputValue("$limit input")}")
        month.get("limit").asJsonPrimitive.isNumber shouldBe true
        month.get("limit").asInt shouldBe 10
        month.get("granularity").asString shouldBe "MONTH"
        page.inputValue("$limit input") shouldBe "500"
        page.textContent(alert).contains("above the declared maximum 100") shouldBe true

        // Corrected to 50: the alert goes and the next refresh carries 50.
        seen = bodies.size
        page.fill("$limit input", "50")
        page.keyboard().press("Tab")
        val corrected = nextSelections(bodies, seen)
        page.waitForSelector(alert, Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED))
        println("497-keep corrected selections=$corrected shown=${page.inputValue("$limit input")}")
        corrected.get("limit").asInt shouldBe 50
        corrected.get("granularity").asString shouldBe "MONTH"
        page.inputValue("$limit input") shouldBe "50"
    }

    private companion object {
        // Read the stale token from the stylesheet so a token change keeps this behavioural guard valid.
        const val STALE_BORDER =
            """(selector) => {
              const rule = Array.from(document.styleSheets).flatMap(s => Array.from(s.cssRules))
                .find(r => r.selectorText === '.dp-dashboard-card--stale');
              const probe = document.createElement('span');
              probe.style.borderTopStyle = 'solid';
              probe.style.borderTopColor = rule.style.borderColor;
              document.body.appendChild(probe);
              const token = getComputedStyle(probe).borderTopColor;
              const actual = getComputedStyle(document.querySelector(selector)).borderTopColor;
              probe.remove();
              window.__dp542Border = { actual, token, declaration: rule.style.borderColor };
              return !!token && actual === token;
            }"""

        /** Tab presses allowed from the first field to the last (the acceptance form has ~30 stops). */
        const val MAX_TABS = 80

        /** Wheel turns allowed to bring the last field into view. */
        const val MAX_WHEELS = 40
        const val WHEEL_STEP = 200.0
        const val WHEEL_SETTLE_MS = 100.0
    }
}
