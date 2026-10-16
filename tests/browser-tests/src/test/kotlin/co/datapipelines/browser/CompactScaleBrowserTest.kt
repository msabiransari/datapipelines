package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #555 — the compact type and density scale, MEASURED in the browser (ui-screens §3.3, the
 * application-shell record §5/§6). Every number below is a computed style or a box read off the
 * real page at 1512×982 and 390×844, on the screens the record names: the pipelines catalog, the
 * datasource form, a lifecycle dialog, the executions data table, the pipeline editor (chrome
 * only), a dashboard board and a template workspace.
 *
 * Each case collects every reading first and prints it (`555-measure|…` in the XML's system-out,
 * the handback's base/tip table), then asserts them softly, so a red names EVERY number that
 * moved rather than the first. The role values are the app tokens (`--app-text-*`,
 * `--app-control-h`, …) as px at the 16px root; a reading here is the rendered result of the
 * token AND the rule that applies it, which is what the record specifies.
 *
 * The board's KPI value is the opposite guard: authored content the record PRESERVES (§5: "preserve
 * authored chart/KPI fonts"), green at the base and at the tip — a change there is the failure the
 * compact scale must not cause.
 */
class CompactScaleBrowserTest : DashboardBrowserSuite() {
    /** One element's computed type and box; null when [selector] matches nothing. */
    @Suppress("UNCHECKED_CAST")
    private fun measure(
        selector: String,
        target: Page = page,
    ): Map<String, Any?>? =
        target.evaluate(
            """(sel) => {
              const e = document.querySelector(sel);
              if (!e) return null;
              const cs = getComputedStyle(e);
              const r = e.getBoundingClientRect();
              return { fontSize: parseFloat(cs.fontSize), lineHeight: parseFloat(cs.lineHeight) || null,
                       fontWeight: parseFloat(cs.fontWeight), height: r.height, width: r.width,
                       radius: parseFloat(cs.borderTopLeftRadius), padTop: parseFloat(cs.paddingTop),
                       padLeft: parseFloat(cs.paddingLeft), maxWidth: cs.maxWidth };
            }""",
            selector,
        ) as Map<String, Any?>?

    private val readings = linkedMapOf<String, Double?>()

    /** Records [key] of [selector] under [label]; the value (or null) is printed with the rest. */
    private fun read(
        label: String,
        selector: String,
        key: String,
        target: Page = page,
    ): Double? = (measure(selector, target)?.get(key) as Number?)?.toDouble().also { readings[label] = it }

    private fun printReadings(case: String) {
        readings.forEach { (label, value) -> println("555-measure|$case|$label|$value") }
    }

    private fun shot(
        name: String,
        target: Page = page,
    ) {
        val dir = Paths.get("build", "browser-evidence")
        Files.createDirectories(dir)
        target.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("555-$name.png")).setFullPage(false))
    }

    /** The in-page REST call (LifecycleDialogBrowserTest's shape): a draft pipeline needs no datasource. */
    @Suppress("UNCHECKED_CAST")
    private fun createDraftPipeline(name: String): String {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const r = await fetch('/api/v1/pipelines', { method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: args.body });
                  return { status: r.status, body: (await r.text()).slice(0, 2000) };
                }""",
                mapOf(
                    "body" to
                        """{"name":"$name","display_name":"${name.substringAfterLast('/')}","description":"555 scale",""" +
                        """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                        """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
                ),
            ) as Map<String, Any?>
        (result["status"] as Number).toInt() shouldBe 201
        return Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(result["body"] as String)!!.groupValues[1]
    }

    private fun near(
        label: String,
        expected: Double,
        tolerance: Double = 0.05,
    ) = withClue(label) { readings[label].shouldNotBeNull().shouldBeBetween(expected - tolerance, expected + tolerance, 0.0) }

    private fun atLeast(
        label: String,
        floor: Double,
    ) = withClue(label) { readings[label].shouldNotBeNull() shouldBeGreaterThanOrEqual floor }

    private fun atMost(
        label: String,
        ceiling: Double,
    ) = withClue(label) { readings[label].shouldNotBeNull() shouldBeLessThanOrEqual ceiling }

    @Test
    @Suppress("LongMethod") // one walk over seven screens: each reading's precondition is the page before it
    fun `desktop 1512 - the 13-12-24 hierarchy, 36px controls, 44px tabs, 8 and 12px radii on seven representative screens`() {
        startTrace()
        val root = ready("cs555")
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val board = seedBoardWithGrid(root)
        val pipeline = createDraftPipeline("$root/scale/probe")
        createTemplate("test/${root}_probe.sql", "SELECT 1 AS one")

        // 1 — the pipelines catalog: body text, the page title, support copy, a resource name, a badge.
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector("a.tpl-result")
        read("body.fontSize", "body", "fontSize")
        read("body.lineHeight", "body", "lineHeight")
        read("main.fontSize", ".app-main", "fontSize")
        read("title.fontSize", ".app-main .app-page-h h1", "fontSize")
        read("title.lineHeight", ".app-main .app-page-h h1", "lineHeight")
        read("title.fontWeight", ".app-main .app-page-h h1", "fontWeight")
        read("subtitle.fontSize", ".app-page-sub", "fontSize")
        read("name.fontSize", "a.tpl-result .tpl-path", "fontSize")
        read("name.lineHeight", "a.tpl-result .tpl-path", "lineHeight")
        read("badge.fontSize", "a.tpl-result .ds-badge", "fontSize")
        read("badge.fontWeight", "a.tpl-result .ds-badge", "fontWeight")
        read("catalogButton.height", ".app-page-actions .ds-button", "height")
        read("catalogButton.fontSize", ".app-page-actions .ds-button", "fontSize")
        read("search.height", ".app-search-input", "height")
        shot("catalog-1512")

        // 2 — metadata: the home stats' captions (the 10px role, short group captions only).
        page.navigate("$baseUrl/dashboard")
        page.waitForSelector(".ds-caption")
        read("caption.fontSize", ".app-main .ds-caption", "fontSize")

        // 3 — the datasource form: a primary button, an input, a select, the dialog and its close.
        page.navigate("$baseUrl/datasources")
        read("primaryButton.height", "[data-verb='datasource-register']", "height")
        read("primaryButton.fontSize", "[data-verb='datasource-register']", "fontSize")
        read("primaryButton.radius", "[data-verb='datasource-register']", "radius")
        page.click("[data-verb='datasource-register']")
        page.waitForSelector("#register-modal:not(.u-backdrop-hidden) .app-modal")
        read("input.height", "#register-modal input[name='name']", "height")
        read("input.fontSize", "#register-modal input[name='name']", "fontSize")
        read("input.radius", "#register-modal input[name='name']", "radius")
        read("select.height", "#register-modal select[name='dialect']", "height")
        read("label.fontSize", "#register-modal label", "fontSize")
        read("dialog.width", "#register-modal .app-modal", "width")
        read("dialog.padTop", "#register-modal .app-modal", "padTop")
        read("dialog.radius", "#register-modal .app-modal", "radius")
        read("dialogTitle.fontSize", "#register-modal .ds-title", "fontSize")
        read("iconButton.height", "#register-modal .app-modal-close", "height")
        read("iconButton.width", "#register-modal .app-modal-close", "width")
        shot("datasource-form-1512")
        page.click("[data-action='datasource-register-close']")

        // 4 — the board's card chrome (its authored KPI is the preserve case below); its initial
        //     refresh is also what gives the executions table rows.
        openBoard(board)
        read("cardTitle.fontSize", "#dp-board .dp-dashboard-card-title", "fontSize")
        shot("board-1512")

        // 5 — the executions data table: 12px cells, an 11px header (the board's refresh ran rows).
        page.navigate("$baseUrl/executions")
        page.waitForSelector(".dt-frame .ds-table tbody td")
        read("tableCell.fontSize", ".dt-frame .ds-table tbody td", "fontSize")
        read("tableHead.fontSize", ".dt-frame .ds-table thead th", "fontSize")
        shot("executions-1512")

        // 6 — the pipeline editor's chrome (never its canvas) and its Release dialog.
        page.navigate("$baseUrl/pipelines/$pipeline")
        page.waitForSelector(".pe-root")
        read("editorRoot.fontSize", ".pe-root", "fontSize")
        read("editorTab.height", ".pe-tab", "height")
        read("editorTab.fontSize", ".pe-tab", "fontSize")
        shot("editor-1512")
        page.locator(".pe-topbar [data-verb='pipeline-release']").click()
        page.locator("#pe-dialog [data-lifecycle-dialog='pipeline-release'] .app-modal").waitFor()
        read("lifecycleDialog.padTop", "#pe-dialog .app-modal", "padTop")
        read("lifecycleDialog.width", "#pe-dialog .app-modal", "width")
        read("lifecycleTitle.fontSize", "#pe-dialog .app-modal .ds-title", "fontSize")
        read("lifecycleSubmit.height", "#pe-dialog .app-modal button[type=submit]", "height")
        shot("lifecycle-dialog-1512")

        // 7 — the template workspace's tabs.
        page.navigate("$baseUrl/templates/test/${root}_probe.sql")
        page.waitForSelector(".tw-root")
        read("templateTab.height", ".tw-tab", "height")
        read("templateTab.fontSize", ".tw-tab", "fontSize")
        shot("template-workspace-1512")

        printReadings("desktop")
        assertSoftly {
            near("body.fontSize", UI_PX)
            near("body.lineHeight", UI_LINE_PX)
            near("main.fontSize", UI_PX)
            near("title.fontSize", TITLE_PX)
            near("title.lineHeight", TITLE_LINE_PX)
            near("title.fontWeight", SEMIBOLD)
            near("subtitle.fontSize", SUPPORT_PX)
            near("name.fontSize", SUPPORT_PX)
            near("name.lineHeight", NAME_LINE_PX)
            near("badge.fontSize", BADGE_PX)
            near("badge.fontWeight", MEDIUM)
            atLeast("catalogButton.height", CONTROL_PX)
            near("catalogButton.fontSize", UI_PX)
            atLeast("search.height", CONTROL_PX)
            near("caption.fontSize", META_PX)
            atLeast("primaryButton.height", CONTROL_PX)
            near("primaryButton.fontSize", UI_PX)
            near("primaryButton.radius", CONTROL_RADIUS_PX)
            atLeast("input.height", CONTROL_PX)
            near("input.fontSize", UI_PX)
            near("input.radius", CONTROL_RADIUS_PX)
            atLeast("select.height", CONTROL_PX)
            atLeast("label.fontSize", SUPPORT_PX)
            atMost("dialog.width", DIALOG_MAX_PX)
            near("dialog.padTop", DIALOG_PAD_PX)
            near("dialog.radius", PANEL_RADIUS_PX)
            near("dialogTitle.fontSize", HEADING_PX)
            near("iconButton.height", ICON_BUTTON_PX, 0.5)
            near("iconButton.width", ICON_BUTTON_PX, 0.5)
            near("cardTitle.fontSize", SUPPORT_PX)
            near("tableCell.fontSize", SUPPORT_PX)
            near("tableHead.fontSize", BADGE_PX)
            near("editorRoot.fontSize", UI_PX)
            near("editorTab.height", TAB_PX, TAB_TOLERANCE_PX)
            near("editorTab.fontSize", SUPPORT_PX)
            near("lifecycleDialog.padTop", DIALOG_PAD_PX)
            atMost("lifecycleDialog.width", DIALOG_MAX_PX)
            near("lifecycleTitle.fontSize", HEADING_PX)
            atLeast("lifecycleSubmit.height", CONTROL_PX)
            near("templateTab.height", TAB_PX, TAB_TOLERANCE_PX)
            near("templateTab.fontSize", SUPPORT_PX)
        }
    }

    @Test
    fun `the board's authored KPI value keeps its size - green at the base and at the tip`() {
        startTrace()
        val root = ready("cs555k")
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        openBoard(seedBoardWithGrid(root))
        read("kpiValue.fontSize", "#dp-board .dp-dashboard-kpi-value", "fontSize")
        read("kpiNumber.fontSize", "#dp-board .dp-dashboard-kpi-number", "fontSize")
        read("kpiComparison.fontSize", "#dp-board .dp-dashboard-kpi-comparison", "fontSize")
        printReadings("kpi")
        assertSoftly {
            near("kpiValue.fontSize", KPI_VALUE_PX)
            near("kpiNumber.fontSize", KPI_VALUE_PX)
        }
    }

    private fun openBoard(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        page.locator("#dp-board .dp-dashboard-kpi-number").first().waitFor()
        shot("board-1512")
    }

    @Test
    fun `phone 390 - the 21px title, 16px text entry and 44px coarse-pointer targets without larger type`() {
        startTrace()
        ready("cs555p")
        // A touch phone: `(pointer: coarse)` is what a real 390px device reports, and the record's
        // 44×44 targets are keyed on it, never on the width alone.
        val phone =
            newBrowserContext(
                Browser
                    .NewContextOptions()
                    .setViewportSize(PHONE_W, PHONE_H)
                    .setHasTouch(true)
                    .setIsMobile(true),
            )
        try {
            phone.addCookies(page.context().cookies())
            val p = phone.newPage()
            p.navigate("$baseUrl/pipelines")
            p.waitForSelector(".app-main .app-page-h h1")
            readings["phone.coarse"] = if (p.evaluate("() => matchMedia('(pointer: coarse)').matches") as Boolean) 1.0 else 0.0
            read("phone.body.fontSize", "body", "fontSize", p)
            read("phone.title.fontSize", ".app-main .app-page-h h1", "fontSize", p)
            read("phone.title.lineHeight", ".app-main .app-page-h h1", "lineHeight", p)
            read("phone.search.fontSize", ".app-search-input", "fontSize", p)
            read("phone.button.height", ".app-page-actions .ds-button", "height", p)
            read("phone.button.fontSize", ".app-page-actions .ds-button", "fontSize", p)
            shot("catalog-390", p)

            p.navigate("$baseUrl/datasources")
            p.click("[data-verb='datasource-register']")
            p.waitForSelector("#register-modal:not(.u-backdrop-hidden) .app-modal")
            read("phone.input.fontSize", "#register-modal input[name='name']", "fontSize", p)
            read("phone.select.fontSize", "#register-modal select[name='dialect']", "fontSize", p)
            read("phone.label.fontSize", "#register-modal label", "fontSize", p)
            read("phone.close.height", "#register-modal .app-modal-close", "height", p)
            read("phone.close.width", "#register-modal .app-modal-close", "width", p)
            shot("datasource-form-390", p)

            printReadings("phone")
            assertSoftly {
                near("phone.coarse", 1.0)
                near("phone.body.fontSize", UI_PX)
                near("phone.title.fontSize", PHONE_TITLE_PX)
                near("phone.title.lineHeight", PHONE_TITLE_LINE_PX)
                near("phone.search.fontSize", PHONE_ENTRY_PX)
                atLeast("phone.button.height", TARGET_PX)
                near("phone.button.fontSize", UI_PX)
                near("phone.input.fontSize", PHONE_ENTRY_PX)
                near("phone.select.fontSize", PHONE_ENTRY_PX)
                near("phone.label.fontSize", SUPPORT_PX)
                atLeast("phone.close.height", TARGET_PX)
                atLeast("phone.close.width", TARGET_PX)
            }
        } finally {
            phone.close()
        }
    }

    private companion object {
        const val DESKTOP_W = 1512
        const val DESKTOP_H = 982
        const val PHONE_W = 390
        const val PHONE_H = 844

        // The record's role table (§5) and component baseline (§6), in px at the 16px root.
        const val UI_PX = 13.0
        const val UI_LINE_PX = 19.5
        const val SUPPORT_PX = 12.0
        const val NAME_LINE_PX = 18.6
        const val HEADING_PX = 14.0
        const val TITLE_PX = 24.0
        const val TITLE_LINE_PX = 32.4
        const val PHONE_TITLE_PX = 21.0
        const val PHONE_TITLE_LINE_PX = 28.35
        const val BADGE_PX = 11.0
        const val META_PX = 10.0
        const val PHONE_ENTRY_PX = 16.0
        const val SEMIBOLD = 600.0
        const val MEDIUM = 500.0
        const val CONTROL_PX = 36.0
        const val TAB_PX = 44.0
        const val TAB_TOLERANCE_PX = 2.0
        const val ICON_BUTTON_PX = 32.0
        const val TARGET_PX = 44.0
        const val CONTROL_RADIUS_PX = 8.0
        const val PANEL_RADIUS_PX = 12.0
        const val DIALOG_PAD_PX = 20.0
        const val DIALOG_MAX_PX = 560.0

        // The authored KPI value: `--text-xl` (dashboards.css `.dp-dashboard-kpi-value`), the number
        // read at the base 4becc8e89 before this lane changed anything — it must not move.
        const val KPI_VALUE_PX = 20.0
    }
}
