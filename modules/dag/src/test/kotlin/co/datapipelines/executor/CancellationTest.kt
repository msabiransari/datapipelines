package co.datapipelines.executor

import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cancellation as a first-class path (dag-executor.md §8.3, §14 "Cancellation tests").
 *
 * The queries here are genuinely slow ([Fixtures.SLOW_SQL]) — a cancellation test over an instant
 * query proves only that the executor can finish before being asked to stop.
 */
class CancellationTest {
    @Test
    fun `each trigger ends the execution ABORTED with its own reason and one execution_aborted`() =
        runBlocking<Unit> {
            AbortReason.entries.forEach { reason ->
                harness().use { h ->
                    val executionId = cancelMidFlight(h) { id -> h.cancellations.cancel(id, reason) }

                    val aborted = h.emitter.allOf<ExecutionAborted>()
                    aborted.size shouldBe 1
                    aborted.single().reason shouldBe reason
                    aborted.single().executionId shouldBe executionId
                    h.emitter.count(SseEventType.PIPELINE_COMPLETED) shouldBe 0
                    h.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 0
                    h.emitter.count(SseEventType.DATA_READY) shouldBe 0
                }
            }
        }

    /**
     * §8.3.2 step 1: `Statement.cancel()` runs from the caller's thread **first**, and that — not
     * coroutine cancellation — is what stops a long query on the source database. Cancelling the
     * coroutine alone would unblock the JVM side and leave the query running on the source server,
     * which is precisely the "never hold a datasource for a caller that left" hole D7 closed.
     *
     * ## The settle delay is the honest part of this test
     *
     * [SETTLE_MS] is not padding: measured against the pinned H2 driver, a `cancel()` issued in the
     * first few milliseconds — after `node_started` but before the driver's command is actually
     * registered as executing — is **silently dropped**, and the query then runs its full ~57s
     * before the already-cancelled coroutine notices. Waiting until the statement is demonstrably
     * in flight is what makes this a test of the cancel path rather than of that race.
     *
     * That race is real in production too, and it is the documented caveat, not a defect this
     * module can close: §8.3.2 already states that statements which ignore `cancel()` "are not
     * waited on — they finish or hit their own `queryTimeout`". `datapipelines.executor
     * .node-query-timeout-seconds` is the backstop for exactly this window.
     */
    @Test
    fun `cancelling interrupts the in-flight statement rather than waiting for it`() =
        runBlocking<Unit> {
            harness().use { h ->
                val elapsed =
                    kotlin.system.measureTimeMillis {
                        cancelMidFlight(h, settleMs = SETTLE_MS) { id ->
                            h.cancellations.cancel(id, AbortReason.CANCELLED)
                        }
                    }

                // SLOW_SQL runs ~57s to completion. Returning far inside that is only possible if
                // Statement.cancel() actually reached the driver.
                (elapsed < INTERRUPT_BUDGET_MS).shouldBeTrue()
            }
        }

    @Test
    fun `a running node reports ABORTED in the abort snapshot`() =
        runBlocking<Unit> {
            harness().use { h ->
                cancelMidFlight(h) { id -> h.cancellations.cancel(id, AbortReason.SHUTDOWN) }

                val stats =
                    h.emitter
                        .allOf<ExecutionAborted>()
                        .single()
                        .nodeStats
                stats.size shouldBe 1
                stats.single().status shouldBe NodeStatus.ABORTED
                // It had started, so its start time is kept — an operator can see how far it got.
                (stats.single().startedAt != null).shouldBeTrue()
            }
        }

    @Test
    fun `the cross-instance Redis flag alone aborts an execution this instance owns`() =
        runBlocking<Unit> {
            // §8.3.1: `DELETE /executions/{id}` lands on an arbitrary instance under a no-sticky-
            // sessions LB. That instance can only write the flag — it holds no statements. The
            // executing instance must therefore pick the flag up on its own poll tick, which is
            // what makes the "worst case ≈ one heartbeat interval" claim true rather than aspirational.
            val config = ExecutorConfig(executionTimeoutSeconds = TIMEOUT_SECONDS, cancelPollIntervalSeconds = 1)
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
                registry = FakeDatasourceRegistry(mapOf(SLOW_DS to slowDatasource())),
                config = config,
            ).use { h ->
                // A *different* instance's registry: it knows nothing about this execution.
                val foreign = ExecutionCancellationService(InMemoryCancellationRegistry(), h.flags, config)

                cancelMidFlight(h) { id ->
                    // false = "not running here", which is exactly the cross-instance case. The
                    // flag is written either way, and that is all the other instance can do.
                    foreign.cancel(id, AbortReason.CANCELLED).shouldBeFalse()
                }

                h.emitter
                    .allOf<ExecutionAborted>()
                    .single()
                    .reason shouldBe AbortReason.CANCELLED
                (h.flags.reads.get() > 0).shouldBeTrue()
            }
        }

    @Test
    fun `cancelling an unknown or already-terminal execution is a no-op`() =
        runBlocking<Unit> {
            val registry = InMemoryCancellationRegistry()

            registry.cancel(UUID.randomUUID(), AbortReason.CANCELLED).shouldBeFalse()

            val id = UUID.randomUUID()
            registry.register(id)
            registry.cancel(id, AbortReason.CANCELLED).shouldBeTrue()
            // Second cancel of the same execution: already terminal, so false and no second unwind.
            registry.cancel(id, AbortReason.SHUTDOWN).shouldBeFalse()

            registry.deregister(id)
            registry.cancel(id, AbortReason.CANCELLED).shouldBeFalse()
        }

    @Test
    fun `cancellation releases the execution slot, the registration and the leased connection`() =
        runBlocking<Unit> {
            val registry = FakeDatasourceRegistry(mapOf(SLOW_DS to slowDatasource()))
            harness(registry).use { h ->
                cancelMidFlight(h) { id -> h.cancellations.cancel(id, AbortReason.CLIENT_DISCONNECT) }

                h.slots.inFlight shouldBe 0
                h.slots.trackedUsers shouldBe 0
                h.cancellations.liveExecutions shouldBe 0
                registry.leased.get() shouldBe registry.closed.get()
            }
        }

    /**
     * B4b: a **tempdb-sourced** node is cancellable too.
     *
     * The two tempdb DQL read branches (caller, write-back) used `staging.withQuery`, whose
     * statement is created inside `staging` and therefore never registered with the handle. That
     * made them the one node shape no cancellation trigger could reach: `DELETE`, disconnect-grace
     * and the execution timeout all cancelled the coroutine while the query ran on regardless.
     * They now run on a statement the executor owns, via `withConnection` — which holds the same
     * serialization lock, so §6.4.2's lock-across-drain guarantee is unchanged.
     */
    @Test
    fun `a tempdb-sourced caller node is cancellable mid-flight`() =
        runBlocking<Unit> {
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("seed" to "SELECT 1 AS n", "slow" to Fixtures.SLOW_SQL)),
                config = ExecutorConfig(maxParallelNodes = 1, executionTimeoutSeconds = TIMEOUT_SECONDS),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("seed", output = NodeOutput.Tempdb("t")),
                        Fixtures.node("slow", output = NodeOutput.Caller, dependsOn = listOf("seed")),
                    )

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        withTimeout(HARNESS_BUDGET_MS) {
                            val run =
                                async {
                                    shouldThrow<ExecutionAbortedException> {
                                        h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                                    }
                                }
                            val executionId = awaitNodeStarted(h, nodeId = "slow")
                            delay(SETTLE_MS)
                            // A live registration is the whole fix — without it the cancel is a
                            // no-op and this test would pass only by the entry guard (F12/C5).
                            (h.cancellations.registeredFor(executionId) >= 1).shouldBeTrue()
                            h.cancellations.cancel(executionId, AbortReason.CANCELLED)
                            run.await()
                        }
                    }

                // SLOW_SQL is a ~57s tempdb query; returning inside the budget means the cancel
                // reached H2, which it could not do while the statement lived inside withQuery.
                (elapsed < INTERRUPT_BUDGET_MS).shouldBeTrue()
                h.emitter
                    .allOf<ExecutionAborted>()
                    .single()
                    .reason shouldBe AbortReason.CANCELLED
            }
        }

    /**
     * F12/C5: the other arm — the **entry guard** — pinned explicitly rather than conflated.
     *
     * `withStatement` throws immediately when the abort reason is already set, so a cancel landing
     * before registration ends the node without any statement being cancelled at all: a fast, green
     * run that proves nothing about `Statement.cancel()`. Asserting a live registration at the
     * moment of cancel (above) separates the two paths; this covers the one that is left.
     */
    @Test
    fun `the entry guard ends a node whose execution was already cancelled, with no statement`() =
        runBlocking<Unit> {
            val registry = InMemoryCancellationRegistry()
            val executionId = UUID.randomUUID()
            val handle = registry.register(executionId)
            registry.cancel(executionId, AbortReason.CANCELLED)

            val statement = RecordingStatement()
            shouldThrow<ExecutionAbortedException> {
                handle.withStatement("n", statement) { error("body must never run") }
            }.reason shouldBe AbortReason.CANCELLED

            statement.cancels.get() shouldBe 0
            handle.registeredStatements shouldBe 0
        }

    /**
     * F12/C6: the check-put-recheck race in `withStatement`, run for real on two threads.
     *
     * A cancel that swept the statement map between the entry check and the `put` would leave the
     * statement registered-but-uncancelled and therefore uninterruptible. The recheck after the put
     * closes it. Either the body never runs, or it runs on a statement that really was cancelled —
     * `-1`, meaning the body completed with neither, must never be observed.
     */
    @Test
    fun `a cancel racing statement registration never leaves an uninterrupted statement`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(RACE_ROUNDS) {
                val registry = InMemoryCancellationRegistry()
                val executionId = UUID.randomUUID()
                val handle = registry.register(executionId)
                val statement = RecordingStatement()
                val startLine = CyclicBarrier(2)

                val registrar =
                    pool.submit<Int> {
                        startLine.await()
                        runCatching {
                            runBlocking {
                                handle.withStatement("n", statement) {
                                    Thread.sleep(RACE_HOLD_MS)
                                    if (statement.cancels.get() > 0) 1 else -1
                                }
                            }
                        }.getOrDefault(0)
                    }
                pool.submit {
                    startLine.await()
                    registry.cancel(executionId, AbortReason.CANCELLED)
                }

                // 0 = refused by a guard, 1 = ran and was cancelled.
                registrar.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldNotBe -1
                handle.registeredStatements shouldBe 0
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * F12/C6 (second half): a driver error raised **because** of the cancel is relabelled.
     *
     * `Statement.cancel()` works by making the driver throw on the blocked thread — SQLState 57014.
     * That exception reaches the runner before the coroutine cancellation is observed, so without
     * the conversion an ABORTED execution reports `pipeline.node.query_execution_failed`.
     */
    @Test
    fun `a driver error raised by the cancel is converted to an abort, not a failure`() =
        runBlocking<Unit> {
            val registry = InMemoryCancellationRegistry()
            val executionId = UUID.randomUUID()
            val handle = registry.register(executionId)
            val statement = RecordingStatement()

            shouldThrow<ExecutionAbortedException> {
                handle.withStatement("n", statement) {
                    registry.cancel(executionId, AbortReason.CLIENT_DISCONNECT)
                    // What H2/Postgres raise on the thread blocked in executeQuery.
                    throw java.sql.SQLException("Statement was canceled or the session timed out", "57014", 57014)
                }
            }.reason shouldBe AbortReason.CLIENT_DISCONNECT

            statement.cancels.get() shouldBe 1
        }

    /**
     * F12/C7: exactly one terminal event when a node fails *while* the execution is aborting.
     *
     * The caller node is latched inside `materialize`, so the abort lands mid-materialisation and
     * the store then throws. Both the `emitTerminal` CAS and `failNode`'s abort funnel have to
     * hold: one terminal event, and no `node_failed` for a failure that is really the abort.
     */
    @Test
    fun `a node failing during an abort yields one terminal event and no node_failed`() =
        runBlocking<Unit> {
            val source = h2Datasource("latched", listOf("CREATE TABLE l (n INT)", "INSERT INTO l VALUES (1)"))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("caller" to "SELECT n FROM l")),
                registry = FakeDatasourceRegistry(mapOf("latched" to source)),
                resultStore = LatchedResultStore(entered, release),
                config = ExecutorConfig(executionTimeoutSeconds = TIMEOUT_SECONDS),
            ).use { h ->
                val nodes = listOf(Fixtures.node("caller", source = "latched"))

                withTimeout(HARNESS_BUDGET_MS) {
                    val run =
                        async {
                            shouldThrow<ExecutionAbortedException> {
                                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                            }
                        }
                    val executionId = awaitNodeStarted(h, nodeId = "caller")
                    withContext(Dispatchers.IO) { entered.await() }
                    h.cancellations.cancel(executionId, AbortReason.CANCELLED)
                    release.countDown()
                    run.await()
                }

                h.emitter.count(SseEventType.EXECUTION_ABORTED) shouldBe 1
                h.emitter.count(SseEventType.PIPELINE_COMPLETED) shouldBe 0
                h.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 0
                h.emitter.count(SseEventType.NODE_FAILED) shouldBe 0
                // F8: the event is suppressed, the stats are not — the snapshot still says why.
                h.emitter
                    .allOf<ExecutionAborted>()
                    .single()
                    .nodeStats
                    .single()
                    .errorCode shouldBe PipelineErrorCodes.Result.STORAGE_UNAVAILABLE
            }
        }

    // ------------------------------------------------------------------ helpers

    /**
     * Starts a one-node execution over [Fixtures.SLOW_SQL], waits until the node has really begun,
     * then runs [cancel] and asserts the execution unwinds as an abort. Returns the execution id.
     *
     * Waiting on the emitted `node_started` — rather than sleeping a fixed interval — is what makes
     * this deterministic: cancelling before the statement exists would test nothing.
     */
    private suspend fun cancelMidFlight(
        h: ExecutorHarness,
        settleMs: Long = 0,
        cancel: (UUID) -> Unit,
    ): UUID =
        withTimeout(HARNESS_BUDGET_MS) {
            val nodes = listOf(Fixtures.node("slow", source = SLOW_DS))
            val run = async { shouldThrow<ExecutionAbortedException> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) } }

            val started = awaitNodeStarted(h)
            if (settleMs > 0) delay(settleMs)
            cancel(started)
            run.await()
            started
        }

    private suspend fun awaitNodeStarted(
        h: ExecutorHarness,
        nodeId: String? = null,
    ): UUID {
        while (h.emitter.allOf<NodeStarted>().none { nodeId == null || it.nodeId == nodeId }) delay(POLL_MS)
        return h.emitter
            .allOf<NodeStarted>()
            .first()
            .executionId
    }

    private fun harness(registry: FakeDatasourceRegistry = FakeDatasourceRegistry(mapOf(SLOW_DS to slowDatasource()))) =
        ExecutorHarness(
            templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
            registry = registry,
            config = ExecutorConfig(executionTimeoutSeconds = TIMEOUT_SECONDS),
        )

    private companion object {
        const val SLOW_DS = "slow_src"
        const val TIMEOUT_SECONDS = 120L
        const val POLL_MS = 5L
        const val HARNESS_BUDGET_MS = 60_000L
        const val RACE_ROUNDS = 200
        const val RACE_HOLD_MS = 2L
        const val RACE_TIMEOUT_SECONDS = 10L

        /** Long enough for the driver to have the statement genuinely executing — see the KDoc. */
        const val SETTLE_MS = 750L

        /** Generous, but far below SLOW_SQL's own ~57s runtime — the test still falsifies. */
        const val INTERRUPT_BUDGET_MS = 20_000L

        fun slowDatasource() = h2Datasource(SLOW_DS, listOf("CREATE TABLE unused (n INT)"))
    }
}
