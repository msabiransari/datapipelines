package co.datapipelines.visualization

import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.UUID

/**
 * [DashboardRefreshRepository] and the V43 tables against a REAL Postgres with the SHIPPED migrations (#10 L2) — every
 * property here is a statement about Postgres that a mocked template cannot make: the CHECKs that make the row honest
 * (a person XOR a key, RUNNING iff unfinished, the 64 KiB selections cap), the primary keys, the guarded terminal write,
 * the sweep's single `UPDATE … RETURNING`, and the cascades retention leans on.
 */
class DashboardRefreshRepositoryIntegrationTest {
    private val jdbc = VisualizationTestDb.jdbc
    private val repository = DashboardRefreshRepository(jdbc)
    private val dashboard = UUID.fromString("d5000000-0000-0000-0000-0000000000a1")
    private val pipeline = UUID.fromString("b1000000-0000-0000-0000-0000000000a1")

    @BeforeEach
    fun fixture() {
        VisualizationTestDb.reset()
        jdbc.jdbcTemplate.execute("TRUNCATE pipeline_executions, pipeline_versions, pipelines CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO dashboards (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$dashboard', '$WORKSPACE', 'rr/boards/one', 'One', '', 1, '$AUTHOR')",
        )
        jdbc.jdbcTemplate.execute(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, released_at, released_by, created_by) " +
                "VALUES ('$dashboard', 1, '{\"display_name\":\"One\",\"visualizations\":[],\"layout\":{}}'::jsonb, " +
                "'RELEASED', 'h', NOW(), '$AUTHOR', '$AUTHOR')",
        )
        jdbc.jdbcTemplate.execute(
            "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) " +
                "VALUES ('$pipeline', 'rr/pipelines/p', 'P', '', '$AUTHOR', '$WORKSPACE', 1)",
        )
        jdbc.jdbcTemplate.execute(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
                "VALUES ('$pipeline', 1, '{}'::jsonb, 'h', 'RELEASED', '$AUTHOR', '$AUTHOR', NOW())",
        )
    }

    private fun record(
        id: UUID = UUID.randomUUID(),
        user: UUID? = AUTHOR,
        key: String? = null,
        startedAt: Instant = Instant.now(),
        selections: String = "{}",
    ) = RefreshRecord(
        id = id,
        dashboardId = dashboard,
        dashboardVersion = 1,
        workspaceId = WORKSPACE,
        instanceId = UUID.randomUUID(),
        principalUserId = user,
        principalKeyId = key,
        scope = ActionScope.TARGETS,
        targetsJson = """["v"]""",
        parameterRevision = 3,
        selectionsJson = selections,
        status = RefreshStatus.RUNNING,
        startedAt = startedAt,
        finishedAt = null,
        summaryJson = "{}",
    )

    private fun execution(
        status: String = "SUCCESS",
        errorCode: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.jdbcTemplate.execute(
            "INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, parameters_json, executed_by," +
                "triggered_via, " +
                "root_execution_id, error_json) VALUES ('$id', '$pipeline', 1, '$status', '{}'::jsonb, '$AUTHOR', 'DASHBOARD', '$id', " +
                (errorCode?.let { "'{\"code\":\"$it\"}'::jsonb" } ?: "NULL") + ")",
        )
        return id
    }

    private fun statusOf(id: UUID): String =
        jdbc.jdbcTemplate.queryForObject("SELECT status FROM dashboard_refreshes WHERE id = '$id'", String::class.java)!!

    @Test
    fun `a refresh is inserted RUNNING with its scope, targets and selections - and a reused id is refused without touching the row`() {
        val first = record()

        repository.insertRunning(first) shouldBe true
        repository.insertRunning(first.copy(selectionsJson = """{"other":1}""")) shouldBe false // the id is taken

        val stored = repository.find(WORKSPACE, first.id).shouldNotBeNull()
        stored.status shouldBe RefreshStatus.RUNNING
        stored.scope shouldBe ActionScope.TARGETS
        stored.targetsJson shouldBe """["v"]"""
        stored.parameterRevision shouldBe 3
        stored.selectionsJson shouldBe "{}" // the second insert did not overwrite
        stored.finishedAt.shouldBeNull()
    }

    @Test
    fun `a refresh is read only in its own workspace - the other's id is absent, not forbidden`() {
        val first = record().also(repository::insertRunning)

        repository.find(OTHER_WORKSPACE, first.id).shouldBeNull()
        repository.list(OTHER_WORKSPACE, dashboard, null, null, 10) shouldBe emptyList()
        repository.count(OTHER_WORKSPACE, dashboard, null) shouldBe 0
    }

    @Test
    fun `the terminal write happens once - a late writer, the sweeper or a second finish, never overwrites a finished refresh`() {
        val first = record().also(repository::insertRunning)

        repository.finish(first.id, RefreshStatus.COMPLETED, """{"targets":{}}""") shouldBe true
        repository.finish(first.id, RefreshStatus.TIMED_OUT, """{"reason":"late"}""") shouldBe false

        statusOf(first.id) shouldBe "COMPLETED"
        repository.find(WORKSPACE, first.id)!!.let {
            it.summaryJson shouldBe """{"targets": {}}""" // jsonb's canonical text
            it.finishedAt.shouldNotBeNull()
        }
        shouldThrow<IllegalArgumentException> { repository.finish(first.id, RefreshStatus.RUNNING, "{}") }
    }

    @Test
    fun `the row's own checks - a person XOR a key, RUNNING iff unfinished, the 64 KiB selections cap - each refuse at the database`() {
        shouldThrow<DataIntegrityViolationException> { repository.insertRunning(record(user = null, key = null)) } // neither
        shouldThrow<DataIntegrityViolationException> { repository.insertRunning(record(user = AUTHOR, key = "dpk_x")) } // both
        val oversized = """{"a":"${"x".repeat(70_000)}"}"""
        shouldThrow<DataIntegrityViolationException> { repository.insertRunning(record(selections = oversized)) }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.jdbcTemplate.execute(
                "INSERT INTO dashboard_refreshes (id, dashboard_id, dashboard_version, workspace_id, instance_id," +
                    "principal_user_id, scope, " +
                    "parameter_revision, status) VALUES ('${UUID.randomUUID()}', '$dashboard', 1, '$WORKSPACE'," +
                    "'${UUID.randomUUID()}', '$AUTHOR', " +
                    "'ALL', 1, 'COMPLETED')", // finished without a finished_at
            )
        }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.jdbcTemplate.execute(
                "INSERT INTO dashboard_refreshes (id, dashboard_id, dashboard_version, workspace_id, instance_id," +
                    "principal_user_id, scope, " +
                    "parameter_revision, status) VALUES ('${UUID.randomUUID()}', '$dashboard', 1, '$WORKSPACE'," +
                    "'${UUID.randomUUID()}', '$AUTHOR', " +
                    "'SOME', 1, 'RUNNING')",
            )
        }
    }

    @Test
    fun `an execution link is one per source, and reads back in source order`() {
        val first = record().also(repository::insertRunning)
        val a = execution()
        val b = execution()

        repository.link(RefreshExecutionLink(first.id, "b_source", b, shared = false))
        repository.link(RefreshExecutionLink(first.id, "a_source", a, shared = true))

        repository.linksOf(first.id).map { it.sourceName to it.shared } shouldContainExactly listOf("a_source" to true, "b_source" to false)
        shouldThrow<DuplicateKeyException> { repository.link(RefreshExecutionLink(first.id, "a_source", b, shared = false)) }
    }

    @Test
    fun `the list is newest first and narrows to one person's own - the count and latestOf agree with it`() {
        val now = Instant.now()
        val old = record(startedAt = now.minusSeconds(60)).also(repository::insertRunning)
        val mine = record(startedAt = now.minusSeconds(30)).also(repository::insertRunning)
        val theirs = record(user = OTHER_AUTHOR, startedAt = now.minusSeconds(10)).also(repository::insertRunning)

        repository.list(WORKSPACE, dashboard, null, null, 10).map { it.id } shouldContainExactly listOf(theirs.id, mine.id, old.id)
        repository.list(WORKSPACE, dashboard, AUTHOR, null, 10).map { it.id } shouldContainExactly listOf(mine.id, old.id)
        repository.list(WORKSPACE, dashboard, AUTHOR, null, limit = 1, offset = 1).map { it.id } shouldContainExactly listOf(old.id)
        repository.count(WORKSPACE, dashboard, AUTHOR) shouldBe 2
        repository.count(WORKSPACE, dashboard, null) shouldBe 3
        repository.latestOf(WORKSPACE, dashboard, AUTHOR)!!.id shouldBe mine.id
        repository.latestOf(WORKSPACE, dashboard, UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `the sweep closes only RUNNING rows past the cutoff - TIMED_OUT, with the reason the executions tell`() {
        val lost = record(startedAt = Instant.now().minusSeconds(7_200)).also(repository::insertRunning)
        val slow = record(startedAt = Instant.now().minusSeconds(7_200)).also(repository::insertRunning)
        val fresh = record(startedAt = Instant.now().minusSeconds(5)).also(repository::insertRunning)
        val done = record(startedAt = Instant.now().minusSeconds(7_200)).also(repository::insertRunning)
        repository.finish(done.id, RefreshStatus.COMPLETED, "{}")
        repository.link(
            RefreshExecutionLink(
                lost.id,
                "s",
                execution(status = "ABORTED", errorCode = "pipeline.execution.instance_lost"),
                shared = false,
            ),
        )
        repository.link(
            RefreshExecutionLink(slow.id, "s", execution(status = "ABORTED", errorCode = "pipeline.execution.aborted"), shared = false),
        )

        val closed = repository.sweepStale(staleAfterSeconds = 915)

        closed.toSet() shouldBe setOf(lost.id, slow.id)
        statusOf(lost.id) shouldBe "TIMED_OUT"
        repository.find(WORKSPACE, lost.id)!!.summaryJson shouldBe """{"reason": "instance_lost"}"""
        repository.find(WORKSPACE, slow.id)!!.summaryJson shouldBe """{"reason": "deadline_passed"}"""
        statusOf(fresh.id) shouldBe "RUNNING"
        statusOf(done.id) shouldBe "COMPLETED"
        repository.sweepStale(915) shouldBe emptyList() // idempotent: nothing left to close
    }

    @Test
    fun `retention deletes finished refreshes past the cutoff with their links - never the execution, never a RUNNING refresh`() {
        val old = record().also(repository::insertRunning)
        val recent = record().also(repository::insertRunning)
        val running = record().also(repository::insertRunning)
        repository.finish(old.id, RefreshStatus.COMPLETED, "{}")
        repository.finish(recent.id, RefreshStatus.COMPLETED, "{}")
        jdbc.jdbcTemplate.execute("UPDATE dashboard_refreshes SET finished_at = NOW() - INTERVAL '10 days' WHERE id = '${old.id}'")
        val kept = execution()
        repository.link(RefreshExecutionLink(old.id, "s", kept, shared = false))

        repository.deleteFinishedOlderThan(retentionDays = 7) shouldBe 1

        repository.find(WORKSPACE, old.id).shouldBeNull()
        repository.linksOf(old.id) shouldBe emptyList() // the cascade
        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pipeline_executions WHERE execution_id = '$kept'", Int::class.java) shouldBe
            1
        repository.find(WORKSPACE, recent.id).shouldNotBeNull()
        repository.find(WORKSPACE, running.id).shouldNotBeNull()
    }

    @Test
    fun `purging a dashboard drops its refreshes with it`() {
        val first = record().also(repository::insertRunning)

        jdbc.jdbcTemplate.execute("DELETE FROM dashboard_versions WHERE dashboard_id = '$dashboard'")
        jdbc.jdbcTemplate.execute("DELETE FROM dashboards WHERE id = '$dashboard'")

        repository.find(WORKSPACE, first.id).shouldBeNull()
    }
}
