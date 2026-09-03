package co.datapipelines.web

import co.datapipelines.events.DataReady
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.NodeStats
import co.datapipelines.executor.NodeStatus
import co.datapipelines.executor.RedisResultStore
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.config.RateLimitProperties
import co.datapipelines.web.executions.ResultCursor
import co.datapipelines.web.metrics.WebMetrics
import co.datapipelines.web.ratelimit.RedisRateLimiter
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.WebEventEmitter
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * The web surface's persistence story against the real stores: a Postgres container running app's
 * shipped migrations and a Redis container — the same rig `dag`'s integration tests
 * use. Covered end to end:
 *
 *  - [WebEventEmitter]: a full event sequence lands the `pipeline_executions` row (RUNNING →
 *    terminal UPDATE), the durable `execution_events` rows with monotonic ids, and the 1-hour
 *    Redis replay log — with the correlation id on every stored payload (carry-forward #1).
 *  - [SseEventLog]: replay order and content.
 *  - [RedisRateLimiter]: the shared per-user counters really are Redis-backed.
 *  - [ResultCursor] over a real [RedisResultStore]: the stored result pages through
 *    `ResultStore.keyFor` (carry-forward #7).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebPersistenceIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executions: ExecutionRepository
    private lateinit var events: ExecutionEventRepository
    private val redis by lazy { TestRedis.template() }
    private val eventLog by lazy { SseEventLog(redis, co.datapipelines.executor.ExecutorJson.mapper) }

    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID

    /** Binds the JDBC template to the module's shared, already-migrated container. */
    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        // (The shared container's migrations include the §4.6 lineage columns (V3)
        // ExecutionRepository.create now writes.)
    }

    @BeforeEach
    fun setUp() {
        executions = ExecutionRepository(jdbc)
        events = ExecutionEventRepository(jdbc)
        // The CASCADE also reaches workspaces (created_by), so the V4-seeded `default`
        // workspace is re-seeded after every truncate.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('$DEFAULT_WORKSPACE_ID', 'default', 'Default')",
        )
        TestRedis.flush(redis)
        userId =
            UUID.randomUUID().also { id ->
                jdbc.update(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
                    mapOf("id" to id, "email" to "u$id@example.com", "sub" to "sub-$id"),
                )
            }
        pipelineId =
            UUID.randomUUID().also { id ->
                jdbc.update(
                    """
                    INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)
                    VALUES (:id, :name, 'P', :owner, 1, '$DEFAULT_WORKSPACE_ID')
                    """.trimIndent(),
                    mapOf("id" to id, "name" to "p_${id.toString().replace("-", "")}", "owner" to userId),
                )
                jdbc.update(
                    """
                    INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                    VALUES (:id, 1, CAST('{}' AS jsonb), 'seed-hash', 'RELEASED', :owner, :owner, NOW())
                    """.trimIndent(),
                    mapOf("id" to id, "owner" to userId),
                )
            }
    }

    @Test
    fun `a full event sequence persists the row, the events and the replay log`() =
        runTest {
            val executionId = UUID.randomUUID()
            val correlationId = UUID.randomUUID()
            val emitter =
                WebEventEmitter(
                    context =
                        ExecutionContext(
                            pipelineId = pipelineId,
                            pipelineVersion = 1,
                            userId = userId,
                            correlationId = correlationId,
                            triggeredVia = ExecutionTrigger.REST,
                            parametersJson = "{}",
                            workspaceId = DEFAULT_WORKSPACE_ID,
                        ),
                    stream = null,
                    streams = mockkRegistry(),
                    eventLog = eventLog,
                    eventRepository = events,
                    executionRepository = executions,
                    persistenceDispatcher = Dispatchers.Default,
                )
            val started = Instant.parse("2026-08-05T14:30:00Z")
            val stats =
                NodeStats("n1", NodeStatus.SUCCESS, started, started.plusMillis(900), 900, 10, 100)

            emitter.emit(ExecutionStarted(executionId, pipelineId, 1, emptyMap(), startedAt = started))
            emitter.emit(NodeStarted(executionId, "n1", started))
            emitter.emit(NodeCompleted(executionId, "n1", stats))
            emitter.emit(PipelineCompleted(executionId, pipelineId, 1, started, started.plusMillis(900), 900, listOf(stats)))
            emitter.emit(
                DataReady(executionId, pipelineId, emptyList(), emptyList(), 0, false, "http://x/result", started, 300),
            )

            val row = executions.findById(DEFAULT_WORKSPACE_ID, executionId).shouldNotBeNull()
            row.status shouldBe ExecutionStatus.SUCCESS
            row.durationMs shouldBe 900L
            row.triggeredVia shouldBe ExecutionTrigger.REST
            row.correlationId shouldBe correlationId

            val stored = events.findByExecution(executionId)
            stored shouldHaveSize 5
            stored.map { it.eventId } shouldBe listOf(1, 2, 3, 4, 5)
            stored.forEach {
                // jsonb::TEXT is Postgres-normalized, so parse rather than substring-match.
                co.datapipelines.executor.ExecutorJson.mapper
                    .readTree(it.payloadJson)
                    .get("correlation_id")
                    .asText() shouldBe correlationId.toString()
            }

            val replayed = eventLog.replay(executionId).shouldNotBeNull()
            replayed.map { it.eventName } shouldBe
                listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
            replayed.map { it.eventId } shouldBe listOf(1, 2, 3, 4, 5)
        }

    @Test
    fun `the rate limiter holds its counts in Redis`() {
        // Real Redis, pinned clock: the fixed window must not roll over mid-test.
        val pinned = Instant.ofEpochSecond(1_700_000_000)
        val limiter = RedisRateLimiter(redis, RateLimitProperties(requestsPerSecond = 2, requestsPerMinute = 1000)) { pinned }
        val user = UUID.randomUUID()

        limiter.consume(user).allowed shouldBe true
        limiter.consume(user).allowed shouldBe true
        val third = limiter.consume(user)
        third.allowed shouldBe false
        third.limit shouldBe 2L
        val retryAfter: Long = third.retryAfterSeconds
        (retryAfter > 0L) shouldBe true
    }

    @Test
    fun `the cursor reads a real stored result through keyFor`() {
        val store: ResultStore = RedisResultStore(redis, ResultConfig(pageSizeRows = 2, pageMaxRows = 10))
        val executionId = UUID.randomUUID()
        runBlocking { store.materialize(executionId, h2Rows(1, 5), Dialect.H2, 300) }

        // The execution row the cursor's ownership/status gate reads.
        executions.create(
            co.datapipelines.executor.ExecutionRecord(
                executionId = executionId,
                pipelineId = pipelineId,
                pipelineVersion = 1,
                status = ExecutionStatus.RUNNING,
                parametersJson = "{}",
                triggeredBy = userId,
                triggeredVia = ExecutionTrigger.REST,
            ),
        )
        executions.complete(executionId, ExecutionStatus.SUCCESS, Instant.now(), 1L, "[]", null, null, 5L, 100L)

        val cursor = ResultCursor(executions, store, ResultConfig(pageSizeRows = 2, pageMaxRows = 10), WebMetrics(SimpleMeterRegistry()))
        val principal =
            co.datapipelines.auth.AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                setOf(co.datapipelines.auth.Scope.READ),
                co.datapipelines.auth.AuthMethod.API_KEY,
                "dpk_x",
                workspace =
                    co.datapipelines.auth
                        .WorkspaceContext(DEFAULT_WORKSPACE_ID, "default"),
            )

        val page = cursor.jsonPage(cursor.readable(executionId, principal), 0L, null)
        page["row_count"] shouldBe 2
        page["total_rows"] shouldBe 5L
        page["has_more"] shouldBe true

        val out = java.io.ByteArrayOutputStream()
        cursor.writeCsv(cursor.readable(executionId, principal), out)
        val lines = out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        lines.size shouldBe 6 // header + 5 rows
        lines[0] shouldBe "n,label,big"
    }

    /** A real forward-only H2 cursor — the fixture shape dag's result-store test uses. */
    private fun h2Rows(
        from: Int,
        to: Int,
    ): ResultSet =
        DriverManager
            .getConnection("jdbc:h2:mem:web_it_${UUID.randomUUID().toString().replace("-", "")}")
            .createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
            .executeQuery(
                """SELECT CAST("X" AS INT) AS "n", CONCAT('r', "X") AS "label", "X" AS "big"
                   FROM SYSTEM_RANGE($from, $to) ORDER BY "X"""",
            )

    private fun mockkRegistry(): co.datapipelines.web.sse.ExecutionStreamRegistry =
        co.datapipelines.web.sse.ExecutionStreamRegistry(
            co.datapipelines.web.config
                .SseProperties(),
            co.datapipelines.executor.ExecutionCancellationService(
                co.datapipelines.executor.InMemoryCancellationRegistry(),
                co.datapipelines.executor.RedisCancellationFlags(redis),
                co.datapipelines.executor.ExecutorConfig(),
            ),
            co.datapipelines.executor.ExecutorJson.mapper,
        )

    private companion object {
        /** The V4-seeded `default` workspace the pipeline fixture and every repository read are scoped to. */
        val DEFAULT_WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

    }
}
