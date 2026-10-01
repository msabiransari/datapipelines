package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

/**
 * #149 — the datasource facts' source-pipeline link opens the editor as a FULL document load.
 * The editor cannot initialise on a boosted swap: Alpine binds the component the moment the
 * swapped markup lands, before the editor's scripts execute, and every binding throws (159's
 * live symptom) — the reason every other route into the editor is `hx-boost="false"`. The
 * facts link was the one `<a>` into the editor that still boosted; it carries
 * `hx-boost="false"` like its siblings (301 #149).
 *
 * Red on the pre-fix tree reproduces the issue's own evidence: the navigation entry still
 * names /datasources (a boosted swap, not a load), the canvas never mounts, and the page
 * errors carry `pipelineEditor is not defined`.
 */
class DatasourceFactsLinkBrowserTest : BrowserSuite() {
    @Test
    fun `the facts' source-pipeline link opens the editor as a full document load`() {
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

        link.first().click()
        // The editor's root exists on BOTH legs — swapped (the defect) or loaded (the fix) —
        // so this wait is a settle, never the assertion.
        page.locator("#app-main .pe-root, .pe-root").first().waitFor()

        val nav = page.evaluate("() => performance.getEntriesByType('navigation')[0].name") as String
        withClue("the editor arrived as a FULL document load (navigation entry: $nav; page errors: $pageErrors)") {
            // The fact's link is the compatibility URL; the entry names the canonical page it landed on (#348).
            nav shouldMatch PipelineWorkspaceUrl.DOCUMENT
        }
        withClue("the editor initialised — the canvas mounted (page errors: $pageErrors)") {
            page.locator("#cy-canvas").count() shouldBeGreaterThan 0
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
}
