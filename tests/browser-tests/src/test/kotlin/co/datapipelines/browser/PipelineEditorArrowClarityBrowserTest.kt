package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 151 in the browser (issue #127), on a REAL run of a one-producer / two-consumer graph with a
 * DDL ordering ([EditorRunFixtures.createFanOutPipeline]). What the page must show, read off
 * the DOM and the live Cytoscape model the user reads:
 *
 *  1. IDLE — every `depends_on` entry is one `dependency` edge; no edge carries a label; the
 *     producer has exactly ONE output port naming `tempdb.stg`; the DDL node has none; the
 *     legend explains the two link kinds.
 *  2. THE PRODUCER WRITES — its port reads `writing · N written` with the flow element while
 *     the samples say so; NEITHER dependency out of it is active, and its two consumers read
 *     Pending: a write is a fact about the producer, not about its arrows.
 *  3. THE PRODUCER IS DONE — its port reads `committed · N rows`; its outgoing dependencies
 *     are `satisfied` (a fact about the source) — and still no label on any edge.
 *  4. CONSUMERS RUN — the edges INTO a running consumer are `active` (static), the producer's
 *     port is still `done` with no flow: consumer-running is not a write.
 *  5. COMPLETED — End reads Finished, every dependency is `satisfied`, nothing on the canvas
 *     is still animating, and no edge anywhere has a label.
 *
 * FALSIFICATION built in: the pre-151 client copied `rows_out` onto every outgoing edge and
 * animated the incoming edges of a running consumer — assertions 2, 3 and 5 go red on it.
 * Screenshots land in `build/reports/151-screenshots/` for the evidence record.
 */
class PipelineEditorArrowClarityBrowserTest : BrowserSuite() {
    @Test
    fun `one producer two consumers - one measured write on the port, two orderings on the arrows`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("ac-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("ac" + generatedPassword("w").take(8).lowercase())

        val datasource = "ac-src-" + generatedPassword("d").take(6).lowercase()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource) shouldBe emptyList<String>()
        val name = "test/ac_" + generatedPassword("p").take(8).lowercase()
        val pipelineId = EditorRunFixtures.createFanOutPipeline(page, name, datasource, rows = 900_000)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card[data-node-id='stg']").waitFor()
        // Only THIS class's shots are refreshed; the folder is shared with the other 151 suites.
        shotDir().toFile().listFiles()?.filter { f -> OWN_SHOTS.any { own -> f.name.startsWith("151-$own") } }?.forEach { it.delete() }

        // 1. Idle: kinds, no labels, one port on the producer, none on the DDL, the legend.
        val edges = edgeModel()
        edges.filter { it["kind"] == "dependency" }.map { it["id"] as String }.sorted() shouldBe
            listOf("mk_index->by_a", "mk_index->by_b", "stg->by_a", "stg->by_b", "stg->mk_index")
        edges.filter { it["kind"] == "boundary" }.size shouldBe 3 // Start→stg, by_a→End, by_b→End
        edges.all { (it["label"] as String).isEmpty() } shouldBe true
        page.locator(".pe-card[data-node-id='stg'] .pe-port").count() shouldBe 1
        page.locator(".pe-card[data-node-id='stg'] .pe-port .pe-port-dest").innerText().trim() shouldBe "tempdb.stg"
        page.locator(".pe-card[data-node-id='stg'] .pe-port").getAttribute("aria-label") shouldBe "Output to tempdb.stg"
        page.locator(".pe-card[data-node-id='mk_index'] .pe-port").count() shouldBe 0
        page.locator(".pe-card[data-node-id='by_b'] .pe-port .pe-port-dest").innerText().trim() shouldBe "caller"
        page.locator(".pe-legend-links").innerText() shouldContain "Depends on"
        page.locator(".pe-legend-links").innerText() shouldContain "Output write"
        shoot("idle")
        shootClose("idle-producer", "stg")

        page.locator("[data-verb='pipeline-execute']").click()

        // 2. The producer writes: port writing with a count and the flow; no arrow active.
        waitUntil("the producer's port reads writing") { portState("stg") == "writing" && WRITTEN.containsMatchIn(portLine("stg")) }
        page.locator(".pe-card[data-node-id='stg'] .pe-port .pe-port-flow").count() shouldBe 1
        val duringWrite = edgeModel()
        duringWrite.filter { it["kind"] == "dependency" }.all { it["active"] == false } shouldBe true
        cardState("by_a") shouldBe "Pending"
        cardState("by_b") shouldBe "Pending"
        cardState("mk_index") shouldBe "Pending"
        duringWrite.all { (it["label"] as String).isEmpty() } shouldBe true
        shoot("producer-writing")
        shootClose("producer-writing-close", "stg")

        // 3. The producer is done: committed count on the port, satisfied orderings, no labels.
        waitUntil("the producer's port reads committed") { portState("stg") == "done" }
        portLine("stg") shouldMatch Regex("committed · 900,000 rows.*")
        page.locator(".pe-card[data-node-id='stg'] .pe-port .pe-port-flow").count() shouldBe 0
        val afterProducer = edgeModel()
        afterProducer.first { it["id"] == "stg->by_a" }["satisfied"] shouldBe true
        afterProducer.first { it["id"] == "stg->by_b" }["satisfied"] shouldBe true
        afterProducer.first { it["id"] == "stg->mk_index" }["satisfied"] shouldBe true
        afterProducer.all { (it["label"] as String).isEmpty() } shouldBe true

        // 4. Consumers run: incoming edges active and STILL; the producer's port unchanged.
        val polls = observeConsumers()
        check(polls.any { it.activeInto.isNotEmpty() }) { "no consumer's incoming edge was ever active: ${polls.distinct()}" }
        check(polls.any { it.stgPort == "done" && it.stgFlow == 0 && it.activeInto.isNotEmpty() }) {
            "while a consumer ran the producer's port must stay done and unanimated: ${polls.distinct()}"
        }
        // (Two consumers writing at once is [PipelineEditorLeaseWaitBrowserTest]'s and
        // [NodeProgressBrowserTest]'s proof: here by_a also waits for the DDL, so the two
        // consumers are not promised to overlap.)

        // 5. Completed.
        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
        page.locator(".pe-status:has-text('Completed')").waitFor()
        page.locator(".pe-card-boundary-end").innerText() shouldContain "Finished"
        val done = edgeModel()
        done.filter { it["kind"] == "dependency" }.all { it["satisfied"] == true && it["active"] == false } shouldBe true
        done.all { (it["label"] as String).isEmpty() } shouldBe true
        page.locator(".pe-port-flow").count() shouldBe 0
        page.locator(".pe-card[data-node-id='by_a'] .pe-port").getAttribute("class") shouldContain "pe-port-done"
        page.locator(".pe-card[data-node-id='by_b'] .pe-port").getAttribute("class") shouldContain "pe-port-done"
        // The a11y list carries the operation words too — without colour or motion.
        page.locator("#pe-node-list [data-node-id='stg']").getAttribute("data-operation") shouldContain "Committed"
        shoot("completed")
        shootClose("completed-close", "stg")

        // The Details pane names the orderings in words.
        page.locator(".pe-card-open[data-node-open='by_a']").evaluate("el => el.click()")
        page.locator("#pe-pane-details .pe-kv").waitFor()
        val details = page.locator("#pe-pane-details .pe-kv").innerText()
        details shouldContain "Depends on"
        details shouldContain "stg (done) · mk_index (done)"
        shoot("details")
    }

    // --------------------------------------------------------------- reads

    private data class Poll(
        val activeInto: List<String>,
        val stgPort: String,
        val stgFlow: Int,
        val livePorts: List<String>,
    )

    /** Polls the model until both consumers are terminal; every read is ONE evaluate. */
    private fun observeConsumers(): List<Poll> {
        val polls = mutableListOf<Poll>()
        var shotConsumers = false
        val deadline = System.currentTimeMillis() + EXECUTION_TIMEOUT_MS.toLong()
        while (System.currentTimeMillis() < deadline) {
            @Suppress("UNCHECKED_CAST")
            val read =
                page.evaluate(
                    """() => {
                      const cy = document.getElementById('cy-canvas')._cyreg.cy;
                      const active = cy.edges('.dependency.active').map(e => e.id());
                      const port = (id) => document.querySelector(".pe-card[data-node-id='" + id + "'] .pe-port");
                      const cls = (el) => el ? [...el.classList].find(c => /^pe-port-(?!stale)[a-z]+$/.test(c) && c !== 'pe-port') : '';
                      const state = (id) => { const c = cls(port(id)); return c ? c.replace('pe-port-', '') : ''; };
                      const live = ['by_a', 'by_b'].filter(id => ['pending','waiting','writing','finalizing','combined'].includes(state(id)));
                      const st = (id) => { const el = document.querySelector(".pe-card[data-node-id='" + id + "'] .pe-card-st"); return el ? el.textContent.trim() : ''; };
                      return { active, stgPort: state('stg'), stgFlow: document.querySelectorAll(".pe-card[data-node-id='stg'] .pe-port-flow").length,
                               live, terminal: ['by_a','by_b'].every(id => ['Done','Failed','Aborted'].includes(st(id))) };
                    }""",
                ) as Map<String, Any?>
            val strings = { key: String -> (read[key] as List<*>).map { it as String } }
            val poll = Poll(strings("active"), read["stgPort"] as String, (read["stgFlow"] as Number).toInt(), strings("live"))
            polls.add(poll)
            if (!shotConsumers && poll.activeInto.isNotEmpty()) {
                shoot("consumers-running")
                shootClose("consumers-running-close", "by_b")
                shotConsumers = true
            }
            if (read["terminal"] == true) return polls
            Thread.sleep(POLL_MS)
        }
        error("the consumers never reached a terminal state")
    }

    /** Every edge of the live model: id, kind, the two 151 state classes, and Cytoscape's painted label. */
    @Suppress("UNCHECKED_CAST")
    private fun edgeModel(): List<Map<String, Any?>> =
        page.evaluate(
            """() => {
              const cy = document.getElementById('cy-canvas')._cyreg.cy;
              return cy.edges().map(e => ({ id: e.id(), kind: e.data('kind') || '', active: e.hasClass('active'),
                satisfied: e.hasClass('satisfied'), label: String(e.style('label') || '') }));
            }""",
        ) as List<Map<String, Any?>>

    private fun portState(nodeId: String): String =
        (page.locator(".pe-card[data-node-id='$nodeId'] .pe-port").getAttribute("class") ?: "")
            .split(" ").firstOrNull { it.startsWith("pe-port-") && it != "pe-port-stale" }?.removePrefix("pe-port-") ?: ""

    private fun portLine(nodeId: String): String =
        page.locator(".pe-card[data-node-id='$nodeId'] .pe-port .pe-port-line").let { if (it.count() == 0) "" else it.innerText().trim() }

    private fun cardState(nodeId: String): String = page.locator(".pe-card[data-node-id='$nodeId'] .pe-card-st").innerText().trim()

    private fun waitUntil(
        what: String,
        done: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + EXECUTION_TIMEOUT_MS.toLong()
        while (System.currentTimeMillis() < deadline) {
            val ok =
                try {
                    done()
                } catch (_: Exception) {
                    false
                }
            if (ok) return
            Thread.sleep(POLL_MS)
        }
        error("never saw: $what")
    }

    private fun shoot(state: String) {
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("151-$state.png")).setFullPage(true))
    }

    /** The stage at zoom 1 centred on [nodeId] (the cards and shapes at their real size), then back to the fit. */
    private fun shootClose(
        state: String,
        nodeId: String,
    ) {
        page.evaluate(
            "(id) => { const cy = document.getElementById('cy-canvas')._cyreg.cy; cy.zoom(1); cy.center(cy.getElementById(id)); }",
            nodeId,
        )
        page.locator(".pe-stage").screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("151-$state.png")))
        page.evaluate("() => window.__peInstance && window.__peInstance.graph && window.__peInstance.graph.fitToView()")
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "151-screenshots").also { it.toFile().mkdirs() }

    private companion object {
        const val EXECUTION_TIMEOUT_MS = 240_000.0
        const val POLL_MS = 40L
        val WRITTEN = Regex("\\d[\\d,]* written")
        val OWN_SHOTS = listOf("idle", "producer-writing", "consumers-running", "completed", "details")
    }
}
