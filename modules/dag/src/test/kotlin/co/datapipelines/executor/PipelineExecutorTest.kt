package co.datapipelines.executor

import co.datapipelines.events.DataReady
import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.NodeFailed
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingEngine
import co.datapipelines.staging.StagingFactory
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `PipelineExecutor` end to end over a real staging database and real H2 "datasources"
 * (dag-executor.md §5, §10 emission rules, §14).
 *
 * Only Redis is substituted ([InMemoryResultStore]); the DAG, the coroutines, the semaphores, the
 * staging mutex and the JDBC drivers are all real, which is the only way the ordering and
 * exactly-once claims below mean anything.
 */
class PipelineExecutorTest {
    @Test
    fun `a diamond pipeline succeeds and emits the documented event sequence`() =
        runBlocking<Unit> {
            val source = h2Datasource("d_src", listOf("CREATE TABLE src (n INT)", "INSERT INTO src VALUES (1), (2), (3)"))
            val nodes =
                listOf(
                    Fixtures.node("fetch", source = "d_src", output = NodeOutput.Tempdb("stg")),
                    Fixtures.node("left", output = NodeOutput.Tempdb("l"), dependsOn = listOf("fetch")),
                    Fixtures.node("right", output = NodeOutput.Tempdb("r"), dependsOn = listOf("fetch")),
                    Fixtures.node("report", output = NodeOutput.Caller, dependsOn = listOf("left", "right")),
                )
            val sql =
                mapOf(
                    "fetch" to "SELECT n FROM src",
                    // Every staged identifier is quoted — see StagedIdentifierCaseFoldingTest for
                    // why unquoted references to staged tables/columns do not resolve today.
                    "left" to """SELECT "n" FROM "stg" WHERE "n" < 3""",
                    "right" to """SELECT "n" FROM "stg" WHERE "n" > 1""",
                    "report" to """SELECT COUNT(*) AS c FROM "l" UNION ALL SELECT COUNT(*) FROM "r" """,
                )

            harness(sql, registry = FakeDatasourceRegistry(mapOf("d_src" to source))).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                result.status shouldBe ExecutionStatus.SUCCESS
                result.nodeStats.map { it.nodeId } shouldContainExactly listOf("fetch", "left", "right", "report")
                result.nodeStats.all { it.status == NodeStatus.SUCCESS } shouldBe true

                // §10: one execution_started, one node_started per node, one terminal, then data_ready.
                h.emitter.count(SseEventType.EXECUTION_STARTED) shouldBe 1
                h.emitter.types().first() shouldBe SseEventType.EXECUTION_STARTED
                // Exactly one node_started per node; `left`/`right` race, so only the DAG's
                // dependency order is asserted — not an arbitrary interleaving of the two.
                val started = h.emitter.allOf<NodeStarted>().map { it.nodeId }
                started.toSet() shouldBe setOf("fetch", "left", "right", "report")
                started.size shouldBe 4
                started.first() shouldBe "fetch"
                started.last() shouldBe "report"
                h.emitter.count(SseEventType.NODE_COMPLETED) shouldBe 4
                h.emitter.count(SseEventType.NODE_FAILED) shouldBe 0
                h.emitter.count(SseEventType.PIPELINE_COMPLETED) shouldBe 1
                h.emitter.count(SseEventType.DATA_READY) shouldBe 1
                h.emitter.types().last() shouldBe SseEventType.DATA_READY
            }
        }

    @Test
    fun `rows_out on a tempdb stage node is the staged row count, not the DDL zero`() =
        runBlocking<Unit> {
            // Regression: `executeUpdate` on `CREATE TABLE … AS …` returns 0 on H2, so the
            // tempdb→tempdb node reported rows_out = 0 for every staged table until the count
            // became an explicit second statement (NodeRunner.tempdbCreateTableAs).
            val nodes =
                listOf(
                    Fixtures.node("seed", type = NodeType.DDL),
                    Fixtures.node("fill", type = NodeType.DML, dependsOn = listOf("seed")),
                    Fixtures.node("stage", output = NodeOutput.Tempdb("staged"), dependsOn = listOf("fill")),
                )
            val sql =
                mapOf(
                    "seed" to """CREATE TABLE "raw" (n INT)""",
                    "fill" to """INSERT INTO "raw" VALUES (1), (2), (3), (4)""",
                    "stage" to """SELECT n FROM "raw" WHERE n > 1""",
                )

            harness(sql).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                result.status shouldBe ExecutionStatus.SUCCESS
                result.nodeStats.single { it.nodeId == "stage" }.rowsOut shouldBe 3
                result.nodeStats.single { it.nodeId == "fill" }.rowsOut shouldBe 4
                result.nodeStats.single { it.nodeId == "seed" }.rowsOut shouldBe 0
            }
        }

    @Test
    fun `a zero-caller pipeline completes with stats and no data_ready`() =
        runBlocking<Unit> {
            // §4.1 / D1: a pure write-back/ETL pipeline has no caller node. Legal, not an error.
            val nodes =
                listOf(
                    Fixtures.node("mk", type = NodeType.DDL),
                    Fixtures.node("stage", output = NodeOutput.Tempdb("only"), dependsOn = listOf("mk")),
                )
            val sql = mapOf("mk" to """CREATE TABLE "z" (n INT)""", "stage" to """SELECT n FROM "z" """)

            harness(sql).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                result.status shouldBe ExecutionStatus.SUCCESS
                result.resultRef.shouldBeNull()
                h.emitter.count(SseEventType.DATA_READY) shouldBe 0
                h.emitter.count(SseEventType.PIPELINE_COMPLETED) shouldBe 1
                h.emitter.types().last() shouldBe SseEventType.PIPELINE_COMPLETED
            }
        }

    @Test
    fun `data_ready is built from the stored result, not from the ResultSet`() =
        runBlocking<Unit> {
            val source = h2Datasource("dr", listOf("CREATE TABLE dr (n INT)", "INSERT INTO dr VALUES (5), (6)"))
            val sql = mapOf("caller" to "SELECT n FROM dr")

            harness(sql, registry = FakeDatasourceRegistry(mapOf("dr" to source))).use { h ->
                val nodes = listOf(Fixtures.node("caller", source = "dr"))
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                val ready = h.emitter.firstOf<DataReady>()
                val stored = h.resultStore.describe(result.resultRef.shouldNotBeNull()).shouldNotBeNull()

                ready.rows shouldBe stored.firstPage
                ready.schema shouldBe stored.schema
                ready.totalRows shouldBe 2
                ready.rowCount shouldBe 2
                ready.hasMore shouldBe false
                ready.resultUrl shouldBe "/api/v1/executions/${result.executionId}/result"
            }
        }

    @Test
    fun `a failing node emits node_failed exactly once and never node_completed for it`() =
        runBlocking<Unit> {
            val nodes =
                listOf(
                    Fixtures.node("ok", type = NodeType.DDL),
                    Fixtures.node("boom", output = NodeOutput.Tempdb("t"), dependsOn = listOf("ok")),
                    Fixtures.node("never", output = NodeOutput.Caller, dependsOn = listOf("boom")),
                )
            val sql =
                mapOf(
                    "ok" to """CREATE TABLE "ok" (n INT)""",
                    "boom" to "SELECT * FROM table_that_does_not_exist",
                    "never" to """SELECT n FROM "t" """,
                )

            harness(sql).use { h ->
                val failure =
                    shouldThrow<PipelineExecutionFailed> {
                        h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                    }

                failure.failedNodeId shouldBe "boom"
                failure.errorCode shouldBe PipelineErrorCodes.Node.STAGING_FAILED

                val failed = h.emitter.allOf<NodeFailed>()
                failed.size shouldBe 1
                failed.single().nodeId shouldBe "boom"
                h.emitter.allOf<NodeCompleted>().map { it.nodeId } shouldContainExactly listOf("ok")

                // Exactly one terminal event, and it is pipeline_failed — never pipeline_completed.
                h.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 1
                h.emitter.count(SseEventType.PIPELINE_COMPLETED) shouldBe 0
                h.emitter.count(SseEventType.DATA_READY) shouldBe 0

                // §7.2: a node that never started reports ABORTED while the execution reports FAILED.
                val stats =
                    h.emitter
                        .firstOf<PipelineFailed>()
                        .nodeStats
                        .associateBy { it.nodeId }
                stats.getValue("ok").status shouldBe NodeStatus.SUCCESS
                stats.getValue("boom").status shouldBe NodeStatus.FAILED
                stats.getValue("boom").errorCode shouldBe PipelineErrorCodes.Node.STAGING_FAILED
                stats.getValue("boom").rowsOut shouldBe NodeResult.NOT_MEASURED
                stats.getValue("never").status shouldBe NodeStatus.ABORTED
                stats.getValue("never").startedAt.shouldBeNull()
            }
        }

    @Test
    fun `the execution slot and the cancellation registration are released on both paths`() =
        runBlocking<Unit> {
            harness(mapOf("only" to "SELECT 1 AS n")).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("only")))))

                h.slots.inFlight shouldBe 0
                h.slots.trackedUsers shouldBe 0
                h.cancellations.liveExecutions shouldBe 0
            }

            harness(mapOf("only" to "SELECT * FROM absent_table")).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("only")))))
                }

                h.slots.inFlight shouldBe 0
                h.slots.trackedUsers shouldBe 0
                h.cancellations.liveExecutions shouldBe 0
            }
        }

    @Test
    fun `a successful execution records the completed and total counters`() =
        runBlocking<Unit> {
            harness(mapOf("only" to "SELECT 1 AS n")).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("only")))))

                val completed = h.emitter.firstOf<PipelineCompleted>()
                completed.nodeStats.size shouldBe 1
                (completed.durationMs >= 0) shouldBe true
                (completed.completedAt >= completed.startedAt) shouldBe true
            }
        }

    /**
     * F9: a staging-creation failure reaches the stream instead of escaping bare.
     *
     * `stagingFactory.create` used to run **before** `execution_started` and outside the `try`, so
     * `pipeline.staging.creation_failed` / `engine_unavailable` — both catalogued in §8.2 — escaped
     * with ZERO events on an execution the caller already held an id for. The SSE consumer saw an
     * open stream that never said anything, and the only signal was the synchronous throw.
     */
    @Test
    fun `a staging creation failure emits execution_started then pipeline_failed`() =
        runBlocking<Unit> {
            val harness =
                ExecutorHarness(
                    templateEngine = Fixtures.templateEngine(mapOf("only" to "SELECT 1 AS n")),
                    stagingFactory = FailingStagingFactory,
                )

            harness.use { h ->
                shouldThrow<DatapipelinesException> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("only")))))
                }.code shouldBe PipelineErrorCodes.Staging.CREATION_FAILED

                h.emitter.count(SseEventType.EXECUTION_STARTED) shouldBe 1
                h.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 1
                h.emitter.count(SseEventType.PIPELINE_COMPLETED) shouldBe 0
                h.emitter.count(SseEventType.NODE_STARTED) shouldBe 0
                val failed = h.emitter.firstOf<PipelineFailed>()
                failed.error.code shouldBe PipelineErrorCodes.Staging.CREATION_FAILED
                failed.failedNodeId.shouldBeNull()
                // No node ran, so every node reports ABORTED — and the slot is released.
                failed.nodeStats.single().status shouldBe NodeStatus.ABORTED
                h.slots.inFlight shouldBe 0
                h.slots.trackedUsers shouldBe 0
                h.cancellations.liveExecutions shouldBe 0
            }
        }

    /**
     * F13: many executions at once stay isolated — distinct results, per-execution warnings, no
     * residual slots.
     *
     * Run on a real multi-threaded dispatcher, because per-execution state (the `WarningSink`, the
     * staging instance, the result ref) is exactly the kind of thing that looks fine when a suite
     * only ever runs one execution at a time.
     */
    @Test
    fun `concurrent executions keep their results, warnings and slots to themselves`() =
        runBlocking<Unit> {
            val source = h2Datasource("iso", listOf("CREATE TABLE iso (n INT)", "INSERT INTO iso VALUES (1), (2)"))

            ExecutorHarness(
                // Constant, `returns`-based engine: see Fixtures.constantTemplateEngine — an
                // `answers` mock shared by 12 concurrent executions hangs in MockK, not in the code
                // under test, which is a spectacularly misleading way for a suite to fail.
                templateEngine = Fixtures.constantTemplateEngine("SELECT n FROM iso"),
                registry = FakeDatasourceRegistry(mapOf("iso" to source)),
                config = ExecutorConfig(executionTimeoutSeconds = TEST_TIMEOUT_SECONDS),
            ).use { h ->
                val nodes = listOf(Fixtures.node("caller", source = "iso"))

                // Bounded: a concurrency test that hangs must fail with a diagnostic, not park the
                // whole suite forever waiting for a continuation nobody will resume.
                val results =
                    withTimeout(CONCURRENT_BUDGET_MS) {
                        (1..CONCURRENT_EXECUTIONS)
                            .map {
                                async(Dispatchers.Default) {
                                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                                }
                            }.awaitAll()
                    }

                results.map { it.executionId }.toSet().size shouldBe CONCURRENT_EXECUTIONS
                results.mapNotNull { it.resultRef }.toSet().size shouldBe CONCURRENT_EXECUTIONS
                results.all { it.status == ExecutionStatus.SUCCESS } shouldBe true
                // Warnings are per execution: a sink shared across runs would pool them all here.
                results.all { it.warnings.isEmpty() } shouldBe true
                results.all { it.nodeStats.single().rowsOut == 2L } shouldBe true

                h.slots.inFlight shouldBe 0
                h.slots.trackedUsers shouldBe 0
                h.cancellations.liveExecutions shouldBe 0
            }
        }

    /** A factory that fails the way staging does when it cannot open its database (§3.1). */
    private object FailingStagingFactory : StagingFactory {
        override fun create(
            executionId: UUID,
            engine: StagingEngine,
        ): Staging =
            throw DatapipelinesException(
                code = PipelineErrorCodes.Staging.CREATION_FAILED,
                message = "could not create the staging database for execution $executionId",
                details = mapOf("execution_id" to executionId.toString()),
            )
    }

    private fun harness(
        sqlByTemplateId: Map<String, String>,
        registry: FakeDatasourceRegistry = FakeDatasourceRegistry(emptyMap()),
        config: ExecutorConfig = ExecutorConfig(executionTimeoutSeconds = TEST_TIMEOUT_SECONDS),
    ) = ExecutorHarness(templateEngine = Fixtures.templateEngine(sqlByTemplateId), registry = registry, config = config)

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 60L
        const val CONCURRENT_EXECUTIONS = 12
        const val CONCURRENT_BUDGET_MS = 60_000L
    }
}
