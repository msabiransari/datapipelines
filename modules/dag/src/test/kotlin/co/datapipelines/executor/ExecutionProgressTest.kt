package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
     * The node's query blocks INSIDE the source (an H2 alias, [HeartbeatRendezvous]) until the
     * sink has counted the second beat, so "two beats arrived while work was in flight" is a
     * property of the construction, never of the clock. The first draft ran ~2 s of real H2 work
     * against the one-second cadence and asserted `beats >= 2` — a fixed-cadence expectation that
     * held on the dev box and lost on a CI runner whose core finished the query in under two
     * ticks (#114, run 35039260539). The rendezvous is bounded by a hang guard, not a cadence: a
     * heartbeat that never comes lets the query return 0 and the assertions below go red.
     */
    @Test
    fun `the heartbeat beats repeatedly while the execution runs and stops with it`() =
        runBlocking<Unit> {
            val progress = RecordingProgress()
            val rendezvous = HeartbeatRendezvous.arm(beats = 2, hangGuard = HEARTBEAT_HANG_GUARD)
            progress.onBeat = rendezvous::beat
            val source = h2Datasource("beat", listOf(HeartbeatRendezvous.CREATE_ALIAS))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to HeartbeatRendezvous.SQL)),
                registry = FakeDatasourceRegistry(mapOf("beat" to source)),
                config = ExecutorConfig(heartbeatSeconds = 1, executionTimeoutSeconds = 60, nodeTimeoutSeconds = 60),
                progress = progress,
            ).use { h ->
                val nodes = listOf(Fixtures.node("slow", source = "beat"))
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS

                // The query returned because the second beat arrived — not because the guard gave up.
                rendezvous.met().shouldBeTrue()
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
                templateEngine = Fixtures.templateEngine(mapOf("stage" to """SELECT "X" AS n FROM SYSTEM_RANGE(1, $STAGED_ROWS)""")),
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

        /** Told of every beat as it lands — the heartbeat test's rendezvous hangs off it. */
        var onBeat: () -> Unit = {}

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
            onBeat()
        }

        override fun forget(executionId: UUID) {
            forgotten.incrementAndGet()
        }
    }

    private companion object {
        /** Five default-sized batches, so "mid-flight" is a real claim and not one report. */
        const val STAGED_ROWS = 5000L

        /** Long enough that a beat that was NOT cancelled would certainly have fired. */
        const val HEARTBEAT_QUIET_MS = 1_500L

        /**
         * How long the node's query waits for the second beat before giving up. At a one-second
         * cadence the wait is ~2 s; only a heartbeat that never comes can exhaust this, and that
         * is a red test, not a slow one.
         */
        val HEARTBEAT_HANG_GUARD: Duration = Duration.ofSeconds(30)
    }
}

/**
 * The rendezvous the heartbeat test puts INSIDE its node's query — [NodeRendezvous]'s shape, one
 * party: H2 calls [await] through the `heartbeat_rendezvous` alias on the thread executing the
 * statement, and the call returns when the recording sink has counted the armed number of beats.
 * The node therefore cannot finish before the beats it is asserted to have produced, whatever the
 * runner's speed. A public object with a `@JvmStatic` method because that is the shape H2 can
 * load and invoke reflectively. One rendezvous armed per run, exactly as [NodeRendezvous].
 */
object HeartbeatRendezvous {
    /** The DDL that binds the alias in a fresh H2 source, for [h2Datasource]. */
    const val CREATE_ALIAS = """CREATE ALIAS heartbeat_rendezvous FOR "co.datapipelines.executor.HeartbeatRendezvous.await""""

    /** The node's query: returns 1 when the beats arrived, 0 when the hang guard gave up. */
    const val SQL = "SELECT heartbeat_rendezvous() AS met"

    @Volatile
    private var current: Armed? = null

    /** Arms a fresh latch of [beats] whose wait gives up after [hangGuard]. */
    fun arm(
        beats: Int,
        hangGuard: Duration,
    ): Armed = Armed(CountDownLatch(beats), hangGuard).also { current = it }

    /** What H2 calls. Returns 1 when the armed beats arrived, 0 when the guard gave up. */
    @JvmStatic
    fun await(): Int = checkNotNull(current) { "heartbeat_rendezvous() called with no rendezvous armed" }.await()

    class Armed(
        private val latch: CountDownLatch,
        private val hangGuard: Duration,
    ) {
        private val met = AtomicBoolean(false)

        /** One beat landed — called from the recording sink on the executor's own thread. */
        fun beat() = latch.countDown()

        fun await(): Int {
            val arrived = latch.await(hangGuard.toMillis(), TimeUnit.MILLISECONDS)
            met.set(arrived)
            return if (arrived) 1 else 0
        }

        /** Whether the query returned because the beats arrived, not because the guard expired. */
        fun met(): Boolean = met.get()
    }
}
