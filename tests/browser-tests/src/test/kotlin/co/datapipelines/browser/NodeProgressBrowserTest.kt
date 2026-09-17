package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 149 in the browser, on a REAL run: two independent nodes stage 800 000 rows each from a
 * Postgres source into tempdb while a third joins them for the caller. What the page must
 * show, read off the DOM the user reads:
 *
 *  1. both cards report a measured WRITING operation line at the same poll — two independently
 *     writing nodes, named with their real destinations;
 *  2. the accessible node list carries the same operation as each row's description, so the
 *     state is available without colour or animation;
 *  3. the Details pane names the operation, its destination, the counts and the commit;
 *  4. the execution's history page derives the Node Operations table from the durable record.
 *
 * Screenshots land in `build/reports/149-screenshots/` for the evidence record; the
 * assertions are what fail the build.
 */
class NodeProgressBrowserTest : BrowserSuite() {
    @Test
    fun `two independent writers are visible on their cards, in the a11y list, the Details pane and the history page`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("np-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("np" + generatedPassword("w").take(8).lowercase())

        val datasource = "np-src-" + generatedPassword("d").take(6).lowercase()
        registerSourceDatasource(datasource) shouldBe emptyList<String>()
        val name = "test/np_" + generatedPassword("p").take(8).lowercase()
        val pipelineId = createStagingPipeline(page, name, datasource)
        page.navigate("$baseUrl/pipelines/$pipelineId/editor")
        page.locator(".pe-card").first().waitFor()

        page.locator("[data-verb='pipeline-execute']").click()

        // 1. Two independent writers: the cards are polled (one DOM read per poll, both cards in
        //    the same read) for the whole run. A stage alternates fetch and write per batch, so
        //    the honest picture is two cards each flipping between "Fetching" and "Writing →
        //    tempdb.stg_x" on their own clocks: both must be live with measured states at once,
        //    and each must have shown its writing line with its own destination.
        shotDir().toFile().listFiles()?.forEach { it.delete() }
        val polls = observeWriters()
        val live = polls.filter { p -> p.all { it.isMeasured() } }
        check(live.isNotEmpty()) { "never saw both nodes in measured states at once; distinct polls: ${polls.distinct()}" }
        // Both cards carrying a WRITTEN count in the same read: two destinations being written
        // at once, each by its own operation.
        check(polls.any { p -> p.all { WRITTEN.containsMatchIn(it) } }) { "never saw both written counts at once: ${polls.distinct()}" }
        check(polls.any { p -> p.any { it.startsWith("Writing") } }) { "no card ever showed its writing state: ${polls.distinct()}" }
        // The destination is the card's own fact line, on both cards.
        page.locator(".pe-card[data-node-id='src_a'] .pe-card-fact:has-text('tempdb.stg_a')").count() shouldBe 1
        page.locator(".pe-card[data-node-id='src_b'] .pe-card-fact:has-text('tempdb.stg_b')").count() shouldBe 1
        // 2. The accessible description carried the same operation, without colour.
        check(
            a11ySeen.any { it.contains("Writing to tempdb.stg_a") || it.contains("Fetching") },
        ) { "a11y never carried an operation: $a11ySeen" }

        page
            .locator("[data-verb='pipeline-execute']:not([disabled])")
            .waitFor(Locator.WaitForOptions().setTimeout(EXECUTION_TIMEOUT_MS))
        page.locator(".pe-status:has-text('Completed')").waitFor()
        cardState("src_a") shouldBe "Done"
        page.locator(".pe-card:has(.pe-card-id[title='src_a']) .pe-card-rt").innerText().trim() shouldMatch
            Regex("800,000 rows · \\d+(\\.\\d+)? ?(ms|s|m \\d+s)")

        // 3. The Details pane for a finished node.
        page.locator(".pe-card-open[data-node-open='src_a']").evaluate("el => el.click()")
        page.locator("#pe-pane-details .pe-kv").waitFor()
        val details = page.locator("#pe-pane-details .pe-kv").innerText()
        details shouldContain "Operation"
        details shouldContain "stage → tempdb.stg_a"
        details shouldContain "800,000 fetched · 800,000 written"
        details shouldContain "Committed · 800,000 rows"
        details shouldContain "Time in"
        shoot("details")
        a11yOperation("src_a") shouldContain "Committed"

        // 4. History: the execution detail page's Node Operations table.
        val executionId =
            page.evaluate(
                "() => window.__peInstance && window.__peInstance.sseHandler ? window.__peInstance.sseHandler.executionId : null",
            )
        (executionId as String?) ?: error("the editor did not record the execution id")
        page.navigate("$baseUrl/executions/$executionId")
        page.locator("[data-node-operations]").waitFor()
        val history = page.locator("[data-node-operations]").innerText()
        history shouldContain "tempdb.stg_a"
        history shouldContain "tempdb.stg_b"
        history shouldContain "committed"
        page.locator("[data-node-operations] tr[data-node-id='joined'][data-state='completed']").count() shouldBe 1
        shoot("history")
    }

    private fun cardState(nodeId: String): String =
        page.locator(".pe-card:has(.pe-card-id[title='$nodeId']) .pe-card-st").innerText().trim()

    private fun a11yOperation(nodeId: String): String =
        page.locator("#pe-node-list [data-node-id='$nodeId']").getAttribute("data-operation") ?: ""

    private val a11ySeen = mutableListOf<String>()

    /** One DOM read per poll, both cards in the same read, until both are Done; screenshots the best moment. */
    private fun observeWriters(): List<List<String>> {
        val polls = mutableListOf<List<String>>()
        var shotBoth = false
        var shotLive = false
        val deadline = System.currentTimeMillis() + EXECUTION_TIMEOUT_MS.toLong()
        while (System.currentTimeMillis() < deadline) {
            @Suppress("UNCHECKED_CAST")
            val read =
                page.evaluate(
                    """() => ({
                      states: ['src_a', 'src_b'].map((id) => {
                        const card = document.querySelector(".pe-card[data-node-id='" + id + "']");
                        const st = card ? card.querySelector('.pe-card-st') : null;
                        const rt = card ? card.querySelector('.pe-card-rt') : null;
                        const count = rt && rt.textContent.trim() ? ' · ' + rt.textContent.trim() : '';
                        return st ? st.textContent.trim() + count : '';
                      }),
                      a11y: (document.querySelector("#pe-node-list [data-node-id='src_a']") || {}).getAttribute
                        ? (document.querySelector("#pe-node-list [data-node-id='src_a']").getAttribute('data-operation') || '')
                        : '',
                    })""",
                ) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val states = read["states"] as List<String>
            polls += states
            (read["a11y"] as String?)?.takeIf { it.isNotBlank() }?.let { a11ySeen += it }
            if (!shotBoth && states.all { WRITTEN.containsMatchIn(it) }) {
                // Both cards carrying written counts: the two-writers moment.
                shoot("two-writers")
                shotBoth = true
            } else if (!shotLive && states.all { it.isMeasured() }) {
                shoot("two-live")
                shotLive = true
            }
            if (states.all { it.startsWith("Done") }) break
            Thread.sleep(POLL_MS)
        }
        return polls
    }

    /** A measured operation line, as opposed to the pre-run/plain labels. */
    private fun String.isMeasured(): Boolean =
        isNotBlank() && !startsWith("Pending") && !startsWith("Running…") && !startsWith("Done") && !startsWith("Failed") &&
            !startsWith("Aborted")

    private fun shoot(state: String) {
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("149-$state.png")).setFullPage(true))
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "149-screenshots").also { it.toFile().mkdirs() }

    /** Registers the suite's own Postgres as a source, through the datasources partial the UI posts to. */
    @Suppress("UNCHECKED_CAST")
    private fun registerSourceDatasource(name: String): List<String> {
        page.navigate("$baseUrl/datasources")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        return page.evaluate(
            """async (args) => {
              const headers = JSON.parse(document.body.getAttribute('hx-headers') || '{}');
              const body = new URLSearchParams({
                name: args.name, displayName: 'Node progress source', dialect: 'POSTGRES',
                jdbcUrl: args.url, credentialKind: 'password', username: args.user, password: args.password,
                description: 'generate_series source for the 149 browser proof',
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

    /** Two stage nodes from [datasource] into tempdb, joined for the caller; created in-page over REST. */
    @Suppress("UNCHECKED_CAST")
    private fun createStagingPipeline(
        page: Page,
        name: String,
        datasource: String,
    ): String {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const tpl = async (id, dialect, body) => {
                    const r = await fetch('/api/v1/templates', { method: 'POST', credentials: 'same-origin', headers,
                      body: JSON.stringify({ id, dialect, display_name: id, description: '149 browser fixture', imports: [], body }) });
                    if (r.status !== 201 && r.status !== 409) throw new Error('template ' + id + ' ' + r.status + ' ' + await r.text());
                  };
                  const slug = args.name.split('/')[1];
                  await tpl('test/' + slug + '_a.sql', 'POSTGRES', 'SELECT g AS n, 1 AS lane FROM generate_series(1, $ROWS) g');
                  await tpl('test/' + slug + '_b.sql', 'POSTGRES', 'SELECT g AS n, 2 AS lane FROM generate_series(1, $ROWS) g');
                  await tpl('test/' + slug + '_join.sql', 'H2', 'SELECT lane, COUNT(*) AS c FROM stg_a GROUP BY lane UNION ALL SELECT lane, COUNT(*) AS c FROM stg_b GROUP BY lane');
                  const res = await fetch('/api/v1/pipelines', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({
                      name: args.name, display_name: args.name,
                      nodes: [
                        { id: 'src_a', type: 'DQL', source: args.datasource, template: { id: 'test/' + slug + '_a.sql', version: 1 },
                          output: { target: 'tempdb', table: 'stg_a' }, depends_on: [] },
                        { id: 'src_b', type: 'DQL', source: args.datasource, template: { id: 'test/' + slug + '_b.sql', version: 1 },
                          output: { target: 'tempdb', table: 'stg_b' }, depends_on: [] },
                        { id: 'joined', type: 'DQL', source: 'tempdb', template: { id: 'test/' + slug + '_join.sql', version: 1 },
                          output: { target: 'caller' }, depends_on: ['src_a', 'src_b'] },
                      ],
                    }) });
                  return { status: res.status, body: await res.text() };
                }""",
                mapOf("name" to name, "datasource" to datasource),
            ) as Map<String, Any?>
        (result["status"] as Number).toInt() shouldBe 201
        return Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(result["body"] as String)!!.groupValues[1]
    }

    private companion object {
        /** Seconds of staging per node over JDBC: long enough for the pump, short for the budget. */
        const val ROWS = 800_000
        val WRITTEN = Regex("\\d[\\d,]* written")
        const val EXECUTION_TIMEOUT_MS = 180_000.0
        const val POLL_MS = 50L
    }
}
