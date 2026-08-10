package co.datapipelines.executor

import co.datapipelines.events.NodeFailed
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The caller-node path (dag-executor.md §6.4.2, D9, §14 "Caller-path tests").
 *
 * The uniform delivery model has exactly one shape: materialize into the store **before** the
 * source connection closes, then build `data_ready` from the stored result. There is no
 * inline-vs-claim-check split and no fallback — a second delivery path is the hole D9 closed.
 */
class CallerResultPathTest {
    @Test
    fun `a result over the cap fails the node with result_too_large and the execution FAILED`() =
        runBlocking<Unit> {
            val source = h2Datasource("big", listOf("CREATE TABLE big (n INT)", "INSERT INTO big SELECT \"X\" FROM SYSTEM_RANGE(1, 500)"))
            // A one-byte cap: the drain must abort on the first row, not buffer and check after.
            val store = InMemoryResultStore(config = ResultConfig(maxSizeBytes = 1))

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("caller" to "SELECT n FROM big")),
                registry = FakeDatasourceRegistry(mapOf("big" to source)),
                resultStore = store,
            ).use { h ->
                val nodes = listOf(Fixtures.node("caller", source = "big"))

                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }.errorCode shouldBe PipelineErrorCodes.Result.TOO_LARGE

                h.emitter
                    .allOf<NodeFailed>()
                    .single()
                    .error.code shouldBe PipelineErrorCodes.Result.TOO_LARGE
                h.emitter.count(SseEventType.DATA_READY) shouldBe 0
                h.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 1
            }
        }

    @Test
    fun `a store that rejects the write fails with result_storage_unavailable and no fallback`() =
        runBlocking<Unit> {
            val source = h2Datasource("un", listOf("CREATE TABLE un (n INT)", "INSERT INTO un VALUES (1)"))
            val store =
                InMemoryResultStore(
                    failWith =
                        DatapipelinesException(
                            code = PipelineErrorCodes.Result.STORAGE_UNAVAILABLE,
                            message = "redis down",
                        ),
                )

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("caller" to "SELECT n FROM un")),
                registry = FakeDatasourceRegistry(mapOf("un" to source)),
                resultStore = store,
            ).use { h ->
                val nodes = listOf(Fixtures.node("caller", source = "un"))

                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }.errorCode shouldBe PipelineErrorCodes.Result.STORAGE_UNAVAILABLE

                // No fallback to inline delivery — the execution simply fails.
                h.emitter.count(SseEventType.DATA_READY) shouldBe 0
            }
        }

    @Test
    fun `the result is fully materialized before the source connection is returned`() =
        runBlocking<Unit> {
            val source = h2Datasource("mat", listOf("CREATE TABLE mat (n INT)", "INSERT INTO mat SELECT \"X\" FROM SYSTEM_RANGE(1, 250)"))
            val registry = FakeDatasourceRegistry(mapOf("mat" to source))

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("caller" to "SELECT n FROM mat ORDER BY n")),
                registry = registry,
            ).use { h ->
                val nodes = listOf(Fixtures.node("caller", source = "mat"))
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                val view = h.resultStore.describe(result.resultRef.shouldNotBeNull()).shouldNotBeNull()
                view.totalRows shouldBe 250
                // Every row landed, and the lease is already back — nothing holds a live cursor.
                registry.leased.get() shouldBe registry.closed.get()
                (registry.closed.get() > 0).shouldBeTrue()
                result.nodeStats.single().rowsOut shouldBe 250
            }
        }

    @Test
    fun `a caller result larger than one page reports hasMore on data_ready`() =
        runBlocking<Unit> {
            val source = h2Datasource("pg", listOf("CREATE TABLE pg (n INT)", "INSERT INTO pg SELECT \"X\" FROM SYSTEM_RANGE(1, 30)"))
            val store = InMemoryResultStore(config = ResultConfig(pageSizeRows = 10))

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("caller" to "SELECT n FROM pg ORDER BY n")),
                registry = FakeDatasourceRegistry(mapOf("pg" to source)),
                resultStore = store,
            ).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("caller", source = "pg")))))

                val ready = h.emitter.firstOf<co.datapipelines.events.DataReady>()
                ready.totalRows shouldBe 30
                ready.rowCount shouldBe 10
                ready.hasMore shouldBe true
            }
        }

    @Test
    fun `the client-requested TTL is clamped before it reaches the store`() =
        runBlocking<Unit> {
            // REST §7.4: effective TTL = clamp(requested, ttl-min, ttl-max). An unbounded
            // client-controlled TTL would let one caller pin gigabytes in Redis.
            val source = h2Datasource("ttl", listOf("CREATE TABLE ttl (n INT)", "INSERT INTO ttl VALUES (1)"))
            val config = ExecutorConfig(result = ResultConfig(ttlMinSeconds = 60, ttlMaxSeconds = 120, ttlDefaultSeconds = 90))

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("caller" to "SELECT n FROM ttl")),
                registry = FakeDatasourceRegistry(mapOf("ttl" to source)),
                config = config,
            ).use { h ->
                val nodes = listOf(Fixtures.node("caller", source = "ttl"))

                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes), resultTtlSeconds = 5))
                h.emitter.firstOf<co.datapipelines.events.DataReady>().ttlSeconds shouldBe 60

                h.emitter.events.clear()
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes), resultTtlSeconds = 99_999))
                h.emitter.firstOf<co.datapipelines.events.DataReady>().ttlSeconds shouldBe 120

                h.emitter.events.clear()
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes), resultTtlSeconds = null))
                h.emitter.firstOf<co.datapipelines.events.DataReady>().ttlSeconds shouldBe 90
            }
        }

    /**
     * B3: a caller result that vanished between materialisation and delivery fails the execution.
     *
     * `data_ready` used to be resolved **after** `pipeline_completed`, and a null view merely
     * logged and returned. The result was an execution reporting `SUCCESS` with no `data_ready` —
     * which is **wire-identical** to a legal zero-caller run (§4.1, §10). A client asking for data
     * got a success with no data and no way to tell that from a pipeline that legitimately returns
     * none. D9 says the opposite in as many words: no fallback, fail loud.
     *
     * The `describe`-before-terminal ordering is what this test pins; the discard simulates the
     * TTL expiring or Redis evicting between the drain and the read.
     */
    @Test
    fun `a caller result that vanished before delivery fails the execution, never silent SUCCESS`() =
        runBlocking<Unit> {
            val source = h2Datasource("gone", listOf("CREATE TABLE gone (n INT)", "INSERT INTO gone VALUES (1)"))
            val store = VanishingResultStore()

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("caller" to "SELECT n FROM gone")),
                registry = FakeDatasourceRegistry(mapOf("gone" to source)),
                resultStore = store,
            ).use { h ->
                val nodes = listOf(Fixtures.node("caller", source = "gone"))

                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }.errorCode shouldBe PipelineErrorCodes.Result.STORAGE_UNAVAILABLE

                // Exactly one terminal event, and it is the failure — not a completed-then-nothing.
                h.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 1
                h.emitter.count(SseEventType.PIPELINE_COMPLETED) shouldBe 0
                h.emitter.count(SseEventType.DATA_READY) shouldBe 0
                // The node itself succeeded and already reported it; §10 forbids a second verdict.
                h.emitter.count(SseEventType.NODE_COMPLETED) shouldBe 1
                h.emitter.count(SseEventType.NODE_FAILED) shouldBe 0
            }
        }

    @Test
    fun `a zero-caller run is still a plain SUCCESS, so the B3 guard did not catch the legal case`() =
        runBlocking<Unit> {
            // The guard must fire only when a caller node produced a ref. A pipeline with no caller
            // node has nothing to resolve and must stay exactly as legal as it was.
            val nodes = listOf(Fixtures.node("mk", type = co.datapipelines.pipeline.NodeType.DDL))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("mk" to """CREATE TABLE "z" (n INT)""")),
                resultStore = VanishingResultStore(),
            ).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                result.status shouldBe ExecutionStatus.SUCCESS
                h.emitter.count(SseEventType.PIPELINE_COMPLETED) shouldBe 1
                h.emitter.count(SseEventType.DATA_READY) shouldBe 0
            }
        }

    @Test
    fun `a tempdb-sourced caller node drains inside the staging lock`() =
        runBlocking<Unit> {
            // §6.4.2: for a tempdb source the drain runs inside `NodeRunner.tempdbCursor`, i.e.
            // inside `staging.withConnection`, so the staging lock is held for the whole
            // materialization — a concurrent stage()/execute() cannot interleave with the open
            // cursor. (It was `staging.withQuery` until B4b; that held the same lock but created
            // the statement inside staging, which left the node uncancellable.)
            val nodes =
                listOf(
                    Fixtures.node("mk", type = co.datapipelines.pipeline.NodeType.DDL),
                    Fixtures.node("fill", type = co.datapipelines.pipeline.NodeType.DML, dependsOn = listOf("mk")),
                    Fixtures.node("out", output = NodeOutput.Caller, dependsOn = listOf("fill")),
                )
            val sql =
                mapOf(
                    "mk" to """CREATE TABLE "t" (n INT)""",
                    "fill" to """INSERT INTO "t" VALUES (1), (2), (3)""",
                    "out" to """SELECT n FROM "t" ORDER BY n""",
                )

            ExecutorHarness(templateEngine = Fixtures.templateEngine(sql)).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                result.status shouldBe ExecutionStatus.SUCCESS
                h.resultStore
                    .describe(result.resultRef.shouldNotBeNull())
                    .shouldNotBeNull()
                    .firstPage shouldBe listOf(listOf(1), listOf(2), listOf(3))
            }
        }

    /**
     * Stores normally, then reports the result as gone — the TTL/eviction race B3 must fail on.
     */
    private class VanishingResultStore(
        private val delegate: InMemoryResultStore = InMemoryResultStore(),
    ) : ResultStore by delegate {
        override fun describe(key: String): StoredResultView? = null

        override suspend fun materialize(
            executionId: UUID,
            resultSet: java.sql.ResultSet,
            sourceDialect: co.datapipelines.typesystem.Dialect,
            ttlSeconds: Long,
        ): StoredResult = delegate.materialize(executionId, resultSet, sourceDialect, ttlSeconds)
    }
}
