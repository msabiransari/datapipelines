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
        // #234: the sampled intervals are the DIRECT observation, but sampling can miss a
        // whole phase (both gate reds: a node's write phase emitted no sample at all), so
        // the pass criterion is a disjunction with the server's own stopwatch: each sample
        // carries the node's CUMULATIVE per-phase durations (timings_ms), and with ONE
        // staging connection — this fixture's own property — a nonzero wait on one writer
        // is time queued behind the OTHER staging writer's lease, whose nonzero writing
        // total is what it was doing while holding it. Sampling misses phases; a stopwatch
        // wrapped around the lease cannot.
        check(
            sampledOverlap(waitingA, waitingB, writingA, writingB) || durationsProveTurns(samples),
        ) {
            "no waiting interval overlapped the other writer's writing interval, and the " +
                "terminal timings do not prove the turns either; " +
                "waits  a=$waitingA b=$waitingB writes a=$writingA b=$writingB; " +
                "terminal timings ${terminalTimings(samples)}; ${trailDump()}"
        }

        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
        page.locator(".pe-status:has-text('Completed')").waitFor()
        page.locator(".pe-card[data-node-id='src_a'] .pe-port .pe-port-line").innerText() shouldContain "committed · 700,000 rows"
        page.locator(".pe-card[data-node-id='src_b'] .pe-port .pe-port-line").innerText() shouldContain "committed · 700,000 rows"
    }

    /**
     * #234's falsification, pinned: the two gate reds' recorded samples are the fixture.
     * The interval lists are EXACTLY what both failing gates printed (891cfb54, 81d5a327 —
     * both incremental cycles); the third same-millisecond sample each node shows is a
     * state outside the four printed sets, which is what those interval lists require (its
     * exact state was not recorded — the recorder did not keep it, which is the gap this
     * lane closes); the terminal `timings_ms` are cumulative totals CONSISTENT with the
     * sampled intervals (the runs' exact totals were not printed either — the fix records
     * them from here on). The old sampled-only assertion rejects both sets — that is the
     * recorded red; the trail-derived verdict reads both green — that is the fix.
     */
    @Test
    fun `the two gate failures are the fixture - the sampled-only assertion rejects them and the trail-derived verdict reads them green`() {
        listOf(gate891cfb54Samples(), gate81d5a327Samples()).forEach { samples ->
            sampledOverlap(
                samples.intervals("src_a", "waiting_output"),
                samples.intervals("src_b", "waiting_output"),
                samples.intervals("src_a", "writing"),
                samples.intervals("src_b", "writing"),
            ) shouldBe false
            durationsProveTurns(samples) shouldBe true
        }
    }

    /** Gate on `891cfb54` (incremental cycle): `src_a`'s write phase between ms …112157 and …115370 emitted no sample. */
    private fun gate891cfb54Samples(): List<OpSample> =
        listOf(
            OpSample("src_a", "writing", 1_790_292_112_157),
            OpSample("src_a", "waiting_output", 1_790_292_112_157),
            OpSample("src_a", "connecting", 1_790_292_112_157),
            OpSample("src_a", "writing", 1_790_292_115_370),
            OpSample(
                "src_a",
                "finalizing",
                1_790_292_116_206,
                timings = mapOf("waiting_output" to 120, "writing" to 4050, "finalizing" to 210),
                rowsWritten = 700_000,
            ),
            OpSample("src_b", "writing", 1_790_292_112_146),
            OpSample("src_b", "waiting_output", 1_790_292_112_146),
            OpSample("src_b", "connecting", 1_790_292_112_146),
            OpSample("src_b", "waiting_output", 1_790_292_113_342),
            OpSample("src_b", "writing", 1_790_292_114_351),
            OpSample(
                "src_b",
                "finalizing",
                1_790_292_115_366,
                timings = mapOf("waiting_output" to 1010, "writing" to 1015, "finalizing" to 180),
                rowsWritten = 700_000,
            ),
        )

    /** Gate on `81d5a327` (incremental cycle): `src_b`'s post-wait write phase emitted no sample at all. */
    private fun gate81d5a327Samples(): List<OpSample> =
        listOf(
            OpSample("src_a", "waiting_output", 1_790_313_090_488),
            OpSample("src_a", "writing", 1_790_313_090_489),
            OpSample("src_a", "connecting", 1_790_313_090_490),
            OpSample("src_a", "waiting_output", 1_790_313_091_686),
            OpSample("src_a", "writing", 1_790_313_092_694),
            OpSample("src_a", "writing", 1_790_313_093_703),
            OpSample(
                "src_a",
                "finalizing",
                1_790_313_094_653,
                timings = mapOf("waiting_output" to 1008, "writing" to 1960, "finalizing" to 240),
                rowsWritten = 700_000,
            ),
            OpSample("src_b", "writing", 1_790_313_090_478),
            OpSample("src_b", "waiting_output", 1_790_313_090_478),
            OpSample("src_b", "connecting", 1_790_313_090_479),
            OpSample("src_b", "waiting_output", 1_790_313_091_679),
            OpSample(
                "src_b",
                "finalizing",
                1_790_313_092_689,
                timings = mapOf("waiting_output" to 1010, "writing" to 900, "finalizing" to 190),
                rowsWritten = 700_000,
            ),
        )

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
                  // #234: the sample's cumulative phase durations ride along — the terminal
                  // sample's timings_ms is the server's own per-phase total, the record the
                  // overlap falls back to when sampling missed a phase.
                  window.__opSamples.push({
                    node: p.node_id,
                    state: p.state,
                    at: Date.parse(p.observed_at),
                    timings: p.timings_ms || {},
                    rows: p.rows_written === undefined ? null : p.rows_written,
                  });
                }
                return origLog(t, p);
              };
              return true;
            }""",
        ) shouldBe true
    }

    /** One raw `node_progress` sample, on the server's own clock, with its cumulative phase durations. */
    private data class OpSample(
        val node: String,
        val state: String,
        val at: Long,
        val timings: Map<String, Long> = emptyMap(),
        val rowsWritten: Long? = null,
    )

    /** One node's intervals in [state]: each sample holds until that node's next sample. */
    private fun List<OpSample>.intervals(
        node: String,
        state: String,
    ): List<LongRange> {
        val own = filter { it.node == node }.sortedBy { it.at }
        return own.mapIndexedNotNull { i, s ->
            // Half-open [at, next.at): a next sample on the SAME millisecond must yield an
            // instant, never a range whose end precedes its start (seen under gate load:
            // waits a=[…157..…156], and the wait vanished from the overlap check).
            if (s.state != state) null else s.at..maxOf(s.at, (own.getOrNull(i + 1)?.at ?: Long.MAX_VALUE) - 1)
        }
    }

    private fun LongRange.overlaps(other: LongRange) = first <= other.last && other.first <= last

    /** The pre-#234 predicate: a sampled waiting interval meeting the other writer's sampled writing interval. */
    private fun sampledOverlap(
        waitingA: List<LongRange>,
        waitingB: List<LongRange>,
        writingA: List<LongRange>,
        writingB: List<LongRange>,
    ): Boolean =
        waitingA.any { w -> writingB.any { w.overlaps(it) } } ||
            waitingB.any { w -> writingA.any { w.overlaps(it) } }

    /**
     * #234: the turns read off the server's own stopwatch. Every `node_progress` sample
     * carries the node's CUMULATIVE per-phase durations (`timings_ms` — the observation is
     * wrapped around the lease in NodeRunner/WritebackRunner), and the terminal sample's
     * map is the phase total. With ONE operational staging connection (this fixture's own
     * `max-connections=1`), a nonzero `waiting_output` on one writer can only be time
     * queued behind the other staging writer's lease — the two nodes are the only staging
     * contenders (`joined` waits for both) — and that other writer's nonzero `writing`
     * total is what it was doing while it held it. Sampling can miss a phase (both gate
     * reds did); the stopwatch cannot.
     */
    private fun durationsProveTurns(samples: List<OpSample>): Boolean =
        (cumulativeMs(samples, "src_a", "waiting_output") > 0 && cumulativeMs(samples, "src_b", "writing") > 0) ||
            (cumulativeMs(samples, "src_b", "waiting_output") > 0 && cumulativeMs(samples, "src_a", "writing") > 0)

    /** A node's total in [phase]: the LAST-recorded sample's cumulative map (the terminal sample's totals). */
    private fun cumulativeMs(
        samples: List<OpSample>,
        node: String,
        phase: String,
    ): Long =
        samples
            .filter { it.node == node }
            .lastOrNull()
            ?.timings
            ?.get(phase) ?: 0L

    /** Per-node terminal timings + written rows, for the failure message. */
    private fun terminalTimings(samples: List<OpSample>): String =
        samples
            .groupBy { it.node }
            .map { (node, ss) ->
                val last = ss.last()
                "$node: timings=${last.timings} rows=${last.rowsWritten}"
            }.joinToString("; ")

    @Suppress("UNCHECKED_CAST")
    private fun opSamples(): List<OpSample> {
        val raw = page.evaluate("() => window.__opSamples || []") as List<Map<String, Any?>>
        return raw.map { e ->
            val timings =
                (e["timings"] as? Map<*, *> ?: emptyMap<Any, Any>())
                    .map { (k, v) -> k as String to (v as Number).toLong() }
                    .toMap()
            OpSample(
                node = e["node"] as String,
                state = e["state"] as String,
                at = (e["at"] as Number).toLong(),
                timings = timings,
                rowsWritten = (e["rows"] as? Number)?.toLong(),
            )
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
