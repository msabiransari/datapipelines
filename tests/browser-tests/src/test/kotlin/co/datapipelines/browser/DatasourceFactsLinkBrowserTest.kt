package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

/**
 * #460 correction — the datasource facts' source-pipeline link opens the editor IN THE
 * PERSISTENT SHELL, and the editor mounts and runs there.
 *
 * #149's original rule required a FULL document load because, before #460, a boosted swap
 * landed the editor's markup while Alpine bound the component before its scripts had executed,
 * so no canvas mounted (159's live symptom). #460's runtime loads the editor's modules in order
 * and activates the component on the boosted arrival — its `data-pe-runtime-epoch` is stamped
 * only after `Alpine.initTree` — so the transport expectation is replaced by the stronger
 * in-shell contract: the SAME document and rail survive the click (no reload), the canonical
 * workspace URL is admitted, the component mounts with real node geometry, and a permitted Run
 * reaches Completed.
 *
 * On the pre-fix tree the click is a same-document swap and the reader is left on /datasources
 * (the transport mismatch the lander refused); forcing the old full-document transport instead
 * detaches the document and rail, so the rail-identity guard goes red. Both falsifications are
 * recorded in the handback.
 */
class DatasourceFactsLinkBrowserTest : BrowserSuite() {
    @Test
    fun `the facts' source-pipeline link opens the editor in the persistent shell and runs`() {
        startTrace()
        val wsName = "dfl149-" + generatedPassword("w").take(8).lowercase()
        val email = uniqueEmail("dfl149-" + generatedPassword("u").take(8))
        val user = seedLocalUser(email, generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace(wsName)

        val pipelineId = seedReleasedPipeline("test/dfl149_probe")
        val dsName = "dfl149-" + generatedPassword("d").take(6).lowercase()
        seedH2Datasource(dsName)
        seedFact(wsName, dsName, email, pipelineId)

        // The page errors are the issue's own evidence channel — collect them alongside the
        // assertions, and print them in the clues so a red names the symptom, not a timeout.
        val pageErrors = mutableListOf<String>()
        page.onPageError(pageErrors::add)

        page.navigate("$baseUrl/datasources")
        page.waitForResponse({ it.url().contains("/facts") }) {
            page.locator("button[data-read='datasource-facts']").first().click()
        }
        val link = page.locator("#ds-dialog a[href*='/editor']")
        withClue("the fact's source-pipeline link renders in the dialog (D-S9)") {
            link.count() shouldBeGreaterThan 0
        }

        // Same-document markers: they survive only if the click is an in-shell swap, never a
        // reload. The rail element identity is the #460 acceptance criterion.
        page.evaluate("() => { window.__dpDoc = document; window.__dpRail = document.getElementById('app-rail'); }")

        link.first().click()
        // The runtime activates the component asynchronously after the swap; the epoch is
        // stamped only after Alpine.initTree has bound the tabs and Run. Readiness, not a sleep.
        page.locator(".pe-root[data-pe-runtime-epoch]").first().waitFor()

        withClue("the editor opened in the shell — the document did not reload") {
            page.evaluate("() => window.__dpDoc === document") shouldBe true
        }
        withClue("the rail is the SAME element after editor entry") {
            page.evaluate("() => window.__dpRail === document.getElementById('app-rail')") shouldBe true
        }
        withClue("the destination URL is the canonical workspace (authorized admission)") {
            page.url() shouldMatch PipelineWorkspaceUrl.DOCUMENT
        }
        withClue("the editor initialised and rendered real node geometry (page errors: $pageErrors)") {
            page.locator(".pe-card").first().waitFor()
            val cardGeometry =
                page.evaluate(
                    "() => { const c = document.querySelector('.pe-card'); const r = c.getBoundingClientRect();" +
                        " return { w: r.width, h: r.height }; }",
                ) as Map<*, *>
            (cardGeometry["w"] as Number).toInt() shouldBeGreaterThan 0
            (cardGeometry["h"] as Number).toInt() shouldBeGreaterThan 0
        }
        withClue("the permitted Run is enabled and a real execution reaches Completed") {
            val run = page.locator("[data-verb='pipeline-execute']")
            run.isEnabled shouldBe true
            run.click()
            page
                .locator("[data-verb='pipeline-execute']:not([disabled])")
                .waitFor(
                    com.microsoft.playwright.Locator
                        .WaitForOptions()
                        .setTimeout(EXECUTION_TIMEOUT_MS),
                )
            page.locator(".pe-status:has-text('Completed')").waitFor()
        }
        withClue("no page error on the way in") { pageErrors.shouldBeEmpty() }
    }

    // ------------------------------------------------------------------ fixtures

    /** A released calculator pipeline through the browser session's REST; returns its id. */
    private fun seedReleasedPipeline(name: String): String {
        val (status, body) =
            send(
                "POST",
                "/api/v1/pipelines",
                """{"name":"$name","display_name":"${name.substringAfterLast('/')}",""" +
                    """"description":"149 fixture","nodes":[{"id":"fq","type":"CALCULATOR",""" +
                    """"kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                    """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
            )
        status shouldBe 201
        val id = Regex(""""id"\s*:\s*"([0-9a-f-]+)"""").find(body!!)!!.groupValues[1]
        val (gs, gbody) = send("GET", "/api/v1/pipelines/$id")
        gs shouldBe 200
        val hash = Regex(""""body_hash"\s*:\s*"([0-9a-f]+)"""").find(gbody!!)!!.groupValues[1]
        send("POST", "/api/v1/pipelines/$id/release", ifMatch = hash).first shouldBe 200
        return id
    }

    private fun seedH2Datasource(name: String) {
        val (status, _) =
            send(
                "POST",
                "/api/v1/datasources",
                """{"name":"$name","display_name":"149 facts fixture","dialect":"H2",""" +
                    """"jdbc_url":"jdbc:h2:mem:${name.replace("-", "_")};DB_CLOSE_DELAY=-1",""" +
                    """"username":"sa","password":"sa"}""",
            )
        status shouldBe 201
    }

    /**
     * A DATASOURCE-scope learned fact whose source is [pipelineId] — the row the facts dialog's
     * D-S9 link renders from. Seeded in SQL (recording is MCP-only; the ExplorerDetailBrowserTest
     * precedent), matching [V25__learned_facts]'s constraints: `recorded_in` is the ACTIVE
     * workspace at record time, `source_pipeline_id` names the pipeline the link points at.
     */
    private fun seedFact(
        workspaceName: String,
        datasourceName: String,
        recordedByEmail: String,
        pipelineId: String,
    ) {
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO learned_facts (id, scope, datasource_name, kind, fact, refs_json,
                                                   trust, schema_fingerprint, recorded_by, recorded_via,
                                                   recorded_in, source_pipeline_id, source_version)
                        SELECT gen_random_uuid(), 'DATASOURCE', '$datasourceName', 'unit',
                               'amount is recorded in cents',
                               jsonb_build_array(jsonb_build_object('table', 'payments', 'column', 'amount')),
                               'observed', 'fingerprint-149', u.id, 'session', w.id,
                               '$pipelineId'::uuid, 1
                          FROM users u
                          CROSS JOIN workspaces w
                         WHERE u.email = '$recordedByEmail'
                           AND w.name = '$workspaceName'
                        """.trimIndent(),
                    )
                }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun send(
        method: String,
        url: String,
        body: String? = null,
        ifMatch: String? = null,
    ): Pair<Int, String?> {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = {'Content-Type': 'application/json',
                                   'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''};
                  if (args.ifMatch) headers['If-Match'] = args.ifMatch;
                  const res = await fetch(args.url, {
                    method: args.method, credentials: 'same-origin', headers,
                    body: args.body ?? undefined,
                  });
                  const text = await res.text();
                  return {status: res.status, body: text.length < 4096 ? text : null};
               }""",
                mapOf("method" to method, "url" to url, "body" to body, "ifMatch" to ifMatch),
            ) as Map<String, Any?>
        return (result["status"] as Number).toInt() to (result["body"] as String?)
    }

    private companion object {
        /** A calculator run is engine-internal (no datasource); 60 s is already generous. */
        const val EXECUTION_TIMEOUT_MS = 60_000.0
    }
}
