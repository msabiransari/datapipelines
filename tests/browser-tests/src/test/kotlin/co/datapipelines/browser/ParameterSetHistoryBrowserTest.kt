package co.datapipelines.browser

import com.microsoft.playwright.options.SelectOption
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalTime
import java.util.UUID

/**
 * #376 — the Parameter Sets History tab in a real browser (ui-screens §4.23 History; the workspace spec §12 scenarios 3,
 * 5, 6 and 7), over the real stack: the live form's evaluates and an observed evaluation land as records the History tab
 * lists, a superseded attempt's record still lands complete, two independent selectors show OVERLAPPING attempt stamps
 * in the record detail, a timed-out evaluation is a TIMEOUT record whose attempt has no end stamp, a render refusal is a
 * REFUSED attempt — distinguishable by the column — and the tab issues no evaluate of its own. Zero CSP refusals; the
 * handback's light and dark screens of the tab and an open record.
 *
 * The evaluate deadline is 3 s in this class so the timeout case answers fast; the unreachable-datasource variant of the
 * refusal arm runs on the real runner in `ParameterEvaluationHistoryIntegrationTest` (a pool is cached by datasource
 * name and an unreachable source refuses the save, so a browser cannot stage it honestly).
 */
class ParameterSetHistoryBrowserTest : ParameterSetBrowserSuite() {
    /** A selector that takes [sleep] seconds and answers `<kind>1` (the default) and `<kind>2`. */
    private fun slowOptions(sleep: String) =
        """
        SELECT v AS value, v AS display_value, (v LIKE '%1') AS is_default
          FROM (SELECT CAST(:kind AS TEXT) || '1' AS v FROM pg_sleep($sleep) UNION ALL SELECT CAST(:kind AS TEXT) || '2') t
         ORDER BY v
        """.trimIndent()

    private fun select(
        name: String,
        template: String,
        datasource: String,
        dependsOn: String = "kind",
    ): String =
        """{"name":"$name","label":"${name.replaceFirstChar {
            it.uppercase()
        }}","type":"STRING","kind":"SELECT","cardinality":"SINGLE",""" +
            """"required":false,"depends_on":["$dependsOn"],"source":{"template":{"id":"$template","version":1},""" +
            """"datasource":"$datasource"},"presentation":{"control":"dropdown"}}"""

    private fun awaitSelected(
        parameter: String,
        label: String,
    ) {
        page.waitForFunction(
            "([p, l]) => { const s = document.querySelector(\"#ps-form-host [data-dp-parameter='\" + p + \"'] select\"); " +
                "return !!(s && s.selectedOptions[0] && s.selectedOptions[0].text === l); }",
            listOf(parameter, label),
        )
    }

    private fun openHistory(id: String) {
        page.navigate("$baseUrl/parameter-sets/$id?tab=history")
        page.waitForSelector("[data-ps-history]")
    }

    private fun rows(): List<Map<String, String>> =
        @Suppress("UNCHECKED_CAST")
        (
            page.evaluate(
                """() => Array.from(document.querySelectorAll('#ps-history tr[data-evaluation-id]')).map(r => ({
                  id: r.getAttribute('data-evaluation-id'), caller: r.getAttribute('data-caller'),
                  status: r.querySelector('[data-status]').getAttribute('data-status') }))""",
            ) as List<Map<String, String>>
        )

    private fun openRecord(id: String) {
        page.click("#ps-history tr[data-evaluation-id='$id'] button.ps-history-open")
        page.waitForSelector("#ps-history-detail [data-evaluation-id='$id'] [data-history-queries]")
    }

    @Test
    fun `scenarios 3 and 7 - every page evaluate lands a complete record, a superseded one too, and the tab evaluates nothing`() {
        startTrace()
        val root = ready("psh")
        val datasource = registerSourceDatasource()
        createTemplate("$root/parameters/slow_options.sql", slowOptions("0.8"))
        val (id, hash) =
            createSet(
                setBody(
                    "$root/parameters/history",
                    "[${constants("kind", listOf("a", "b", "c"))},${select("item", "$root/parameters/slow_options.sql", datasource)}]",
                ),
            )
        release(id, hash, withTemplates = true)

        // The live form: the first render, then two changes — the second supersedes the first's pending evaluate.
        page.navigate("$baseUrl/parameter-sets/$id")
        awaitSelected("item", "a1")
        page.selectOption("#ps-form-host [data-dp-parameter='kind'] select", SelectOption().setLabel("B"))
        page.selectOption("#ps-form-host [data-dp-parameter='kind'] select", SelectOption().setLabel("C"))
        awaitSelected("item", "c1")
        // And one OBSERVED evaluation (the S2 route; its client half is #383) — the PAGE caller, the client's id as the key.
        val observed = UUID.randomUUID().toString()
        val (status, _) =
            api(
                "POST",
                "/api/v1/parameter-sets/$id/evaluations",
                """{"version":1,"selections":{},"evaluation_id":"$observed","instance_id":"${UUID.randomUUID()}"}""",
            )
        status shouldBe 200

        val requests = mutableListOf<String>()
        page.onRequest { request -> requests += request.method() + " " + request.url() }
        drainCspViolations()
        openHistory(id)

        val listed = rows()
        withClue("first render, B (superseded on the client), C, and the observed one: $listed") {
            listed.size shouldBeGreaterThanOrEqual 4
            listed.count { it["caller"] == "REST" } shouldBeGreaterThanOrEqual 3
            listed.single { it["caller"] == "PAGE" }["id"] shouldBe observed
            listed.all { it["status"] == "COMPLETED" } shouldBe true
        }
        openRecord(observed)
        page.locator("#ps-history-detail [data-history-queries] tr[data-parameter='item']").count() shouldBe 1
        page.locator("#ps-history-detail [data-history-outcomes] tr[data-parameter='kind']").count() shouldBe 1

        ensureTheme("light")
        shot("parameter-sets-history-light")
        ensureTheme("dark")
        shot("parameter-sets-history-dark")
        ensureTheme("light")
        withClue("the History tab issues no evaluate of its own") {
            requests.filter { it.startsWith("POST ") && it.contains("/evaluat") }.shouldBeEmpty()
        }
        drainCspViolations().filter { !it.contains("'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='") }.shouldBeEmpty()
    }

    @Test
    fun `scenario 5 - two independent selectors overlap in the record's attempt stamps, never serialised`() {
        startTrace()
        val root = ready("psho")
        val datasource = registerSourceDatasource()
        createTemplate("$root/parameters/left.sql", slowOptions("0.6"))
        createTemplate("$root/parameters/right.sql", slowOptions("0.6"))
        val parameters =
            "[${constants("kind", listOf("a"))},${select("left", "$root/parameters/left.sql", datasource)}," +
                "${select("right", "$root/parameters/right.sql", datasource)}]"
        val (id, hash) = createSet(setBody("$root/parameters/overlap", parameters))
        release(id, hash, withTemplates = true)
        val (status, _) = api("POST", "/api/v1/parameter-sets/$id/evaluate", """{"selections":{}}""")
        status shouldBe 200

        openHistory(id)
        val record = requireNotNull(rows().single()["id"])
        openRecord(record)

        fun stamps(parameter: String): Pair<LocalTime, LocalTime> {
            val row = "#ps-history-detail [data-history-queries] tr[data-parameter='$parameter']"
            return LocalTime.parse(page.locator("$row [data-started]").innerText()) to
                LocalTime.parse(page.locator("$row [data-ended]").innerText())
        }
        val (leftStart, leftEnd) = stamps("left")
        val (rightStart, rightEnd) = stamps("right")
        withClue("left $leftStart..$leftEnd, right $rightStart..$rightEnd — as the page shows them") {
            assertTrue(leftStart < rightEnd && rightStart < leftEnd, "the two attempts do not overlap")
        }
        ensureTheme("dark")
        detailShot("parameter-sets-history-detail-dark")
        ensureTheme("light")
        detailShot("parameter-sets-history-detail-light")
    }

    @Test
    fun `scenario 6 - a timed-out evaluation is a TIMEOUT record with no end stamp, a render refusal a REFUSED attempt`() {
        startTrace()
        val root = ready("pshe")
        val datasource = registerSourceDatasource()
        createTemplate(
            "$root/parameters/wait.sql",
            "SELECT 'x' AS value, 'x' AS display_value, TRUE AS is_default " +
                "FROM pg_sleep(CASE WHEN CAST(:kind AS TEXT) = 'slow' THEN 20 ELSE 0 END) ORDER BY 1",
        )
        // Renders only when the parent is not 'bad' — at evaluate the bad choice is a template render failure (REFUSED).
        createTemplate(
            "$root/parameters/picky.sql",
            "<#if (kind!'') == 'bad'>\${undefined_name}</#if>SELECT 'y' AS value, 'y' AS display_value, TRUE AS is_default ORDER BY 1",
        )
        val parameters =
            "[${constants("kind", listOf("fast", "slow", "bad"))},${select("wait", "$root/parameters/wait.sql", datasource)}," +
                "${select("picky", "$root/parameters/picky.sql", datasource)}]"
        val (id, hash) = createSet(setBody("$root/parameters/errors", parameters))
        release(id, hash, withTemplates = true)

        val (timedOut, timeoutBody) = api("POST", "/api/v1/parameter-sets/$id/evaluate", """{"selections":{"kind":"slow"}}""")
        withClue(timeoutBody.take(400)) { timedOut shouldBe 504 }
        val (refusedStatus, _) = api("POST", "/api/v1/parameter-sets/$id/evaluate", """{"selections":{"kind":"bad"}}""")
        refusedStatus shouldBe 200

        openHistory(id)
        val listed = rows()
        val timeout = requireNotNull(listed.single { it["status"] == "TIMEOUT" }["id"])
        val refused = requireNotNull(listed.single { it["status"] == "COMPLETED" }["id"])

        openRecord(timeout)
        page.locator("#ps-history-detail [data-outcome-code]").innerText() shouldBe "parameter.evaluate.timeout"
        val held = "#ps-history-detail [data-history-queries] tr[data-parameter='wait']"
        page.locator(held).getAttribute("data-outcome") shouldBe "TIMEOUT"
        withClue("the worker may still run: no end stamp is claimed") { page.locator("$held [data-ended]").innerText() shouldBe "—" }

        openRecord(refused)
        val picky = "#ps-history-detail [data-history-queries] tr[data-parameter='picky']"
        page.locator(picky).getAttribute("data-outcome") shouldBe "REFUSED"
        page.locator(picky).innerText() shouldContain "pipeline.node.template_render_failed"
        withClue("distinguishable BY THE COLUMN from the statement that ran beside it") {
            page.locator("#ps-history-detail [data-history-queries] tr[data-parameter='wait']").getAttribute("data-outcome") shouldBe
                "EXECUTED"
        }
    }

    /** The open record alone — the main pane scrolls, so a page shot would stop at the viewport above its attempts. */
    private fun detailShot(name: String) {
        page.waitForTimeout(SETTLE_MS)
        page
            .locator("#ps-history-detail")
            .screenshot(
                com.microsoft.playwright.Locator
                    .ScreenshotOptions()
                    .setPath(
                        java.nio.file.Paths
                            .get("build", "reports", "$name.png"),
                    ),
            )
    }

    companion object {
        private const val SETTLE_MS = 300.0

        @JvmStatic
        @DynamicPropertySource
        fun shortDeadline(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.parameters.evaluate-timeout-seconds") { "3" }
            registry.add("datapipelines.parameters.selector-query-timeout-seconds") { "3" }
        }
    }
}
