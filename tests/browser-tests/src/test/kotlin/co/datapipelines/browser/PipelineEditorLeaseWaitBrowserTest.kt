package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 151 in the browser (issue #127): a REAL lease wait on the output port.
 *
 * The staging pool is capped at ONE operational connection for this class alone
 * (`datapipelines.staging.h2.max-connections=1`, configuration.md §3 — "every tempdb
 * operation then queues behind one connection"), so two producers staging into tempdb at
 * once take turns per batch: while one holds the connection and writes, the other's
 * `node_progress` sample says `waiting_output`. The port must show that as a WAIT —
 * `waiting for tempdb connection`, amber, no flow — and never as a write. The reading is
 * the port's own class and text (a sample is a fact about the destination), not a guess
 * from the node's status.
 *
 * #150: the port is read where the page WRITES it — a recorder wrapped around the graph's
 * own `setNodeOperation` keeps every port view in event order, and the wait/write overlap
 * is decided from that recorded order. The first cut sampled the DOM on a 30 ms poll and
 * required one poll to land inside the overlap; the DOM's re-render is deferred
 * (setTimeout(0) coalescing), so under load the transient never reached a sample.
 *
 * This class boots its own application context (the property differs), so it costs one
 * extra boot in the browser gate; it exists because the only honest way to show a wait
 * is to make one happen. Screenshots join `build/reports/151-screenshots/`.
 */
class PipelineEditorLeaseWaitBrowserTest : BrowserSuite() {
    @Test
    fun `with one staging connection two writers take turns and the waiting port says so`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("lw-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("lw" + generatedPassword("w").take(8).lowercase())

        val datasource = "lw-src-" + generatedPassword("d").take(6).lowercase()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource) shouldBe emptyList<String>()
        val name = "test/lw_" + generatedPassword("p").take(8).lowercase()
        val pipelineId = createTwoWriters(page, name, datasource, rows = 700_000)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card[data-node-id='src_a']").waitFor()

        installPortRecorder()
        page.locator("[data-verb='pipeline-execute']").click()

        waitUntil("the recorder saw a port waiting", ::trailDump) { portTrail().any { it.state == "waiting" } }
        shootWait()
        waitUntil("both writers committed", ::trailDump) {
            portTrail()
                .filter { it.state == "done" }
                .map { it.node }
                .toSet()
                .containsAll(listOf("src_a", "src_b"))
        }

        val trail = portTrail()
        val waits = trail.filter { it.state == "waiting" }
        check(waits.isNotEmpty()) { "no port ever read waiting with one staging connection; trail: ${trail.transitions()}" }
        // A wait is a wait: the amber line names the connection.
        waits.all { it.text == "waiting for tempdb connection" } shouldBe true
        // And nothing flows while waiting: the flow element is buildPortHtml's
        // writing-ONLY marker (graph.js) — a pure mapping, asserted as one.
        val waitingCard =
            page.evaluate(
                """() => window.PEGraphUtil.buildCardHtml({ id: 'src_a', type: 'DQL', state: 'running',
                  output: { text: 'tempdb.stg_a' },
                  port: { state: 'waiting', text: 'waiting for tempdb connection', kindLabel: 'stage', a11y: 'a11y' } })""",
            ) as String
        waitingCard shouldContain "pe-port-waiting"
        waitingCard.contains("pe-port-flow") shouldBe false
        // And the same run also wrote: the overlap is read off the samples' OWN
        // observation instants (observed_at — one server clock, comparable across nodes),
        // never off arrival order (the pump emits the two nodes' samples in ITS order; a
        // waiting sample can arrive after the other's writing-ended sample although the
        // wait happened during the write — measured, 150-alone-8) and never off a
        // coincident DOM sample (the 30 ms poll of the first cut).
        val samples = opSamples()
        val waitingA = samples.intervals("src_a", "waiting_output")
        val waitingB = samples.intervals("src_b", "waiting_output")
        val writingA = samples.intervals("src_a", "writing")
        val writingB = samples.intervals("src_b", "writing")
        check(
            waitingA.any { w -> writingB.any { w.overlaps(it) } } ||
                waitingB.any { w -> writingA.any { w.overlaps(it) } },
        ) {
            "no waiting interval overlapped the other writer's writing interval; " +
                "waits a=$waitingA b=$waitingB writes a=$writingA b=$writingB"
        }

        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
        page.locator(".pe-status:has-text('Completed')").waitFor()
        page.locator(".pe-card[data-node-id='src_a'] .pe-port .pe-port-line").innerText() shouldContain "committed · 700,000 rows"
        page.locator(".pe-card[data-node-id='src_b'] .pe-port .pe-port-line").innerText() shouldContain "committed · 700,000 rows"
    }

    /** One recorded port view — what the page was TOLD to show, in event order. */
    private data class TrailEntry(
        val node: String,
        val state: String,
        val text: String,
    )

    /** The transition list for failure messages: consecutive duplicates collapsed. */
    private fun List<TrailEntry>.transitions(): String =
        fold(mutableListOf<Pair<String, String>>()) { acc, e ->
            if (acc.lastOrNull() != (e.node to e.state)) acc += e.node to e.state
            acc
        }.joinToString(" → ") { "${it.first}=${it.second}" }

    /**
     * #150: wraps the graph's `setNodeOperation` — the synchronous write every SSE event
     * makes — so every port view is recorded in arrival order, load or no load. Also wraps
     * `editor.logEvent` to keep the raw `node_progress` samples with their own
     * `observed_at` instant: the overlap of a wait with the other's write is decided on
     * THAT timeline, which emission order cannot reorder.
     */
    private fun installPortRecorder() {
        page.evaluate(
            """() => {
              const editor = window.__peInstance;
              if (!editor || !editor.graph || typeof editor.graph.setNodeOperation !== 'function') return false;
              window.__portTrail = [];
              const orig = editor.graph.setNodeOperation.bind(editor.graph);
              editor.graph.setNodeOperation = function (nodeId, view) {
                const r = orig(nodeId, view);
                if (view && view.port) {
                  window.__portTrail.push({ node: nodeId, state: view.port.state || '', text: view.port.text || '' });
                }
                return r;
              };
              if (typeof editor.logEvent !== 'function') return false;
              window.__opSamples = [];
              const origLog = editor.logEvent.bind(editor);
              editor.logEvent = function (t, p) {
                if (t === 'node_progress' && p && (p.node_id === 'src_a' || p.node_id === 'src_b')) {
                  window.__opSamples.push({ node: p.node_id, state: p.state, at: Date.parse(p.observed_at) });
                }
                return origLog(t, p);
              };
              return true;
            }""",
        ) shouldBe true
    }

    /** One raw `node_progress` sample, on the server's own clock. */
    private data class OpSample(
        val node: String,
        val state: String,
        val at: Long,
    )

    /** One node's intervals in [state]: each sample holds until that node's next sample. */
    private fun List<OpSample>.intervals(
        node: String,
        state: String,
    ): List<LongRange> {
        val own = filter { it.node == node }.sortedBy { it.at }
        return own.mapIndexedNotNull { i, s ->
            if (s.state != state) null else s.at until (own.getOrNull(i + 1)?.at ?: Long.MAX_VALUE)
        }
    }

    private fun LongRange.overlaps(other: LongRange) = first <= other.last && other.first <= last

    @Suppress("UNCHECKED_CAST")
    private fun opSamples(): List<OpSample> {
        val raw = page.evaluate("() => window.__opSamples || []") as List<Map<String, Any?>>
        return raw.map { e ->
            OpSample(node = e["node"] as String, state = e["state"] as String, at = (e["at"] as Number).toLong())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun portTrail(): List<TrailEntry> {
        val raw = page.evaluate("() => window.__portTrail || []") as List<Map<String, Any?>>
        return raw.map { e ->
            TrailEntry(node = e["node"] as String, state = e["state"] as String, text = e["text"] as String)
        }
    }

    /** Best-effort evidence: the DOM may already be past the wait — the trail is the proof. */
    private fun shootWait() {
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("151-lease-wait.png")).setFullPage(true))
        val waiting = portTrail().lastOrNull { it.state == "waiting" }?.node ?: "src_a"
        page.evaluate(
            "(id) => { const cy = document.getElementById('cy-canvas')._cyreg.cy; cy.zoom(1); cy.center(cy.getElementById(id)); }",
            waiting,
        )
        page.locator(".pe-stage").screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("151-lease-wait-close.png")))
        page.evaluate("() => window.__peInstance && window.__peInstance.graph && window.__peInstance.graph.fitToView()")
    }

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

    /** Two independent stages into tempdb and a caller that reads both. */
    private fun createTwoWriters(
        page: Page,
        name: String,
        datasource: String,
        rows: Int,
    ): String {
        val slug = name.split("/")[1]
        EditorRunFixtures.createTemplate(page, "test/${slug}_a.sql", "SELECT g AS n FROM generate_series(1, $rows) g")
        EditorRunFixtures.createTemplate(page, "test/${slug}_b.sql", "SELECT g AS m FROM generate_series(1, $rows) g")
        EditorRunFixtures.createTemplate(
            page,
            "test/${slug}_join.sql",
            "SELECT (SELECT COUNT(*) FROM stg_a) + (SELECT COUNT(*) FROM stg_b) AS c",
            dialect = "H2",
        )
        return EditorRunFixtures.postPipeline(
            page,
            name,
            """[
              { "id": "src_a", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_a.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_a" }, "depends_on": [] },
              { "id": "src_b", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_b.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_b" }, "depends_on": [] },
              { "id": "joined", "type": "DQL", "source": "tempdb", "template": { "id": "test/${slug}_join.sql", "version": 1 },
                "output": { "target": "caller" }, "depends_on": ["src_a", "src_b"] }
            ]""",
        )
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "151-screenshots").also { it.toFile().mkdirs() }

    companion object {
        private const val EXECUTION_TIMEOUT_MS = 240_000.0
        private const val POLL_MS = 30L

        /** ONE operational staging connection: the wait the port must show is made, not hoped for. */
        @JvmStatic
        @DynamicPropertySource
        fun oneStagingConnection(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.staging.h2.max-connections") { 1 }
        }
    }
}
