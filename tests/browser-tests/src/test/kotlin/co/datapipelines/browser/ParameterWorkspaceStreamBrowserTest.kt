package co.datapipelines.browser

import com.microsoft.playwright.options.SelectOption
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * #383 - the workspace's OBSERVED evaluation in a real browser (rest-api section 21.5, spec section 12): the
 * live form streams `POST /api/v1/parameter-sets/{id}/evaluations`, the page's own frame log
 * (`window.PSWorkspace.frames`) is the witness every assertion reads - never a timeout - and the graph's node
 * states move waiting -> admitted -> running -> resolved | failed with the frames. The scenarios are the spec's:
 * 3 (the cascade, observed), 6 (a failing selector and the evaluate-timeout case), 7 (a superseded attempt's
 * stream closes; a mid-stream version switch never mixes generations), 10 (an ordinary REST evaluate beside an
 * open stream - byte-identical, no page frames), and the promoter arm (no control, and NO POST to any
 * `/api/v1/parameter-sets/` path leaves the page - filtered by METHOD and PATH). Both themes and the CSP
 * collector cover the workspace during a streamed evaluation.
 *
 * The context this class boots carries a SHORT evaluate deadline (`datapipelines.parameters.evaluate-timeout-seconds=2`):
 * the timeout case must land its `evaluation_failed` WELL INSIDE the page's own 30 s lock - at the default 30 s
 * the server's terminal frame and the client's lock expiry would race. The property forks one extra Spring
 * context for this class; every other browser class shares the default one.
 */
class ParameterWorkspaceStreamBrowserTest : ParameterSetBrowserSuite() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun streamDeadline(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.parameters.evaluate-timeout-seconds") { "2" }
            // A selector statement must fit inside its evaluate deadline (the config's own rule) — both move together.
            registry.add("datapipelines.parameters.selector-query-timeout-seconds") { "2" }
        }
    }

    /** level1 (constants) -> level2 (a selector that fails for `boom`) -> level3 (a selector on level2). */
    private fun seedCascadeSet(root: String): String {
        val datasource = registerSourceDatasource()
        createTemplate(
            "$root/parameters/level2_options.sql",
            """
            SELECT v AS value, v AS display_value, (v LIKE '%1') AS is_default
              FROM (SELECT CAST(:level1 AS TEXT) || '1' AS v UNION ALL SELECT CAST(:level1 AS TEXT) || '2') t
             WHERE 1 / (CASE WHEN CAST(:level1 AS TEXT) = 'boom' THEN 0 ELSE 1 END) = 1
             ORDER BY v
            """.trimIndent(),
        )
        createTemplate(
            "$root/parameters/level3_options.sql",
            "SELECT CAST(:level2 AS TEXT) || '-child' AS value, CAST(:level2 AS TEXT) || '-child' AS display_value, " +
                "true AS is_default ORDER BY 1",
        )
        val level2 =
            """{"name":"level2","label":"Level two","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                """"depends_on":["level1"],"source":{"template":{"id":"$root/parameters/level2_options.sql","version":1},""" +
                """"datasource":"$datasource"},"presentation":{"control":"dropdown"}}"""
        val level3 =
            """{"name":"level3","label":"Level three","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                """"depends_on":["level2"],"source":{"template":{"id":"$root/parameters/level3_options.sql","version":1},""" +
                """"datasource":"$datasource"},"presentation":{"control":"dropdown"}}"""
        val body = setBody("$root/parameters/cascade", "[${constants("level1", listOf("a", "b", "boom"))},$level2,$level3]")
        val (id, hash) = createSet(body)
        release(id, hash, withTemplates = true)
        return id
    }

    /** level1 (constants) -> a selector that sleeps 1.7 s for `slow` (the in-flight window, under the deadline). */
    private fun seedSlowSet(root: String): Pair<String, String> {
        val datasource = registerSourceDatasource()
        createTemplate(
            "$root/parameters/paced_options.sql",
            """
            SELECT CAST(:paced1 AS TEXT) AS value, upper(CAST(:paced1 AS TEXT)) AS display_value, true AS is_default
              FROM (SELECT pg_sleep(CASE WHEN CAST(:paced1 AS TEXT) = 'slow' THEN 1.7 ELSE 0 END)) s
             ORDER BY 1
            """.trimIndent(),
        )
        val paced =
            """{"name":"paced","label":"Paced","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                """"depends_on":["paced1"],"source":{"template":{"id":"$root/parameters/paced_options.sql","version":1},""" +
                """"datasource":"$datasource"},"presentation":{"control":"dropdown"}}"""
        val body = setBody("$root/parameters/paced", "[${constants("paced1", listOf("a", "ok", "slow"))},$paced]")
        val (id, hash) = createSet(body)
        return id to release(id, hash, withTemplates = true)
    }

    /**
     * The DEADLINE case's chain: two selectors of 1.2 s each - every statement fits its own 2 s query timeout,
     * the CASCADE'S TOTAL passes the 2 s evaluate deadline, so the whole-evaluation `withTimeout` (the one shape
     * `parameter.evaluate.timeout` projects from) fires deterministically mid-second-statement.
     */
    private fun seedDeadlineSet(root: String): String {
        val datasource = registerSourceDatasource()
        val sleepSql = { inner: String ->
            """
            SELECT $inner AS value, upper($inner) AS display_value, true AS is_default
              FROM (SELECT pg_sleep(CASE WHEN CAST(:c1 AS TEXT) = 'deep' THEN 1.2 ELSE 0 END)) s
             ORDER BY 1
            """.trimIndent()
        }
        createTemplate("$root/parameters/deep1_options.sql", sleepSql("CAST(:c1 AS TEXT)"))
        createTemplate("$root/parameters/deep2_options.sql", sleepSql("CAST(:c1 AS TEXT) || '-2'"))
        val deep1 =
            """{"name":"deep1","label":"Deep one","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                """"depends_on":["c1"],"source":{"template":{"id":"$root/parameters/deep1_options.sql","version":1},""" +
                """"datasource":"$datasource"},"presentation":{"control":"dropdown"}}"""
        val deep2 =
            """{"name":"deep2","label":"Deep two","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                """"depends_on":["deep1","c1"],"source":{"template":{"id":"$root/parameters/deep2_options.sql","version":1},""" +
                """"datasource":"$datasource"},"presentation":{"control":"dropdown"}}"""
        val (id, hash) = createSet(setBody("$root/parameters/deep", "[${constants("c1", listOf("a", "deep"))},$deep1,$deep2]"))
        release(id, hash, withTemplates = true)
        return id
    }

    private fun openWorkspace(id: String) {
        page.navigate("$baseUrl/parameter-sets/$id")
        page.waitForSelector("#ps-form-host [data-dp-parameter] select")
        page.waitForSelector("#ps-graph canvas")
        awaitLog("frames => frames.some(f => f.applied && f.event === 'evaluation_completed')")
    }

    private fun choose(
        parameter: String,
        label: String,
    ) {
        page.selectOption("#ps-form-host [data-dp-parameter='$parameter'] select", SelectOption().setLabel(label))
    }

    private fun selectedLabel(parameter: String): String =
        page.locator("#ps-form-host [data-dp-parameter='$parameter'] select").evaluate("s => s.selectedOptions[0].text") as String

    /** Waits until the page's OWN frame log satisfies [condition] - the log is the witness, never a timeout. */
    private fun awaitLog(condition: String) {
        page.waitForFunction("() => ($condition)(window.PSWorkspace.frames || [])")
    }

    @Suppress("UNCHECKED_CAST")
    private fun frames(): List<Map<String, Any?>> = page.evaluate("() => window.PSWorkspace.frames") as List<Map<String, Any?>>

    private fun appliedFrames(): List<Map<String, Any?>> = frames().filter { it["applied"] == true }

    /** The node's Cytoscape classes, read through the page's own graph handle. */
    private fun nodeClasses(name: String): String =
        page.evaluate(
            """(n) => {
                 const root = document.querySelector('.ps-root');
                 const held = window.PSWorkspace.mounted.get(root);
                 const graph = held && held.structure && held.structure.graph();
                 return graph ? graph.cy.getElementById(n).classes().join(' ') : '';
               }""",
            name,
        ) as String

    private fun completedCount(): Int = appliedFrames().count { it["event"] == "evaluation_completed" }

    @Test
    fun `scenario 3 - the cascade observed - frame order, node states, the reset mark and one-sentence announcements`() {
        startTrace()
        val id = seedCascadeSet(ready("pw3"))
        openWorkspace(id)
        drainCspViolations()

        val before = completedCount()
        choose("level1", "B")
        awaitLog("frames => frames.filter(f => f.applied && f.event === 'evaluation_completed').length === ${before + 1}")

        // The frame order of the SECOND attempt, read from the page's own log.
        val attemptFrames = appliedFrames()
        val attemptId = attemptFrames.last()["evaluation_id"] as String
        val seq = attemptFrames.filter { it["evaluation_id"] == attemptId }.map { it["event"].toString() + ":" + (it["name"] ?: "") }
        seq.first() shouldBe "evaluation_started:"
        seq.last() shouldBe "evaluation_completed:"
        assertTrue(seq.indexOf("parameter_resolved:level2") < seq.indexOf("parameter_admitted:level3"), "the cascade's true order: $seq")
        for (name in listOf("level2", "level3")) {
            val path = seq.filter { it.endsWith(":" + name) }
            assertTrue(
                path.indexOf("parameter_waiting:$name") < path.indexOf("parameter_admitted:$name") &&
                    path.indexOf("parameter_admitted:$name") < path.indexOf("parameter_running:$name") &&
                    path.indexOf("parameter_running:$name") < path.indexOf("parameter_resolved:$name"),
                "the per-parameter path is forward-only: $path",
            )
        }
        assertTrue(seq.contains("parameter_resolved:level1"), "the constants parameter resolves without query frames: $seq")
        assertTrue(seq.none { it.startsWith("parameter_running:level1") }, "a constants parameter emits no query frames: $seq")

        // The graph carries the end state, the reset mark rides beside it, and the live region spoke in sentences.
        nodeClasses("level2") shouldContain "ps-resolved"
        nodeClasses("level2") shouldContain "ps-reset"
        nodeClasses("level3") shouldContain "ps-resolved"
        nodeClasses("level1") shouldContain "ps-resolved"
        page.locator("#ps-graph-live").innerText() shouldBe "The evaluation completed."
        // The applied state is the new generation's: the form shows b's options, never a's.
        selectedLabel("level2") shouldBe "b1"

        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `scenario 6 - a failing selector fails its node with the code verbatim, and the deadline ends evaluation_failed`() {
        startTrace()
        val root = ready("pw6")
        val id = seedCascadeSet(root)
        openWorkspace(id)

        val before = completedCount()
        choose("level1", "BOOM")
        awaitLog("frames => frames.filter(f => f.applied && f.event === 'evaluation_completed').length === ${before + 1}")
        // The failed SELECTOR's frame is first in the cascade; the dependent gets its own terminal frame.
        val failureFrame = appliedFrames().first { it["event"] == "parameter_failed" }
        failureFrame["name"] shouldBe "level2"
        val code = failureFrame["code"] as String
        assertTrue(code.isNotBlank(), "the frame carries the catalogued code")
        val terminal = appliedFrames().last()
        terminal["event"] shouldBe "evaluation_completed"
        val level3Frames = appliedFrames().filter { it["name"] == "level3" }
        assertTrue(
            level3Frames.any { it["event"] == "parameter_resolved" || it["event"] == "parameter_failed" },
            "the dependent reached a terminal per-parameter frame: ${level3Frames.map { it["event"] }}",
        )
        assertTrue(nodeClasses("level2").contains("ps-failed"), "the failed node shows failed")
        assertTrue(
            nodeClasses("level3").contains("ps-resolved") || nodeClasses("level3").contains("ps-failed"),
            "the dependent settled to a terminal state: " + nodeClasses("level3"),
        )
        // The inspector carries the failed parameter's catalogued code as TEXT (the applied response's own
        // error row - the durable record a rendered response keeps).
        page.focus("#ps-graph")
        page.keyboard().press("ArrowDown")
        page.keyboard().press("ArrowDown")
        page.waitForFunction("(c) => document.getElementById('ps-inspector').innerText.includes(c)", code)
        val inspector = page.locator("#ps-inspector").innerText()
        assertTrue(inspector.contains(code), "the inspector names the failed parameter's code: $inspector")

        // The timeout case: the cascade's TOTAL passes the 2 s deadline mid-second-statement (each statement
        // fits its own query timeout), so `parameter.evaluate.timeout` is the deterministic end.
        val deepId = seedDeadlineSet(root + "deep")
        openWorkspace(deepId)
        choose("c1", "DEEP")
        awaitLog("frames => frames.filter(f => f.applied && f.event === 'evaluation_failed').length === 1")
        val failedTerminal = appliedFrames().last()
        failedTerminal["event"] shouldBe "evaluation_failed"
        failedTerminal["code"] shouldBe "parameter.evaluate.timeout"
        assertTrue(
            nodeClasses("deep2").contains("ps-failed"),
            "D3: the unfinished node fails from the terminal frame; deep2=" + nodeClasses("deep2"),
        )
        assertTrue(nodeClasses("deep1").contains("ps-resolved"), "the parameter that resolved before the deadline stays resolved")
        page.locator("#ps-form-error").isVisible() shouldBe true
        page.locator("#ps-form-error-code").innerText() shouldBe "parameter.evaluate.timeout"
        page.locator("#ps-graph-live").innerText() shouldBe "The evaluation failed (parameter.evaluate.timeout)."
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `scenario 7 - a supersede closes the old stream mid-flight and the old generation's frames never land`() {
        startTrace()
        val id = seedSlowSet(ready("pw7")).first
        openWorkspace(id)
        drainCspViolations()

        val bootstrappedId = appliedFrames().last { it["event"] == "evaluation_started" }["evaluation_id"] as String
        choose("paced1", "SLOW")
        awaitLog(
            "frames => frames.some(f => f.applied && f.event === 'parameter_running' && f.name === 'paced'" +
                " && f.evaluation_id !== '$bootstrappedId')",
        )
        val oldId = appliedFrames().last { it["event"] == "evaluation_started" }["evaluation_id"] as String
        assertTrue(oldId != bootstrappedId)
        assertTrue(nodeClasses("paced").contains("ps-running"), "the in-flight parameter is running: " + nodeClasses("paced"))
        page.locator("#ps-graph-live").innerText() shouldContain "is running"
        choose("paced1", "OK")
        awaitLog("frames => frames.some(f => f.event === 'stream_closed' && f.reason === 'superseded' && f.evaluation_id === '$oldId')")
        val log = frames()
        val supersededAt = log.indexOfFirst { it["event"] == "stream_closed" && it["reason"] == "superseded" }
        val after = log.drop(supersededAt + 1).filter { it["applied"] == true }
        val afterEvents = after.map { it["event"] }
        assertTrue(
            after.none { it["evaluation_id"] == oldId },
            "the old generation's frames change nothing after the supersede: $afterEvents",
        )
        awaitLog("frames => frames.filter(f => f.applied && f.event === 'evaluation_completed').length >= 2")
        assertTrue(nodeClasses("paced").contains("ps-resolved"))
        assertTrue(nodeClasses("paced").contains("ps-reset"), "the dropped selection is marked reset")
        selectedLabel("paced1") shouldBe "OK"

        // A SECOND streamed window, undisturbed: both themes and the CSP collector across a live evaluation.
        drainCspViolations()
        choose("paced1", "SLOW")
        awaitLog(
            "frames => frames.some(f => f.applied && f.event === 'parameter_running' && f.name === 'paced'" +
                " && f.evaluation_id !== '$oldId')",
        )
        ensureTheme("light")
        shot("parameter-stream-light")
        ensureTheme("dark")
        shot("parameter-stream-dark")
        ensureTheme("light")
        awaitLog("frames => frames.filter(f => f.applied && f.event === 'evaluation_completed').length >= 3")
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `scenario 7b - a mid-stream version switch aborts the old stream and the new page never mixes generations`() {
        startTrace()
        val root = ready("pw7b")
        val datasource = registerSourceDatasource()
        createTemplate(
            "$root/parameters/paced_options.sql",
            """
            SELECT CAST(:paced1 AS TEXT) AS value, upper(CAST(:paced1 AS TEXT)) AS display_value, true AS is_default
              FROM (SELECT pg_sleep(CASE WHEN CAST(:paced1 AS TEXT) = 'slow' THEN 1.5 ELSE 0 END)) s
             ORDER BY 1
            """.trimIndent(),
        )
        val paced =
            """{"name":"paced","label":"Paced","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                """"depends_on":["paced1"],"source":{"template":{"id":"$root/parameters/paced_options.sql","version":1},""" +
                """"datasource":"$datasource"},"presentation":{"control":"dropdown"}}"""
        val body = setBody("$root/parameters/paced", "[${constants("paced1", listOf("a", "slow"))},$paced]")
        val (id, v1Hash) = createSet(body)
        val released = release(id, v1Hash, withTemplates = true)
        val v2Body = setBody("$root/parameters/paced", "[${constants("paced1", listOf("a", "slow"))},$paced]", displayName = "Paced v2")
        val v2Hash = newDraft(id, released, v2Body)
        release(id, v2Hash, withTemplates = true)

        page.navigate("$baseUrl/parameter-sets/$id")
        page.waitForSelector("#ps-form-host [data-dp-parameter] select")
        awaitLog("frames => frames.some(f => f.applied && f.event === 'evaluation_completed')")
        choose("paced1", "SLOW")
        awaitLog("frames => frames.some(f => f.applied && f.event === 'parameter_running' && f.name === 'paced')")

        // The switch: a FULL-document link (hx-boost="false"). The old page unmounts, dispose closes its stream.
        val aborted = mutableListOf<String>()
        page.onRequestFailed { request ->
            if (request.method() == "POST" && request.url().endsWith("/evaluations")) aborted += request.url()
        }
        page.click(".ps-versions a:has-text('v2')")
        page.waitForURL("**/parameter-sets/$id?version=2")
        page.waitForSelector("[data-ps-viewed-label]")
        page.locator("[data-ps-viewed-label]").innerText() shouldBe "v2 · released · current"
        // The old stream's AbortController fired - Playwright saw the request cancelled (a bounded wait for the
        // browser event; Playwright offers no waitFor for requestFailed).
        val deadline = System.currentTimeMillis() + 10_000L
        while (aborted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(aborted.isNotEmpty(), "the superseded stream's request was never seen aborted")
        // The new page's log is its own: one fresh attempt for v2, no frame of the old generation.
        awaitLog("frames => frames.some(f => f.applied && f.event === 'evaluation_completed')")
        val newLog = frames()
        assertTrue(newLog.map { it["evaluation_id"] }.toSet().size == 1, "the new page streams only its own attempt: $newLog")
        assertTrue(newLog.none { (it["name"] ?: "") == "paced" && it["event"] == "parameter_failed" })
    }

    @Test
    fun `scenario 10 - an ordinary REST evaluate beside an open stream - byte-identical response, no page frames`() {
        startTrace()
        val id = seedSlowSet(ready("pw10")).first
        openWorkspace(id)
        val bootedId = appliedFrames().last { it["event"] == "evaluation_started" }["evaluation_id"] as String
        choose("paced1", "SLOW")
        awaitLog(
            "frames => frames.some(f => f.applied && f.event === 'parameter_running' && f.name === 'paced'" +
                " && f.evaluation_id !== '$bootedId')",
        )
        val idsBefore = frames().map { it["evaluation_id"] }.toSet()

        val (status, body) =
            api("POST", "/api/v1/parameter-sets/$id/evaluate", """{"version":1,"selections":{"paced1":"slow","paced":"a"}}""")
        status shouldBe 200

        awaitLog("frames => frames.filter(f => f.applied && f.event === 'evaluation_completed').length >= 2")
        // The REST evaluate streamed NOTHING to the page: no new evaluation_id entered the log.
        val idsAfter = frames().map { it["evaluation_id"] }.toSet()
        idsAfter.size shouldBe idsBefore.size
        // The terminal frame's response equals the REST response's data - the same object, applied to the form.
        @Suppress("UNCHECKED_CAST")
        val identical =
            page.evaluate(
                """(restBody) => {
                     const frame = window.PSWorkspace.frames.filter((f) => f.applied && f.event === 'evaluation_completed').pop();
                     const rest = JSON.parse(restBody).data;
                     const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b) ||
                       (a && b && typeof a === 'object' && typeof b === 'object' &&
                         Object.keys(a).length === Object.keys(b).length &&
                         Object.keys(a).every((k) => equal(a[k], b[k])));
                     return { responsePresent: !!frame, equal: !!(frame && frame.response) && equal(frame.response, rest),
                              frameJson: frame && frame.response ? JSON.stringify(frame.response) : 'null',
                              restJson: JSON.stringify(rest) };
                   }""",
                body,
            ) as Map<String, Any?>
        assertTrue(identical["responsePresent"] == true, "the page's terminal frame is missing")
        assertTrue(
            identical["equal"] == true,
            "the REST response differs from the page's evaluation_completed.response" +
                "\n  frame: " + identical["frameJson"] + "\n  rest:  " + identical["restJson"],
        )
        // The form applied the streamed response (the REST call's answer changed nothing on the page).
        selectedLabel("paced") shouldBe "SLOW"
    }

    @Test
    fun `the promoter's page renders the structure, offers no control and issues no POST to any parameter-sets path`() {
        startTrace()
        val root = ready("pwpro")
        val workspaceName = activeWorkspace()
        val (id, hash) = createSet(setBody("$root/parameters/promoted_stream", "[${constants("kind", listOf("a", "b"))}]"))
        release(id, hash)

        val promoter = openPromoterSession(workspaceName)
        val requests = mutableListOf<String>()
        promoter.page.onRequest { request -> requests += request.method() + " " + request.url() }

        promoter.page.navigate("$baseUrl/parameter-sets/$id")
        promoter.page.waitForSelector("[data-ps-read-only]")
        promoter.page.locator("#ps-form-host").count() shouldBe 0
        promoter.page.waitForSelector("#ps-graph canvas")
        // The wire: NO request with method POST to any /api/v1/parameter-sets/ path - METHOD and PATH, never a substring.
        requests.filter { it.startsWith("POST ") && it.contains("/api/v1/parameter-sets/") }.shouldBeEmpty()
        // The page never even mounted a form to stream from.
        assertTrue(
            promoter.page.evaluate("() => (window.PSWorkspace.frames || []).filter((f) => f.applied).length") as Int == 0,
            "the promoter's page streamed frames",
        )
        promoter.close()
    }
}
