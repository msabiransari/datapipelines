package co.datapipelines.executor

import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [ExecutionRepository] and [ExecutionEventRepository] against a real Postgres running the
 * **shipped** `V1__initial_schema.sql` (metadata-db §4.6/§4.7).
 *
 * The migration is executed off disk rather than through Flyway: domain modules carry no Flyway
 * dependency (module-structure §3.1 rule 2), the same discipline the sibling
 * `AuthRepositoriesIntegrationTest` and `PipelineRepositoryIntegrationTest` follow. Running the
 * real DDL is the point — the JSONB casts, the composite FK to `pipeline_versions`, the status
 * CHECK constraint and the `(execution_id, event_id)` UNIQUE are all things only Postgres enforces.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionRepositoriesIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executions: ExecutionRepository
    private lateinit var events: ExecutionEventRepository
    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID

    @BeforeAll
    fun createSchema() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        jdbc.jdbcTemplate.execute(RepoFiles.read(RepoFiles.MIGRATION_PATH))
    }

    @BeforeEach
    fun setUp() {
        executions = ExecutionRepository(jdbc)
        events = ExecutionEventRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = insertUser()
        pipelineId = insertPipelineWithVersion(userId)
    }

    @Test
    fun `create inserts a RUNNING row and findById reads every column back`() {
        val record = running()

        executions.create(record)

        val found = executions.findById(record.executionId).shouldNotBeNull()
        found.pipelineId shouldBe pipelineId
        found.pipelineVersion shouldBe 1
        found.status shouldBe ExecutionStatus.RUNNING
        found.triggeredBy shouldBe userId
        found.triggeredVia shouldBe ExecutionTrigger.REST
        found.correlationId shouldBe record.correlationId
        found.completedAt.shouldBeNull()
        found.nodeStatsJson.shouldBeNull()
        // The JSONB round trip is exact — `parameters_json::TEXT` is what the mapper reads.
        found.parametersJson shouldBe """{"start_date": "2026-01-01"}"""
    }

    @Test
    fun `complete writes the single terminal update, including the result history columns`() {
        val record = running()
        executions.create(record)
        val completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        val updated =
            executions.complete(
                executionId = record.executionId,
                status = ExecutionStatus.SUCCESS,
                completedAt = completedAt,
                durationMs = 1_234,
                nodeStatsJson = NODE_STATS_JSON,
                resultRowCount = 4_500,
                resultSizeBytes = 800_000,
            )

        updated.shouldBeTrue()
        val found = executions.findById(record.executionId).shouldNotBeNull()
        found.status shouldBe ExecutionStatus.SUCCESS
        found.durationMs shouldBe 1_234
        found.resultRowCount shouldBe 4_500
        found.resultSizeBytes shouldBe 800_000
        found.nodeStatsJson.shouldNotBeNull()
        found.completedAt.shouldNotBeNull()
    }

    @Test
    fun `a failed execution records its node and error envelope, and a zero-caller run records no result`() {
        val record = running()
        executions.create(record)

        executions.complete(
            executionId = record.executionId,
            status = ExecutionStatus.FAILED,
            completedAt = Instant.now(),
            durationMs = 60,
            nodeStatsJson = NODE_STATS_JSON,
            failedNodeId = "final_report",
            errorJson = """{"code": "pipeline.node.query_execution_failed"}""",
        )

        val found = executions.findById(record.executionId).shouldNotBeNull()
        found.status shouldBe ExecutionStatus.FAILED
        found.failedNodeId shouldBe "final_report"
        found.errorJson.shouldNotBeNull()
        // NULL, not 0: "no caller node" is not "a result of zero rows" (metadata-db §4.6).
        found.resultRowCount.shouldBeNull()
        found.resultSizeBytes.shouldBeNull()
    }

    @Test
    fun `completing an unknown execution reports false rather than throwing`() {
        executions.complete(
            executionId = UUID.randomUUID(),
            status = ExecutionStatus.SUCCESS,
            completedAt = Instant.now(),
            durationMs = 1,
            nodeStatsJson = NODE_STATS_JSON,
        ) shouldBe false
    }

    @Test
    fun `listings are newest-first and scoped to their pipeline or user`() {
        val other = insertPipelineWithVersion(userId)
        val a = running().also(executions::create)
        Thread.sleep(SPACING_MS)
        val b = running().also(executions::create)
        val elsewhere = running().copy(executionId = UUID.randomUUID(), pipelineId = other).also(executions::create)

        executions.findByPipeline(pipelineId).map { it.executionId } shouldContainExactly listOf(b.executionId, a.executionId)
        executions.findByPipeline(other).map { it.executionId } shouldContainExactly listOf(elsewhere.executionId)
        executions.findByUser(userId).size shouldBe 3
        executions.findByUser(UUID.randomUUID()).size shouldBe 0
        executions.findByPipeline(pipelineId, limit = 1).size shouldBe 1
    }

    @Test
    fun `the admin listing crosses users, stays newest-first, and still filters by pipeline`() {
        // rest-api §10.1 with an `admin` principal (auth §7.6): not "my executions", *all* of them.
        val otherUser = insertUser()
        val otherPipeline = insertPipelineWithVersion(otherUser)
        val mine = running().also(executions::create)
        Thread.sleep(SPACING_MS)
        val theirs =
            running()
                .copy(executionId = UUID.randomUUID(), pipelineId = otherPipeline, triggeredBy = otherUser)
                .also(executions::create)

        val all = executions.findAll()

        // Newest first, and the user-scoped listing sees only half of what admin sees.
        all.map { it.executionId } shouldContainExactly listOf(theirs.executionId, mine.executionId)
        executions.findByUser(userId).map { it.executionId } shouldContainExactly listOf(mine.executionId)
        executions.findAll(pipelineId = otherPipeline).map { it.executionId } shouldContainExactly
            listOf(theirs.executionId)
        // Pagination is over the same newest-first order, so page 2 resumes where page 1 stopped.
        executions.findAll(limit = 1).map { it.executionId } shouldContainExactly listOf(theirs.executionId)
        executions.findAll(limit = 1, offset = 1).map { it.executionId } shouldContainExactly listOf(mine.executionId)
    }

    @Test
    fun `the crash sweep flips only stale RUNNING rows`() {
        // metadata-db §8.3: RUNNING rows whose instance died. Not a cancellation — nothing is
        // running any more, so there is no statement to cancel and no execution_aborted event.
        val stale = running().copy(startedAt = Instant.now().minus(2, ChronoUnit.HOURS)).also(executions::create)
        val fresh = running().also(executions::create)
        val finished = running().also(executions::create)
        executions.complete(finished.executionId, ExecutionStatus.SUCCESS, Instant.now(), 5, NODE_STATS_JSON)

        val swept = executions.sweepStaleRunning(Instant.now().minus(1, ChronoUnit.HOURS))

        swept shouldBe 1
        val sweptRow = executions.findById(stale.executionId).shouldNotBeNull()
        sweptRow.status shouldBe ExecutionStatus.ABORTED
        // F1: a swept row is shaped like every other terminal row — `completed_at` AND a duration.
        sweptRow.completedAt.shouldNotBeNull()
        (sweptRow.durationMs.shouldNotBeNull() > 0).shouldBeTrue()
        // F2: the envelope is written by the repository, so there is exactly one spelling of it.
        sweptRow.errorJson.shouldNotBeNull() shouldContain PipelineErrorCodes.Execution.INSTANCE_LOST

        executions.findById(fresh.executionId).shouldNotBeNull().status shouldBe ExecutionStatus.RUNNING
        executions.findById(finished.executionId).shouldNotBeNull().status shouldBe ExecutionStatus.SUCCESS
    }

    // -------------------------------------------------------------- events

    @Test
    fun `events append in sequence and replay in order`() {
        val execution = running().also(executions::create)

        events.append(execution.executionId, 1, SseEventType.EXECUTION_STARTED, Instant.now(), """{"a":1}""")
        events.append(execution.executionId, 2, SseEventType.NODE_STARTED, Instant.now(), """{"a":2}""")
        events.append(execution.executionId, 3, SseEventType.PIPELINE_COMPLETED, Instant.now(), """{"a":3}""")

        val replayed = events.findByExecution(execution.executionId)
        replayed.map { it.eventId } shouldContainExactly listOf(1, 2, 3)
        replayed.map { it.eventType } shouldContainExactly
            listOf("execution_started", "node_started", "pipeline_completed")
        events.lastEventId(execution.executionId) shouldBe 3
        events.lastEventId(UUID.randomUUID()) shouldBe 0
    }

    @Test
    fun `a repeated sequence number is refused rather than silently dropped`() {
        // A duplicate (execution_id, event_id) means the emitter lost count. Swallowing it would
        // hide that while corrupting replay, so the UNIQUE violation is allowed to escape.
        val execution = running().also(executions::create)
        events.append(execution.executionId, 1, SseEventType.EXECUTION_STARTED, Instant.now(), "{}")

        shouldThrow<DuplicateKeyException> {
            events.append(execution.executionId, 1, SseEventType.NODE_STARTED, Instant.now(), "{}")
        }
    }

    @Test
    fun `retention is decided per completed execution, never per event`() {
        // F3: deleting on the EVENT's own timestamp opened a front-gap — a long-running execution
        // lost its early events and kept its late ones, so a replay began mid-stream with no
        // execution_started and no way to distinguish that from a real gap.
        val old = running().also(executions::create)
        events.append(old.executionId, 1, SseEventType.EXECUTION_STARTED, Instant.now().minus(10, ChronoUnit.DAYS), "{}")
        events.append(old.executionId, 2, SseEventType.PIPELINE_COMPLETED, Instant.now().minus(9, ChronoUnit.DAYS), "{}")
        executions.complete(old.executionId, ExecutionStatus.SUCCESS, Instant.now().minus(9, ChronoUnit.DAYS), 5, NODE_STATS_JSON)

        // Same age of early event, but the execution never completed: it keeps everything.
        val stuck = running().also(executions::create)
        events.append(stuck.executionId, 1, SseEventType.EXECUTION_STARTED, Instant.now().minus(10, ChronoUnit.DAYS), "{}")
        events.append(stuck.executionId, 2, SseEventType.NODE_STARTED, Instant.now(), "{}")

        // Completed recently: nothing of its is old enough to go.
        val recent = running().also(executions::create)
        events.append(recent.executionId, 1, SseEventType.EXECUTION_STARTED, Instant.now().minus(10, ChronoUnit.DAYS), "{}")
        executions.complete(recent.executionId, ExecutionStatus.SUCCESS, Instant.now(), 5, NODE_STATS_JSON)

        events.deleteOlderThan(Instant.now().minus(7, ChronoUnit.DAYS)) shouldBe 2

        events.findByExecution(old.executionId).shouldBeEmpty()
        events.findByExecution(stuck.executionId).map { it.eventId } shouldContainExactly listOf(1, 2)
        events.findByExecution(recent.executionId).map { it.eventId } shouldContainExactly listOf(1)
    }

    // ------------------------------------------------------------- fixtures

    private fun running(): ExecutionRecord =
        ExecutionRecord(
            executionId = UUID.randomUUID(),
            pipelineId = pipelineId,
            pipelineVersion = 1,
            status = ExecutionStatus.RUNNING,
            parametersJson = """{"start_date": "2026-01-01"}""",
            triggeredBy = userId,
            triggeredVia = ExecutionTrigger.REST,
            correlationId = UUID.randomUUID(),
        )

    private fun insertUser(): UUID =
        UUID.randomUUID().also { id ->
            jdbc.update(
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject)
                VALUES (:id, :email, 'Test User', 'google', :sub)
                """.trimIndent(),
                mapOf("id" to id, "email" to "u$id@example.com", "sub" to "sub-$id"),
            )
        }

    private fun insertPipelineWithVersion(owner: UUID): UUID =
        UUID.randomUUID().also { id ->
            jdbc.update(
                """
                INSERT INTO pipelines (id, name, display_name, owner_id, current_version)
                VALUES (:id, :name, 'Test Pipeline', :owner, 1)
                """.trimIndent(),
                mapOf("id" to id, "name" to "p_${id.toString().replace("-", "")}", "owner" to owner),
            )
            jdbc.update(
                """
                INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by)
                VALUES (:id, 1, CAST('{}' AS jsonb), :owner)
                """.trimIndent(),
                mapOf("id" to id, "owner" to owner),
            )
        }

    private fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    private companion object {
        const val SPACING_MS = 5L
        const val NODE_STATS_JSON = """[{"node_id":"a","status":"SUCCESS"}]"""

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
