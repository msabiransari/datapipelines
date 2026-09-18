package co.datapipelines.executor

import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
     * Four sibling nodes, each a query that blocks on a shared rendezvous **inside H2** — the
     * `node_rendezvous()` alias, a real user-defined function the driver calls on the node's
     * thread while the statement is executing. Nothing about the executor is faked.
     *
     * "Independent nodes run at the same time" means: at some instant, more than one node is
     * INSIDE its query. That, and only that, is what this asserts (O7, 128 §A):
     *  - `max-parallel-nodes = 4`: the four queries meet at the barrier, so all four were inside
     *    at once (`peak == 4`, no timeouts), and the pipeline completes. The barrier's wait is a
     *    generous hang guard, never a slowness bet — a starved box makes the meeting later, not
     *    impossible.
     *  - `max-parallel-nodes = 1`: the barrier cannot trip — the first node in waits for siblings
     *    the scheduler will not start until it returns — so it gives up after a short wait, the
     *    barrier breaks, and the remaining three fall through it on entry. Every node went in
     *    alone (`peak == 1`, none met). This is the arm that keeps the test able to fail: a
     *    scheduler that ignored the limit would let the four meet here and go red on `peak`.
     *
     * No time is measured anywhere, so no load on the box can turn either arm red. The stopwatch
     * version this replaces (parallel elapsed < serial × 0.7) went red six times since August
     * with no defect anywhere, whenever another build starved four ~1 s queries into running back
     * to back.
     */
    @Test
    fun `independent nodes really run at the same time`() =
        runBlocking<Unit> {
            val parallel = runFanOut(maxParallelNodes = FAN_OUT, rendezvousTimeout = HANG_GUARD)
            parallel.peak shouldBe FAN_OUT
            parallel.missed shouldBe 0

            val serial = runFanOut(maxParallelNodes = 1, rendezvousTimeout = GIVE_UP)
            serial.peak shouldBe 1
            serial.missed shouldBe FAN_OUT
        }

    /**
     * Runs the fan-out with every node's query blocking on a fresh [NodeRendezvous] of
     * [FAN_OUT] parties, and reports what the rendezvous saw. The execution itself must succeed
     * on both arms — a node that gave up at the barrier still returns its row.
     */
    private suspend fun runFanOut(
        maxParallelNodes: Int,
        rendezvousTimeout: Duration,
    ): NodeRendezvous.Outcome {
        val rendezvous = NodeRendezvous.arm(parties = FAN_OUT, timeout = rendezvousTimeout)
        val source = h2Datasource("par$maxParallelNodes", listOf(NodeRendezvous.CREATE_ALIAS))
        val nodes = (1..FAN_OUT).map { Fixtures.node("n$it", source = "par$maxParallelNodes", output = NodeOutput.Tempdb("t$it")) }

        return ExecutorHarness(
            templateEngine = Fixtures.templateEngine((1..FAN_OUT).associate { "n$it" to RENDEZVOUS_SQL }),
            registry = FakeDatasourceRegistry(mapOf("par$maxParallelNodes" to source)),
            config = ExecutorConfig(maxParallelNodes = maxParallelNodes, executionTimeoutSeconds = TIMEOUT_SECONDS),
        ).use { h ->
            h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
            h.emitter.allOf<NodeStarted>().size shouldBe FAN_OUT
            rendezvous.outcome()
        }
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
                        val failed =
                            shouldThrow<PipelineExecutionFailed> {
                                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                            }
                        // T202: a statement the driver cancelled on OUR timeout reports the
                        // timeout and the budget it blew, not `query_execution_failed` + H2's text.
                        failed.errorCode shouldBe PipelineErrorCodes.Node.QUERY_TIMEOUT
                        failed.errorDetails["timeout_seconds"] shouldBe 1
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
                        }.errorCode shouldBe PipelineErrorCodes.Node.QUERY_TIMEOUT
                    }

                (elapsed < QUERY_TIMEOUT_BUDGET_MS).shouldBeTrue()
            }
        }

    /**
     * 156, #2 — a node's own `settings.query_timeout_seconds` is the TOP of the statement-timeout
     * precedence: it must win even over a longer datasource setting. `details.source` says so.
     */
    @Test
    fun `a node's own query_timeout_seconds overrides the datasource setting, and details name the node tier`() =
        runBlocking<Unit> {
            val source = h2Datasource("qtn", listOf("CREATE TABLE qtn (n INT)"), queryTimeoutSeconds = 600)
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
                registry = FakeDatasourceRegistry(mapOf("qtn" to source)),
                config = ExecutorConfig(nodeQueryTimeoutSeconds = 600, executionTimeoutSeconds = TIMEOUT_SECONDS),
            ).use { h ->
                val nodes = listOf(Fixtures.node("slow", source = "qtn", queryTimeoutSeconds = 1))

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        val failed =
                            shouldThrow<PipelineExecutionFailed> {
                                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                            }
                        failed.errorCode shouldBe PipelineErrorCodes.Node.QUERY_TIMEOUT
                        failed.errorDetails["timeout_seconds"] shouldBe 1
                        failed.errorDetails["source"] shouldBe "node"
                    }

                (elapsed < QUERY_TIMEOUT_BUDGET_MS).shouldBeTrue()
            }
        }

    /**
     * 156, #2 — the pipeline's `settings.query_timeout_seconds` overrides the datasource setting
     * when the node declares none of its own.
     */
    @Test
    fun `a pipeline's query_timeout_seconds overrides the datasource setting when the node declares none`() =
        runBlocking<Unit> {
            val source = h2Datasource("qtp", listOf("CREATE TABLE qtp (n INT)"), queryTimeoutSeconds = 600)
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
                registry = FakeDatasourceRegistry(mapOf("qtp" to source)),
                config = ExecutorConfig(nodeQueryTimeoutSeconds = 600, executionTimeoutSeconds = TIMEOUT_SECONDS),
            ).use { h ->
                val nodes = listOf(Fixtures.node("slow", source = "qtp"))
                val pipeline = Fixtures.pipeline(nodes, settings = PipelineSettings(queryTimeoutSeconds = 1))

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        val failed =
                            shouldThrow<PipelineExecutionFailed> {
                                h.executor.execute(Fixtures.request(pipeline))
                            }
                        failed.errorCode shouldBe PipelineErrorCodes.Node.QUERY_TIMEOUT
                        failed.errorDetails["timeout_seconds"] shouldBe 1
                        failed.errorDetails["source"] shouldBe "pipeline"
                    }

                (elapsed < QUERY_TIMEOUT_BUDGET_MS).shouldBeTrue()
            }
        }

    /**
     * 156, #2 — the operator's per-dialect default applies when neither the node, the pipeline
     * nor the datasource declares a statement timeout — falsified by removing the dialect's entry
     * from the map, which lets the same node run to the flat application default instead.
     */
    @Test
    fun `the operator's per-dialect default applies when no author or datasource tier overrides it`() =
        runBlocking<Unit> {
            val source = h2Datasource("qtd", listOf("CREATE TABLE qtd (n INT)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
                registry = FakeDatasourceRegistry(mapOf("qtd" to source)),
                config =
                    ExecutorConfig(
                        nodeQueryTimeoutSeconds = 600,
                        executionTimeoutSeconds = TIMEOUT_SECONDS,
                        nodeQueryTimeoutSecondsByDialect = mapOf(Dialect.H2 to 1),
                    ),
            ).use { h ->
                val nodes = listOf(Fixtures.node("slow", source = "qtd"))

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        val failed =
                            shouldThrow<PipelineExecutionFailed> {
                                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                            }
                        failed.errorCode shouldBe PipelineErrorCodes.Node.QUERY_TIMEOUT
                        failed.errorDetails["timeout_seconds"] shouldBe 1
                        failed.errorDetails["source"] shouldBe "dialect"
                    }

                (elapsed < QUERY_TIMEOUT_BUDGET_MS).shouldBeTrue()
            }
        }

    /**
     * §5.3 (108 §A) — the NODE deadline, beside the two statement-timeout cases above, because
     * the three are one precedence and a reader comparing them should not have to change files.
     *
     * The difference this case exists to state: both tests above are the DRIVER stopping one
     * statement on our `queryTimeout`. This one is the EXECUTOR stopping the whole node, and it is
     * set up so the driver cannot be what ends it — the statement reports no timeout at all, and
     * [DriverLikeStatement]'s prologue swallows every cancel the executor issues. `queryTimeout`
     * is 300 s besides. If the node still fails in ~2 s, only the wall-clock deadline can have
     * done it.
     */
    @Test
    fun `the node wall-clock deadline bounds a node the driver will not stop`() =
        runBlocking<Unit> {
            val driver = BlockingDriver(prologueMs = DROPPED_CANCEL_PROLOGUE_MS)
            val source = h2Datasource("nd", listOf("CREATE TABLE nd (n INT)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("wipe" to "DELETE FROM nd")),
                registry = FakeDatasourceRegistry(mapOf("nd" to source), blockingDriver = driver),
                config =
                    ExecutorConfig(
                        nodeQueryTimeoutSeconds = NO_RESCUE_QUERY_TIMEOUT_SECONDS,
                        nodeTimeoutSeconds = 1,
                        cancelGraceSeconds = 1,
                        executionTimeoutSeconds = TIMEOUT_SECONDS,
                        cancelPollIntervalSeconds = TIMEOUT_SECONDS,
                    ),
            ).use { h ->
                val nodes = listOf(Fixtures.node("wipe", type = co.datapipelines.pipeline.NodeType.DML, source = "nd"))

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        val failed =
                            shouldThrow<PipelineExecutionFailed> {
                                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                            }
                        failed.errorCode shouldBe PipelineErrorCodes.Node.TIMEOUT
                        failed.errorDetails["phase"] shouldBe "execute"
                    }

                (elapsed < NODE_DEADLINE_BUDGET_MS).shouldBeTrue()
                // The executor issued the cancel and the driver dropped it — without both, the
                // elapsed time above could be the cancel working rather than the deadline.
                (driver.statement.cancels.get() >= 1).shouldBeTrue()
                driver.statement.interrupted
                    .get()
                    .shouldBeFalse()
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

        /** Longer than the node's whole budget, so every cancel lands in the prologue and is dropped. */
        const val DROPPED_CANCEL_PROLOGUE_MS = 8_000L

        /** deadline 1s + grace 1s + slack, and far below the driver call's ~8s. */
        const val NODE_DEADLINE_BUDGET_MS = 6_000L

        const val FAN_OUT = 4
        const val CHAIN = 4

        /** Every node's query: one row, blocking inside H2 until the siblings arrive or the wait ends. */
        const val RENDEZVOUS_SQL = "SELECT node_rendezvous() AS met"

        /** Parallel arm: only a hang can exhaust this — four nodes and eight dispatcher threads. */
        val HANG_GUARD: Duration = Duration.ofSeconds(30)

        /** Serial arm: how long the lone node inside waits for siblings that cannot come. */
        val GIVE_UP: Duration = Duration.ofSeconds(2)

        /**
         * ~1s of real work. Calibrated, not guessed: SLOW_SQL's 9·10⁸ row visits take ≈57s here, so
         * ≈1.6·10⁷ visits (4000 × 4000) is the ~1s target.
         */
        const val MEDIUM_SQL =
            """SELECT COUNT(*) AS c FROM SYSTEM_RANGE(1, 4000) a, SYSTEM_RANGE(1, 4000) b WHERE MOD(a."X" + b."X", 7) = 0"""
    }
}

/**
 * The rendezvous [ConcurrencyTest] puts INSIDE each node's query.
 *
 * H2 calls [meet] through the `node_rendezvous` alias on the thread executing the statement, so
 * the count of callers currently inside [Armed.meet] is exactly the count of nodes inside their
 * query at that instant — the claim, observed where it is made rather than inferred from a
 * clock. A public object with a `@JvmStatic` method because that is the shape H2 can load and
 * invoke reflectively (`FunctionAlias` needs a public static method on a public class).
 *
 * One rendezvous is armed per run; the two arms of the test run one after the other, never at
 * once, which is what lets a static alias target reach the right instance.
 */
object NodeRendezvous {
    /** The DDL that binds the alias in a fresh H2 source, for [h2Datasource]. */
    const val CREATE_ALIAS = """CREATE ALIAS node_rendezvous FOR "co.datapipelines.executor.NodeRendezvous.meet""""

    @Volatile
    private var current: Armed? = null

    /** Arms a fresh barrier of [parties] whose waits give up after [timeout]. */
    fun arm(
        parties: Int,
        timeout: Duration,
    ): Armed = Armed(CyclicBarrier(parties), timeout).also { current = it }

    /** What H2 calls. Returns 1 when the caller met every other party, 0 when it gave up. */
    @JvmStatic
    fun meet(): Int = checkNotNull(current) { "node_rendezvous() called with no rendezvous armed" }.meet()

    /** What the rendezvous saw over one run. */
    data class Outcome(
        /** The most callers inside their query at one instant. */
        val peak: Int,
        /** Callers that entered and did not meet the other parties — timed out, or found the barrier broken. */
        val missed: Int,
    )

    class Armed(
        private val barrier: CyclicBarrier,
        private val timeout: Duration,
    ) {
        private val inside = AtomicInteger()
        private val peak = AtomicInteger()
        private val missed = AtomicInteger()

        fun meet(): Int {
            val now = inside.incrementAndGet()
            peak.updateAndGet { p -> maxOf(p, now) }
            try {
                barrier.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
                return 1
            } catch (_: TimeoutException) {
                missed.incrementAndGet()
            } catch (_: BrokenBarrierException) {
                missed.incrementAndGet()
            } finally {
                inside.decrementAndGet()
            }
            return 0
        }

        fun outcome(): Outcome = Outcome(peak = peak.get(), missed = missed.get())
    }
}
