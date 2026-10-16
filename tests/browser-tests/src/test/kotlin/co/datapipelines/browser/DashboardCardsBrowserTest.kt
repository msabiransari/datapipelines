package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ReducedMotion
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #473 — the chart CARD, on the real board page: every chart is a bordered article whose heading
 * names the pinned visualization, and a failure speaks INSIDE the card — loud border first, then
 * the quiet issue border with a persistent message. Since #536 no state shows a badge: the border
 * is every state's voice, measured per phase in both themes by case 3.
 *
 * The DOM contract's unit half lives in `modules/web/src/test/js/dashboard-cards.test.mjs`; what
 * the page adds is the real vendored bytes, the real stream and the abort harness's deterministic
 * loud state (the hung board races nothing — the 60 s sleep outlives the case).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardCardsBrowserTest : DashboardBrowserSuite() {
    private fun openBoard(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        val code = page.evaluate("() => window.__dpPage.code") as String?
        if (code != null) {
            val error = page.evaluate("() => String(window.__dpPage.error)") as String
            throw AssertionError("the board page failed to boot: $code — $error")
        }
    }

    @Test
    @Order(1)
    fun `every chart is a card whose heading names the pinned visualization - and no ready badge shows`() {
        startTrace()
        val root = ready("dpcards")
        val board = seedBoard(root)
        openBoard(board)
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
        page.locator("#dp-board .dp-dashboard-table tbody tr").first().waitFor()
        page.locator("#dp-board .dp-dashboard-kpi-number").first().waitFor()

        // The card carries the pin (moved off the viz div), and the heading its label points at
        // reads the pinned VERSION's display name — the fixture's plotly body says "Chart", the
        // table and KPI bodies say their own names.
        val headings =
            page.evaluate(
                """() => Array.from(document.querySelectorAll('#dp-board article.dp-dashboard-card[data-dp-viz]'))
                  .map(function (card) {
                    const heading = document.getElementById(card.getAttribute('aria-labelledby'));
                    return { viz: card.getAttribute('data-dp-viz'), heading: heading ? heading.textContent : null };
                  })""",
            ) as List<*>
        val byViz = headings.filterIsInstance<Map<*, *>>().associateBy { it["viz"] as String }
        headings.size shouldBe 4
        byViz.keys shouldBe setOf("revenue", "cells", "total", "slowchart")
        byViz["cells"]!!["heading"] shouldBe "Cells"
        byViz["total"]!!["heading"] shouldBe "Total"
        page.evaluate(
            """() => {
              const card = document.querySelector('#dp-board article.dp-dashboard-card[data-dp-viz="total"]');
              const walker = document.createTreeWalker(card, NodeFilter.SHOW_TEXT);
              let count = 0;
              while (walker.nextNode()) {
                if (walker.currentNode.textContent.trim() === 'Total') count++;
              }
              return count;
            }""",
        ) shouldBe 1

        byViz["revenue"]!!["heading"] shouldBe "Chart"

        assertNoChipIsVisible()
    }

    /**
     * Settled boards are quiet: no error voice is on any card, and NO chip is ever visible (#536 — the border
     * is the state): every chip's box is at most one pixel on both axes, yet it stays in the render tree
     * (display is never none), so assistive technology still hears its role=status text.
     */
    private fun assertNoChipIsVisible() {
        val resting =
            page.evaluate(
                """() => Array.from(document.querySelectorAll('#dp-board article.dp-dashboard-card'))
                  .map(function (card) {
                    const chip = card.querySelector('.dp-dashboard-status');
                    const box = chip ? chip.getBoundingClientRect() : null;
                    const tokens = card.className.split(/\s+/);
                    return {
                      viz: card.getAttribute('data-dp-viz'),
                      chip: !!chip,
                      width: box ? box.width : -1,
                      height: box ? box.height : -1,
                      display: chip ? getComputedStyle(chip).display : null,
                      text: chip ? chip.textContent : null,
                      loud: tokens.indexOf('dp-dashboard-card--error') !== -1 || tokens.indexOf('dp-dashboard-card--abort') !== -1,
                    };
                  })""",
            ) as List<*>
        val chips = resting.filterIsInstance<Map<*, *>>()
        chips.size shouldBe 4
        chips.forEach {
            withClue("card ${it["viz"]}: $it") {
                (it["loud"] as Boolean) shouldBe false
                (it["chip"] as Boolean) shouldBe true
                (it["width"] as Number).toDouble() shouldBeLessThanOrEqual 1.0
                (it["height"] as Number).toDouble() shouldBeLessThanOrEqual 1.0
                (it["display"] as String) shouldNotBe "none"
                (it["text"] as String).isNotEmpty() shouldBe true
            }
        }
    }

    @Test
    @Order(2)
    fun `an abort raises the loud card, then the quiet issue border with a persistent message`() {
        startTrace()
        val root = ready("dpcabort2")
        val board = seedHungBoard(root)
        openBoard(board)
        val refreshId =
            page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'targets', targets: ['hungcells'] })") as String
        page.waitForSelector("[data-dp-viz='hungcells'] .dp-dashboard-status[data-dp-state='in-progress']")
        awaitRefreshStatus(board, refreshId, "RUNNING")

        // LOUD: the abort acknowledged through the page's own instance flips the card red with
        // a message inside it — the card's own classes, not the chip's.
        abortAndNameAck("window.__dpPage.instance", board, refreshId)
        chipStateIs("hungcells", "abort")
        val loud =
            page.evaluate(
                """() => { const card = document.querySelector("[data-dp-viz='hungcells']");
                  const message = card.querySelector('.dp-dashboard-card-message');
                  return { card: card.className, message: message ? message.textContent : null,
                           inside: message ? card.contains(message) : false }; }""",
            ) as Map<*, *>
        (loud["card"] as String).split(Regex("\\s+")) shouldContain "dp-dashboard-card--abort"
        (loud["message"] as String?)!!.isNotEmpty() shouldBe true
        (loud["inside"] as Boolean) shouldBe true

        // QUIET: after the accent window the red border retires; the issue border and the
        // message persist — the reader still knows the chart is not current. The chip itself
        // answers to the SERVER's later word (the cancelled target's stale/no-data frame re-renders
        // it, #356's abort chip stands only until then), so the settled-state check is that the
        // card stopped loading — the message and border carry the rest.
        page.waitForFunction(
            """() => (function () {
              const card = document.querySelector("[data-dp-viz='hungcells']");
              return card.className.indexOf('--has-issue') !== -1 && card.className.indexOf('--abort') === -1;
            })()""",
            null,
            com.microsoft.playwright.Page
                .WaitForFunctionOptions()
                .setTimeout(8_000.0),
        )
        val quiet =
            page.evaluate(
                """() => { const card = document.querySelector("[data-dp-viz='hungcells']");
                  return { card: card.className,
                           message: (card.querySelector('.dp-dashboard-card-message') || {}).textContent || '' }; }""",
            ) as Map<*, *>
        (quiet["card"] as String).contains("--has-issue") shouldBe true
        (quiet["message"] as String).isNotEmpty() shouldBe true
        chipStateIsNot("hungcells", "in-progress")
    }

    /**
     * #536 — the BORDER is every state's voice, measured: each phase's computed `border-top-color`
     * equals the design token it names, resolved in the page through an ATTACHED probe (a detached
     * one computes to ""), in both themes. Real phases where the page can reach them: the hung
     * board's refresh (busy), its abort (loud red, then the settled issue red), a real refresh of
     * the KPI (green, then the runtime's settle to rest), and the runtime's own parameter-change
     * stale path (`_markAllStale`). The `error` voice, which no seeded board fails into, is driven
     * through the composite adapter's renderStatus — the call the runtime itself makes — with a
     * stale reason so the settled phase also proves the issue border wins over stale.
     */
    @Test
    @Order(3)
    fun `the border carries every state - busy animates, success is green then rests, stale the warning accent, abort and error red`() {
        startTrace()
        val root = ready("dpcborder")
        // The pinned grid, so the evidence shots show cards at their real size.
        val board = seedBoardWithGrid(root)
        val hung = seedHungBoard(root)
        try {
            // Soft: every phase's verdict is collected, so one run names each state the sheet does not paint.
            assertSoftly {
                for (theme in listOf("light", "dark")) walkTheStates(theme, board, hung)
            }
        } finally {
            ensureTheme("light")
        }
    }

    /** Every border read's heading-line height, per card and phase: no state may resize the heading (#501). */
    private val headHeights = mutableListOf<Triple<String, String, Double>>()

    private fun walkTheStates(
        theme: String,
        board: String,
        hung: String,
    ) {
        headHeights.clear()
        walkTheSettledBoard(theme, board)
        walkTheHungBoard(theme, hung)
        recordHeadHeights(theme)
    }

    /** ready, success, success settled to ready, stale — on the bootstrap-settled board. */
    private fun walkTheSettledBoard(
        theme: String,
        board: String,
    ) {
        openBoard(board)
        ensureTheme(theme)
        page.waitForFunction("() => window.__dpPage.notifications.some(function (n) { return n.code === 'refresh.completed'; })")
        // The bootstrap's own success settles first, so the success read below is THIS refresh's.
        chipStateIs("total", "ready")
        borderIs("total", "var(--border-default)", "$theme ready at rest")

        page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'targets', targets: ['total'] })")
        chipStateIs("total", "success")
        borderIs("total", "var(--accent-success)", "$theme success")
        shot("$theme-success", "total")
        chipStateIs("total", "ready")
        borderIs("total", "var(--border-default)", "$theme success settled to ready")
        shot("$theme-ready", "total")

        page.evaluate("() => window.__dpPage.instance._markAllStale()")
        classHas("total", "dp-dashboard-card--stale")
        borderIs("total", "var(--accent-warning)", "$theme stale")
        shot("$theme-stale", "total")
    }

    /** busy (animated, then still under reduced motion), abort loud and settled, error loud and settled over stale. */
    private fun walkTheHungBoard(
        theme: String,
        hung: String,
    ) {
        openBoard(hung)
        val refreshId =
            page.evaluate("() => window.__dpPage.instance.refresh({ scope: 'targets', targets: ['hungcells'] })") as String
        chipStateIs("hungcells", "in-progress")
        awaitRefreshStatus(hung, refreshId, "RUNNING")
        classHas("hungcells", "dp-dashboard-card--busy")
        read("hungcells", "var(--accent-info)")["animation"] shouldBe "dp-card-busy"
        shot("$theme-busy", "hungcells")
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE))
        try {
            read("hungcells", "var(--accent-info)")["animation"] shouldBe "none"
            borderIs("hungcells", "var(--accent-info)", "$theme busy under reduced motion")
            shot("$theme-busy-reduced-motion", "hungcells")
        } finally {
            page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.NO_PREFERENCE))
        }

        abortAndNameAck("window.__dpPage.instance", hung, refreshId)
        chipStateIs("hungcells", "abort")
        borderIs("hungcells", "var(--accent-danger)", "$theme abort, loud")
        shot("$theme-abort-loud", "hungcells")
        classHas("hungcells", "dp-dashboard-card--has-issue")
        borderIs("hungcells", "var(--border-danger)", "$theme abort, settled")
        shot("$theme-abort-settled", "hungcells")

        page.evaluate(
            """() => window.__dpPage.instance._adapter.renderStatus({ name: 'hungcells', type: 'visualization' },
                 { state: 'error', stale: true, reason: { code: 'source_unavailable', stage: 'refresh' } })""",
        )
        chipStateIs("hungcells", "error")
        borderIs("hungcells", "var(--accent-danger)", "$theme error, loud")
        shot("$theme-error-loud", "hungcells")
        classHas("hungcells", "dp-dashboard-card--has-issue")
        classHas("hungcells", "dp-dashboard-card--stale")
        borderIs("hungcells", "var(--border-danger)", "$theme error settled - the issue border wins over stale")
        shot("$theme-error-settled", "hungcells")
    }

    /** Writes this theme's heading heights beside the shots and asserts each card's never changed across phases. */
    private fun recordHeadHeights(theme: String) {
        shotDir()
            .resolve("536-head-heights-$theme.txt")
            .toFile()
            .writeText(headHeights.joinToString("\n", postfix = "\n") { (viz, phase, height) -> "$viz\t$phase\t$height" })
        headHeights.groupBy { it.first }.forEach { (viz, reads) ->
            withClue("$theme: the heading line of $viz changed height across states: $reads") {
                reads.map { it.third }.toSet().size shouldBe 1
            }
        }
    }

    /** One card's computed border beside [token] resolved through an attached probe, and its animation name. */
    private fun read(
        viz: String,
        token: String,
    ): Map<*, *> = page.evaluate(READ_BORDER, listOf(viz, token)) as Map<*, *>

    /**
     * Waits for the card's border to land on [token] (the 150 ms colour transition runs first), then asserts it.
     * The wait stays inside the shortest phase it reads (the 3 s loud accent and success hold), so a red
     * read names the class of the phase it was waiting in, not the one after it. The wait is an in-page poll
     * that resolves false on its deadline instead of throwing: under assertSoftly a thrown timeout would end
     * the walk, while the soft assertion below records this phase and lets the next one run.
     */
    private fun borderIs(
        viz: String,
        token: String,
        phase: String,
    ) {
        page.evaluate(AWAIT_BORDER, listOf(viz, token, BORDER_WAIT_MS))
        val read = read(viz, token)
        headHeights += Triple(viz, phase, (read["head"] as Number).toDouble())
        withClue("$phase: card '${read["classes"]}' border ${read["border"]}, want $token = ${read["token"]}") {
            (read["token"] as String).isNotEmpty() shouldBe true
            read["border"] shouldBe read["token"]
        }
    }

    /** Waits for one class token on the card — the runtime's settle timer, never a sleep. */
    private fun classHas(
        viz: String,
        token: String,
    ) {
        page.waitForFunction(
            "([viz, token]) => { const c = document.querySelector('article.dp-dashboard-card[data-dp-viz=\"' + viz + '\"]');" +
                " return !!c && c.className.split(/\\s+/).indexOf(token) !== -1; }",
            listOf(viz, token),
        )
    }

    private fun shot(
        name: String,
        viz: String,
    ) {
        page
            .locator("article.dp-dashboard-card[data-dp-viz='$viz']")
            .screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("536-$name.png")))
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "536-screenshots").also { it.toFile().mkdirs() }

    private companion object {
        /** Reads every value while the probe is still ATTACHED, then removes it. */
        const val READ_BORDER =
            """([viz, token]) => {
              const card = document.querySelector('article.dp-dashboard-card[data-dp-viz="' + viz + '"]');
              const probe = document.createElement('span');
              probe.style.display = 'none';
              probe.style.borderTopStyle = 'solid';
              probe.style.borderTopColor = token;
              document.body.appendChild(probe);
              const resolved = getComputedStyle(probe).borderTopColor;
              probe.remove();
              const style = getComputedStyle(card);
              const head = card.querySelector('.dp-dashboard-card-head').getBoundingClientRect().height;
              return { border: style.borderTopColor, token: resolved, animation: style.animationName, classes: card.className, head: head };
            }"""

        /** Polls [READ_BORDER] until the border equals the token or the deadline passes; resolves true or false, never throws. */
        const val AWAIT_BORDER =
            """async ([viz, token, waitMs]) => {
              const read = $READ_BORDER;
              const deadline = Date.now() + waitMs;
              for (;;) {
                const r = read([viz, token]);
                if (r.border === r.token) return true;
                if (Date.now() >= deadline) return false;
                await new Promise(function (resolve) { setTimeout(resolve, 50); });
              }
            }"""

        const val BORDER_WAIT_MS = 2_000
    }
}
