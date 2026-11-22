package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.events.DataReady
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #1035 — what the EXECUTOR does at the root's commit boundary, over a real wired run (real H2 behind a
 * POSTGRES-dialect registration, the production [LocalWritePlanner]): a refused commit ends OUTCOME_UNKNOWN
 * and exposes nothing; a confirmed commit whose result cannot be delivered stays SUCCESS and is marked
 * `result_unavailable`; a failure before the boundary leaves no stamp at all.
 */
class PipelineCommitSettlementTest {
    private val calls = CopyOnWriteArrayList<String>()
    private val resultUnavailableMarks = CopyOnWriteArrayList<UUID>()

    /** The run's real result store; [loseResultOnConfirm] removes the stored result the moment the confirmation is durable. */
    private val store = InMemoryResultStore()

    @Volatile
    private var loseResultOnConfirm = false

    private val ledger =
        object : CommitLedger {
            override fun recordAttempt(executionId: UUID): Boolean {
                calls += "attempt"
                return true
            }

            override fun recordConfirmed(executionId: UUID): Boolean {
                calls += "confirmed"
                // #1035 R4: the stored caller result is lost AFTER the confirmation is recorded and before anything announces it.
                if (loseResultOnConfirm) {
                    check(store.describe(store.keyFor(executionId)) != null) { "the result must be stored before the loss" }
                    store.discard(store.keyFor(executionId))
                }
                return true
            }

            override fun recordResultUnavailable(executionId: UUID): Boolean {
                calls += "result_unavailable"
                resultUnavailableMarks += executionId
                return true
            }
        }

    private fun source(): Datasource = h2Datasource("commit_settle", listOf("CREATE TABLE tgt (id INT)")).copy(dialect = Dialect.POSTGRES)

    /** The real registry, except `commit` on the leased connection (when [refuseCommit]) refuses. */
    private fun registry(
        source: Datasource,
        refuseCommit: Boolean,
    ): DatasourceRegistry {
        val base = FakeDatasourceRegistry(mapOf(source.name to source))
        return object : DatasourceRegistry by base {
            override fun poolFor(datasource: Datasource): ConnectionPool =
                object : ConnectionPool {
                    override val name get() = datasource.name

                    override fun leaseConnection(): Connection {
                        val delegate = DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.secret ?: "")
                        return if (!refuseCommit) {
                            delegate
                        } else {
                            object : Connection by delegate {
                                override fun commit(): Unit = throw SQLException("commit refused (test)")
                            }
                        }
                    }

                    override fun close() = Unit
                }
        }
    }

    private fun harness(
        source: Datasource,
        refuseCommit: Boolean = false,
        failDataReady: Boolean = false,
    ): ExecutorHarness {
        val registry = registry(source, refuseCommit)
        return ExecutorHarness(
            resultStore = store,
            templateEngine =
                Fixtures.templateEngine(
                    mapOf(
                        "write" to "INSERT INTO tgt VALUES (1)",
                        "read" to "SELECT id FROM tgt",
                        "boom" to "SELECT id FROM table_that_does_not_exist",
                    ),
                ),
            registry = registry,
            localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8, ledger),
            emitterFailsOn = { event -> if (failDataReady && event is DataReady) IllegalStateException("stream gone (test)") else null },
        )
    }

    private fun rows(source: Datasource): Long =
        DriverManager.getConnection(source.jdbcUrl, source.username, source.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tgt").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun familyNodes(source: Datasource) =
        listOf(
            Fixtures.node("write", NodeType.DML, source.name),
            Fixtures.node("read", source = source.name, dependsOn = listOf("write")),
        )

    @Test
    fun `a refused commit ends OUTCOME_UNKNOWN - no success event, no result, a failure naming the commit`() {
        runBlocking {
            val source = source()
            harness(source, refuseCommit = true).use { harness ->
                val thrown =
                    shouldThrow<DatapipelinesException> {
                        harness.executor.execute(Fixtures.request(Fixtures.pipeline(familyNodes(source))))
                    }

                thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED
                val types = harness.emitter.types()
                types shouldNotContain SseEventType.PIPELINE_COMPLETED
                types shouldNotContain SseEventType.DATA_READY
                val failed = harness.emitter.allOf<PipelineFailed>().single()
                failed.status shouldBe ExecutionStatus.OUTCOME_UNKNOWN
                failed.error.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED
                failed.settlesFamily shouldBe true
                calls.toList() shouldBe listOf("attempt")
                rows(source) shouldBe 0L
            }
        }
    }

    @Test
    fun `a confirmed commit publishes success and the held result only after the confirmation`() {
        runBlocking {
            val source = source()
            harness(source).use { harness ->
                val result = harness.executor.execute(Fixtures.request(Fixtures.pipeline(familyNodes(source))))

                result.status shouldBe ExecutionStatus.SUCCESS
                calls.toList() shouldBe listOf("attempt", "confirmed")
                val types = harness.emitter.types()
                val completedAt = types.indexOf(SseEventType.PIPELINE_COMPLETED)
                val dataReadyAt = types.indexOf(SseEventType.DATA_READY)
                (completedAt in 0 until dataReadyAt) shouldBe true
                val completed = harness.emitter.allOf<PipelineCompleted>().single()
                completed.settlesFamily shouldBe true
                completed.resultUnavailable shouldBe false
                completed.resultRowCount shouldBe 1L
                result.resultUnavailable shouldBe false
                harness.emitter
                    .allOf<DataReady>()
                    .single()
                    .totalRows shouldBe 1L
                rows(source) shouldBe 1L
            }
        }
    }

    @Test
    fun `a stored result lost after the confirmed commit is SUCCESS with the result unavailable - never announced or empty`() {
        runBlocking {
            val source = source()
            loseResultOnConfirm = true
            harness(source).use { harness ->
                val result = harness.executor.execute(Fixtures.request(Fixtures.pipeline(familyNodes(source))))

                result.status shouldBe ExecutionStatus.SUCCESS
                result.resultUnavailable shouldBe true
                harness.emitter.types() shouldNotContain SseEventType.DATA_READY
                val completed = harness.emitter.allOf<PipelineCompleted>().single()
                // The terminal row itself says so, and keeps the prepared result's count: it cannot read as a zero-caller run.
                completed.resultUnavailable shouldBe true
                completed.resultRowCount shouldBe 1L
                calls.toList() shouldBe listOf("attempt", "confirmed", "result_unavailable")
                rows(source) shouldBe 1L
            }
        }
    }

    @Test
    fun `a confirmed commit with no caller node completes with stats only - not marked unavailable`() {
        runBlocking {
            val source = source()
            harness(source).use { harness ->
                val nodes = listOf(Fixtures.node("write", NodeType.DML, source.name))

                val result = harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                result.status shouldBe ExecutionStatus.SUCCESS
                result.resultRef shouldBe null
                result.resultUnavailable shouldBe false
                harness.emitter.types() shouldNotContain SseEventType.DATA_READY
                val completed = harness.emitter.allOf<PipelineCompleted>().single()
                completed.resultUnavailable shouldBe false
                completed.resultRowCount shouldBe null
                completed.nodeStats.map { it.nodeId } shouldContain "write"
                calls.toList() shouldBe listOf("attempt", "confirmed")
                rows(source) shouldBe 1L
            }
        }
    }

    @Test
    fun `a result that cannot be delivered after the confirmed commit stays SUCCESS and is marked unavailable`() {
        runBlocking {
            val source = source()
            harness(source, failDataReady = true).use { harness ->
                val request = Fixtures.request(Fixtures.pipeline(familyNodes(source)))

                val result = harness.executor.execute(request)

                result.status shouldBe ExecutionStatus.SUCCESS
                result.resultUnavailable shouldBe true
                harness.emitter.types() shouldContain SseEventType.PIPELINE_COMPLETED
                harness.emitter.types() shouldNotContain SseEventType.DATA_READY
                calls.toList() shouldBe listOf("attempt", "confirmed", "result_unavailable")
                resultUnavailableMarks.size shouldBe 1
                rows(source) shouldBe 1L
            }
        }
    }

    @Test
    fun `a failure before the commit boundary stamps nothing and rolls the family write back`() {
        runBlocking {
            val source = source()
            harness(source).use { harness ->
                val nodes =
                    listOf(
                        Fixtures.node("write", NodeType.DML, source.name),
                        Fixtures.node("boom", source = source.name, dependsOn = listOf("write")),
                    )

                shouldThrow<PipelineExecutionFailed> {
                    harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }

                calls.toList() shouldBe emptyList()
                val failed = harness.emitter.allOf<PipelineFailed>().single()
                failed.status shouldBe ExecutionStatus.FAILED
                rows(source) shouldBe 0L
            }
        }
    }
}
