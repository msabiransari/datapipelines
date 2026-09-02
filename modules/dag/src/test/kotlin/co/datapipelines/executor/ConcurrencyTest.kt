package co.datapipelines.executor

import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency controls (dag-executor.md §5.3, §12.1, §14 "Concurrency tests").
 *
 * Every claim here is exercised with real concurrent coroutines against real JDBC — a
 * single-threaded test of concurrent code proves only that the code compiles.
 */
class ConcurrencyTest {
    @Test
    fun `the per-user limit rejects the surplus with concurrency_limit while the global stays free`() =
        runBlocking<Unit> {
            val user = UUID.randomUUID()
            val slots = ExecutionSlots(maxPerUser = 2, maxPerInstance = 100)
            val holding = CountDownLatch(2)

            val held =
                (1..2).map {
                    async {
                        slots.withSlot(user) {
                            holding.countDown()
                            delay(HOLD_MS)
                        }
                    }
                }
            while (holding.count > 0) delay(POLL_MS)

            val rejected = shouldThrow<PipelineConcurrencyLimitException> { slots.withSlot(user) { } }
            rejected.scope shouldBe LimitScope.PER_USER
            rejected.code shouldBe PipelineErrorCodes.Execution.CONCURRENCY_LIMIT
            // A per-user rejection must not burn a global slot on the way out.
            slots.inFlight shouldBe 2

            // Another user is unaffected — the limit is per user, not a global queue.
            slots.withSlot(UUID.randomUUID()) { slots.inFlight shouldBe 3 }

            held.awaitAll()
            slots.inFlight shouldBe 0
            slots.trackedUsers shouldBe 0
        }

    @Test
    fun `the global limit rejects even a user with slots to spare`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 10, maxPerInstance = 2)
            val holding = CountDownLatch(2)

            val held =
                (1..2).map {
                    async {
                        slots.withSlot(UUID.randomUUID()) {
                            holding.countDown()
                            delay(HOLD_MS)
                        }
                    }
                }
            while (holding.count > 0) delay(POLL_MS)

            shouldThrow<PipelineConcurrencyLimitException> { slots.withSlot(UUID.randomUUID()) { } }
                .scope shouldBe LimitScope.GLOBAL

            held.awaitAll()
            slots.inFlight shouldBe 0
        }

    /**
     * The per-user check-and-increment holds under a **real** race.
     *
     * Two defects had to go before this asserted anything (B6/C1, C2):
     *  - bare `async` runs on `runBlocking`'s single-threaded event loop, so the "racers" were
     *    interleaved cooperatively and never raced at all — the test finished in 8ms and a naive
     *    get-then-put implementation would have sailed through it. They now run on a pool with a
     *    thread **per racer**, behind a `CyclicBarrier`, so every racer reaches the admission call
     *    at the same instant on a different thread. (The pool must be that wide: `CyclicBarrier`
     *    blocks its thread, so on a core-count dispatcher the barrier can never fill and the test
     *    deadlocks rather than racing — which it did, once.)
     *  - the limits were equal (`maxPerUser == maxPerInstance`) and the peak sampled the **global**
     *    counter, which made per-user over-admission arithmetically unobservable: the global cap
     *    would have hidden it. The global is now roomy and the peak samples `inFlightFor(user)`,
     *    so the per-user rule is the only thing this can fail on.
     */
    @Test
    fun `concurrent admission never oversubscribes the per-user limit`() =
        runBlocking<Unit> {
            val user = UUID.randomUUID()
            val slots = ExecutionSlots(maxPerUser = PER_USER_LIMIT, maxPerInstance = ROOMY_GLOBAL)
            val peak = AtomicInteger()
            val admitted = AtomicInteger()
            val startLine = CyclicBarrier(RACERS)
            val racers = Executors.newFixedThreadPool(RACERS)

            try {
                val pool = racers.asCoroutineDispatcher()
                (1..RACERS)
                    .map {
                        async(pool) {
                            startLine.await()
                            runCatching {
                                slots.withSlot(user) {
                                    admitted.incrementAndGet()
                                    peak.updateAndGet { p -> maxOf(p, slots.inFlightFor(user)) }
                                    delay(SHORT_HOLD_MS)
                                }
                            }
                        }
                    }.awaitAll()
            } finally {
                racers.shutdownNow()
            }

            // The claim: never more than `maxPerUser` of this user's executions in flight at once.
            (peak.get() <= PER_USER_LIMIT).shouldBeTrue()
            // ...and the limiter did not simply reject everything, which would also satisfy it.
            (admitted.get() >= PER_USER_LIMIT).shouldBeTrue()
            slots.inFlight shouldBe 0
            slots.trackedUsers shouldBe 0
        }

    /**
     * Four sibling nodes, each a ~1s query.
     *
     * The baseline is **measured, not assumed**: the same pipeline is run once with
     * `max-parallel-nodes = 1` and once with `= 4`, and the parallel run must be substantially
     * faster. A hard-coded millisecond floor would be a machine-speed bet that either goes flaky on
     * a loaded CI box or silently stops discriminating on a fast one.
     */
    @Test
    fun `independent nodes really run at the same time`() =
        runBlocking<Unit> {
            val serial = runFanOut(maxParallelNodes = 1)
            val parallel = runFanOut(maxParallelNodes = 4)

            // Four-way parallelism cannot reach 4× on a shared box, but "meaningfully faster than
            // serial" is exactly the claim, and serial scheduling cannot satisfy it.
            (parallel < serial * PARALLEL_SPEEDUP_FACTOR).shouldBeTrue()
        }

    /**
     * §5.3: blowing the overall timeout is a **FAILURE**. `ABORTED` is reserved for the three
     * cancellation paths, and conflating them would tell an operator a caller left when in fact the
     * pipeline is too slow.
     *
     * The pipeline is a chain of second-scale nodes, so the timeout fires mid-chain and the unwind
     * is deterministic. `a timed-out node has its source query interrupted` covers the harder
     * half — that the timeout actually reaches a node blocked in JDBC.
     */
    @Test
    fun `the execution timeout fails the run with pipeline_execution_timeout, not ABORTED`() =
        runBlocking<Unit> {
            val source = h2Datasource("to", listOf("CREATE TABLE to_t (n INT)"))
            val nodes =
                (1..CHAIN).map { i ->
                    Fixtures.node(
                        id = "c$i",
                        source = "to",
                        output = NodeOutput.Tempdb("t$i"),
                        dependsOn = if (i == 1) emptyList() else listOf("c${i - 1}"),
                    )
                }

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine((1..CHAIN).associate { "c$it" to MEDIUM_SQL }),
                registry = FakeDatasourceRegistry(mapOf("to" to source)),
                config = ExecutorConfig(maxParallelNodes = 1, executionTimeoutSeconds = 1, cancelPollIntervalSeconds = 1),
            ).use { h ->
                val failure =
                    withTimeout(TIMEOUT_BUDGET_MS) {
                        shouldThrow<PipelineTimeoutException> {
                            h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                        }
                    }

                failure.code shouldBe PipelineErrorCodes.Execution.TIMEOUT
                h.emitter
                    .allOf<PipelineFailed>()
                    .single()
                    .error.code shouldBe PipelineErrorCodes.Execution.TIMEOUT
                h.emitter.count(co.datapipelines.events.SseEventType.EXECUTION_ABORTED) shouldBe 0
                h.emitter.count(co.datapipelines.events.SseEventType.PIPELINE_COMPLETED) shouldBe 0
                h.slots.inFlight shouldBe 0
                h.cancellations.liveExecutions shouldBe 0
            }
        }

    /**
     * B4a: the execution timeout must reach the **source query**, not merely the coroutine.
     *
     * `withTimeout` cancels the scope, but a node blocked inside `executeQuery` observes nothing
     * until that call returns — only `Statement.cancel()` stops the query on the source server.
     * Before the fix nothing on the timeout path cancelled statements, so this execution ran for
     * SLOW_SQL's full ~57s with its caller long gone: §2 principle 7 ("a caller that leaves never
     * keeps a source database busy") held for `DELETE` and disconnect but not for timeout.
     *
     * The node query timeout is deliberately far above both the execution timeout and the
     * assertion budget, so `queryTimeout` cannot be what rescues this — only the statement cancel
     * can. That is what makes the elapsed-time assertion falsifying rather than decorative.
     */
    @Test
    fun `a timed-out node has its source query interrupted, not merely its coroutine`() =
        runBlocking<Unit> {
            val source = h2Datasource("interrupt", listOf("CREATE TABLE t (n INT)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
                registry = FakeDatasourceRegistry(mapOf("interrupt" to source)),
                config =
                    ExecutorConfig(
                        executionTimeoutSeconds = 2,
                        nodeQueryTimeoutSeconds = NO_RESCUE_QUERY_TIMEOUT_SECONDS,
                        cancelPollIntervalSeconds = 1,
                    ),
            ).use { h ->
                val nodes = listOf(Fixtures.node("slow", source = "interrupt"))

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        shouldThrow<PipelineTimeoutException> {
                            h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                        }.code shouldBe PipelineErrorCodes.Execution.TIMEOUT
                    }

                (elapsed < INTERRUPT_BUDGET_MS).shouldBeTrue()
                // The unwind is a FAILED timeout, not an ABORTED cancellation (§5.3) — and the
                // node's interrupted driver error must not surface as a spurious node_failed.
                h.emitter
                    .allOf<PipelineFailed>()
                    .single()
                    .error.code shouldBe PipelineErrorCodes.Execution.TIMEOUT
                h.emitter.count(co.datapipelines.events.SseEventType.EXECUTION_ABORTED) shouldBe 0
                h.emitter.count(co.datapipelines.events.SseEventType.NODE_FAILED) shouldBe 0
                h.slots.inFlight shouldBe 0
            }
        }

    private suspend fun runFanOut(maxParallelNodes: Int): Long {
        val source = h2Datasource("par$maxParallelNodes", listOf("CREATE TABLE par (n INT)"))
        val nodes = (1..FAN_OUT).map { Fixtures.node("n$it", source = "par$maxParallelNodes", output = NodeOutput.Tempdb("t$it")) }

        return ExecutorHarness(
            templateEngine = Fixtures.templateEngine((1..FAN_OUT).associate { "n$it" to MEDIUM_SQL }),
            registry = FakeDatasourceRegistry(mapOf("par$maxParallelNodes" to source)),
            config = ExecutorConfig(maxParallelNodes = maxParallelNodes, executionTimeoutSeconds = TIMEOUT_SECONDS),
        ).use { h ->
            kotlin.system
                .measureTimeMillis {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
                }.also { h.emitter.allOf<NodeStarted>().size shouldBe FAN_OUT }
        }
    }

    @Test
    fun `a datasource query_timeout_seconds overrides the executor default for its nodes`() =
        runBlocking<Unit> {
            // datasources §5.5 precedence, asserted behaviourally: the executor default is 600s
            // here, so a query that dies after ~1s can only have used the datasource's own value.
            val source = h2Datasource("qt", listOf("CREATE TABLE qt (n INT)"), queryTimeoutSeconds = 1)
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
                registry = FakeDatasourceRegistry(mapOf("qt" to source)),
                config = ExecutorConfig(nodeQueryTimeoutSeconds = 600, executionTimeoutSeconds = TIMEOUT_SECONDS),
            ).use { h ->
                val nodes = listOf(Fixtures.node("slow", source = "qt"))

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        shouldThrow<PipelineExecutionFailed> {
                            h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                        }.errorCode shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
                    }

                (elapsed < QUERY_TIMEOUT_BUDGET_MS).shouldBeTrue()
            }
        }

    @Test
    fun `with no datasource override the executor default is what bounds the node`() =
        runBlocking<Unit> {
            val source = h2Datasource("qd", listOf("CREATE TABLE qd (n INT)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
                registry = FakeDatasourceRegistry(mapOf("qd" to source)),
                config = ExecutorConfig(nodeQueryTimeoutSeconds = 1, executionTimeoutSeconds = TIMEOUT_SECONDS),
            ).use { h ->
                val nodes = listOf(Fixtures.node("slow", source = "qd"))

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        shouldThrow<PipelineExecutionFailed> {
                            h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                        }.errorCode shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
                    }

                (elapsed < QUERY_TIMEOUT_BUDGET_MS).shouldBeTrue()
            }
        }

    private companion object {
        const val PER_USER_LIMIT = 3

        /** Far above the per-user limit, so the global can never be what caps the peak (C2). */
        const val ROOMY_GLOBAL = 40
        const val RACERS = 40
        const val HOLD_MS = 300L
        const val SHORT_HOLD_MS = 5L
        const val POLL_MS = 2L
        const val TIMEOUT_SECONDS = 120L
        const val TIMEOUT_BUDGET_MS = 30_000L
        const val QUERY_TIMEOUT_BUDGET_MS = 20_000L

        /** Far above the execution timeout AND the budget, so queryTimeout cannot rescue the test. */
        const val NO_RESCUE_QUERY_TIMEOUT_SECONDS = 300

        /** Well inside SLOW_SQL's ~57s natural runtime — the test still falsifies. */
        const val INTERRUPT_BUDGET_MS = 25_000L

        const val FAN_OUT = 4
        const val CHAIN = 4

        /** Parallel must beat serial by a clear margin; loose enough to survive a loaded CI box. */
        const val PARALLEL_SPEEDUP_FACTOR = 0.7

        /**
         * ~1s of real work. Calibrated, not guessed: SLOW_SQL's 9·10⁸ row visits take ≈57s here, so
         * ≈1.6·10⁷ visits (4000 × 4000) is the ~1s target.
         */
        const val MEDIUM_SQL =
            """SELECT COUNT(*) AS c FROM SYSTEM_RANGE(1, 4000) a, SYSTEM_RANGE(1, 4000) b WHERE MOD(a."X" + b."X", 7) = 0"""
    }
}
