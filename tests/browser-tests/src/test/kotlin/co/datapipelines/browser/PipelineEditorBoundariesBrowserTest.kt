package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 150 in the browser (issue #126), with #135's abort pulse along the way: the
 * editor's derived Start and End boundaries on REAL runs, read off the DOM the
 * user reads.
 *
 *  1. Multiple roots: Start and End render for an authored graph the moment the
 *     editor opens — before any run — as view-only pills with accessible names.
 *  2. One completed branch while another runs: the fast branch reads Done and the
 *     blocked join stays Pending while the slow branch is still live — and End
 *     still says End. A finished branch is not a finished execution.
 *  3. Authoritative outcome: when the execution completes, End says Finished.
 *  4. Failure (#135 in the browser): a node fails, its RUNNING sibling is
 *     cancelled by the executor, and the sibling's card reads Aborted with the
 *     running pulse class GONE; End says Failed.
 *  5. Stopped: the user's cancel makes End say Stopped — the boundary is a word,
 *     never a button.
 *
 * Screenshots land in `build/reports/150-screenshots/` for the evidence record;
 * the assertions are what fail the build.
 */
class PipelineEditorBoundariesBrowserTest : BrowserSuite() {
    @Test
    fun `boundaries render at load, survive one branch finishing before another, and End says Finished`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("bd-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("bd" + generatedPassword("w").take(8).lowercase())

        val datasource = "bd-src-" + generatedPassword("d").take(6).lowercase()
        registerSourceDatasource(datasource) shouldBe emptyList<String>()
        val name = "test/bd_" + generatedPassword("p").take(8).lowercase()
        // An UNEQUAL fan-out: 10k rows land in seconds, 2M run for tens of seconds —
        // a wide, reliable window in which one branch is Done and the other is live.
        val pipelineId = createBranchingPipeline(page, name, datasource, fastRows = 10_000, slowRows = 2_000_000)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card[data-node-id='src_fast']").waitFor()

        // 1. The boundaries exist BEFORE any run — derived from the graph, not the stream.
        page.locator(".pe-card-boundary-start").waitFor()
        page.locator(".pe-card-boundary-end").waitFor()
        startLabel() shouldBe "Start"
        endLabel() shouldBe "End"
        startAria() shouldBe "Execution start"
        endAria() shouldBe "Execution end"
        // View-only: nothing to open on a marker.
        page.locator(".pe-card-boundary .pe-card-open").count() shouldBe 0

        page.locator("[data-verb='pipeline-execute']").click()
        // Start armed; End still neutral. The pill repaints when execution_started
        // lands — wait for it rather than racing the stream.
        waitUntil("Start arms on execution_started") { startAria() == "Execution start — running" }
        endLabel() shouldBe "End"

        // 2. One completed branch while another runs — and End does NOT move.
        waitUntil("one branch done, the other live") {
            val read = readBranchStates()
            read["src_fast"] == "Done" && read["src_slow"].let { it != null && it != "Pending" && it != "Done" }
        }
        endLabel() shouldBe "End"
        shoot("one-branch-done")

        // 3. The authoritative outcome: the execution's completion, not a leaf's.
        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
        page.locator(".pe-status:has-text('Completed')").waitFor()
        endLabel() shouldBe "Finished"
        endAria() shouldBe "Execution end — finished"
        startLabel() shouldBe "Start"
        shoot("finished")
    }

    @Test
    fun `a failed execution marks End Failed and the cancelled sibling loses the running pulse`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("bf-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("bf" + generatedPassword("w").take(8).lowercase())

        val datasource = "bf-src-" + generatedPassword("d").take(6).lowercase()
        registerSourceDatasource(datasource) shouldBe emptyList<String>()
        val name = "test/bf_" + generatedPassword("p").take(8).lowercase()
        // `bad` fails inside its first statement while `slow` is still staging: the
        // executor cancels the running sibling and emits NO node event for it — the
        // exact shape of #135's witness, now in a real browser.
        val pipelineId = createFailingPipeline(page, name, datasource, slowRows = 800_000)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card[data-node-id='bad']").waitFor()
        page.locator(".pe-card-boundary-end").waitFor()

        page.locator("[data-verb='pipeline-execute']").click()
        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))

        // The failed node reads Failed.
        page.locator(".pe-card[data-node-id='bad']").getAttribute("class") shouldContain "pe-card-failed"
        // THE #135 ASSERTION: the cancelled sibling reads Aborted and carries the
        // aborted class — the running pulse class must be gone.
        val slowClass = page.locator(".pe-card[data-node-id='slow']").getAttribute("class") ?: ""
        slowClass shouldContain "pe-card-aborted"
        check(!slowClass.contains("pe-card-running")) { "the cancelled sibling kept the running pulse class: $slowClass" }
        page.locator(".pe-card[data-node-id='slow'] .pe-card-st").innerText().trim() shouldBe "Aborted"
        // End speaks the AUTHORITATIVE outcome.
        endLabel() shouldBe "Failed"
        endAria() shouldBe "Execution end — failed"
        shoot("failed")
    }

    /**
     * Re-enabled with the #143 fix: the abort unwind no longer waits out the statement
     * abandonment grace before the terminal event, so the LIVE stream delivers
     * `execution_aborted` inside the editor's own 5 s cancel fallback window and the page
     * reaches Stopped without a reload.
     */
    @Test
    fun `stopping a run marks End Stopped — the boundary is a word, not a button`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("bs-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("bs" + generatedPassword("w").take(8).lowercase())

        val datasource = "bs-src-" + generatedPassword("d").take(6).lowercase()
        registerSourceDatasource(datasource) shouldBe emptyList<String>()
        val name = "test/bs_" + generatedPassword("p").take(8).lowercase()
        val pipelineId = createBranchingPipeline(page, name, datasource, fastRows = 10_000, slowRows = 2_000_000)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card[data-node-id='src_slow']").waitFor()
        page.locator(".pe-card-boundary-end").waitFor()

        page.locator("[data-verb='pipeline-execute']").click()
        waitUntil("the slow branch is live") {
            val state = readBranchStates()["src_slow"]
            state != null && state != "Pending" && state != "Done"
        }
        cancelUntilTerminal()

        page.locator(".pe-card[data-node-id='src_slow'] .pe-card-st").innerText().trim() shouldBe "Aborted"
        endLabel() shouldBe "Stopped"
        endAria() shouldBe "Execution end — stopped"
        shoot("stopped")
    }

    /**
     * The UI's own cancel contract (§10.4): the 204 acknowledges the REQUEST and
     * `execution_aborted` marks its completion — worst case about one poll tick
     * (15 s). Re-clicking while the run is still live is exactly what a real user
     * does; the assertion stays strict: the run must end ABORTED. Each failed
     * attempt also prints what the server recorded and whether the §10.3 replay
     * log carries the event, so a delivery gap is visible in the failure itself.
     */
    private fun cancelUntilTerminal() {
        val execId = pageExecutionId()
        var lastStatus = "unknown"
        for (attempt in 1..CANCEL_ATTEMPTS) {
            if (!readExecuteEnabled()) page.locator("[data-verb='execution-cancel']").click()
            if (awaitExecuteEnabled()) return
            lastStatus = serverStatusOf(execId)
            val replayHasAbort = replayCarriesAbort(execId)
            println("150-cancel-attempt=$attempt serverStatus=$lastStatus replayAbort=$replayHasAbort")
        }
        error("the cancelled execution never reached a terminal state (last server status: $lastStatus)")
    }

    private fun pageExecutionId(): String? =
        page.evaluate(
            "() => window.__peInstance && window.__peInstance.sseHandler ? window.__peInstance.sseHandler.executionId : null",
        ) as String?

    private fun awaitExecuteEnabled(): Boolean {
        val deadline = System.currentTimeMillis() + CANCEL_ABORT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (readExecuteEnabled()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun serverStatusOf(execId: String?): String {
        if (execId == null) return "no-execution-id"
        return page.evaluate(
            """async (id) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const res = await fetch('/api/v1/executions/' + id, { credentials: 'same-origin', headers: { 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' } });
              if (!res.ok) return 'http-' + res.status;
              const data = await res.json();
              return (data.data || data).status;
            }""",
            execId,
        ) as String
    }

    /** Did the SERVER emit the abort at all? The replay log is the record; the live stream is the delivery. */
    private fun replayCarriesAbort(execId: String?): String {
        if (execId == null) return "no-execution-id"
        return page.evaluate(
            """async (id) => {
              try {
                const res = await fetch('/api/v1/executions/' + id + '/events', { credentials: 'same-origin' });
                if (!res.ok) return 'http-' + res.status;
                const text = await res.text();
                return text.includes('execution_aborted') ? 'yes' : 'no';
              } catch (e) { return 'err'; }
            }""",
            execId,
        ) as String
    }

    // --------------------------------------------------------- the reads

    private fun startLabel(): String = page.locator(".pe-card-boundary-start .pe-card-st").innerText().trim()

    private fun endLabel(): String = page.locator(".pe-card-boundary-end .pe-card-st").innerText().trim()

    private fun startAria(): String = page.locator(".pe-card-boundary-start").getAttribute("aria-label") ?: ""

    private fun endAria(): String = page.locator(".pe-card-boundary-end").getAttribute("aria-label") ?: ""

    private fun readExecuteEnabled(): Boolean = page.locator("[data-verb='pipeline-execute']").evaluate("el => !el.disabled") as Boolean

    /** Both branch cards' state words, one DOM read per poll. */
    private fun readBranchStates(): Map<String, String> =
        @Suppress("UNCHECKED_CAST")
        (
            page.evaluate(
                """() => {
              const read = (id) => {
                const card = document.querySelector(".pe-card[data-node-id='" + id + "']");
                const st = card ? card.querySelector('.pe-card-st') : null;
                return st ? st.textContent.trim() : '';
              };
              return { src_fast: read('src_fast'), src_slow: read('src_slow'), slow: read('slow') };
            }""",
            ) as Map<String, Any?>
        ).mapValues { (_, v) -> v as? String ?: "" }

    /** Polls [done] (one DOM read per poll) until it holds or the execution budget runs out. */
    private fun waitUntil(
        what: String,
        done: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + EXECUTION_TIMEOUT_MS.toLong()
        var last = false
        while (System.currentTimeMillis() < deadline) {
            // A read racing a card re-render degrades to "not yet" — the next poll retries.
            last =
                try {
                    done()
                } catch (_: Exception) {
                    false
                }
            if (last) return
            Thread.sleep(POLL_MS)
        }
        error("never saw: $what")
    }

    private fun shoot(state: String) {
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("150-$state.png")).setFullPage(true))
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "150-screenshots").also { it.toFile().mkdirs() }

    /** Registers the suite's own Postgres as a source, through the datasources partial the UI posts to. */
    @Suppress("UNCHECKED_CAST")
    private fun registerSourceDatasource(name: String): List<String> {
        page.navigate("$baseUrl/datasources")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        return page.evaluate(
            """async (args) => {
              const headers = JSON.parse(document.body.getAttribute('hx-headers') || '{}');
              const body = new URLSearchParams({
                name: args.name, displayName: 'Boundary source', dialect: 'POSTGRES',
                jdbcUrl: args.url, credentialKind: 'password', username: args.user, password: args.password,
                description: 'generate_series source for the 150 browser proof',
              });
              const res = await fetch('/partials/datasources', {
                method: 'POST', credentials: 'same-origin',
                headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' }, body,
              });
              return res.ok ? [] : [res.status + ' ' + (await res.text()).slice(0, 300)];
            }""",
            mapOf(
                "name" to name,
                "url" to SharedBrowserE2e.jdbcUrl.substringBefore("?"),
                "user" to SharedBrowserE2e.username,
                "password" to SharedBrowserE2e.password,
            ),
        ) as List<String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun postPipeline(
        page: Page,
        name: String,
        nodes: String,
    ): String {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const res = await fetch('/api/v1/pipelines', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ name: args.name, display_name: args.name, nodes: JSON.parse(args.nodes) }) });
                  const text = await res.text();
                  let id = null;
                  try { const data = JSON.parse(text); id = data.id || (data.data && data.data.id) || null; } catch (e) {}
                  return { status: res.status, id: id, body: text.slice(0, 200) };
                }""",
                mapOf("name" to name, "nodes" to nodes),
            ) as Map<String, Any?>
        (result["status"] as Number).toInt() shouldBe 201
        val id = result["id"] as String?
        check(id != null && Regex("[0-9a-f]{8}-[0-9a-f-]{27}").matches(id)) {
            "the create response did not name the pipeline id: ${result["body"]}"
        }
        return id
    }

    /** Two parallel stage roots — [fastRows] and [slowRows] — joined for the caller. */
    private fun createBranchingPipeline(
        page: Page,
        name: String,
        datasource: String,
        fastRows: Int,
        slowRows: Int,
    ): String {
        val slug = name.split("/")[1]
        createTemplate(page, "test/${slug}_fast.sql", "SELECT g AS n FROM generate_series(1, $fastRows) g")
        createTemplate(page, "test/${slug}_slow.sql", "SELECT g AS n FROM generate_series(1, $slowRows) g")
        createTemplate(page, "test/${slug}_join.sql", body = "SELECT COUNT(*) AS c FROM stg_fast", dialect = "H2")
        return postPipeline(
            page,
            name,
            """[
              { "id": "src_fast", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_fast.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_fast" }, "depends_on": [] },
              { "id": "src_slow", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_slow.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_slow" }, "depends_on": [] },
              { "id": "joined", "type": "DQL", "source": "tempdb", "template": { "id": "test/${slug}_join.sql", "version": 1 },
                "output": { "target": "caller" }, "depends_on": ["src_fast", "src_slow"] }
            ]""",
        )
    }

    /** `bad` fails inside its statement; `slow` stages long enough to be RUNNING when it does. */
    private fun createFailingPipeline(
        page: Page,
        name: String,
        datasource: String,
        slowRows: Int,
    ): String {
        val slug = name.split("/")[1]
        createTemplate(page, "test/${slug}_bad.sql", "SELECT * FROM missing_table_150")
        createTemplate(page, "test/${slug}_slow.sql", "SELECT g AS n FROM generate_series(1, $slowRows) g")
        return postPipeline(
            page,
            name,
            """[
              { "id": "bad", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_bad.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_bad" }, "depends_on": [] },
              { "id": "slow", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_slow.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_slow" }, "depends_on": [] }
            ]""",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun createTemplate(
        page: Page,
        id: String,
        body: String,
        dialect: String = "POSTGRES",
    ) {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const r = await fetch('/api/v1/templates', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ id: args.id, dialect: args.dialect, display_name: args.id, description: '150 browser fixture', imports: [], body: args.body }) });
                  return { status: r.status, body: r.status === 201 ? '' : await r.text() };
                }""",
                mapOf("id" to id, "dialect" to dialect, "body" to body),
            ) as Map<String, Any?>
        val status = (result["status"] as Number).toInt()
        check(status == 201 || status == 409) { "template $id $status ${result["body"]}" }
    }

    private companion object {
        const val EXECUTION_TIMEOUT_MS = 180_000.0
        const val POLL_MS = 50L

        /** The cancel contract's own latency bound (§8.3.1): about one poll tick, with slack. */
        const val CANCEL_ABORT_WAIT_MS = 45_000L

        /** A real user re-clicks Cancel when nothing happens; three attempts bound the patience. */
        const val CANCEL_ATTEMPTS = 3
    }
}
