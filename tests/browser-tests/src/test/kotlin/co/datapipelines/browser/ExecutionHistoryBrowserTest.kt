package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Golden path 7's page half: the execution history screen renders with its filter bar —
 * the pipeline dropdown and the status set — and shows the honest empty state when the
 * workspace has no runs. (The row/pagination half needs a real execution, which needs
 * the pipeline editor's Phase-2 rebuild — deferred in TEST-GAP-2026-09.md.)
 */
class ExecutionHistoryBrowserTest : BrowserSuite() {
    @Test
    fun `the history screen renders its filter bar and the empty state`() {
        startTrace()
        val user =
            seedLocalUser(
                uniqueEmail("hist-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("histws-" + generatedPassword("w").take(8).lowercase())

        page.navigate("$baseUrl/executions")
        page.waitForURL("**/executions")
        // The page model's two filter inputs: pipelines for the dropdown, statuses for the bar.
        page.locator("select").first().isVisible() shouldBe true
        // The empty state rides the htmx-loaded partial — wait for ITS arrival, not
        // the page's initial HTML.
        page.waitForSelector("text=No executions found")
    }

    /**
     * #250 (R3 on every surface): a VIEWER's history screen lists the run a schedule fired
     * — nobody's own, the system identity ran it — and still does NOT list another member's
     * own interactive run. The rows are seeded straight into the shared Postgres with the
     * same attribution the scheduler writes (`triggered_via = 'SCHEDULE'`, a member as the
     * interactive run's actor), so the page is read exactly as a deployment's would be.
     */
    @Test
    fun `a viewer's history lists the scheduled run and not another member's own run`() {
        startTrace()
        val suffix = generatedPassword("s").take(8).lowercase()
        val viewer = seedActor("histv-$suffix", role = "viewer")
        val author = seedActor("hista-$suffix", role = "author")

        val scheduledPipeline = seedPipelineAndRuns("test/execvis-sched-$suffix", author.userId, scheduledRun = true)
        val ownPipeline = seedPipelineAndRuns("test/execvis-own-$suffix", author.userId, scheduledRun = false)

        login(viewer.email, viewer.oneTimePassword)
        page.waitForURL("**/dashboard")
        page.navigate("$baseUrl/executions")
        page.waitForURL("**/executions")
        // The htmx-loaded partial carries the rows — wait for the scheduled run's row.
        // Scoped to #execution-table: the filter bar's <select> options carry the same names.
        page
            .locator("#execution-table")
            .locator("text=$scheduledPipeline")
            .first()
            .waitFor()
        val table = page.locator("#execution-table").innerText()
        table.shouldContain("SCHEDULE")
        table.shouldContain(scheduledPipeline)
        // The absence is the assertion that can fail: R3 widens SCHEDULED rows only.
        table.shouldNotContain(ownPipeline)
    }

    /**
     * One pipeline (draft v1 — the executions' FK needs only the version row) plus ONE
     * execution: a `SCHEDULE` run or an interactive `UI` run of [actorId]'s, per [scheduledRun].
     * The pipeline lives in the `default` workspace the seeded memberships grant. Returns the
     * pipeline NAME, which the history row renders when the join resolves.
     */
    private fun seedPipelineAndRuns(
        name: String,
        actorId: UUID,
        scheduledRun: Boolean,
    ): String {
        val executionId = UUID.randomUUID()
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO pipelines (id, workspace_id, name, display_name, owner_id, current_version)
                        VALUES ('${UUID.randomUUID()}', 'defa0000-0000-0000-0000-000000000001', '$name', '$name', '$actorId', 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by, body_hash)
                        SELECT id, 1, '{}'::jsonb, '$actorId', 'seeded-fixture-hash'
                          FROM pipelines WHERE name = '$name'
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status,
                                                         executed_by, triggered_via, started_at, completed_at,
                                                         duration_ms, root_execution_id)
                        SELECT '$executionId', id, 1, 'SUCCESS', '$actorId', '${if (scheduledRun) "SCHEDULE" else "UI"}',
                               NOW() - interval '1 hour', NOW() - interval '59 minutes', 60000, '$executionId'
                          FROM pipelines WHERE name = '$name'
                        """.trimIndent(),
                    )
                }
            }
        return name
    }

    private fun seedActor(
        slug: String,
        role: String,
    ): SeededActor {
        val email = uniqueEmail(slug)
        val password = generatedPassword("pw")
        super.seedLocalUser(email, password, mustChange = false, isAdmin = false, role = role)
        val id =
            DriverManager
                .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
                .use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT id FROM users WHERE email = '$email'").use { rows ->
                            rows.next()
                            UUID.fromString(rows.getString("id"))
                        }
                    }
                }
        return SeededActor(email, password, id)
    }

    /** The seeded user plus the id the executions rows need for their `executed_by` FK. */
    private data class SeededActor(
        val email: String,
        val oneTimePassword: String,
        val userId: UUID,
    )
}
