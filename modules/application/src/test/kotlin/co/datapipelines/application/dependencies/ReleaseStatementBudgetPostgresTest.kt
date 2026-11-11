package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ResolvedNode
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * #786 R3, landing review r4 (record §25.4) on a REAL PostgreSQL: the statement bound is recomputed for EACH statement,
 * not once per source read. The round-4 ceiling set `min(statement limit, remaining deadline)` once around a whole source
 * call, so the pipeline source's second and third statements kept the budget measured before its first (the reviewer's
 * probe: `expected:<1s> but was:<2s>`).
 *
 * [ObservedStatements] records the `statement_timeout` each REAL repository statement executes under and advances the
 * walk's injected clock after it, as if the statement had taken that long. The transaction manager, the ceiling and the
 * repositories all share the observing data source — one connection per transaction, as in production.
 */
class ReleaseStatementBudgetPostgresTest {
    private val clock = ManualClock()
    private var elapse: (statement: Int) -> Duration = { Duration.ZERO }
    private var onStatement: (statement: Int) -> Unit = {}
    private var executed = 0
    private val statements =
        ObservedStatements(SharedPostgres.dataSource()) {
            executed += 1
            clock.now = clock.now.plus(elapse(executed))
            onStatement(executed)
        }
    private val jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    private val manager = DataSourceTransactionManager(statements.dataSource)
    private val ceiling = PostgresStatementCeiling(statements.dataSource)
    private val holder = TableLock()
    private var userId: UUID = UUID.randomUUID()
    private var workspaceId: UUID = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to workspaceId, "name" to "w_${workspaceId.toString().replace("-", "")}", "owner" to userId),
        )
        val pipelineId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, NULL, :ws)",
            mapOf("id" to pipelineId, "name" to PIPELINE, "owner" to userId, "ws" to workspaceId),
        )
        jdbc.update(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by)" +
                " VALUES (:id, 1, CAST(:body AS jsonb), 'hp1', 'DRAFT', :owner)",
            mapOf("id" to pipelineId, "body" to PipelineSerializer().write(body()), "owner" to userId),
        )
        val endpointId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO published_endpoints (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, created_by)" +
                " VALUES (:id, :ws, :path, :pipeline, 30, :owner)",
            mapOf("id" to endpointId, "ws" to workspaceId, "path" to PATH, "pipeline" to pipelineId, "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO published_endpoint_versions (endpoint_id, version, status, pipeline_name, pipeline_version, timeout_seconds," +
                " body_hash, created_by) VALUES (:id, 1, 'DRAFT', :pipeline, 1, 30, 'h1', :owner)",
            mapOf("id" to endpointId, "pipeline" to PIPELINE, "owner" to userId),
        )
        statements.observed.clear()
        executed = 0
    }

    @AfterEach
    fun release() {
        holder.release()
    }

    @Test
    fun `the pipeline source's later statements run under the budget left when EACH starts - the review r4 probe`() {
        elapse = { statement -> if (statement == 1) Duration.ofSeconds(1) else Duration.ZERO }

        checkNotNull(walk(budget = Duration.ofSeconds(2), limit = Duration.ofSeconds(30)).plan(pipelineRoot(), userId))

        withClue("three real statements (record, version detail, body); one second spent after the first") {
            statements.timeouts() shouldContainExactly listOf("2s", "1s", "1s")
        }
    }

    @Test
    fun `control - with no time spent every statement keeps the whole budget`() {
        checkNotNull(walk(budget = Duration.ofSeconds(2), limit = Duration.ofSeconds(30)).plan(pipelineRoot(), userId))

        statements.timeouts() shouldContainExactly listOf("2s", "2s", "2s")
    }

    @Test
    fun `each statement gets min(statement limit, remaining deadline) - the limit first, then the deadline`() {
        elapse = { statement -> if (statement <= 2) Duration.ofSeconds(4) else Duration.ZERO }

        checkNotNull(walk(budget = Duration.ofSeconds(10), limit = Duration.ofSeconds(3)).plan(pipelineRoot(), userId))

        // 10 s left: the 3 s limit; 6 s left: the limit; 2 s left: the deadline (the template batch then still has 2 s).
        statements.timeouts() shouldContainExactly listOf("3s", "3s", "2s")
    }

    @Test
    fun `across families - the API statement, then each pipeline statement, read the budget left at its own start`() {
        elapse = { Duration.ofSeconds(1) }

        checkNotNull(walk(budget = Duration.ofSeconds(5), limit = Duration.ofSeconds(30)).plan(apiRoot(), userId))

        statements.timeouts() shouldContainExactly listOf("5s", "4s", "3s", "2s")
    }

    @Test
    fun `a LATER statement waiting on a lock is cancelled at the budget left - not at the budget measured before the first`() {
        // After the first statement 9 of 10 seconds are spent and another session locks the table the second one reads.
        lockAfterFirst(spent = Duration.ofSeconds(9))

        val started = System.nanoTime()
        val refusal =
            shouldThrow<DatapipelinesException> {
                walk(budget = Duration.ofSeconds(10), limit = Duration.ofSeconds(30)).plan(pipelineRoot(), userId)
            }
        val took = Duration.ofNanos(System.nanoTime() - started)

        refusal.code shouldBe "pipeline.release.metadata_unavailable"
        withClue("the database cancelled the second statement (57014) at its 1 s bound, long before the 10 s one") {
            PostgresStatementCeiling.cancelled(refusal) shouldBe true
            statements.timeouts() shouldContainExactly listOf("10s", "1s")
            took shouldBeLessThan Duration.ofSeconds(4)
        }
    }

    @Test
    fun `a later statement whose budget is spent is never issued - refused before it, never a zero (unbounded) timeout`() {
        lockAfterFirst(spent = Duration.ofSeconds(3))

        val started = System.nanoTime()
        val refusal =
            shouldThrow<DatapipelinesException> {
                walk(budget = Duration.ofSeconds(2), limit = Duration.ofSeconds(30)).plan(pipelineRoot(), userId)
            }
        val took = Duration.ofNanos(System.nanoTime() - started)

        refusal.code shouldBe "pipeline.release.metadata_unavailable"
        withClue("the second statement was not executed: one observed statement, no cancellation, no wait on the lock") {
            statements.observed shouldHaveSize 1
            causes(refusal).any { it is StatementBudgetExhausted } shouldBe true
            PostgresStatementCeiling.cancelled(refusal) shouldBe false
            took shouldBeLessThan Duration.ofSeconds(2)
        }
    }

    @Test
    fun `in a guarded caller's transaction a cancelled later statement rolls back the caller's write and returns no plan`() {
        // Statement 1 is the caller's own write; statement 2 the pipeline source's first; statement 3 waits on the lock.
        lockAfter(statement = 2, spent = Duration.ofSeconds(9))
        val marker = UUID.randomUUID()
        val walk = walk(budget = Duration.ofSeconds(10), limit = Duration.ofSeconds(30))
        var closure: ReleaseClosure? = null

        val refusal =
            shouldThrow<DatapipelinesException> {
                TransactionTemplate(manager).executeWithoutResult {
                    NamedParameterJdbcTemplate(statements.dataSource).update(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :e, 'M', 'google', :s)",
                        mapOf("id" to marker, "e" to "m$marker@example.com", "s" to "m-$marker"),
                    )
                    closure = walk.walkInCurrentTransaction(pipelineRoot(), userId)
                }
            }

        refusal.code shouldBe "pipeline.release.metadata_unavailable"
        PostgresStatementCeiling.cancelled(refusal) shouldBe true
        statements.timeouts().drop(1) shouldContainExactly listOf("10s", "1s")
        withClue("no partial plan and no partial effect") {
            closure shouldBe null
            jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :id", mapOf("id" to marker), Int::class.java) shouldBe 0
        }
    }

    @Test
    fun `a returning multi-statement walk leaves the caller's setting, and no setting outlives the planning transaction`() {
        elapse = { Duration.ofSeconds(1) }
        val outside = jdbc.jdbcTemplate.queryForObject("SHOW statement_timeout", String::class.java)

        checkNotNull(walk(budget = Duration.ofSeconds(5), limit = Duration.ofSeconds(30)).plan(apiRoot(), userId))
        jdbc.jdbcTemplate.queryForObject("SHOW statement_timeout", String::class.java) shouldBe outside

        val caller = NamedParameterJdbcTemplate(statements.dataSource).jdbcTemplate
        val walk = walk(budget = Duration.ofSeconds(5), limit = Duration.ofSeconds(30))
        val after =
            TransactionTemplate(manager).execute {
                caller.execute("SET LOCAL statement_timeout = '45s'")
                checkNotNull(walk.walkInCurrentTransaction(apiRoot(), userId))
                caller.queryForObject("SHOW statement_timeout", String::class.java)
            }
        withClue("the per-statement bounds of four statements ran, and the caller's own value came back") {
            statements.observed shouldHaveSize 8
            after shouldBe "45s"
        }
    }

    /** After the first observed statement: [spent] passes on the walk's clock and another session locks `pipeline_versions`. */
    private fun lockAfterFirst(spent: Duration) = lockAfter(statement = 1, spent = spent)

    /** After observed statement number [statement]: [spent] passes on the walk's clock, another session locks `pipeline_versions`. */
    private fun lockAfter(
        statement: Int,
        spent: Duration,
    ) {
        elapse = { executed -> if (executed == statement) spent else Duration.ZERO }
        onStatement = { executed -> if (executed == statement) holder.lock("pipeline_versions") }
    }

    private fun causes(failure: Throwable): List<Throwable> =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.toList()

    /** The production walk over the observing data source: real pipeline and API sources, the template a released leaf. */
    private fun walk(
        budget: Duration,
        limit: Duration,
    ): ReleasePlanningWalk {
        val sources =
            mapOf(
                ReleaseFamily.ENDPOINT to EndpointDependencySource(PublishedEndpointVersionRepository(ceiling.jdbc)),
                ReleaseFamily.PIPELINE to PipelineDependencySource(PipelineRepository(ceiling.jdbc)),
                ReleaseFamily.TEMPLATE to DependencySource { identity -> leaf(identity) },
            )
        return ReleasePlanningWalk(
            ReleaseDependencyFamilies.registry(sources, ceiling),
            ReleaseBounds(),
            ReleasePlanningWalk.planningTransaction(manager),
            ReleasePlanningWalk.speculativeReads(manager),
            ReleasePlanningDeadline.after(budget, clock),
            clock = clock,
            planning = ReleasePlanningLimits(statementTimeout = limit),
        )
    }

    private fun pipelineRoot() = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PIPELINE, PIPELINE, 1)

    private fun apiRoot() = ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, PATH, 1)

    private fun leaf(identity: ReleaseNodeIdentity) =
        ResolvedNode(identity, identity.entityId, ReleaseNodeStatus.RELEASED, "ht", identity.version, emptyList(), UUID.randomUUID())

    private fun body() =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = PIPELINE,
            displayName = "P",
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes =
                listOf(
                    Node(
                        id = "rides",
                        description = "",
                        type = NodeType.DQL,
                        source = "pg",
                        template = TemplateRef(TEMPLATE, 1),
                        output = NodeOutput.Caller,
                        dependsOn = emptyList(),
                    ),
                ),
        )

    /** Another session's ACCESS EXCLUSIVE lock, let go after [HOLD] on its own so an unbounded wait ends (late). */
    private class TableLock {
        private var connection: Connection? = null
        private var timer: Thread? = null

        fun lock(table: String) {
            val holder = SharedPostgres.dataSource().connection
            holder.autoCommit = false
            holder.createStatement().use { it.execute("LOCK TABLE $table IN ACCESS EXCLUSIVE MODE") }
            connection = holder
            timer =
                Thread {
                    try {
                        Thread.sleep(HOLD.toMillis())
                        release()
                    } catch (_: InterruptedException) {
                        // The test ended first and released the lock itself.
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
        }

        @Synchronized
        fun release() {
            connection?.let {
                it.rollback()
                it.close()
            }
            connection = null
            timer?.takeIf { it !== Thread.currentThread() }?.interrupt()
        }
    }

    private class ManualClock : Clock() {
        @Volatile var now: Instant = Instant.parse("2026-11-11T00:00:00Z")

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    private companion object {
        const val PIPELINE = "acme/rides/daily"
        const val PATH = "/acme/v1/rides"
        const val TEMPLATE = "acme/rides/rides.sql"

        /** How long the lock holder keeps the table; a correctly bounded statement is cancelled well before it lets go. */
        val HOLD: Duration = Duration.ofSeconds(8)
    }
}
