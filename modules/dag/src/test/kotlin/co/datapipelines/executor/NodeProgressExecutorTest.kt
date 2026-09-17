package co.datapipelines.executor

import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.NodeProgress
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `node_progress` through the REAL executor (149 §A/§B): real H2 source, real staging pool,
 * real write-back, the recording emitter. Each supported path produces the documented
 * operation kind, destination, the phases it entered, counts and terminal sample — and the
 * samples sit strictly between the node's `node_started` and its terminal event.
 *
 * These nodes are FAST (milliseconds), so the pump's tick may never fall inside them: the
 * proof that every boundary was instrumented is the terminal sample's `timings_ms` KEYS — a
 * phase that was never entered is absent by construction. The live state sequence under
 * controlled slowness is `NodeProgressAcceptanceTest`'s job.
 */
class NodeProgressExecutorTest {
    private fun harness(
        sql: Map<String, String>,
        registry: FakeDatasourceRegistry = FakeDatasourceRegistry(emptyMap()),
        maxConnections: Int = 4,
    ) = ExecutorHarness(
        templateEngine = Fixtures.templateEngine(sql),
        registry = registry,
        config = ExecutorConfig(executionTimeoutSeconds = 60, progressWriteIntervalSeconds = 1),
        stagingFactory = H2StagingFactory(H2StagingProperties(maxConnections = maxConnections, insertBatchSize = 100)),
    )

    private fun RecordingEmitter.progressOf(nodeId: String) = allOf<NodeProgress>().filter { it.nodeId == nodeId }.map { it.snapshot }

    private fun source() =
        h2Datasource(
            "src",
            listOf("CREATE TABLE t (id INT, label VARCHAR(10))", """INSERT INTO t SELECT "X", 'r' || "X" FROM SYSTEM_RANGE(1, 350)"""),
        )

    @Test
    fun `a stage node reports executing, fetching, waiting, writing and a committed terminal sample with counts`() =
        runBlocking<Unit> {
            val nodes = listOf(Fixtures.node("stage", source = "src", output = NodeOutput.Tempdb("stg")))
            harness(mapOf("stage" to "SELECT id, label FROM t"), FakeDatasourceRegistry(mapOf("src" to source()))).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
                val samples = h.emitter.progressOf("stage")
                samples.isNotEmpty().shouldBeTrue()
                samples.map { it.sequence } shouldContainExactly (1..samples.size).toList()
                samples.all { it.kind == OperationKind.STAGE && it.destination == OperationDestination.tempdb("stg") }.shouldBeTrue()
                samples.last().state shouldBe OperationState.COMPLETED
                val terminal = samples.last()
                // Every measured boundary was entered (a phase never entered is absent by construction).
                terminal.timingsMs.keys shouldBe
                    setOf(
                        OperationPhase.CONNECTING,
                        OperationPhase.EXECUTING,
                        OperationPhase.FETCHING,
                        OperationPhase.WAITING_OUTPUT,
                        OperationPhase.WRITING,
                        OperationPhase.FINALIZING,
                    )
                terminal.rowsFetched shouldBe 350
                terminal.rowsWritten shouldBe 350
                terminal.batchesWritten shouldBe 4
                terminal.committed shouldBe true
                terminal.timingsMs shouldContainKey OperationPhase.WRITING
                terminal.timingsMs shouldContainKey OperationPhase.FETCHING
                // No committed label before the terminal sample.
                samples.dropLast(1).all { it.committed == null }.shouldBeTrue()
                // The node's result is unchanged by instrumentation.
                h.emitter
                    .firstOf<NodeCompleted>()
                    .stats.rowsOut shouldBe 350
            }
        }

    @Test
    fun `progress samples sit strictly between node_started and node_completed`() =
        runBlocking<Unit> {
            val nodes = listOf(Fixtures.node("stage", source = "src", output = NodeOutput.Tempdb("stg")))
            harness(mapOf("stage" to "SELECT id FROM t"), FakeDatasourceRegistry(mapOf("src" to source()))).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                val types = h.emitter.types()
                val started = types.indexOf(SseEventType.NODE_STARTED)
                val completed = types.indexOf(SseEventType.NODE_COMPLETED)
                val progress = types.withIndex().filter { it.value == SseEventType.NODE_PROGRESS }.map { it.index }
                progress.isNotEmpty().shouldBeTrue()
                progress.all { it > started && it < completed }.shouldBeTrue()
                // Nothing after the execution's terminal event.
                types.indexOfLast { it == SseEventType.NODE_PROGRESS } < types.indexOf(SseEventType.PIPELINE_COMPLETED)
            }
        }

    @Test
    fun `a tempdb CTAS is one combined executing state and counts rows only at the end`() =
        runBlocking<Unit> {
            val nodes = listOf(Fixtures.node("ctas", output = NodeOutput.Tempdb("made")))
            harness(mapOf("ctas" to """SELECT "X" AS n FROM SYSTEM_RANGE(1, 42)""")).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
                val samples = h.emitter.progressOf("ctas")
                samples.all { it.kind == OperationKind.CTAS }.shouldBeTrue()
                samples.last().timingsMs.keys shouldBe
                    setOf(OperationPhase.WAITING_OUTPUT, OperationPhase.EXECUTING, OperationPhase.FINALIZING)
                // No invented fetch/write split: the CTAS is one executing interval.
                samples
                    .last()
                    .timingsMs.keys
                    .none { it == OperationPhase.FETCHING || it == OperationPhase.WRITING }
                    .shouldBeTrue()
                samples.dropLast(1).all { it.rowsFetched == null && it.rowsWritten == null }.shouldBeTrue()
                samples.last().rowsWritten shouldBe 42
                samples.last().committed shouldBe true
            }
        }

    @Test
    fun `caller materialization reports fetching and writing to the caller destination`() =
        runBlocking<Unit> {
            val nodes = listOf(Fixtures.node("out", source = "src", output = NodeOutput.Caller))
            harness(mapOf("out" to "SELECT id FROM t"), FakeDatasourceRegistry(mapOf("src" to source()))).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
                val samples = h.emitter.progressOf("out")
                samples.all { it.kind == OperationKind.MATERIALIZE && it.destination == OperationDestination.CALLER }.shouldBeTrue()
                samples.last().timingsMs.keys shouldContainAll
                    listOf(OperationPhase.CONNECTING, OperationPhase.EXECUTING, OperationPhase.FETCHING, OperationPhase.WRITING)
                samples.last().rowsFetched shouldBe 350
                samples.last().rowsWritten shouldBe 350
                samples.last().committed shouldBe true
            }
        }

    @Test
    fun `external write-back reports the target destination, waiting, writing, finalizing and commit`() =
        runBlocking<Unit> {
            val target = h2Datasource("wb", listOf("CREATE TABLE tgt (id INT, label VARCHAR(10))"))
            val nodes = listOf(Fixtures.node("wb", source = "src", output = NodeOutput.Datasource("wb", "tgt", WriteMode.APPEND)))
            harness(mapOf("wb" to "SELECT id, label FROM t"), FakeDatasourceRegistry(mapOf("src" to source(), "wb" to target))).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
                val samples = h.emitter.progressOf("wb")
                samples
                    .all { it.kind == OperationKind.WRITEBACK && it.destination == OperationDestination.datasource("wb", "tgt") }
                    .shouldBeTrue()
                samples.last().timingsMs.keys shouldBe
                    setOf(
                        OperationPhase.CONNECTING,
                        OperationPhase.EXECUTING,
                        OperationPhase.WAITING_OUTPUT,
                        OperationPhase.FETCHING,
                        OperationPhase.WRITING,
                        OperationPhase.FINALIZING,
                    )
                samples.last().rowsFetched shouldBe 350
                samples.last().rowsWritten shouldBe 350
                samples.last().committed shouldBe true
                samples.last().rolledBack.shouldBeNull()
            }
        }

    @Test
    fun `a failed write-back is reported failed, not committed, and rolled back`() =
        runBlocking<Unit> {
            // The target has ONE column; the INSERT of two fails inside the transaction.
            val target = h2Datasource("wb", listOf("CREATE TABLE tgt (id INT)"))
            val nodes = listOf(Fixtures.node("wb", source = "src", output = NodeOutput.Datasource("wb", "tgt", WriteMode.APPEND)))
            harness(mapOf("wb" to "SELECT id, label FROM t"), FakeDatasourceRegistry(mapOf("src" to source(), "wb" to target))).use { h ->
                runCatching { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }.isFailure.shouldBeTrue()
                val terminal = h.emitter.progressOf("wb").last()
                terminal.state shouldBe OperationState.FAILED
                terminal.committed shouldBe false
                terminal.rolledBack shouldBe true
                val types = h.emitter.types()
                types.indexOfLast { it == SseEventType.NODE_PROGRESS } < types.indexOf(SseEventType.NODE_FAILED)
            }
        }

    @Test
    fun `a datasource DML statement reports connecting, executing and its update count while DDL reports no rows`() =
        runBlocking<Unit> {
            val ds = h2Datasource("ds", listOf("CREATE TABLE t (id INT)", "INSERT INTO t VALUES (1), (2), (3)"))
            val nodes =
                listOf(
                    Fixtures.node("upd", type = NodeType.DML, source = "ds"),
                    Fixtures.node("ddl", type = NodeType.DDL, source = "ds", dependsOn = listOf("upd")),
                )
            val sql = mapOf("upd" to "UPDATE t SET id = id + 1", "ddl" to "CREATE TABLE made (x INT)")
            harness(sql, FakeDatasourceRegistry(mapOf("ds" to ds))).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
                val upd = h.emitter.progressOf("upd")
                upd.all { it.kind == OperationKind.STATEMENT && it.destination == OperationDestination.datasource("ds") }.shouldBeTrue()
                upd.last().timingsMs.keys shouldBe setOf(OperationPhase.CONNECTING, OperationPhase.EXECUTING)
                upd.last().state shouldBe OperationState.COMPLETED
                upd.last().committed shouldBe true
                upd.last().rowsWritten shouldBe 3
                upd.last().rowsFetched.shouldBeNull()
                val ddl = h.emitter.progressOf("ddl")
                ddl.last().destination shouldBe OperationDestination.NONE
                ddl.last().rowsWritten.shouldBeNull()
                ddl.last().committed.shouldBeNull()
                ddl.last().state shouldBe OperationState.COMPLETED
            }
        }

    @Test
    fun `a zero-row stage reaches completed with zero counts`() =
        runBlocking<Unit> {
            val empty = h2Datasource("e", listOf("CREATE TABLE t (id INT)"))
            val nodes = listOf(Fixtures.node("stage", source = "e", output = NodeOutput.Tempdb("stg")))
            harness(mapOf("stage" to "SELECT id FROM t"), FakeDatasourceRegistry(mapOf("e" to empty))).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
                val terminal = h.emitter.progressOf("stage").last()
                terminal.state shouldBe OperationState.COMPLETED
                terminal.rowsFetched shouldBe 0
                terminal.rowsWritten shouldBe 0
                terminal.committed shouldBe true
            }
        }

    @Test
    fun `a calculator node emits no progress at all`() =
        runBlocking<Unit> {
            val text = { v: String ->
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .textNode(v)
            }
            val calc =
                co.datapipelines.pipeline.Node(
                    id = "fq",
                    description = "calc",
                    type = NodeType.CALCULATOR,
                    source = "",
                    template = co.datapipelines.pipeline.TemplateRef(),
                    output = null,
                    dependsOn = emptyList(),
                    kind = "fiscal_quarter",
                    inputs = mapOf("date" to text("2026-02-03"), "fiscal_start" to text("\$org_fiscal_start_date")),
                    contextKey = "q",
                )
            harness(emptyMap()).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(calc)))).status shouldBe ExecutionStatus.SUCCESS
                h.emitter.progressOf("fq").shouldBeEmpty()
            }
        }

    @Test
    fun `event volume is bounded on a many-batch stage`() =
        runBlocking<Unit> {
            val big =
                h2Datasource("big", listOf("CREATE TABLE t (id INT)", """INSERT INTO t SELECT "X" FROM SYSTEM_RANGE(1, 20000)"""))
            val nodes = listOf(Fixtures.node("stage", source = "big", output = NodeOutput.Tempdb("stg")))
            // insert-batch-size 100 → 200 batches, 200 fetch/write alternations.
            harness(mapOf("stage" to "SELECT id FROM t"), FakeDatasourceRegistry(mapOf("big" to big))).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
                val samples = h.emitter.progressOf("stage")
                samples.last().batchesWritten shouldBe 200
                samples.last().rowsWritten shouldBe 20000
                // ≤ 1 + first-seen phases + duration/1s + terminal — never one per batch.
                val elapsedSeconds = (samples.last().elapsedMs / 1000L).toInt() + 1
                (samples.size <= 1 + OperationPhase.entries.size + elapsedSeconds + 1).shouldBeTrue()
                samples.size shouldBeGreaterThan 0
            }
        }
}
