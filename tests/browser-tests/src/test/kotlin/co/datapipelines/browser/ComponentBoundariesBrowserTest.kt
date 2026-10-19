package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.idIn
import co.datapipelines.browser.ScheduleFixtures.send
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.RequestOptions
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #609 — the shared components' edges outside the shell, each drawn with the role its rendered behaviour earns
 * (ui-screens §2 principle 8): a component boundary is `--border-default` at 3:1, a required internal division
 * `--border-subtle` at 2:1, a nonessential line the decorative role or none.
 *
 * Measured, never derived from the stylesheet text: per page, per theme the running app offers (plus `auto` under an
 * emulated dark preference — its dark half renders only there), every row's computed border colour and width, the
 * surface painted INSIDE the component and the surface painted OUTSIDE it (its parent, or the named element behind an
 * overlay), composited bottom-up through translucent layers on a 1×1 canvas (DangerInkContrastBrowserTest's method,
 * copied: this is its only other user). Each ratio must reach the row's floor, except a design-system token pair the
 * vendored audit does not cover, listed in [GAPS] with its follow-up issue — and a listed pair that now passes is red,
 * so the list cannot outlive its reason. The full table lands in `build/reports/609-component-boundaries/`.
 */
class ComponentBoundariesBrowserTest : VisualizationBrowserSuite() {
    /** One measured line: [side] of [selector] in [role] (null = no line), the floor its ratios meet, the expected width. */
    private data class Edge(
        val label: String,
        val selector: String,
        val role: String?,
        val floor: Double,
        val side: String = "Top",
        val width: String? = if (role == null) "0px" else "1px",
        val outside: String? = null,
        val kind: String = "border",
    ) {
        fun wire() =
            mapOf(
                "label" to label,
                "selector" to selector,
                "role" to role,
                "side" to side,
                "outside" to outside,
                "kind" to kind,
            )
    }

    /** A vendored token pair below a floor on named themes, tracked by [issue] (the design system audits only surface-default). */
    private data class Gap(
        val role: String,
        val surface: String,
        val themes: Set<String>,
        val issue: Int,
    )

    @Test
    fun `the dashboard board - the filters pane and a card's message`() {
        startTrace()
        val root = ready("edges609b")
        val board = seedParameterisedBoard(root)
        desktopBoard()
        openBoard(board)
        page.waitForFunction("() => document.querySelector('.dp-board-page').getAttribute('data-dp-filters') === 'some'")
        // The first refresh settles first (success → ready clears the message), then the quiet no-data voice through the
        // composite adapter's renderStatus (DashboardCardsBrowserTest's path): nothing later overwrites it.
        page.waitForFunction(
            "() => ['ready', 'no-data', 'error'].includes(document.querySelector('.dp-dashboard-status')?.getAttribute('data-dp-state'))",
        )
        page.evaluate(
            """() => { const name = document.querySelector('[data-dp-viz]').getAttribute('data-dp-viz');
                 window.__dpPage.instance._adapter.renderStatus({ name, type: 'visualization' }, { state: 'no-data' }); }""",
        )
        page.locator(".dp-dashboard-card-message:not(:empty)").first().waitFor()
        sweep(
            "board",
            listOf(
                Edge("filters pane", ".dp-board-filters", BOUNDARY, BOUNDARY_FLOOR),
                Edge("card message", ".dp-dashboard-card-message:not(:empty)", SEPARATOR, SEPARATOR_FLOOR),
            ),
        )
    }

    @Test
    fun `the dashboard dock - the pane and a refresh row`() {
        startTrace()
        val root = ready("edges609d")
        val board = seedBoardWithGrid(root)
        desktopBoard()
        openBoard(board)
        page.reload()
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        page.click("#dp-board-dock-tab-history")
        page.locator("#dp-board-dock-pane-history .dp-refreshes-row").first().waitFor()
        sweep(
            "dock",
            listOf(
                Edge("dock pane", ".dp-board-dock", BOUNDARY, BOUNDARY_FLOOR),
                Edge("refresh row", "#dp-board-dock-pane-history .dp-refreshes-row", SEPARATOR, SEPARATOR_FLOOR),
            ),
        )
    }

    @Test
    fun `a document - its code block`() {
        startTrace()
        ready("edges609x")
        page.navigate("$baseUrl/docs/environments")
        page.locator(".doc-body pre").first().waitFor()
        sweep("docs", listOf(Edge("code block", ".doc-body pre", SEPARATOR, SEPARATOR_FLOOR)))
    }

    @Test
    fun `a parameter set - its cards and the focused card's ring`() {
        startTrace()
        val root = ready("edges609p")
        val created =
            send(
                page,
                "POST",
                "/api/v1/parameter-sets",
                """{"name":"$root/parameters/kinds","display_name":"Kinds","description":"#609 fixture","parameters":[""" +
                    """{"name":"kind","label":"Kind","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                    """"source":{"constants":[{"value":"a","display_value":"A","is_default":true},""" +
                    """{"value":"b","display_value":"B","is_default":false}]},"presentation":{"control":"dropdown"}}]}""",
            )
        withClue(created.body.take(EXCERPT)) { created.status shouldBe 201 }
        page.navigate("$baseUrl/parameter-sets/${idIn(created.body)}")
        page.locator(".ps-card").first().waitFor()
        page.locator(".ps-card :is(input, select, textarea, button)").first().focus()
        sweep(
            "parameter-sets",
            listOf(
                Edge("card", ".ps-card", BOUNDARY, BOUNDARY_FLOOR),
                Edge("card focus ring", ".ps-card:focus-within", FOCUS, NO_FLOOR, width = "2px", kind = "outline"),
            ),
        )
    }

    @Test
    fun `the pipeline editor - the hint, minimap, view controls, dock and its grip`() {
        startTrace()
        val root = ready("edges609e")
        val created =
            send(
                page,
                "POST",
                "/api/v1/pipelines",
                """{"name":"$root/flows/four","display_name":"Four","nodes":[""" +
                    listOf("a", "b", "c", "d").joinToString(",") { calculator(it) } + "]}",
            )
        withClue(created.body.take(EXCERPT)) { created.status shouldBe 201 }
        page.setViewportSize(EDITOR_WIDTH, EDITOR_HEIGHT)
        page.navigate("$baseUrl/pipelines/${idIn(created.body)}")
        page.locator(".pe-card").first().waitFor()
        // The dock starts collapsed on this window (#615), which hides its grip; a tab opens it (the dock resize test's path).
        page.click("#pe-dock-tab-details")
        page.waitForFunction("() => !document.querySelector('.pe-dock').classList.contains('pe-dock-collapsed')")
        sweep(
            "editor",
            listOf(
                // A label, not a control: the legend chips' pill shape and role. The controls group's buttons are borderless,
                // so its outline is the boundary that identifies them.
                Edge("keyboard hint", ".pe-hint", SEPARATOR, SEPARATOR_FLOOR),
                Edge("minimap", ".pe-minimap", BOUNDARY, BOUNDARY_FLOOR),
                Edge("view controls", ".pe-graph-controls", BOUNDARY, BOUNDARY_FLOOR),
                Edge("dock top", ".pe-dock", BOUNDARY, BOUNDARY_FLOOR, outside = ".pe-stage"),
                Edge("dock grip", ".pe-dock-splitter", BOUNDARY, NO_FLOOR, width = null, kind = "grip", outside = ".pe-dock"),
            ),
        )
    }

    @Test
    fun `the schedule form - its fieldsets`() {
        startTrace()
        ready("edges609s")
        page.navigate("$baseUrl/schedules")
        page.locator("#schedule-list:not([aria-busy])").waitFor()
        page.locator("[data-verb='schedule-create']").first().click()
        page.locator("#sch-dialog [data-sch-form] .sch-fieldset").first().waitFor()
        // A group inside one dialog, named by its legend; every input in it keeps its own boundary.
        sweep("schedules", listOf(Edge("form group", "#sch-dialog .sch-fieldset", SEPARATOR, SEPARATOR_FLOOR)))
    }

    @Test
    fun `a pipeline's versions - the row menu, the switch rows and the release check rows`() {
        startTrace()
        val root = ready("edges609t")
        val id = pipelineWithTwoReleases("$root/flows/versions")
        page.navigate("$baseUrl/pipelines/$id?tab=versions")
        page.waitForSelector("tr[data-version-row]")
        val v1 = page.locator("tr[data-version-row]").filter(Locator.FilterOptions().setHasText("v1")).first()
        v1.locator("details.tplx-vmenu summary").click()
        page.locator(".tplx-vmenu-list:popover-open").waitFor()
        // Three sweeps on one fixture: each measures and records, and the verdict comes after the last, so a red menu
        // still leaves the dialogs' tables.
        val menu = measure("versions-menu", listOf(Edge("version menu", ".tplx-vmenu-list:popover-open", BOUNDARY, BOUNDARY_FLOOR)))

        page.locator(".tplx-vmenu-list:popover-open button").filter(Locator.FilterOptions().setHasText("Switch to v1")).click()
        page.locator("[data-lifecycle-dialog='pipeline-switch'] .tplx-switchrow").first().waitFor()
        val switch =
            measure(
                "versions-switch",
                listOf(Edge("switch row", "[data-lifecycle-dialog='pipeline-switch'] .tplx-switchrow", SEPARATOR, SEPARATOR_FLOOR)),
            )

        val checked = pipelineWithChecks("$root/flows/checked")
        page.navigate("$baseUrl/pipelines/$checked")
        page.waitForSelector(".pe-root")
        page.locator(".pe-topbar [data-verb='pipeline-release']").click()
        page.locator("[data-lifecycle-dialog] .plc-check-row").nth(1).waitFor()
        val checks =
            measure("versions-checks", listOf(Edge("check row", "[data-lifecycle-dialog] .plc-check-row", SEPARATOR, SEPARATOR_FLOOR)))
        withClue("see build/reports/609-component-boundaries/versions-*.txt") { menu + switch + checks shouldBe emptyList() }
    }

    @Test
    fun `a visualization's evidence - the stored JSON and the screenshot`() {
        startTrace()
        val root = ready("edges609v")
        val (id, _) = createVisualization("$root/charts/units")
        val sessionId = api("POST", "/api/v1/visualizations/$id/tests/sessions", "").at("data", "session_id") as String
        val submitted =
            api(
                "POST",
                "/api/v1/visualizations/$id/tests/sessions/$sessionId/results",
                """{"cases":[{"name":"two series","verdict":"green"},{"name":"empty","verdict":"green"}],
                   "environment":{"browser":"chromium (playwright)","theme":"light","viewport":"1440x900"}}""",
            )
        val runId = submitted.at("data", "run_id") as String
        val upload = submitted.at("data", "upload") as Map<*, *>
        val stored =
            page.request().post(
                "$baseUrl${upload["url"]}?case=two%20series",
                RequestOptions
                    .create()
                    .setHeader(upload["header"] as String, upload["token"] as String)
                    .setHeader("Content-Type", "image/png")
                    .setData(page.screenshot()),
            )
        withClue(stored.text().take(EXCERPT)) { stored.status() shouldBe 201 }
        page.navigate("$baseUrl/visualizations/$id?tab=evidence")
        page.click("#viz-pane-evidence:not([hidden]) [data-run-row='$runId'] button")
        page.waitForFunction(
            "() => { const i = document.querySelector('#viz-evidence-run img.viz-screenshot-img'); return i && i.naturalWidth > 0; }",
        )
        sweep(
            "visualizations",
            listOf(
                Edge("stored JSON", "#viz-evidence-run .viz-json", SEPARATOR, SEPARATOR_FLOOR),
                // Required (a light shot's edge vanishes on a light card), but it frames an image, not a control.
                Edge("screenshot frame", "#viz-evidence-run .viz-screenshot-img", SEPARATOR, SEPARATOR_FLOOR),
            ),
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun openBoard(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        val code = page.evaluate("() => window.__dpPage.code") as String?
        withClue("the board page failed to boot: $code") { code shouldBe null }
    }

    private fun calculator(id: String) =
        """{"id":"$id","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"q_$id",""" +
            """"inputs":{"date":"2026-08-14","fiscal_start":"09-15"}}"""

    /** v1 released, then a second body released as v2 — so v1's row menu offers Switch to v1 (LifecycleDialogBrowserTest's path). */
    private fun pipelineWithTwoReleases(name: String): String {
        val body = { note: String ->
            """{"schema_version":1,"name":"$name","display_name":"Versions","description":"$note","parameters":{},""" +
                """"nodes":[${calculator("fq")}]}"""
        }
        val created = send(page, "POST", "/api/v1/pipelines", body("first"))
        withClue(created.body.take(EXCERPT)) { created.status shouldBe 201 }
        val id = ScheduleFixtures.release(page, idIn(created.body))
        val head = hashOf(send(page, "GET", "/api/v1/pipelines/$id").body)
        val drafted = send(page, "PUT", "/api/v1/pipelines/$id", body("second"), ifMatch = head)
        withClue(drafted.body.take(EXCERPT)) { drafted.status shouldBe 200 }
        return ScheduleFixtures.release(page, id)
    }

    /** A draft whose two checks pass on a bundled H2 datasource (ReleaseChecksBrowserTest's fixture): the release dialog runs them. */
    private fun pipelineWithChecks(name: String): String {
        val datasource = "h2-edges609-" + suffix()
        val registered =
            send(
                page,
                "POST",
                "/api/v1/datasources",
                """{"name":"$datasource","display_name":"Edges H2","dialect":"H2",""" +
                    """"jdbc_url":"jdbc:h2:mem:${datasource.replace("-", "_")};DB_CLOSE_DELAY=-1","username":"sa","password":"sa"}""",
            )
        withClue(registered.body.take(EXCERPT)) { registered.status shouldBe 201 }
        val check = { key: String ->
            """{"id":"$key","name":"$key","datasource":"$datasource","sql":"SELECT 1","expected":{"kind":"value","value":1}}"""
        }
        val created =
            send(
                page,
                "POST",
                "/api/v1/pipelines",
                """{"name":"$name","display_name":"Checked","nodes":[${calculator("fq")}],""" +
                    """"checks":[${check("first")},${check("second")}]}""",
            )
        withClue(created.body.take(EXCERPT)) { created.status shouldBe 201 }
        return idIn(created.body)
    }

    private fun hashOf(body: String): String =
        requireNotNull(Regex(""""body_hash"\s*:\s*"([0-9a-f]+)"""").find(body)) { "no body_hash in: ${body.take(EXCERPT)}" }.groupValues[1]

    // ------------------------------------------------------------------ the sweep

    private fun reportDir(): Path = Paths.get("build", "reports", "609-component-boundaries").also { it.toFile().mkdirs() }

    /** Every theme the top bar offers (a tenth vendored theme joins by itself), plus auto's dark half. */
    private fun themes(): List<Pair<String, ColorScheme>> {
        val offered =
            (
                page.evaluate(
                    "() => [...document.querySelectorAll('#app-appearance [data-mode], .app-swatch[data-swatch]')]" +
                        ".map(e => e.dataset.mode || e.dataset.swatch)",
                ) as List<*>
            ).map { it as String }
        offered.size shouldBeGreaterThanOrEqual MIN_THEMES
        return offered.map { it to ColorScheme.LIGHT } + ("auto" to ColorScheme.DARK)
    }

    /** [measure], then the verdict: no failed row. */
    private fun sweep(
        name: String,
        edges: List<Edge>,
    ) {
        val failures = measure(name, edges)
        withClue("see build/reports/609-component-boundaries/$name.txt") { failures shouldBe emptyList() }
    }

    /** Measures [edges] on every theme, writes the page's table, photographs light and dark; returns every failed judgement. */
    @Suppress("UNCHECKED_CAST")
    private fun measure(
        name: String,
        edges: List<Edge>,
    ): List<String> {
        val lines = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((theme, scheme) in themes()) {
            val label = if (scheme == ColorScheme.DARK) "$theme(dark)" else theme
            page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(scheme))
            page.evaluate(SWAP_THEME, theme) shouldBe true
            val rows = page.evaluate(MEASURE, edges.map { it.wire() }) as List<Map<String, Any?>>
            rows.size shouldBe edges.size
            println("609-edges|$name|$label|$rows")
            edges.zip(rows).forEach { (edge, row) -> judge(label, edge, row, lines, failures) }
            if (label == "light" || label == "dark") {
                page.screenshot(Page.ScreenshotOptions().setPath(reportDir().resolve("$name-$label.png")))
            }
        }
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT))
        val verdicts = if (failures.isEmpty()) listOf("verdict: green") else listOf("verdict: ${failures.size} failed") + failures
        val header = "#609 $name — theme  edge  role  width  colour  inside(ratio)  outside(ratio)"
        val report = (listOf(header) + lines + verdicts).joinToString("\n")
        reportDir().resolve("$name.txt").toFile().writeText(report + "\n")
        println(report)
        return failures
    }

    private fun judge(
        theme: String,
        edge: Edge,
        m: Map<String, Any?>,
        lines: MutableList<String>,
        failures: MutableList<String>,
    ) {
        val where = "$theme ${edge.label} (${edge.selector})"
        if (m["present"] != true || m["visible"] != true) {
            failures += "$where: present=${m["present"]} visible=${m["visible"]}"
            return
        }
        val pairs = listOfNotNull(pair("inside", m), pair("outside", m))
        lines += "$theme  ${edge.label}  ${edge.role ?: "none"}  ${m["width"]}  ${m["actual"]}  " +
            pairs.joinToString("  ") { (side, ratio, tokens) -> "$side=${"%.2f".format(ratio)}:1 ${tokens.ifEmpty { "(untokened)" }}" }
        if (edge.width != null && m["width"] != edge.width) failures += "$where: width ${m["width"]}, expected ${edge.width}"
        if (edge.role != null && m["actual"] != m["expected"]) failures += "$where: paints ${m["actual"]}, ${edge.role} is ${m["expected"]}"
        if (edge.floor == NO_FLOOR || edge.role == null) return
        for ((side, ratio, tokens) in pairs) {
            val gap = GAPS.firstOrNull { it.role == edge.role && it.surface in tokens.split("=") && theme in it.themes }
            judgeRatio("$where: $side", ratio, tokens, edge.floor, gap, lines, failures)
        }
    }

    /** Below the floor is red unless a [Gap] lists the pair (an exception line); a listed pair that passes is red too. */
    private fun judgeRatio(
        where: String,
        ratio: Double,
        tokens: String,
        floor: Double,
        gap: Gap?,
        lines: MutableList<String>,
        failures: MutableList<String>,
    ) {
        val shown = "%.2f".format(ratio)
        when {
            gap == null -> if (ratio < floor) failures += "$where $shown:1 on $tokens < $floor"
            ratio < floor -> lines += "    exception: ${gap.role} on ${gap.surface} $shown:1 (#${gap.issue})"
            else -> failures += "$where: listed gap ${gap.role} on ${gap.surface} now $shown:1 — remove it"
        }
    }

    private fun pair(
        side: String,
        m: Map<String, Any?>,
    ): Triple<String, Double, String>? = (m["${side}Ratio"] as Number?)?.let { Triple(side, it.toDouble(), m["${side}Tokens"] as String) }

    private companion object {
        const val BOUNDARY = "--border-default"
        const val SEPARATOR = "--border-subtle"
        const val FOCUS = "--border-focus"
        const val BOUNDARY_FLOOR = 3.0
        const val SEPARATOR_FLOOR = 2.0
        const val NO_FLOOR = 0.0
        const val MIN_THEMES = 9
        const val EDITOR_WIDTH = 1440
        const val EDITOR_HEIGHT = 900

        /** Filled from the base measurement (A.1); each entry names its follow-up issue. */
        val GAPS = emptyList<Gap>()

        /** ShellSurfacesBrowserTest's swap: REPLACE the theme link and wait for its own load. */
        val SWAP_THEME =
            """
            (theme) => new Promise((resolve, reject) => {
              const old = document.getElementById('theme-link');
              const link = document.createElement('link');
              link.rel = 'stylesheet';
              link.onload = () => { old.remove(); link.id = 'theme-link'; resolve(true); };
              link.onerror = () => reject(new Error('theme ' + theme + ' did not load'));
              link.href = '/vendor/design-system/themes/' + theme + '.css';
              old.insertAdjacentElement('afterend', link);
            })
            """.trimIndent()

        /**
         * One theme's rows. The canvas resolves any computed colour syntax to painted sRGB bytes; `paint` composites an
         * element's background chain bottom-up from its first opaque ancestor. A surface is named by every token that
         * resolves to its colour (light themes share default and raised). Probes stay attached until every read is done.
         */
        val MEASURE =
            """
            async (rows) => {
              const canvas = document.createElement('canvas');
              canvas.width = canvas.height = 1;
              const ctx = canvas.getContext('2d', { willReadFrequently: true });
              const rgba = c => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = '#000'; ctx.fillStyle = c;
                ctx.fillRect(0, 0, 1, 1); const d = ctx.getImageData(0, 0, 1, 1).data; return [d[0], d[1], d[2], d[3] / 255]; };
              const css = a => 'rgb(' + a[0] + ', ' + a[1] + ', ' + a[2] + ')';
              const paint = el => {
                const layers = [];
                for (let node = el; node; node = node.parentElement) {
                  const bg = getComputedStyle(node).backgroundColor;
                  const c = rgba(bg);
                  if (c[3] > 0) layers.push(bg);
                  if (c[3] === 1) break;
                }
                if (!layers.length || rgba(layers[layers.length - 1])[3] !== 1) throw new Error('no opaque ancestor: ' + el.className);
                ctx.clearRect(0, 0, 1, 1);
                for (let i = layers.length - 1; i >= 0; i--) { ctx.fillStyle = layers[i]; ctx.fillRect(0, 0, 1, 1); }
                const d = ctx.getImageData(0, 0, 1, 1).data;
                return [d[0], d[1], d[2], 1];
              };
              const over = (top, under) => top[3] === 1 ? top
                : [0, 1, 2].map(i => Math.round(top[i] * top[3] + under[i] * (1 - top[3]))).concat([1]);
              const lum = c => { const f = v => { const s = v / 255;
                  return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
                return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]); };
              const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b);
                return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
              const settle = async el => {
                // subtree: a pseudo-element's transition (the dock grip's ::after) belongs to its element's subtree.
                const running = () => el.getAnimations({ subtree: true })
                  .filter(a => a instanceof CSSTransition && a.playState === 'running');
                const deadline = performance.now() + 3000;
                while (running().length && performance.now() < deadline) await new Promise(r => setTimeout(r, 25));
                if (running().length) throw new Error('transition still running on ' + el.className);
              };
              const host = document.createElement('div');
              document.body.appendChild(host);
              const token = name => { const s = document.createElement('span'); s.style.color = 'var(' + name + ')';
                host.appendChild(s); return css(rgba(getComputedStyle(s).color)); };
              const surfaces = ['--surface-page', '--surface-default', '--surface-raised', '--surface-overlay',
                                '--surface-inset', '--surface-hover', '--surface-selected'].map(n => [n, token(n)]);
              const named = c => surfaces.filter(([, v]) => v === css(c)).map(([n]) => n).join('=');
              const result = [];
              for (const r of rows) {
                const el = document.querySelector(r.selector);
                if (!el) { result.push({ label: r.label, present: false }); continue; }
                await settle(el);
                const cs = r.kind === 'grip' ? getComputedStyle(el, '::after') : getComputedStyle(el);
                const raw = r.kind === 'outline' ? cs.outlineColor : r.kind === 'grip' ? cs.backgroundColor
                  : cs['border' + r.side + 'Color'];
                const width = r.kind === 'outline' ? cs.outlineWidth : r.kind === 'grip' ? null : cs['border' + r.side + 'Width'];
                const image = el.tagName === 'IMG';
                const inside = image || r.kind !== 'border' ? null : paint(el);
                const outside = paint(r.outside ? document.querySelector(r.outside) : el.parentElement);
                const line = rgba(raw);
                const row = { label: r.label, present: true, visible: el.getClientRects().length > 0, width,
                  actual: css(over(line, inside || outside)), expected: r.role ? token(r.role) : null,
                  outsideRatio: ratio(over(line, outside), outside), outsideTokens: named(outside),
                  insideRatio: inside ? ratio(over(line, inside), inside) : null, insideTokens: inside ? named(inside) : null };
                result.push(row);
              }
              host.remove();
              return result;
            }
            """.trimIndent()
    }
}
