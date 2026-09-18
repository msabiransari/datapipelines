package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Disabled
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
 * This class boots its own application context (the property differs), so it costs one
 * extra boot in the browser gate; it exists because the only honest way to show a wait
 * is to make one happen. Screenshots join `build/reports/151-screenshots/`.
 */
class PipelineEditorLeaseWaitBrowserTest : BrowserSuite() {
    /**
     * #150: the guard samples the lease wait on a 30 ms poll and requires one poll to land inside
     * the window where one port waits while the other writes. It failed on CI's 2-vCPU runner
     * (run 35303693520 on 77f5a11e) and on a loaded dev box, and passes alone; disabled until it
     * synchronises on the port-state events instead of time (issue #150 has the poll evidence).
     */
    @Disabled("#150: samples a 30 ms window; red on CI's runner and under load, green alone")
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

        page.locator("[data-verb='pipeline-execute']").click()

        val polls = observe()
        val waits = polls.filter { it.state == "waiting" }
        check(waits.isNotEmpty()) { "no port ever read waiting with one staging connection; distinct polls: ${polls.distinct().take(40)}" }
        // A wait is a wait: the amber line names the connection, nothing flows.
        waits.all { it.line == "waiting for tempdb connection" && it.flow == 0 } shouldBe true
        // And the same run also wrote: the OTHER port was writing while this one waited.
        check(polls.any { it.otherState == "writing" && it.state == "waiting" }) {
            "never saw one port waiting while the other wrote: ${polls.distinct().take(40)}"
        }

        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
        page.locator(".pe-status:has-text('Completed')").waitFor()
        page.locator(".pe-card[data-node-id='src_a'] .pe-port .pe-port-line").innerText() shouldContain "committed · 700,000 rows"
        page.locator(".pe-card[data-node-id='src_b'] .pe-port .pe-port-line").innerText() shouldContain "committed · 700,000 rows"
    }

    private data class Poll(
        val node: String,
        val state: String,
        val line: String,
        val flow: Int,
        val otherState: String,
    )

    /** One evaluate per poll over both ports; screenshots the first wait it sees. */
    private fun observe(): List<Poll> {
        val polls = mutableListOf<Poll>()
        var shot = false
        val deadline = System.currentTimeMillis() + EXECUTION_TIMEOUT_MS.toLong()
        while (System.currentTimeMillis() < deadline) {
            @Suppress("UNCHECKED_CAST")
            val read =
                page.evaluate(
                    """() => {
                      const port = (id) => document.querySelector(".pe-card[data-node-id='" + id + "'] .pe-port");
                      const state = (id) => { const p = port(id); if (!p) return ''; const c = [...p.classList].find(c => c.startsWith('pe-port-') && c !== 'pe-port-stale'); return c ? c.replace('pe-port-', '') : ''; };
                      const line = (id) => { const l = port(id) && port(id).querySelector('.pe-port-line'); return l ? l.textContent.trim() : ''; };
                      const flow = (id) => document.querySelectorAll(".pe-card[data-node-id='" + id + "'] .pe-port-flow").length;
                      const st = (id) => { const el = document.querySelector(".pe-card[data-node-id='" + id + "'] .pe-card-st"); return el ? el.textContent.trim() : ''; };
                      return { a: [state('src_a'), line('src_a'), flow('src_a')], b: [state('src_b'), line('src_b'), flow('src_b')],
                               terminal: ['src_a','src_b'].every(id => ['Done','Failed','Aborted'].includes(st(id))) };
                    }""",
                ) as Map<String, Any?>
            val a = (read["a"] as List<*>)
            val b = (read["b"] as List<*>)
            polls.add(Poll("src_a", a[0] as String, a[1] as String, (a[2] as Number).toInt(), b[0] as String))
            polls.add(Poll("src_b", b[0] as String, b[1] as String, (b[2] as Number).toInt(), a[0] as String))
            if (!shot && (a[0] == "waiting" || b[0] == "waiting")) {
                page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("151-lease-wait.png")).setFullPage(true))
                // And the waiting card at its real size — the wait is fleeting, so the close-up
                // is taken in the same poll that saw it, then the fit is restored.
                val waiting = if (a[0] == "waiting") "src_a" else "src_b"
                page.evaluate(
                    "(id) => { const cy = document.getElementById('cy-canvas')._cyreg.cy; cy.zoom(1); cy.center(cy.getElementById(id)); }",
                    waiting,
                )
                page.locator(".pe-stage").screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("151-lease-wait-close.png")))
                page.evaluate("() => window.__peInstance && window.__peInstance.graph && window.__peInstance.graph.fitToView()")
                shot = true
            }
            if (read["terminal"] == true) return polls
            Thread.sleep(POLL_MS)
        }
        error("the writers never reached a terminal state")
    }

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
