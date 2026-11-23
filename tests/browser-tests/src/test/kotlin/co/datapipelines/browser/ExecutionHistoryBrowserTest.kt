package co.datapipelines.browser

import com.microsoft.playwright.Locator.LocatorOptions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.SelectOption
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Execution history's first paint, visibility, filtering and pagination in a real browser.
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
        page.locator("select").first().isVisible() shouldBe true
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
        // Scoped to #execution-table: the filter bar's <select> options carry the same names.
        page
            .locator("#execution-table")
            .locator("text=$scheduledPipeline")
            .first()
            .waitFor()
        val table = page.locator("#execution-table").innerText()
        table.shouldContain("SCHEDULE")
        table.shouldContain("System")
        table.shouldContain(scheduledPipeline)
        // The absence is the assertion that can fail: R3 widens SCHEDULED rows only.
        table.shouldNotContain(ownPipeline)
    }

    @Test
    fun `date and status changes refresh history and the pager keeps those filters`() {
        startTrace()
        val viewer = seedActor("history-date-filter", role = "viewer")
        val today = LocalDate.now(ZoneOffset.UTC)
        val midnight = today.atStartOfDay(ZoneOffset.UTC).toInstant()
        val previousRuns = (1L..21L).map { midnight.minus(it, ChronoUnit.MICROS) }
        val pipeline =
            seedPipelineAndRuns(
                "test/execdates-${viewer.userId}",
                viewer.userId,
                scheduledRun = false,
                starts = listOf(midnight) + previousRuns,
            )
        login(viewer.email, viewer.oneTimePassword)
        page.waitForURL("**/dashboard")

        val table = page.locator("#execution-table")
        page.navigate("$baseUrl/executions?started_before=$today")
        assertThat(table).containsText("Browser User")
        assertThat(table).not().containsText("$today 00:00")

        page.locator("#pipeline_id").selectOption(SelectOption().setLabel(pipeline))
        page.locator("#started_after").fill(today.minusDays(1).toString())
        page.locator("#started_before").fill("")
        assertThat(table).containsText("$today 00:00")

        page.locator("#started_before").fill(today.toString())
        assertThat(table).not().containsText("$today 00:00")
        assertThat(table.locator("tbody tr.app-row-clickable")).hasCount(20)
        table.locator("button", LocatorOptions().setHasText("Next")).click()
        assertThat(table.locator("tbody tr.app-row-clickable")).hasCount(1)
        assertThat(table).containsText("Browser User")

        page.locator("#status").selectOption("FAILED")
        assertThat(table).containsText("No executions found")
        page.locator("#status").selectOption("SUCCESS")
        assertThat(table.locator("tbody tr.app-row-clickable")).hasCount(20)
        assertThat(table).not().containsText("$today 00:00")
    }

    /**
     * One pipeline in the default workspace, with executions at [starts] attributed to
     * the System actor for schedules or [actorId] for interactive runs.
     */
    private fun seedPipelineAndRuns(
        name: String,
        actorId: UUID,
        scheduledRun: Boolean,
        starts: List<Instant> = listOf(Instant.now().minus(1, ChronoUnit.HOURS)),
    ): String {
        val actor = if (scheduledRun) "(SELECT id FROM users WHERE kind = 'system')" else "'$actorId'"
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
                    starts.forEach { started ->
                        val executionId = UUID.randomUUID()
                        statement.execute(
                            """
                            INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status,
                                                             executed_by, triggered_via, started_at, completed_at,
                                                             duration_ms, root_execution_id)
                            SELECT '$executionId', id, 1, 'SUCCESS', $actor, '${if (scheduledRun) "SCHEDULE" else "UI"}',
                                   '$started', '${started.plusSeconds(60)}', 60000, '$executionId'
                              FROM pipelines WHERE name = '$name'
                            """.trimIndent(),
                        )
                    }
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
