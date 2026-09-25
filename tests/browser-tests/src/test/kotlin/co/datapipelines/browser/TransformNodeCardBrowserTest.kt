package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 7d (#7, transform-nodes design §9.3, §10.10) — the TRANSFORM node card and its Details pane,
 * over a pipeline saved through the REAL API (7c's validator: the contracts, the inputs, the
 * output shapes, rejects and strict are all checked at save — a fixture the validator refuses
 * fails here, not in a mock). The pipeline covers the three output shapes:
 *
 * ```
 *   stage_orders ──▶ shape_orders (row, rejects, strict) ──▶ line_count (value → $line_count)
 *                                                       ╲──▶ list_lines (table → caller)
 * ```
 *
 * The card's language is the pinned template's type, which is not on the node JSON: the card
 * says `transform` until graph.js resolves the pin (GET /api/v1/templates/versions, the
 * loadDialects pattern), then `jsonata`. The walk rides the app's doors (explorer search →
 * result row → Open in editor). Screenshots under build/reports/7d-screenshots.
 */
class TransformNodeCardBrowserTest : BrowserSuite() {
    @Test
    fun `a TRANSFORM card says its language, what it reads and writes, and its rejects - and Details says the rest`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("tnc-" + suffix()), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val slug = "tnc" + suffix()
        val datasource = "tnc-src-" + suffix()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource).shouldBeEmpty()
        val name = seedPipeline(slug, datasource)

        page.navigate("$baseUrl/pipelines?q=$slug")
        page.locator("button.tpl-result, button.tpl-leaf").first().click()
        page.locator("a:has-text('Open in editor')").first().click()
        page.waitForURL("**/pipelines/*/editor")
        page.locator(".pe-card[data-node-id='shape_orders']").waitFor()
        // The pin resolves after the first paint — wait for the language, never for time.
        page.waitForFunction(
            "() => (document.querySelector(\".pe-card[data-node-id='shape_orders']\") || {}).innerText?.includes('jsonata ·')",
        )

        val shape = page.locator(".pe-card[data-node-id='shape_orders']")
        shape.getAttribute("data-type") shouldBe "transform"
        shape.locator(".pe-card-kind").innerText().lowercase() shouldContain "transform"
        shape.locator(".pe-card-tile use").getAttribute("href") shouldContain "#code"
        val facts = shape.locator(".pe-card-fact").allInnerTexts()
        facts[0] shouldBe "jsonata · test/${slug}_shape.jsonata @ v1"
        facts[1] shouldBe "1 input → tempdb.order_lines"
        facts[2] shouldBe "rejects → tempdb.order_rejects · strict"
        page.locator(".pe-card[data-node-id='line_count'] .pe-card-fact").allInnerTexts()[1] shouldBe "1 input → \$line_count"
        page.locator(".pe-card[data-node-id='list_lines'] .pe-card-fact").allInnerTexts()[1] shouldBe "1 input → caller"
        page.locator(".pe-legend-chip", Page.LocatorOptions().setHasText("Transform")).count() shouldBe 1
        themedShots("card", listOf(1440, 1920))

        // Details: the node's facts and the template's (mode is the contract's, read off the pin).
        detailsOf("shape_orders").let { rows ->
            rows["Template"] shouldBe "test/${slug}_shape.jsonata @ v1"
            rows["Language"] shouldBe "jsonata"
            rows["Mode"] shouldBe "row"
            rows["Inputs"] shouldBe "orders ← stg_orders"
            rows["Output"] shouldBe "tempdb.order_lines"
            rows["Rejects"] shouldBe "tempdb.order_rejects"
            rows["Strict"] shouldBe "yes — any reject fails the node"
            rows.containsKey("Query Timeout") shouldBe false
        }
        page.locator("#pe-node-def").innerText() shouldContain "-- TRANSFORM nodes have no SQL"
        themedShots("details-row", listOf(1440))
        detailsOf("line_count").let { rows ->
            rows["Mode"] shouldBe "value"
            rows["Output"] shouldBe "\$line_count"
            rows["Strict"] shouldBe "no"
        }
        detailsOf("list_lines")["Mode"] shouldBe "table"
        themedShots("details-table", listOf(1440))
        drainCspViolations().shouldBeEmpty()
        name shouldContain slug
    }

    // ------------------------------------------------------------------ fixtures

    private fun suffix(): String = generatedPassword("s").takeLast(8).lowercase()

    /** The staging SQL, three transform templates (7b's gate runs each suite at create), the pipeline. */
    private fun seedPipeline(
        slug: String,
        datasource: String,
    ): String {
        EditorRunFixtures.createTemplate(
            page,
            "test/${slug}_orders.sql",
            "SELECT g AS order_id, CASE WHEN g % 2 = 0 THEN 'c' || g ELSE NULL END AS customer_id FROM generate_series(1, 6) g",
        )
        createTransform(
            "test/${slug}_shape.jsonata",
            """{ "rows": [ rows[customer_id != null].{ "order_id": order_id, "customer_id": customer_id } ],
                 "rejects": [ rows[customer_id = null].{ "row": ${'$'}, "reason": "no customer" } ] }""",
            """{"mode":"row","inputs":{"orders":{"kind":"table","columns":[$ORDER_ID,{"name":"customer_id","type":"STRING","nullable":true}]}},
                "output":{"kind":"table","columns":[$ORDER_ID,{"name":"customer_id","type":"STRING"}]},"rejects":true}""",
            """[{"name":"empty input","input":{"rows":[]},"expect":{"output":{"rows":[],"rejects":[]}}}]""",
        )
        createTransform(
            "test/${slug}_count.jsonata",
            "${'$'}count(inputs.lines)",
            """{"mode":"value","inputs":{"lines":{"kind":"table","columns":[$ORDER_ID,{"name":"customer_id","type":"STRING"}]}},
                "output":{"kind":"value","type":"INTEGER"}}""",
            """[{"name":"empty input","input":{"inputs":{"lines":[]}},"expect":{"output":0}}]""",
        )
        createTransform(
            "test/${slug}_list.jsonata",
            """[ inputs.lines.{ "order_id": order_id } ]""",
            """{"mode":"table","inputs":{"lines":{"kind":"table","columns":[$ORDER_ID,{"name":"customer_id","type":"STRING"}]}},
                "output":{"kind":"table","columns":[$ORDER_ID]}}""",
            """[{"name":"empty input","input":{"inputs":{"lines":[]}},"expect":{"output":[]}}]""",
        )
        val name = "test/$slug"
        EditorRunFixtures.postPipeline(
            page,
            name,
            """[
              { "id": "stage_orders", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_orders.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_orders" }, "depends_on": [] },
              { "id": "shape_orders", "type": "TRANSFORM", "template": { "id": "test/${slug}_shape.jsonata", "version": 1 },
                "inputs": { "orders": "stg_orders" },
                "output": { "target": "tempdb", "table": "order_lines", "rejects": "order_rejects" }, "strict": true,
                "depends_on": ["stage_orders"] },
              { "id": "line_count", "type": "TRANSFORM", "template": { "id": "test/${slug}_count.jsonata", "version": 1 },
                "inputs": { "lines": "order_lines" }, "context_key": "line_count", "depends_on": ["shape_orders"] },
              { "id": "list_lines", "type": "TRANSFORM", "template": { "id": "test/${slug}_list.jsonata", "version": 1 },
                "inputs": { "lines": "order_lines" }, "output": { "target": "caller" }, "depends_on": ["shape_orders"] }
            ]""",
        )
        return name
    }

    /** A jsonata template over REST in the session (no key reaches /api/v1); 7b's gate runs its suite. */
    @Suppress("UNCHECKED_CAST")
    private fun createTransform(
        id: String,
        body: String,
        contract: String,
        tests: String,
    ) {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const r = await fetch('/api/v1/templates', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ id: args.id, type: 'jsonata', display_name: args.id, description: '7d browser fixture',
                      body: args.body, contract: JSON.parse(args.contract), invariants: [], tests: JSON.parse(args.tests) }) });
                  return { status: r.status, body: r.status === 201 ? '' : await r.text() };
                }""",
                mapOf("id" to id, "body" to body, "contract" to contract, "tests" to tests),
            ) as Map<String, Any?>
        check((result["status"] as Number).toInt() == 201) { "transform template $id: ${result["status"]} ${result["body"]}" }
    }

    /** Selects [nodeId] through its card's own Details affordance and reads the pane's rows. */
    @Suppress("UNCHECKED_CAST")
    private fun detailsOf(nodeId: String): Map<String, String> {
        page.locator(".pe-card-open[data-node-open='$nodeId']").click()
        page.locator("#pe-pane-details .pe-details-id", Page.LocatorOptions().setHasText(nodeId)).waitFor()
        return page.evaluate(
            """() => Object.fromEntries([...document.querySelectorAll('#pe-pane-details .pe-kv-row')]
                 .map(r => [r.querySelector('dt').innerText.trim(), r.querySelector('dd').innerText.trim()]))""",
        ) as Map<String, String>
    }

    // ------------------------------------------------------------------ screenshots

    private fun mode(): String =
        if ((page.locator("#theme-link").first().getAttribute("href") ?: "").contains("/light.css")) "light" else "dark"

    private fun themedShots(
        name: String,
        widths: List<Int>,
    ) {
        val back = mode()
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            widths.forEach { width ->
                page.setViewportSize(width, 1000)
                dismissToasts()
                page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("7d-$name-$width-$theme.png")))
            }
        }
        ensureTheme(back)
        page.setViewportSize(1440, 1000)
    }

    /**
     * The theme switch that each light/dark pair needs leaves a "Theme updated" toast over the
     * header for 6 s; every visible toast is dismissed through its own × before a shot, so the
     * picture shows the screen and not the switch that took it.
     */
    private fun dismissToasts() {
        page.locator("#toast .ds-toast-close").all().forEach { close -> if (close.isVisible) close.click() }
        page.waitForFunction("() => document.querySelectorAll('#toast .ds-toast').length === 0")
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "7d-screenshots").also { it.toFile().mkdirs() }

    private companion object {
        const val ORDER_ID = """{"name":"order_id","type":"INTEGER"}"""
    }
}
