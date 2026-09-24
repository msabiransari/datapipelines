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
 *  2. THE PRODUCER WRITES — the port recorder (installed before Execute, wrapping the
 *     graph's own `setNodeOperation`) holds every port view the page was TOLD to show,
 *     in event order: it contains a `writing` entry, and no dependency was active in
 *     that snapshot. Sampling the DOM for a transient state was the #206 flake: the
 *     html-label re-render is deferred (setTimeout(0) coalescing), so under load the DOM
 *     may never paint `writing` at all — and the tracker's first writing sample carries
 *     no count, so the count is asserted on the sequence, not on the writing entry.
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
        shotDir()
            .toFile()
            .listFiles()
            ?.filter { f -> OWN_SHOTS.any { own -> f.name.startsWith("151-$own") } }
            ?.forEach { it.delete() }

        idlePicture()
        installPortRecorder()
        page.locator("[data-verb='pipeline-execute']").click()
        producerWriting()
        producerDone()
        consumersRunning()
        completed()
        detailsNameTheOrderings()
    }

    /** 1. Idle: kinds, no labels, one port on the producer, none on the DDL, the legend. */
    private fun idlePicture() {
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
    }

    /**
     * 2. The producer writes: the RECORDED port sequence holds a writing entry, and the
     * snapshot taken at that event shows the write is a fact about the producer — no
     * dependency active, both consumers and the DDL still Pending, no edge labelled.
     * Nothing is sampled: the DOM's deferred re-render can skip a transient state under
     * load (that was the #206 flake), so the port is read where the page writes it — the
     * `setNodeOperation` call itself. The entry is GUARANTEED: NodeOperationTracker emits
     * a first-entry sample per phase and flushes it before the terminal one.
     */
    private fun producerWriting() {
        waitUntil("the port recorder saw the producer writing", ::trailDump) {
            portTrail().any { it.node == "stg" && it.state == "writing" }
        }
        val writing = portTrail().filter { it.node == "stg" && it.state == "writing" }
        writing.all { it.activeDeps.isEmpty() } shouldBe true
        writing.all { w -> listOf("by_a", "by_b", "mk_index").all { w.cardStates[it] == "idle" } } shouldBe true
        writing.all { !it.labelled } shouldBe true
        // The flow element is buildPortHtml's writing-only marker — a PURE function of the
        // port state, so it is asserted as a mapping, not timed against the live DOM.
        val card =
            page.evaluate(
                """() => window.PEGraphUtil.buildCardHtml({ id: 'stg', type: 'DQL', state: 'running',
                  output: { text: 'tempdb.stg' },
                  port: { state: 'writing', text: 'writing · 1,234 written', kindLabel: 'stage', a11y: 'a11y' } })""",
            ) as String
        card shouldContain "pe-port-writing"
        card shouldContain "pe-port-flow"
        shoot("producer-writing")
        shootClose("producer-writing-close", "stg")
    }

    /** 3. The producer is done: committed count on the port, satisfied orderings, no labels. */
    private fun producerDone() {
        waitUntil("the producer's port reads committed", ::trailDump) { portState("stg") == "done" }
        // The recorded sequence, not a sample: writing came BEFORE done, and the write was
        // MEASURED — the cumulative count showed on the port while the producer was live.
        // The count is NOT required on a writing entry: the tracker's first-entry sample
        // for the writing phase is taken at phase entry, before the first batch completes,
        // so it reads "writing" bare and the count lands on a later sample's own state
        // (finalizing carries "· 900,000 written"). Requiring both in one sample was the
        // other half of the #206 race.
        val trail = portTrail().filter { it.node == "stg" }
        val lastWrite = trail.indexOfLast { it.state == "writing" }
        val firstDone = trail.indexOfFirst { it.state == "done" }
        check(lastWrite >= 0 && firstDone > lastWrite) {
            "the recorded port sequence has no writing before done: ${trail.map { it.state }}"
        }
        check(trail.subList(0, firstDone).any { WRITTEN.containsMatchIn(it.text) }) {
            "the recorded port sequence never measured the write (no 'N written' before done): ${trail.map { it.text }}"
        }
        portLine("stg") shouldMatch Regex("committed · 900,000 rows.*")
        page.locator(".pe-card[data-node-id='stg'] .pe-port .pe-port-flow").count() shouldBe 0
        val afterProducer = edgeModel()
        afterProducer.first { it["id"] == "stg->by_a" }["satisfied"] shouldBe true
        afterProducer.first { it["id"] == "stg->by_b" }["satisfied"] shouldBe true
        afterProducer.first { it["id"] == "stg->mk_index" }["satisfied"] shouldBe true
        afterProducer.all { (it["label"] as String).isEmpty() } shouldBe true
    }

    /**
     * 4. Consumers run: incoming edges active and STILL; the producer's port unchanged. (Two
     * consumers writing at once is [PipelineEditorLeaseWaitBrowserTest]'s and
     * [NodeProgressBrowserTest]'s proof: here both consumers also wait for the DDL.)
     */
    private fun consumersRunning() {
        val polls = observeConsumers()
        check(polls.any { it.activeInto.isNotEmpty() }) { "no consumer's incoming edge was ever active: ${polls.distinct()}" }
        check(polls.any { it.stgPort == "done" && it.stgFlow == 0 && it.activeInto.isNotEmpty() }) {
            "while a consumer ran the producer's port must stay done and unanimated: ${polls.distinct()}"
        }
    }

    /** 5. Completed: End Finished, every dependency satisfied, nothing animating, no label anywhere. */
    private fun completed() {
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
    }

    /** The Details pane names the orderings in words. */
    private fun detailsNameTheOrderings() {
        page.locator(".pe-card-open[data-node-open='by_a']").evaluate("el => el.click()")
        page.locator("#pe-pane-details .pe-kv").waitFor()
        val details = page.locator("#pe-pane-details .pe-kv").innerText()
        details shouldContain "Depends on"
        details shouldContain "stg (done) · mk_index (done)"
        shoot("details")
    }

    // --------------------------------------------------------------- reads

    /**
     * #206: the port recorder — one entry per port view the page is TOLD to show, in event
     * order, wrapped around the graph's own `setNodeOperation` (the synchronous write every
     * SSE event makes; the DOM re-render it triggers is deferred and may coalesce a
     * transient state away under load). Each entry also snapshots what the write-phase
     * assertions read: the active dependency edges, every node's card state, and whether
     * any edge carries a label.
     */
    private data class TrailEntry(
        val node: String,
        val state: String,
        val text: String,
        val activeDeps: List<String>,
        val cardStates: Map<String, String>,
        val labelled: Boolean,
    )

    private fun installPortRecorder() {
        page.evaluate(
            """() => {
              const editor = window.__peInstance;
              if (!editor || !editor.graph || typeof editor.graph.setNodeOperation !== 'function') return false;
              const cy = document.getElementById('cy-canvas')._cyreg.cy;
              window.__portTrail = [];
              const orig = editor.graph.setNodeOperation.bind(editor.graph);
              editor.graph.setNodeOperation = function (nodeId, view) {
                const r = orig(nodeId, view);
                if (view && view.port) {
                  window.__portTrail.push({
                    node: nodeId,
                    state: view.port.state || '',
                    text: view.port.text || '',
                    activeDeps: cy.edges('.dependency.active').map(e => e.id()),
                    cardStates: Object.fromEntries(cy.nodes().map(n => [n.id(), n.data('state') || ''])),
                    labelled: cy.edges().some(e => String(e.style('label') || '') !== ''),
                  });
                }
                return r;
              };
              return true;
            }""",
        ) shouldBe true
    }

    @Suppress("UNCHECKED_CAST")
    private fun portTrail(): List<TrailEntry> {
        val raw = page.evaluate("() => window.__portTrail || []") as List<Map<String, Any?>>
        return raw.map { e ->
            TrailEntry(
                node = e["node"] as String,
                state = e["state"] as String,
                text = e["text"] as String,
                activeDeps = (e["activeDeps"] as List<*>).map { it as String },
                cardStates = (e["cardStates"] as Map<*, *>).map { (k, v) -> k as String to v as String }.toMap(),
                labelled = e["labelled"] as Boolean,
            )
        }
    }

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
            .split(" ")
            .firstOrNull { it.startsWith("pe-port-") && it != "pe-port-stale" }
            ?.removePrefix("pe-port-")
            ?: ""

    private fun portLine(nodeId: String): String =
        page.locator(".pe-card[data-node-id='$nodeId'] .pe-port .pe-port-line").let { if (it.count() == 0) "" else it.innerText().trim() }

    private fun waitUntil(
        what: String,
        diagnostics: () -> String = { "" },
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
        error("never saw: $what ${diagnostics()}")
    }

    /** The recorded trail, compressed to transitions, for failure messages. */
    private fun trailDump(): String =
        "trail: " +
            portTrail()
                .fold(mutableListOf<String>()) { acc, e ->
                    val t = "${e.node}:${e.state}:${e.text}"
                    if (acc.lastOrNull() != t) acc += t
                    acc
                }.joinToString(" → ")

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
