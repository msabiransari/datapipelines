package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * #1035 R1 — the crash sweep labels a row by the commit evidence it holds WHEN IT IS WRITTEN, whichever evidence
 * lands first. Real Postgres, two real connections, no sleeping: a second connection holds the row's lock with an
 * uncommitted stamp, the sweep statement is observed WAITING on that lock in `pg_stat_activity`, and only then does
 * the stamp commit. That is the exact interleaving the three ordered statements of the first delivery lost: the
 * sweep had already judged the row on its earlier snapshot, so an attempt stamped after the "unknown" statement
 * ended ABORTED and a confirmation stamped after the "success" statement ended OUTCOME_UNKNOWN.
 *
 * Every assertion reads the persisted row on a connection of the test's own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaleSweepCommitEvidenceIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executions: ExecutionRepository
    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun setUp() {
        executions = ExecutionRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE', 'default', 'Default')",
        )
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Test User', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        pipelineId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) " +
                "VALUES (:id, :name, 'Test Pipeline', :owner, 1, '$WORKSPACE')",
            mapOf("id" to pipelineId, "name" to "p_${pipelineId.toString().replace("-", "")}", "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
                "VALUES (:id, 1, CAST('{}' AS jsonb), 'seed-hash', 'RELEASED', :owner, :owner, NOW())",
            mapOf("id" to pipelineId, "owner" to userId),
        )
    }

    @Test
    fun `an attempt stamped while the sweep waits on the row is classified OUTCOME_UNKNOWN, never ABORTED`() {
        val id = staleRow()

        val swept = sweepWhileStamping(id, "UPDATE pipeline_executions SET commit_attempted_at = NOW() WHERE execution_id = :id")

        val row = rowOf(id)
        withClue("swept=$swept row=$row") {
            row["status"] shouldBe "OUTCOME_UNKNOWN"
            row["commit_attempted_at"].shouldNotBeNull()
            row["commit_confirmed_at"].shouldBeNull()
            row["error_code"] shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED
            swept.map { it.status } shouldContainExactly listOf(ExecutionStatus.OUTCOME_UNKNOWN)
        }
    }

    @Test
    fun `a confirmation stamped while the sweep waits on an attempted row is classified SUCCESS, never unknown`() {
        val id = staleRow(attempted = true)

        val swept =
            sweepWhileStamping(id, "UPDATE pipeline_executions SET commit_confirmed_at = NOW() WHERE execution_id = :id")

        val row = rowOf(id)
        withClue("swept=$swept row=$row") {
            row["status"] shouldBe "SUCCESS"
            row["commit_confirmed_at"].shouldNotBeNull()
            row["result_unavailable"] shouldBe "true"
            row["error_code"].shouldBeNull()
            swept.map { it.status } shouldContainExactly listOf(ExecutionStatus.SUCCESS)
        }
    }

    @Test
    fun `a heartbeat that resumes while the sweep waits leaves the row RUNNING for a later tick`() {
        val id = staleRow()

        val swept = sweepWhileStamping(id, "UPDATE pipeline_executions SET heartbeat_at = NOW() WHERE execution_id = :id")

        val row = rowOf(id)
        withClue("swept=$swept row=$row") {
            row["status"] shouldBe "RUNNING"
            row["completed_at"].shouldBeNull()
            swept.size shouldBe 0
        }
    }

    @Test
    fun `two sweepers racing for one row write it once, by its evidence`() {
        val id = staleRow(attempted = true)
        val first = CompletableFuture<List<SweptExecution>>()
        val second = CompletableFuture<List<SweptExecution>>()

        connection().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use {
                it.execute(
                    "UPDATE pipeline_executions SET result_unavailable = FALSE WHERE execution_id = '$id'",
                )
            }
            CompletableFuture.runAsync { first.complete(sweepNow()) }
            CompletableFuture.runAsync { second.complete(sweepNow()) }
            awaitWaiters(2)
            holder.commit()
        }

        val results = listOf(first.get(WAIT_S, TimeUnit.SECONDS), second.get(WAIT_S, TimeUnit.SECONDS))
        withClue("results=$results row=${rowOf(id)}") {
            results.sumOf { it.size } shouldBe 1
            rowOf(id)["status"] shouldBe "OUTCOME_UNKNOWN"
        }
        sweepNow().size shouldBe 0
    }

    @Test
    fun `a resumed execution cannot stamp its attempt after the sweep aborted it, nor overwrite an unknown by a late confirmation stamp`() {
        val lost = staleRow()
        val window = staleRow(attempted = true)

        sweepNow().map { it.status }.sorted() shouldContainExactly listOf(ExecutionStatus.OUTCOME_UNKNOWN, ExecutionStatus.ABORTED).sorted()

        // The instance that was merely slow wakes up: its attempt stamp is refused (so it never calls the driver's
        // commit) and a confirmation stamp for the unknown row is refused too — the sweep's honest label stands.
        executions.markCommitAttempt(UUID.fromString(lost)) shouldBe false
        executions.markCommitConfirmed(UUID.fromString(window)) shouldBe false
        rowOf(lost)["status"] shouldBe "ABORTED"
        rowOf(lost)["commit_attempted_at"].shouldBeNull()
        rowOf(window)["status"] shouldBe "OUTCOME_UNKNOWN"
        rowOf(window)["commit_confirmed_at"].shouldBeNull()
    }

    @Test
    fun `the evidence decides each label in one sweep over a mixed batch`() {
        val never = staleRow()
        val attempted = staleRow(attempted = true)
        val confirmed = staleRow(attempted = true, confirmed = true)

        val swept = sweepNow().associate { it.executionId.toString() to it.status }

        swept shouldBe
            mapOf(
                never to ExecutionStatus.ABORTED,
                attempted to ExecutionStatus.OUTCOME_UNKNOWN,
                confirmed to ExecutionStatus.SUCCESS,
            )
        rowOf(never)["error_code"] shouldBe PipelineErrorCodes.Execution.INSTANCE_LOST
        rowOf(confirmed)["completed_at"].shouldNotBeNull()
        withClue("a swept SUCCESS root reports its result as unavailable") { rowOf(confirmed)["result_unavailable"] shouldBe "true" }
        rowOf(attempted)["error_code"] shouldContain "local_write_commit_failed"
    }

    // ------------------------------------------------------------ fixtures

    /** Runs the production sweep while a second connection holds [stampSql]'s change uncommitted; commits it once the sweep waits. */
    private fun sweepWhileStamping(
        id: String,
        stampSql: String,
    ): List<SweptExecution> {
        val sweep = CompletableFuture<List<SweptExecution>>()
        connection().use { stamper ->
            stamper.autoCommit = false
            stamper.prepareStatement(stampSql.replace(":id", "'$id'")).use { it.executeUpdate() }
            CompletableFuture.runAsync { runCatching { sweepNow() }.fold(sweep::complete, sweep::completeExceptionally) }
            awaitWaiters(1)
            stamper.commit()
        }
        return sweep.get(WAIT_S, TimeUnit.SECONDS)
    }

    private fun sweepNow(): List<SweptExecution> =
        executions.sweepStaleRunningDetailed(
            Instant.now().minus(1, ChronoUnit.HOURS),
            Instant.now().minus(45, ChronoUnit.SECONDS),
        )

    /** A RUNNING row an instance left behind: old, no heartbeat stamp, with the given commit evidence. */
    private fun staleRow(
        attempted: Boolean = false,
        confirmed: Boolean = false,
    ): String {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, executed_by, triggered_via,
                                             root_execution_id, started_at, commit_attempted_at, commit_confirmed_at)
            VALUES (:id, :pipeline, 1, 'RUNNING', :user, 'REST', :id, NOW() - INTERVAL '3 hours',
                    CASE WHEN :attempted THEN NOW() - INTERVAL '3 hours' END,
                    CASE WHEN :confirmed THEN NOW() - INTERVAL '3 hours' END)
            """.trimIndent(),
            mapOf("id" to id, "pipeline" to pipelineId, "user" to userId, "attempted" to attempted, "confirmed" to confirmed),
        )
        return id.toString()
    }

    /** Blocks until [count] backends are waiting on a row lock inside a pipeline_executions UPDATE — no sleeping on time. */
    private fun awaitWaiters(count: Int) {
        val deadline = System.currentTimeMillis() + WAIT_S * MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (lockWaiters() >= count) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("fewer than $count backends ever waited on the row lock")
    }

    /** One poll, on its own connection per call: pg_stat_activity inside an open transaction is a frozen snapshot. */
    private fun lockWaiters(): Int =
        connection().use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                            "AND query LIKE 'UPDATE pipeline_executions%' AND pid <> pg_backend_pid()",
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
            }
        }

    private fun rowOf(id: String): Map<String, String?> =
        connection().use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT status, commit_attempted_at::text, commit_confirmed_at::text, completed_at::text, " +
                            "result_unavailable::text, error_json ->> 'code' FROM pipeline_executions WHERE execution_id = '$id'",
                    ).use { rs ->
                        check(rs.next()) { "no row $id" }
                        mapOf(
                            "status" to rs.getString(1),
                            "commit_attempted_at" to rs.getString(2),
                            "commit_confirmed_at" to rs.getString(3),
                            "completed_at" to rs.getString(4),
                            "result_unavailable" to rs.getString(5),
                            "error_code" to rs.getString(6),
                        )
                    }
            }
        }

    private fun connection(): Connection = SharedPostgres.dataSource().connection

    private companion object {
        const val WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        const val WAIT_S = 30L
        const val MILLIS = 1000L
        const val POLL_MS = 25L
    }
}
