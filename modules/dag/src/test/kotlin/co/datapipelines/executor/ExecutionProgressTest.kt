package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live progress and the instance heartbeat (dag-executor.md §5, 108 §D / T199 #5–#6).
 *
 * The sink here is a RECORDING FAKE, never a mock, and that is deliberate: the contract under
 * test is "you must CALL me", and a strict mock inverts such a test — it throws on an unstubbed
 * call, so the suite passes precisely BECAUSE the call is missing and would go red the moment
 * someone added it. The question to ask of every assertion below is "could this fail if the
 * production call were deleted?", and each one can.
 */
class ExecutionProgressTest {
    /**
     * A node boundary writes progress, and what it writes names the RUNNING node.
     *
     * Both halves matter. That `record` was called proves the wiring; that the snapshot says
     * `RUNNING` for the node in flight and carries its start time proves the write is worth
     * making — a snapshot that reported the live node as `ABORTED` (what the TERMINAL snapshot
     * means by "started and never reported") would put "aborted" on a screen watching a healthy
     * run.
     */
    @Test
    fun `a node boundary writes a live snapshot naming the running node`() =
        runBlocking<Unit> {
            val progress = RecordingProgress()
            val source = h2Datasource("prog", listOf("CREATE TABLE prog (n INT)", "INSERT INTO prog VALUES (1)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("a" to "SELECT n FROM prog", "b" to "SELECT n FROM prog")),
                registry = FakeDatasourceRegistry(mapOf("prog" to source)),
                progress = progress,
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("a", source = "prog", output = NodeOutput.Tempdb("t_a")),
                        Fixtures.node("b", source = "prog", output = NodeOutput.Caller, dependsOn = listOf("a")),
                    )
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS

                // Four boundaries: a starts, a completes, b starts, b completes.
                (progress.snapshots.size >= 4).shouldBeTrue()
                // The FIRST write is taken at a's start, so it must say a is running and must not
                // yet mention b at all — an absent node is "not started", never a status.
                val first = progress.snapshots.first()
                first.map { it.nodeId } shouldBe listOf("a")
                first.single().status shouldBe NodeStatus.RUNNING
                first.single().startedAt shouldBe
                    h.emitter
                        .allOf<co.datapipelines.events.NodeStarted>()
                        .first()
                        .startedAt
                // By the last write both nodes have finished and neither is RUNNING any more.
                progress.snapshots.last().map { it.status } shouldBe listOf(NodeStatus.SUCCESS, NodeStatus.SUCCESS)
                // The per-execution state is released on the way out, on the one path every
                // execution takes.
                progress.forgotten.get() shouldBe 1
            }
        }

    /**
     * The heartbeat beats while the execution runs, and stops when it ends.
     *
     * At a one-second interval a node doing ~2 s of real work must produce at least two beats.
     * Asserted as a floor rather than an exact count because the tick is a `delay`, not a clock;
     * the claim under test is "it beats repeatedly while work is in flight", which a floor states
     * exactly and an equality would state as a flake.
     */
    @Test
    fun `the heartbeat beats repeatedly while the execution runs and stops with it`() =
        runBlocking<Unit> {
            val progress = RecordingProgress()
            val source = h2Datasource("beat", listOf("CREATE TABLE beat (n INT)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to TWO_SECOND_SQL)),
                registry = FakeDatasourceRegistry(mapOf("beat" to source)),
                config = ExecutorConfig(heartbeatSeconds = 1, executionTimeoutSeconds = 60, nodeTimeoutSeconds = 60),
                progress = progress,
            ).use { h ->
                val nodes = listOf(Fixtures.node("slow", source = "beat"))
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS

                (progress.beats.get() >= 2).shouldBeTrue()
                val afterRun = progress.beats.get()
                // The beat coroutine is cancelled with the poller, so nothing arrives after the
                // execution returns. Without that cancel this would climb forever.
                kotlinx.coroutines.delay(HEARTBEAT_QUIET_MS)
                progress.beats.get() shouldBe afterRun
            }
        }

    /**
     * A node staging rows reports how far it got, mid-flight.
     *
     * This is the half of §D the staging drain owns: without it a live row can say WHICH node is
     * running but not whether it is a tenth of the way through or nearly done, which for a
     * multi-minute node is most of the question.
     */
    @Test
    fun `a staging node reports its rows so far while it is still draining`() =
        runBlocking<Unit> {
            val progress = RecordingProgress()
            val source = h2Datasource("rows", listOf("CREATE TABLE rows_t (n INT)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("stage" to "SELECT x AS n FROM SYSTEM_RANGE(1, $STAGED_ROWS)")),
                registry = FakeDatasourceRegistry(mapOf("rows" to source)),
                // Zero throttle: the assertion is that the drain REPORTS, not that the throttle
                // works — throttling is asserted by its own arithmetic, not by hoping for a gap.
                config = ExecutorConfig(progressWriteIntervalSeconds = 1, executionTimeoutSeconds = 60),
                progress = progress,
            ).use { h ->
                val nodes = listOf(Fixtures.node("stage", source = "rows", output = NodeOutput.Tempdb("t")))
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS

                // The drain's own reports, unthrottled, straight off the staging callback.
                progress.stagedRows.isNotEmpty().shouldBeTrue()
                progress.stagedRows.last() shouldBe STAGED_ROWS
                // Mid-flight, not just at the end: with 5 000 rows at the default 1 000-row batch
                // there are five reports, and the first is 1 000.
                progress.stagedRows shouldContain 1000L
            }
        }

    /** Records what it was told, and asserts nothing — see the class KDoc on why not a mock. */
    private class RecordingProgress : ExecutionProgress {
        val snapshots = CopyOnWriteArrayList<List<NodeStats>>()
        val stagedRows = CopyOnWriteArrayList<Long>()
        val beats = AtomicInteger()
        val forgotten = AtomicInteger()

        override fun record(
            executionId: UUID,
            nodeStats: List<NodeStats>,
        ) {
            snapshots += nodeStats
        }

        override fun recordThrottled(
            executionId: UUID,
            nodeStats: () -> List<NodeStats>,
        ) {
            // Deliberately NOT throttled here: this double records what the executor offered, so a
            // test can see every report. The throttle is production behaviour and is asserted
            // where it lives.
            val stats = nodeStats()
            snapshots += stats
            stats.firstOrNull { it.status == NodeStatus.RUNNING }?.let { stagedRows += it.rowsOut }
        }

        override fun heartbeat(executionId: UUID) {
            beats.incrementAndGet()
        }

        override fun forget(executionId: UUID) {
            forgotten.incrementAndGet()
        }
    }

    private companion object {
        /** ~2 s of real H2 work — see `ConcurrencyTest.MEDIUM_SQL` for the calibration. */
        const val TWO_SECOND_SQL =
            """SELECT COUNT(*) AS c FROM SYSTEM_RANGE(1, 5600) a, SYSTEM_RANGE(1, 5600) b WHERE MOD(a."X" + b."X", 7) = 0"""

        /** Five default-sized batches, so "mid-flight" is a real claim and not one report. */
        const val STAGED_ROWS = 5000L

        /** Long enough that a beat that was NOT cancelled would certainly have fired. */
        const val HEARTBEAT_QUIET_MS = 1_500L
    }
}
