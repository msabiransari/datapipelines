package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionEvent
import co.datapipelines.events.NodeProgress
import co.datapipelines.events.PipelineFailed
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.staging.StageObserver
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingEngine
import co.datapipelines.staging.StagingFactory
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The §149 acceptance matrix over the REAL runner, staging pool, write-back and emitter — with
 * deterministic gates at the driver boundaries instead of sleeps. Each gate holds the node
 * inside ONE measured boundary until the test has SEEN the live `node_progress` sample that
 * names it, so the assertion is about the state the wire reported while the node was really
 * there: a slow query reads `executing`, a slow cursor `fetching`, a full pool
 * `waiting_output`, a slow insert `writing`; two nodes overlap at capacity four and queue at
 * capacity one; a cancelled write ends `aborted`/uncommitted with nothing after it; an
 * abandoned driver body publishes nothing after the node's deadline.
 */
class NodeProgressAcceptanceTest {
    // ------------------------------------------------------------------ gates and fixtures

    /** A one-shot gate a driver call parks on; `cancel()` on the parked statement releases it with 57014. */
    private class Gate {
        val reached = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val cancelled = AtomicBoolean()

        fun open() = release.countDown()

        fun park() {
            reached.countDown()
            check(release.await(GATE_S, TimeUnit.SECONDS)) { "gate never opened" }
            if (cancelled.get()) throw SQLException("Statement was canceled or the session timed out", "57014", 57014)
        }

        fun cancel() {
            cancelled.set(true)
            release.countDown()
        }
    }

    /** A registry over a real H2 source whose statements and cursors park on the given gates. */
    private class GatedRegistry(
        private val inner: FakeDatasourceRegistry,
        private val executeGate: Gate? = null,
        private val fetchGateAfterRows: Pair<Int, Gate>? = null,
        private val batchGate: Gate? = null,
        private val batchGateFor: String? = null,
    ) : DatasourceRegistry by inner {
        override fun poolFor(datasource: Datasource): ConnectionPool {
            val pool = inner.poolFor(datasource)
            return object : ConnectionPool by pool {
                override fun leaseConnection(): Connection = GatedConnection(pool.leaseConnection(), datasource.name)
            }
        }

        private inner class GatedConnection(
            private val c: Connection,
            private val name: String,
        ) : Connection by c {
            override fun createStatement(
                type: Int,
                concurrency: Int,
            ): Statement = GatedStatement(c.createStatement(type, concurrency))

            override fun createStatement(): Statement = GatedStatement(c.createStatement())

            override fun prepareStatement(sql: String): PreparedStatement =
                GatedPrepared(c.prepareStatement(sql), if (batchGateFor == null || batchGateFor == name) batchGate else null)
        }

        private inner class GatedStatement(
            private val s: Statement,
        ) : Statement by s {
            override fun executeQuery(sql: String): ResultSet {
                executeGate?.park()
                return GatedResultSet(s.executeQuery(sql))
            }

            override fun cancel() {
                executeGate?.cancel()
                fetchGateAfterRows?.second?.cancel()
                s.cancel()
            }
        }

        private inner class GatedResultSet(
            private val rs: ResultSet,
        ) : ResultSet by rs {
            private var rows = 0

            override fun next(): Boolean {
                fetchGateAfterRows?.let { (after, gate) -> if (rows == after) gate.park() }
                val more = rs.next()
                if (more) rows++
                return more
            }
        }

        private class GatedPrepared(
            private val p: PreparedStatement,
            private val gate: Gate?,
        ) : PreparedStatement by p {
            override fun executeBatch(): IntArray {
                gate?.park()
                return p.executeBatch()
            }

            override fun cancel() {
                gate?.cancel()
                p.cancel()
            }
        }
    }

    /** Parks each node INSIDE its held staging lease (the writing boundary) on [inside]. */
    private class GatedWriteStaging(
        private val inner: Staging,
        private val inside: (String) -> Unit,
    ) : Staging by inner {
        override suspend fun stage(
            resultSet: ResultSet,
            tableName: String,
            sourceDialect: Dialect,
            observer: StageObserver,
        ) = inner.stage(
            resultSet,
            tableName,
            sourceDialect,
            object : StageObserver by observer {
                override fun connectionAcquired() {
                    observer.connectionAcquired()
                    inside(tableName)
                }
            },
        )
    }

    private fun gatedStagingFactory(
        maxConnections: Int,
        inside: (String) -> Unit,
    ): StagingFactory {
        val real = H2StagingFactory(H2StagingProperties(maxConnections = maxConnections, insertBatchSize = BATCH))
        return object : StagingFactory {
            override fun create(
                executionId: UUID,
                engine: StagingEngine,
            ): Staging = GatedWriteStaging(real.create(executionId, engine), inside)
        }
    }

    private fun source(rows: Int = ROWS) =
        h2Datasource("src", listOf("CREATE TABLE t (id INT)", """INSERT INTO t SELECT "X" FROM SYSTEM_RANGE(1, $rows)"""))

    private fun harness(
        registry: DatasourceRegistry,
        stagingFactory: StagingFactory = H2StagingFactory(H2StagingProperties(maxConnections = 4, insertBatchSize = BATCH)),
        config: ExecutorConfig = ExecutorConfig(executionTimeoutSeconds = 60, progressWriteIntervalSeconds = 1),
        sql: Map<String, String> = mapOf("a" to "SELECT id FROM t", "b" to "SELECT id FROM t", "wb" to "SELECT id FROM t"),
    ) = ExecutorHarness(
        templateEngine = Fixtures.templateEngine(sql),
        registry = registry,
        config = config,
        stagingFactory = stagingFactory,
    )

    private fun RecordingEmitter.samples(nodeId: String) = allOf<NodeProgress>().filter { it.nodeId == nodeId }.map { it.snapshot }

    /** Waits on the ACTUAL event: polls the emitter until a sample of [nodeId] in [state] exists. */
    private fun RecordingEmitter.awaitState(
        nodeId: String,
        state: OperationState,
    ): OperationSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GATE_S)
        while (System.nanoTime() < deadline) {
            samples(nodeId).firstOrNull { it.state == state }?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("no $state sample for $nodeId within ${GATE_S}s; saw ${samples(nodeId).map { it.state }}")
    }

    private fun stageNode(id: String) = Fixtures.node(id, source = "src", output = NodeOutput.Tempdb(id))

    // ------------------------------------------------------------------ the matrix

    @Test
    fun `a slow query is reported executing — nothing fetched, nothing written`() =
        runBlocking<Unit> {
            val gate = Gate()
            harness(GatedRegistry(FakeDatasourceRegistry(mapOf("src" to source())), executeGate = gate)).use { h ->
                val run = async(Dispatchers.IO) { h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(stageNode("a"))))) }
                gate.reached.await(GATE_S, TimeUnit.SECONDS).shouldBeTrue()
                val live = h.emitter.awaitState("a", OperationState.EXECUTING)
                live.rowsFetched.shouldBeNull()
                live.rowsWritten.shouldBeNull()
                h.emitter
                    .samples("a")
                    .none { it.state == OperationState.FETCHING || it.state == OperationState.WRITING }
                    .shouldBeTrue()
                gate.open()
                run.await().status shouldBe ExecutionStatus.SUCCESS
                val terminal = h.emitter.samples("a").last()
                terminal.state shouldBe OperationState.COMPLETED
                terminal.timingsMs.getValue(OperationPhase.EXECUTING) shouldBeGreaterThanOrEqual
                    live.timingsMs.getValue(OperationPhase.EXECUTING)
                terminal.rowsWritten shouldBe ROWS.toLong()
            }
        }

    @Test
    fun `a slow cursor is reported fetching — and only the batches actually inserted count as written`() =
        runBlocking<Unit> {
            val gate = Gate()
            // Parks on the 151st row: batch 1 (100 rows) is inserted, batch 2 is mid-fetch.
            val registry = GatedRegistry(FakeDatasourceRegistry(mapOf("src" to source())), fetchGateAfterRows = (BATCH + 50) to gate)
            harness(registry).use { h ->
                val run = async(Dispatchers.IO) { h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(stageNode("a"))))) }
                gate.reached.await(GATE_S, TimeUnit.SECONDS).shouldBeTrue()
                val live = h.emitter.awaitState("a", OperationState.FETCHING)
                // The wire must not call the 50 rows read so far "written": exactly one batch is.
                val latest = h.emitter.samples("a").last()
                latest.state shouldBe OperationState.FETCHING
                latest.rowsWritten shouldBe BATCH.toLong()
                latest.batchesWritten shouldBe 1
                live.committed.shouldBeNull()
                gate.open()
                run.await().status shouldBe ExecutionStatus.SUCCESS
                h.emitter
                    .samples("a")
                    .last()
                    .timingsMs
                    .getValue(OperationPhase.FETCHING) shouldBeGreaterThanOrEqual 0L
                h.emitter
                    .samples("a")
                    .last()
                    .rowsWritten shouldBe ROWS.toLong()
            }
        }

    @Test
    fun `at capacity one the second writer waits for the lease while the first is writing`() =
        runBlocking<Unit> {
            val release = CountDownLatch(1)
            val inside = CountDownLatch(1)
            val holder =
                java.util.concurrent.atomic
                    .AtomicReference<String>()
            val factory =
                gatedStagingFactory(maxConnections = 1) { table ->
                    // Whichever node leases FIRST holds the one connection until the test has seen
                    // the other one waiting on the wire; the order the two race in is irrelevant.
                    if (holder.compareAndSet(null, table)) {
                        inside.countDown()
                        check(release.await(GATE_S, TimeUnit.SECONDS)) { "the holder was never released" }
                    }
                }
            harness(FakeDatasourceRegistry(mapOf("src" to source())), factory).use { h ->
                val run =
                    async(
                        Dispatchers.IO,
                    ) { h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(stageNode("a"), stageNode("b"))))) }
                inside.await(GATE_S, TimeUnit.SECONDS).shouldBeTrue()
                val holding = holder.get()
                val waiting = if (holding == "a") "b" else "a"
                val waiter = h.emitter.awaitState(waiting, OperationState.WAITING_OUTPUT)
                h.emitter.awaitState(holding, OperationState.WRITING)
                // The waiter is not writing while the holder has the only connection, and it
                // has written nothing: the holder took the pool's one lease first.
                waiter.rowsWritten.shouldBeNull()
                h.emitter
                    .samples(waiting)
                    .none { it.state == OperationState.WRITING }
                    .shouldBeTrue()
                release.countDown()
                run.await().status shouldBe ExecutionStatus.SUCCESS
                h.emitter
                    .samples(waiting)
                    .last()
                    .timingsMs
                    .getValue(OperationPhase.WAITING_OUTPUT) shouldBeGreaterThanOrEqual 1L
                h.emitter
                    .samples("a")
                    .last()
                    .rowsWritten shouldBe ROWS.toLong()
                h.emitter
                    .samples("b")
                    .last()
                    .rowsWritten shouldBe ROWS.toLong()
            }
        }

    @Test
    fun `at capacity four two independent writers are reported writing at the same instant`() =
        runBlocking<Unit> {
            val barrier = CyclicBarrier(2)
            val met = AtomicInteger()
            val seenBoth = CountDownLatch(1)
            val factory =
                gatedStagingFactory(maxConnections = 4) { _ ->
                    // Rendezvous INSIDE the held leases, then stay there until the test has seen
                    // BOTH writing samples on the wire — both are writing at once, provably.
                    if (met.get() < 2) {
                        barrier.await(GATE_S, TimeUnit.SECONDS)
                        met.incrementAndGet()
                        check(seenBoth.await(GATE_S, TimeUnit.SECONDS)) { "the test never released the writers" }
                    }
                }
            harness(FakeDatasourceRegistry(mapOf("src" to source())), factory).use { h ->
                val run =
                    async(
                        Dispatchers.IO,
                    ) { h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(stageNode("a"), stageNode("b"))))) }
                val a = h.emitter.awaitState("a", OperationState.WRITING)
                val b = h.emitter.awaitState("b", OperationState.WRITING)
                seenBoth.countDown()
                run.await().status shouldBe ExecutionStatus.SUCCESS
                // Both live samples were taken while neither operation had ended.
                val aEnd =
                    h.emitter
                        .samples("a")
                        .last()
                        .observedAt
                val bEnd =
                    h.emitter
                        .samples("b")
                        .last()
                        .observedAt
                (a.observedAt <= bEnd && b.observedAt <= aEnd).shouldBeTrue()
                met.get() shouldBe 2
                h.emitter
                    .samples("a")
                    .last()
                    .timingsMs
                    .getValue(OperationPhase.WAITING_OUTPUT) shouldBeGreaterThanOrEqual 0L
            }
        }

    @Test
    fun `a slow external insert is reported writing with no commit before the commit`() =
        runBlocking<Unit> {
            val gate = Gate()
            val target = h2Datasource("wb", listOf("CREATE TABLE tgt (id INT)"))
            val registry =
                GatedRegistry(FakeDatasourceRegistry(mapOf("src" to source(), "wb" to target)), batchGate = gate, batchGateFor = "wb")
            val node = Fixtures.node("wb", source = "src", output = NodeOutput.Datasource("wb", "tgt", WriteMode.APPEND))
            harness(registry).use { h ->
                val run = async(Dispatchers.IO) { h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(node)))) }
                gate.reached.await(GATE_S, TimeUnit.SECONDS).shouldBeTrue()
                val live = h.emitter.awaitState("wb", OperationState.WRITING)
                live.committed.shouldBeNull()
                live.rowsWritten.shouldBeNull()
                h.emitter
                    .samples("wb")
                    .none { it.state == OperationState.FINALIZING || it.committed == true }
                    .shouldBeTrue()
                gate.open()
                run.await().status shouldBe ExecutionStatus.SUCCESS
                val terminal = h.emitter.samples("wb").last()
                terminal.committed shouldBe true
                terminal.rowsWritten shouldBe ROWS.toLong()
                terminal.timingsMs.getValue(OperationPhase.WRITING) shouldBeGreaterThanOrEqual 1L
            }
        }

    @Test
    fun `cancellation mid-write ends the operation aborted and uncommitted, with nothing after the terminal event`() =
        runBlocking<Unit> {
            val gate = Gate()
            val target = h2Datasource("wb", listOf("CREATE TABLE tgt (id INT)"))
            val registry =
                GatedRegistry(FakeDatasourceRegistry(mapOf("src" to source(), "wb" to target)), batchGate = gate, batchGateFor = "wb")
            val node = Fixtures.node("wb", source = "src", output = NodeOutput.Datasource("wb", "tgt", WriteMode.APPEND))
            harness(registry).use { h ->
                val run = async(Dispatchers.IO) { h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(node)))) }
                gate.reached.await(GATE_S, TimeUnit.SECONDS).shouldBeTrue()
                h.emitter.awaitState("wb", OperationState.WRITING)
                val executionId =
                    h.emitter.events
                        .first()
                        .executionId
                // The write-back statement is not registered for Statement.cancel() (it is the
                // runner's own), so the cancel reaches it through the gate — the same shape a
                // driver interrupt takes.
                h.cancellations.cancel(executionId, AbortReason.CANCELLED).shouldBeTrue()
                gate.cancel()
                shouldThrow<ExecutionAbortedException> { run.await() }
                val terminal = h.emitter.samples("wb").last()
                terminal.state shouldBe OperationState.ABORTED
                terminal.committed shouldBe false
                terminal.rolledBack shouldBe true
                val events: List<ExecutionEvent> = h.emitter.events.toList()
                val abortedAt = events.indexOfFirst { it is ExecutionAborted }
                events.drop(abortedAt + 1).filterIsInstance<NodeProgress>().shouldBeEmpty()
                events.indexOfLast { it is NodeProgress } < abortedAt
            }
        }

    @Test
    fun `an abandoned driver body publishes nothing after the node deadline`() =
        runBlocking<Unit> {
            val gate = Gate()
            val drained = CountDownLatch(1)
            // The cursor parks on the 51st row, ignores cancel(), and is released only AFTER the
            // node has failed on schedule — the abandoned body then reads its batch and reports it
            // into the sealed tracker (its insert meets a closed pool and the body unwinds, which
            // is when it closes the source connection: the signal the test waits on).
            val registry =
                object : DatasourceRegistry by FakeDatasourceRegistry(mapOf("src" to source())) {
                    val inner = FakeDatasourceRegistry(mapOf("src" to source()))

                    override fun poolFor(datasource: Datasource): ConnectionPool {
                        val pool = inner.poolFor(datasource)
                        return object : ConnectionPool by pool {
                            override fun leaseConnection(): Connection = IgnoringCancelConnection(pool.leaseConnection(), gate, drained)
                        }
                    }
                }
            val config =
                ExecutorConfig(
                    nodeTimeoutSeconds = 1,
                    cancelGraceSeconds = 1,
                    executionTimeoutSeconds = 60,
                    progressWriteIntervalSeconds = 1,
                    nodeQueryTimeoutSeconds = 300,
                )
            harness(registry, config = config).use { h ->
                val failed =
                    shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(stageNode("a"))))) }
                failed.errorCode shouldBe PipelineErrorCodes.Node.TIMEOUT
                val terminal = h.emitter.samples("a").last()
                terminal.state shouldBe OperationState.FAILED
                terminal.committed shouldBe false
                val countAtTerminal = h.emitter.events.size
                h.emitter.events
                    .last()
                    .let { (it is PipelineFailed).shouldBeTrue() }
                // Now release the abandoned body and let it finish its drain.
                gate.open()
                drained.await(GATE_S, TimeUnit.SECONDS).shouldBeTrue()
                // One pump tick's worth of settling after the body unwound — a tick that found a
                // sealed tracker must publish nothing; this is the window in which it would.
                Thread.sleep(SETTLE_MS)
                // Its observations hit a sealed tracker: nothing new was published.
                h.emitter.events.size shouldBe countAtTerminal
                h.emitter.types().last() shouldBe SseEventType.PIPELINE_FAILED
                h.cancellations.liveExecutions shouldBe 0
            }
        }

    /** A source connection whose cursor parks and whose `cancel()` is dropped on the floor. */
    private class IgnoringCancelConnection(
        private val c: Connection,
        private val gate: Gate,
        private val drained: CountDownLatch,
    ) : Connection by c {
        override fun close() {
            c.close()
            drained.countDown()
        }

        override fun createStatement(
            type: Int,
            concurrency: Int,
        ): Statement = statement(c.createStatement(type, concurrency))

        override fun createStatement(): Statement = statement(c.createStatement())

        private fun statement(s: Statement): Statement =
            object : Statement by s {
                override fun executeQuery(sql: String): ResultSet {
                    val rs = s.executeQuery(sql)
                    return object : ResultSet by rs {
                        private var rows = 0

                        override fun next(): Boolean {
                            if (rows == BATCH / 2) gate.park()
                            val more = rs.next()
                            if (more) rows++
                            return more
                        }
                    }
                }

                override fun cancel() = Unit
            }
    }

    private companion object {
        const val ROWS = 400
        const val BATCH = 100
        const val GATE_S = 20L
        const val POLL_MS = 20L
        const val SETTLE_MS = 600L
    }
}
