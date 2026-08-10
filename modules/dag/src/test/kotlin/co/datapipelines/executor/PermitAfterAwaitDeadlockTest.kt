package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test

/**
 * The **deadlock regression test** dag-executor.md §14 requires.
 *
 * §5.2: the parallelism permit is taken **after** `awaitAll(deps)`. Taking it before would let
 * `max-parallel-nodes` coroutines sit blocked on dependencies while holding every permit, so the
 * dependencies they wait for could never acquire one — any chain longer than `max-parallel-nodes`
 * would hang until the execution timeout.
 *
 * Two tests, and the pair is the point:
 *  1. [a linear chain longer than max-parallel-nodes completes] runs the **real executor** over a
 *     6-node chain with `max-parallel-nodes = 2`. It fails (by timing out) if the ordering is ever
 *     inverted in `PipelineExecutor.runNodes`.
 *  2. [the inverted ordering really does deadlock the same shape] replicates both orderings in
 *     ~15 lines and shows permit-first hangs while permit-after completes. Without it, test 1
 *     passing would be evidence of nothing in particular: a test that cannot fail is not a guard.
 *     This is what proves test 1 discriminates.
 */
class PermitAfterAwaitDeadlockTest {
    /**
     * `runBlocking`, not `runTest`, and deliberately so: `runTest` runs on a virtual-time
     * scheduler, and the executor's own `withTimeout(execution-timeout-seconds)` and cancel-flag
     * `delay` would then fire the instant the test scheduler considered itself idle — while the
     * real node work was still running on the executor's own dispatcher. Virtual time is the
     * right tool for the pure-coroutine replica below; it would make this test lie.
     */
    @Test
    fun `a linear chain longer than max-parallel-nodes completes`() =
        runBlocking<Unit> {
            val chain = CHAIN_LENGTH
            val nodes =
                (1..chain).map { index ->
                    Fixtures.node(
                        id = "n$index",
                        output = if (index == chain) NodeOutput.Caller else NodeOutput.Tempdb("t$index"),
                        dependsOn = if (index == 1) emptyList() else listOf("n${index - 1}"),
                    )
                }
            val sql =
                (1..chain).associate { index ->
                    "n$index" to if (index == 1) "SELECT 1 AS n" else """SELECT n FROM "t${index - 1}" """
                }

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(sql),
                // Strictly fewer permits than the chain is long: the shape §5.2 calls out.
                config = ExecutorConfig(maxParallelNodes = 2, executionTimeoutSeconds = EXECUTION_TIMEOUT_SECONDS),
            ).use { harness ->
                val result = harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                result.status shouldBe ExecutionStatus.SUCCESS
                result.nodeStats.size shouldBe chain
                result.nodeStats.all { it.status == NodeStatus.SUCCESS } shouldBe true
                result.resultRef.shouldNotBeNull()
            }
        }

    @Test
    fun `the inverted ordering really does deadlock the same shape`() =
        runTest {
            // Permit AFTER await — the shipped ordering. Completes.
            withTimeoutOrNull(FALSIFY_TIMEOUT_MS) { runChain(permitBeforeAwait = false) }.shouldNotBeNull()

            // Permit BEFORE await — the regression. Never completes: the two permits are held by
            // coroutines waiting for nodes that cannot get a permit.
            withTimeoutOrNull(FALSIFY_TIMEOUT_MS) { runChain(permitBeforeAwait = true) }.shouldBeNull()
        }

    /**
     * A minimal replica of `PipelineExecutor.runNodes`' scheduling: every node started up front, a
     * chain of dependencies, and one semaphore — with the permit taken on either side of the
     * await, selected by [permitBeforeAwait].
     *
     * ## Why the coroutines are started tail-first
     *
     * Both arms use the **same** start order, and it is the reverse of the topological one. That
     * is not stacking the deck: the executor dispatches its node coroutines onto a multi-threaded
     * pool, so nothing orders permit *acquisition* — six coroutines hit `Semaphore.acquire`
     * concurrently and any of them can win. Tail-first is simply one order that pool can produce,
     * made deterministic here.
     *
     * The property being demonstrated is therefore the real one: **permit-after-await completes
     * under every acquisition order; permit-before-await does not.** Under this order the two
     * permits are taken by nodes 6 and 5, both waiting on predecessors that can now never acquire
     * one — a permanent deadlock, which is exactly what a flaky "it usually passed in CI"
     * regression would look like in production.
     */
    private suspend fun runChain(permitBeforeAwait: Boolean): List<Int> =
        coroutineScope {
            val permits = Semaphore(2)
            val scheduled = LinkedHashMap<Int, Deferred<Int>>()
            (1..CHAIN_LENGTH).forEach { index ->
                val dependencies = listOfNotNull(scheduled[index - 1])
                scheduled[index] =
                    async(start = CoroutineStart.LAZY) {
                        if (permitBeforeAwait) {
                            permits.withPermit {
                                dependencies.awaitAll()
                                index
                            }
                        } else {
                            dependencies.awaitAll()
                            permits.withPermit { index }
                        }
                    }
            }
            scheduled.keys.reversed().forEach { scheduled.getValue(it).start() }
            scheduled.values.awaitAll()
        }

    private companion object {
        const val CHAIN_LENGTH = 6
        const val EXECUTION_TIMEOUT_SECONDS = 60L
        const val FALSIFY_TIMEOUT_MS = 1_000L
    }
}
