package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 106 §B — the explorers' detail pane measured at the seven widths the owner's machines and
 * phones actually are: 390, 768, 1100, 1440, 1920, 2560, 3491.
 *
 * ## What each width is asked
 *
 * | width | the shape | what is asserted |
 * |---|---|---|
 * | 390 | drawer; one-column key/value; no tab counts | the explorer region fits its own box |
 * | 768 | drawer; the detail is the full width | the drawer opens AND closes on selection |
 * | 1100 | tree \| detail, the detail's columns STACKED | reading and acting share a left edge |
 * | 1440+ | tree \| detail, the detail SIDE BY SIDE | acting starts to the RIGHT of reading |
 *
 * and every width is asked the two questions that do not vary: `scrollWidth <= innerWidth`
 * (the `AppShellBrowserTest` assertion — a page that scrolls sideways is broken at any size)
 * and the layout-shift score of a SELECTION, which must stay under 0.05.
 *
 * ## Why the measurement is the geometry and not only CLS
 *
 * The lesson [ExplorerPaneGeometryBrowserTest] paid for: `PerformanceObserver('layout-shift')`
 * scores a wrong-from-the-start layout at 0.0000, because inserting content in the wrong
 * geometry is not a *shift*. So the columns' geometry is read directly at each width, and CLS
 * is the second instrument rather than the only one.
 *
 * The screenshots at the bottom are the handback's evidence, light and dark, at all seven.
 */
class ExplorerDetailBrowserTest : BrowserSuite() {
    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("det-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("detws-" + generatedPassword("w").take(8).lowercase())
    }

    /**
     * A pipeline and a template, both in a folder — the root renders folders only (§4.1), so a
     * flat row would give the tree nothing to open and every assertion below would measure an
     * empty pane. The REST seeding is [ExplorerStressBrowserTest]'s: cookie session plus the
     * dp_csrf double-submit pair, in-page.
     */
    private fun seed() {
        page.navigate("$baseUrl/dashboard")
        postJson(
            "/api/v1/templates",
            """{"id":"test/detail_probe","type":"sql","dialect":"POSTGRES",""" +
                """"display_name":"detail_probe","description":"106 detail fixture","body":"SELECT 1"}""",
        )
        postJson(
            "/api/v1/pipelines",
            """{"name":"test/detail_probe","display_name":"detail_probe",""" +
                // 138 §E: the sectioned shape the skill teaches — label, line, blank line, label…
                // The JSON carries real newlines (\n escapes); the first section keeps the 106
                // sentence, long enough to exercise the 78ch measure.
                """"description":"Question\nThe 106 detail fixture — long enough to exercise the 78ch reading measure in the """ +
                """overview card without wrapping into the acting column beside it.\n\nWindow and door\nOne year, chosen by year.",""" +
                """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )
    }

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

    /** Opens the tree's one folder and SELECTS its leaf — the state every assertion is about. */
    private fun selectLeaf(open: Boolean = true) {
        if (open) openDrawerIfPresent()
        page.waitForSelector("summary.tpl-summary")
        if (page.locator("details.tpl-folder[open]").count() == 0) {
            page.waitForResponse({ it.url().contains("prefix=test") }) {
                page.locator("summary.tpl-summary").first().click()
            }
        }
        page.waitForSelector("button.tpl-leaf")
        page.waitForResponse({ it.url().contains("/detail") || it.url().contains("/versions") }) {
            page.locator("button.tpl-leaf").first().click()
        }
        page.waitForSelector(".tplx-detail-header")
    }

    /**
     * Waits for the drawer to be CLOSED and SETTLED.
     *
     * The class is removed the moment the selection lands; the pane then slides out over
     * `--duration-fast`. Waiting on the class alone leaves a tree half-way across the screen —
     * which is what the first 390px screenshot of this round showed. The wait is on the
     * geometry, which is the fact, not on a duration, which would be a guess.
     */
    private fun waitForDrawerClosed() {
        page.waitForFunction(
            """
            () => {
              const body = document.querySelector('.tplx-body');
              if (!body || body.classList.contains('is-drawer-open')) return false;
              const tree = document.querySelector('.tplx-tree');
              if (!tree || getComputedStyle(tree).position !== 'fixed') return true;
              return tree.getBoundingClientRect().right <= 1;
            }
            """.trimIndent(),
        )
    }

    /** Below 1100px the tree is behind the Browse button; above it, the button is not rendered. */
    private fun openDrawerIfPresent() {
        val browse = page.locator("[data-explorer-drawer-open]")
        if (browse.count() > 0 && browse.first().isVisible) {
            browse.first().click()
            page.waitForSelector(".tplx-body.is-drawer-open")
        }
    }

    private fun overflow(): Long =
        (page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toLong()

    /** The offenders, named — "the page overflows by 9px" is a fact nobody can act on. */
    private fun culprits(): String =
        page
            .evaluate(
                """
                () => Array.from(document.querySelectorAll('*'))
                  .filter(e => e.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
                  .slice(0, 5)
                  .map(e => (e.tagName + (typeof e.className === 'string' && e.className.trim()
                    ? '.' + e.className.trim().split(/\s+/).join('.') : '')))
                  .join(' | ')
                """.trimIndent(),
            ).toString()

    /** The two columns' boxes, read in ONE evaluate so they describe the same frame. */
    private val columnProbe =
        """
        () => {
          const r = document.querySelector('.tplx-read');
          const a = document.querySelector('.tplx-act');
          if (!r || !a) return null;
          const rb = r.getBoundingClientRect(), ab = a.getBoundingClientRect();
          return { readLeft: rb.left, readRight: rb.right, actLeft: ab.left, actTop: ab.top, readTop: rb.top };
        }
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun columns(): Map<String, Any?> = page.evaluate(columnProbe) as Map<String, Any?>

    private fun Map<String, Any?>.d(key: String) = (this[key] as Number).toDouble()

    // ------------------------------------------------------------------- §B

    @Test
    fun `no explorer width scrolls the document sideways, from 768 to a 3491px window`() {
        startTrace()
        ready()
        seed()

        val offenders = mutableListOf<String>()
        WIDTHS.filter { it.first >= SHELL_FLOOR }.forEach { (w, h) ->
            listOf("/pipelines", "/templates").forEach { route ->
                page.setViewportSize(w, h)
                page.navigate("$baseUrl$route")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                selectLeaf()
                page.waitForLoadState(LoadState.NETWORKIDLE)
                val extra = overflow()
                if (extra > 0) offenders += "$route at ${w}x$h overflows by ${extra}px — ${culprits()}"
            }
        }
        offenders shouldBe emptyList()
    }

    /**
     * At 390 the DOCUMENT overflows on every screen in the app, including `/dashboard`, which
     * has no explorer on it at all — measured on this branch: dashboard 154px, executions 139,
     * datasources 275, api-console 75. The culprit is `HEADER.app-topbar` (the crumbs, the
     * icon buttons, the user chip) inside the shell's fixed `--rail-w` grid, and both
     * `app.css` and the shell layout belong to 103. Reported in the 106 handback; not fixed
     * here, because a lane that reaches into another lane's stylesheet to make its own
     * assertion pass is how two rounds of work get lost.
     *
     * What 106 CAN be held to at 390 is its own region, and that is what this asserts: the
     * explorer page does not overflow ITS box, and nothing inside the detail pane sticks out
     * past the detail pane. The assertion goes red the moment a chip row, a key/value strip or
     * a parameters table is what widens the phone layout — which is the failure this width was
     * added to catch.
     */
    @Test
    fun `at 390 the explorer region fits its own box, whatever the shell around it does`() {
        startTrace()
        ready()
        seed()

        page.setViewportSize(390, 844)
        listOf("/pipelines", "/templates").forEach { route ->
            page.navigate("$baseUrl$route")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            selectLeaf()
            page.waitForLoadState(LoadState.NETWORKIDLE)

            val regionOverflow =
                (
                    page.evaluate(
                        "() => { const p = document.querySelector('.tplx-page'); return p.scrollWidth - p.clientWidth; }",
                    ) as Number
                ).toLong()
            withClue({ "$route: the explorer region overflows by ${regionOverflow}px — ${insideCulprits(".tplx-page")}" }) {
                regionOverflow shouldBeLessThanOrEqual 1L
            }

            // Nothing inside the detail sticks out of it: a table or a chip row that does is
            // exactly what "tables scroll inside their cards, never the page" forbids.
            val stickingOut =
                page
                    .evaluate(
                        """
                        () => {
                          const d = document.querySelector('.tplx-detail');
                          const edge = d.getBoundingClientRect().right;
                          return Array.from(d.querySelectorAll('*'))
                            .filter(e => e.getBoundingClientRect().right > edge + 1)
                            .slice(0, 5)
                            .map(e => (e.tagName + (typeof e.className === 'string' && e.className.trim()
                              ? '.' + e.className.trim().split(/\s+/).join('.') : '')))
                            .join(' | ');
                        }
                        """.trimIndent(),
                    ).toString()
            stickingOut shouldBe ""
        }
    }

    @Test
    fun `at 1440 and above the detail is two columns, and at 1100 they stack`() {
        startTrace()
        ready()
        seed()

        page.setViewportSize(1100, 900)
        page.navigate("$baseUrl/pipelines")
        selectLeaf()
        val stacked = columns()
        // STACKED: the acting column starts on the reading column's left edge, below it.
        stacked.d("actLeft") shouldBe stacked.d("readLeft")
        stacked.d("actTop") shouldBeGreaterThan stacked.d("readTop")

        listOf(1440, 1920, 2560, 3491).forEach { width ->
            page.setViewportSize(width, 900)
            page.navigate("$baseUrl/pipelines")
            selectLeaf()
            val side = columns()
            // SIDE BY SIDE: acting begins past reading's right edge, on the same top line.
            side.d("actLeft") shouldBeGreaterThan side.d("readRight") - 1.0
            side.d("actTop") shouldBe side.d("readTop")
        }
    }

    /**
     * #240 — a long line in the source excerpt scrolls inside its card; it never sizes the
     * reading column. The excerpt is a `<pre>`, so its longest line is its min-content, and while
     * `.tplx-read`'s one track was a bare `auto` that line sized BOTH reading cards (the track
     * is shared) past the column: at 1440 7e's 63-character jsonata line put their right edge at
     * 1163 against the acting column's left edge at 1088, and an 84-character SQL line put it at
     * 1310 (222px under the acting card) and, at 1100, stacked, 134px past the column and off the
     * window (measured on a3e79706). The SQL fixture here is longer still (126 characters), so it
     * needs more room than the reading column has at every desktop width, 1920 included.
     *
     * Asserted at the three desktop widths, for both fixtures: every reading card ends inside the
     * reading column, and side by side (1440, 1920) left of the acting column. The Source tab's
     * full body is held to the same rule inside the acting card — it already scrolled there;
     * this keeps it so. Non-vacuity: the width the Overview card would need to show the whole
     * line (the `<pre>`'s scroll width plus the card's chrome around it — the same number before
     * and after the fix) exceeds the reading column for the SQL fixture at every width and for
     * 7e's at 1440, the width 7e measured; a green run is a line that was contained, never one
     * that happened to fit. (7e's line fits a 1920 column outright, so it is not asked there.)
     */
    @Test
    fun `a long first excerpt line scrolls inside its card - the reading cards never run under the acting column`() {
        startTrace()
        ready()
        val slug = "longline" + generatedPassword("s").takeLast(6).lowercase()
        postJson(
            "/api/v1/templates",
            """{"id":"test/$slug/orders_sql","type":"sql","dialect":"POSTGRES","display_name":"orders_sql",""" +
                """"description":"#240 fixture","body":"$LONG_SQL_FIRST_LINE\nORDER BY order_id"}""",
        )
        // 7e's fixture, verbatim: the transform whose 63-character first line 7e measured.
        postJson(
            "/api/v1/templates",
            """{"id":"test/$slug/active_orders","type":"jsonata","display_name":"active_orders","description":"#240 fixture",""" +
                """"body":"[ inputs.orders[customer_id != null].{ \"order_id\": order_id } ]",""" +
                """"contract":{"mode":"table","inputs":{"orders":{"kind":"table","columns":[{"name":"order_id","type":"INTEGER"},""" +
                """{"name":"customer_id","type":"STRING","nullable":true}]}},""" +
                """"output":{"kind":"table","columns":[{"name":"order_id","type":"INTEGER"}]}},""" +
                """"invariants":[],"tests":[{"name":"empty input","input":{"inputs":{"orders":[]}},"expect":{"output":[]}}]}""",
        )

        val offenders = mutableListOf<String>()
        page.navigate("$baseUrl/templates")
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            listOf("orders_sql", "active_orders").forEach { leaf ->
                listOf(1100, 1440, 1920).forEach { width -> offenders += longExcerptOffenders(slug, leaf, width, theme) }
            }
        }
        offenders shouldBe emptyList()
    }

    /**
     * [leaf]'s detail at [width]: what ends past where it must — a reading card past its column
     * or (side by side) under the acting column, the Source tab's body past the acting column —
     * after asserting the fixture is not one that simply fits. Photographs the Overview first.
     */
    private fun longExcerptOffenders(
        slug: String,
        leaf: String,
        width: Int,
        theme: String,
    ): List<String> {
        page.setViewportSize(width, 900)
        openTemplate(slug, leaf)
        val edges = readingEdges()
        val where = "$leaf at $width ($theme)"
        val readRight = edges.d("readRight")
        val actLeft = edges.d("actLeft")
        // The measured edges, pass or fail — the before/after table of the handback.
        println("#240 $where: read ..$readRight, act $actLeft.., cards ..${edges["cards"]}, card needs ${edges["cardNeeds"]}")
        if (leaf == "orders_sql" || width == SIDE_BY_SIDE) {
            withClue({ "$where: the card needs ${edges.d("cardNeeds")}px for the line — the fixture must not fit its column" }) {
                edges.d("cardNeeds") shouldBeGreaterThan readRight - edges.d("readLeft")
            }
        }
        val offenders = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        (edges["cards"] as List<Number>).map { it.toDouble() }.forEachIndexed { i, right ->
            if (right > readRight + EDGE_SLACK) offenders += "$where: reading card $i ends at $right, past the reading column's $readRight"
            if (width >= SIDE_BY_SIDE && right > actLeft + EDGE_SLACK) {
                offenders += "$where: reading card $i ends at $right, under the acting column's left edge $actLeft"
            }
        }
        shot("long-excerpt-$leaf-$width-$theme", fullPage = true)
        page.locator("[data-tab-panel='template-tab-source']").click()
        page.waitForFunction("() => !document.getElementById('template-tab-source').hidden")
        val source = readingEdges()
        val sourceRight = source.d("sourceRight")
        val actRight = source.d("actRight")
        if (sourceRight > actRight + EDGE_SLACK) {
            offenders += "$where: the Source tab's body ends at $sourceRight, past the acting column's $actRight"
        }
        return offenders
    }

    /** The explorer's search for [slug], its [leaf] result selected — the detail pane, reading and acting columns. */
    private fun openTemplate(
        slug: String,
        leaf: String,
    ) {
        page.navigate("$baseUrl/templates?q=$slug")
        page.waitForResponse({ it.url().contains("/partials/templates/versions") }) {
            page.locator("button.tpl-result", Page.LocatorOptions().setHasText(leaf)).first().click()
        }
        page.locator("#template-detail .tplx-read .tplx-excerpt").waitFor()
    }

    /** The reading column, its cards' right edges, the acting column and the Source tab's body — ONE frame. */
    @Suppress("UNCHECKED_CAST")
    private fun readingEdges(): Map<String, Any?> =
        page.evaluate(
            """
            () => {
              const read = document.querySelector('#template-detail .tplx-read');
              const act = document.querySelector('#template-detail .tplx-act');
              const pre = read.querySelector('.tplx-excerpt');
              const card = pre.closest('.ds-card');
              const source = document.querySelector('#template-tab-source');
              const rb = read.getBoundingClientRect(), ab = act.getBoundingClientRect();
              return { readLeft: rb.left, readRight: rb.right, actLeft: ab.left, actRight: ab.right,
                       cards: [...read.children].map(c => c.getBoundingClientRect().right),
                       cardNeeds: pre.scrollWidth + card.getBoundingClientRect().width - pre.clientWidth,
                       sourceRight: source.hidden ? 0 : source.querySelector('pre').getBoundingClientRect().right };
            }
            """.trimIndent(),
        ) as Map<String, Any?>

    @Test
    fun `below 1100 the tree is a drawer - it opens on Browse and closes when a leaf is chosen`() {
        startTrace()
        ready()
        seed()

        page.setViewportSize(768, 900)
        page.navigate("$baseUrl/pipelines")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        // Closed by default: the detail owns the width, and the Browse button is the way in.
        page.locator(".tplx-body.is-drawer-open").count() shouldBe 0
        val browse = page.locator("[data-explorer-drawer-open]")
        browse.first().isVisible shouldBe true
        browse.first().getAttribute("aria-expanded") shouldBe "false"

        browse.first().click()
        page.waitForSelector(".tplx-body.is-drawer-open")
        browse.first().getAttribute("aria-expanded") shouldBe "true"

        // Choosing a leaf is what the drawer was opened FOR, so it closes on the swap.
        selectLeaf(open = false)
        // Closed AND off the screen — a drawer that stops half-way is still covering the answer.
        waitForDrawerClosed()
        page.locator(".tplx-detail-header").isVisible shouldBe true

        // …and above the breakpoint the button is not rendered at all: the tree is a column.
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/pipelines")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.locator("[data-explorer-drawer-open]").first().isVisible shouldBe false
    }

    @Test
    fun `a selection holds the layout-shift budget at every width`() {
        startTrace()
        ready()
        seed()

        WIDTHS.forEach { (w, h) ->
            page.setViewportSize(w, h)
            page.addInitScript(
                """
                window.__cls = 0;
                new PerformanceObserver((l) => { for (const e of l.getEntries())
                  if (!e.hadRecentInput) window.__cls += e.value; }).observe({type: 'layout-shift', buffered: true});
                """.trimIndent(),
            )
            page.navigate("$baseUrl/pipelines")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            page.evaluate("() => { window.__cls = 0; }")
            selectLeaf()
            page.waitForLoadState(LoadState.NETWORKIDLE)
            val cls = (page.evaluate("() => window.__cls") as Number).toDouble()
            cls shouldBeLessThan CLS_BUDGET
        }
    }

    /**
     * The first 1440px screenshot of this round showed a version row whose meta column was
     * about ten pixels wide: "just now · Browser User · 0 runs" set ONE CHARACTER PER LINE,
     * beside three ghost buttons in an `auto` track that would not shrink. Every assertion in
     * this class passed — the row existed, the text was in the DOM, nothing overflowed — which
     * is exactly the shape a green suite hides.
     *
     * So the READABLE WIDTH is asserted, not the presence: the meta column takes a real share
     * of the row and sets on a small number of lines. It goes red on the layout that was in
     * that screenshot.
     */
    @Test
    fun `a version row's meta column is readable, not one character per line`() {
        startTrace()
        ready()
        seed()

        listOf(1440, 1920, 2560).forEach { width ->
            page.setViewportSize(width, 900)
            page.navigate("$baseUrl/pipelines")
            selectLeaf()
            page.locator("#pipeline-tab-versions .tplx-vrow").first().waitFor()

            @Suppress("UNCHECKED_CAST")
            val meta =
                page.evaluate(
                    """
                    () => {
                      const m = document.querySelector('#pipeline-tab-versions .tplx-vmeta');
                      const row = m.closest('.tplx-vrow');
                      const line = parseFloat(getComputedStyle(m).lineHeight) || 16;
                      return { width: m.getBoundingClientRect().width,
                               rowWidth: row.getBoundingClientRect().width,
                               lines: m.getBoundingClientRect().height / line };
                    }
                    """.trimIndent(),
                ) as Map<String, Any?>

            withClue({ "at ${width}px the meta column is ${meta.d("width")}px of ${meta.d("rowWidth")}px" }) {
                meta.d("width") shouldBeGreaterThan META_MIN_WIDTH
            }
            withClue({ "at ${width}px the meta column sets on ${meta.d("lines")} lines" }) {
                meta.d("lines") shouldBeLessThan META_MAX_LINES
            }
        }
    }

    /**
     * 138 §E.1 — the description's blank line is a paragraph break on screen. The re-run's
     * descriptions had sections separated by blank lines, and the browser collapsed every
     * newline because the element had no `white-space` rule: two labelled sections read as one
     * run-on line. The assertion is geometric, not stylistic: the second section's label sits
     * at least two line-heights below the first (its line, the section's line, the blank line),
     * which is exactly what collapsing makes impossible — removed `u-pre-line`, the two labels
     * share a line and this goes red.
     */
    @Test
    fun `a description's blank line renders as a paragraph break - two sections, two blocks`() {
        startTrace()
        ready()
        seed()
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/pipelines")
        selectLeaf()
        page.locator("#pipeline-detail .tplx-measure").first().waitFor()

        @Suppress("UNCHECKED_CAST")
        val blocks =
            page.evaluate(
                """
                () => {
                  const p = document.querySelector('#pipeline-detail .tplx-measure');
                  const text = p.firstChild;
                  const top = (label) => {
                    const r = document.createRange();
                    const at = text.data.indexOf(label);
                    r.setStart(text, at); r.setEnd(text, at + label.length);
                    return r.getBoundingClientRect().top;
                  };
                  return { whiteSpace: getComputedStyle(p).whiteSpace,
                           line: parseFloat(getComputedStyle(p).lineHeight) || 16,
                           gap: top('Window and door') - top('Question') };
                }
                """.trimIndent(),
            ) as Map<String, Any?>

        blocks["whiteSpace"] shouldBe "pre-line"
        withClue({ "the second label sits ${blocks.d("gap")}px below the first at a ${blocks.d("line")}px line" }) {
            blocks.d("gap") shouldBeGreaterThan 2.0 * blocks.d("line")
        }
    }

    @Test
    fun `the tabs load once and swap - Runs and Usage arrive on the first click and not again`() {
        startTrace()
        ready()
        seed()

        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/pipelines")
        selectLeaf()

        // Versions is the FIRST PAINT — no request was needed for it.
        page.locator("#pipeline-tab-versions .tplx-vrow").first().waitFor()

        var runsRequests = 0
        page.onRequest { if (it.url().contains("/runs")) runsRequests++ }
        page.waitForResponse({ it.url().contains("/runs") }) {
            page.locator("[data-tab-panel='pipeline-tab-runs']").click()
        }
        page.waitForFunction("() => !document.getElementById('pipeline-tab-runs').hidden")

        // A second click swaps back to a panel that is already loaded: no second request.
        page.locator("[data-tab-panel='pipeline-tab-versions']").click()
        page.locator("[data-tab-panel='pipeline-tab-runs']").click()
        page.waitForTimeout(200.0)
        runsRequests shouldBe 1

        page.waitForResponse({ it.url().contains("/usage") }) {
            page.locator("[data-tab-panel='pipeline-tab-usage']").click()
        }
        page.waitForFunction("() => !document.getElementById('pipeline-tab-usage').hidden")
    }

    @Test
    fun `selecting another leaf replaces all three regions`() {
        startTrace()
        ready()
        seed()
        page.navigate("$baseUrl/dashboard")
        postJson(
            "/api/v1/pipelines",
            """{"name":"test/second_probe","display_name":"second_probe","nodes":[{"id":"fq",""" +
                """"type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )

        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/pipelines")
        selectLeaf()
        val first = page.locator(".tplx-detail-title").innerText()

        page.waitForResponse({ it.url().contains("/partials/pipelines/detail") }) {
            page.locator("button.tpl-leaf").nth(1).click()
        }
        page.waitForFunction(
            "(previous) => document.querySelector('.tplx-detail-title').innerText !== previous",
            first,
        )
        // The acting column came with it — a stale Versions list beside a new header would be
        // the failure a header-only assertion misses.
        page.locator("#pipeline-tab-versions .tplx-vrow").first().waitFor()
        page.locator("#pipeline-tab-runs").getAttribute("hidden").shouldBeHiddenAttribute()
    }

    @Test
    fun `the release button opens the 4_3d dialog - the plain confirm is gone`() {
        // SUPERSEDES 106's plain-confirm pin (data-verb-url/data-confirm/data-if-match):
        // 102 replaced the fetch-and-confirm wiring with the §4.3d dialog partials. The hash
        // precondition moved SERVER-side — the dialog's POST reads the draft's hash, and a
        // stale one is the service's pipeline.version.conflict (the golden path drives it).
        startTrace()
        ready()
        seed()
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/pipelines")
        selectLeaf()

        val release =
            page.locator(
                ".tplx-detail-actions button",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText("Release v1"),
            )
        release.isVisible shouldBe true
        page.waitForResponse("**/lifecycle/release*") { release.click() }
        page.locator("#px-dialog [data-lifecycle-dialog='pipeline-release']").waitFor()
        // No verb attributes anywhere on the pane — the fetch path is gone.
        page.locator("#pipeline-detail [data-verb-url]").count() shouldBe 0
        page.keyboard().press("Escape")
        page.locator("#px-dialog [data-lifecycle-dialog]").count() shouldBe 0
    }

    // ---------------------------------------------------------- the evidence

    @Test
    fun `the seven widths, light and dark, photographed for the handback`() {
        startTrace()
        ready()
        seed()

        page.navigate("$baseUrl/pipelines")
        ensureTheme("light") // the deployment default is dark; the light walk asks for light
        walk("light")
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/pipelines")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        // The deployment default is dark: a blind toggle would flip to LIGHT and the wait
        // for dark would match only by racing the swap (it did, on laptops; not on CI).
        ensureTheme("dark")
        walk("dark")
    }

    private fun walk(mode: String) {
        WIDTHS.forEach { (w, h) ->
            listOf("pipelines", "templates").forEach { screen ->
                page.setViewportSize(w, h)
                page.navigate("$baseUrl/$screen")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                selectLeaf()
                page.waitForLoadState(LoadState.NETWORKIDLE)
                // The drawer closes on the selection; photographing mid-transition would show a
                // state the user never rests in.
                waitForDrawerClosed()
                // The 390px shell overflows horizontally on every screen in the app (see the
                // 390 test's KDoc), so a click can leave the document scrolled right and the
                // shot would show the page cropped. Back to the origin before photographing.
                page.evaluate("() => window.scrollTo(0, 0)")
                shot("$screen-$w-$mode", fullPage = w >= 1100)
            }
        }
    }

    /** Which descendants of [selector] stick out past its right edge — named, not counted. */
    private fun insideCulprits(selector: String): String =
        page
            .evaluate(
                """
                (selector) => {
                  const root = document.querySelector(selector);
                  if (!root) return 'no ' + selector;
                  const edge = root.getBoundingClientRect().right;
                  return Array.from(root.querySelectorAll('*'))
                    .filter(e => e.getBoundingClientRect().right > edge + 1)
                    .slice(0, 6)
                    .map(e => {
                      const id = n => n.tagName + (n.id ? '#' + n.id : '')
                        + (typeof n.className === 'string' && n.className.trim()
                          ? '.' + n.className.trim().split(/\s+/).join('.') : '');
                      const chain = [];
                      for (let n = e; n && chain.length < 4; n = n.parentElement) chain.push(id(n));
                      const r = e.getBoundingClientRect();
                      return chain.join(' < ') + ' [' + Math.round(r.left) + '..' + Math.round(r.right)
                        + ' vs ' + Math.round(edge) + ']';
                    })
                    .join(' | ');
                }
                """.trimIndent(),
                selector,
            ).toString()

    private fun shotDir(): Path = Paths.get("build", "reports", "106-screenshots").also { it.toFile().mkdirs() }

    /**
     * A viewport shot below 1100px, a full-page shot above it.
     *
     * Playwright's full-page capture stitches scrolled bands and renders `position: fixed`
     * elements once — which is exactly what the drawer and its backdrop are, so a full-page
     * shot of the narrow layout shows a tree floating over content it is not over. The
     * viewport shot is what the phone actually looks like.
     */
    private fun shot(
        name: String,
        fullPage: Boolean,
    ) = page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("106-$name.png")).setFullPage(fullPage))

    private fun String?.shouldBeHiddenAttribute() {
        (this != null) shouldBe true
    }

    private companion object {
        /** The owner's own widths: a phone, a tablet, the stacked breakpoint, and four desktops. */
        val WIDTHS =
            listOf(390 to 844, 768 to 1024, 1100 to 900, 1440 to 900, 1920 to 1080, 2560 to 1440, 3491 to 1440)

        /** Google's "good" CLS is 0.1; the prompt's budget for a SELECTION is half of that. */
        const val CLS_BUDGET = 0.05

        /**
         * The narrowest width at which the app SHELL itself fits. Below it every screen
         * overflows, `/dashboard` included — see the 390 test's KDoc.
         */
        const val SHELL_FLOOR = 768

        /** A meta column narrower than this cannot set "3 days ago - someone - 7 runs" at all. */
        const val META_MIN_WIDTH = 120.0

        /** ...and it must not need more than a few lines to do it. */
        const val META_MAX_LINES = 4.0

        /** The detail's two columns sit side by side from this width (template-tree.css). */
        const val SIDE_BY_SIDE = 1440

        /** Sub-pixel rounding between two boxes that touch; anything past it is an overlap. */
        const val EDGE_SLACK = 0.5

        /** #240's SQL fixture: a 126-character first line, wider than the reading column at every desktop width. */
        const val LONG_SQL_FIRST_LINE =
            "SELECT order_id, customer_id, amount_cents, currency, placed_at FROM orders WHERE customer_id IS NOT NULL AND amount_cents > 0"
    }
}
