package co.datapipelines.executor

import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.NodeStarted
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

/**
 * 086 B — the cancel race, swept instead of stepped over.
 *
 * `CancellationTest` used to wait 750 ms before cancelling, on the belief that the window between
 * `node_started` and a cancellable statement was a driver caveat to be dodged. It is not: it is a
 * window the executor owns (§8.3.2), and 750 ms was in any case a guess about an idle box — under
 * load on the shared machine the statement was still not executing when the cancel arrived, the
 * cancel was dropped by the driver, `Fixtures.SLOW_SQL` ran its full ~57 s, and the harness's own
 * `withTimeout` fired: `Expected exception ExecutionAbortedException but a
 * TimeoutCancellationException was thrown instead`, red in 5 of 9 full gates over one week.
 *
 * ## Why the window is created rather than waited for
 *
 * The first version of this sweep cancelled a fixed 0–50 ms after `node_started` and was **green
 * with the fix reverted**: on an idle box the node thread reaches `executeQuery` in well under a
 * millisecond, so the cancel always found a registered command and H2 honoured it. It measured
 * the in-flight path while claiming to measure the race — the exact "a guard that cannot go red on
 * revert is not a guard" trap.
 *
 * So the sweep supplies the one thing an idle box does not: the *descheduling*.
 * `FakeDatasourceRegistry(driverPrologueMs = …)` makes each statement enter the driver a
 * randomised 0–[MAX_PROLOGUE_MS] ms late, and the cancel is fired the instant the statement is
 * **registered** — deterministically inside the window rather than hopefully near it. Only the
 * scheduler is simulated: the statement is a real H2 one, and the cancel that lands during the
 * prologue is dropped by H2 itself.
 *
 * ## The non-vacuity floor
 *
 * The decorator counts the cancels that arrived while it was still in its prologue —
 * `cancelsInPrologue`, the executor-external record of "this iteration really was the race". The
 * run asserts at least [MIN_IN_WINDOW] of [ITERATIONS], so a sweep that stopped reproducing the
 * window fails loudly instead of passing for the wrong reason.
 */
class CancelRaceStressTest {
    @Test
    fun `a cancel landing anywhere in the registration window still aborts the execution`() =
        runBlocking<Unit> {
            var inWindow = 0
            var slowest = 0L

            repeat(ITERATIONS) { iteration ->
                val prologueMs = Random(SEED + iteration).nextLong(MAX_PROLOGUE_MS + 1)
                val registry =
                    FakeDatasourceRegistry(
                        datasources = mapOf(SLOW_DS to h2Datasource(SLOW_DS, listOf("CREATE TABLE unused (n INT)"))),
                        driverPrologueMs = { prologueMs },
                    )
                ExecutorHarness(
                    templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
                    registry = registry,
                    config = ExecutorConfig(executionTimeoutSeconds = EXECUTION_TIMEOUT_SECONDS),
                ).use { h ->
                    val elapsed =
                        kotlin.system.measureTimeMillis {
                            withTimeout(ITERATION_BUDGET_MS) {
                                val nodes = listOf(Fixtures.node("slow", source = SLOW_DS))
                                val run =
                                    async {
                                        shouldThrow<ExecutionAbortedException> {
                                            h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                                        }
                                    }

                                val executionId = awaitNodeStarted(h)
                                // Registered, and — for every non-zero prologue — not yet in the
                                // driver. This is the window, entered on purpose.
                                while (h.cancellations.registeredFor(executionId) < 1) delay(POLL_MS)
                                h.cancellations.cancel(executionId, AbortReason.CANCELLED)

                                run.await().reason shouldBe AbortReason.CANCELLED
                            }
                        }
                    slowest = maxOf(slowest, elapsed)
                    inWindow += registry.cancelsInPrologue.get()

                    h.emitter
                        .allOf<ExecutionAborted>()
                        .single()
                        .reason shouldBe AbortReason.CANCELLED
                }
            }

            println("086 B | $ITERATIONS iterations | cancels-in-window=$inWindow | slowest=${slowest}ms")
            (inWindow >= MIN_IN_WINDOW).shouldBeTrue()
        }

    private suspend fun awaitNodeStarted(h: ExecutorHarness): UUID {
        while (h.emitter.allOf<NodeStarted>().isEmpty()) delay(POLL_MS)
        return h.emitter
            .allOf<NodeStarted>()
            .first()
            .executionId
    }

    private companion object {
        const val SLOW_DS = "slow_src"
        const val ITERATIONS = 20

        /** The width of the descheduling the sweep injects — the prompt's 0–50 ms. */
        const val MAX_PROLOGUE_MS = 50L
        const val POLL_MS = 5L
        const val SEED = 86L
        const val EXECUTION_TIMEOUT_SECONDS = 120L

        /**
         * A twelfth of `SLOW_SQL`'s ~57 s. An iteration that returns inside this cannot have run
         * the query, so the assertion is about the cancel path and nothing else.
         */
        const val ITERATION_BUDGET_MS = 5_000L

        /**
         * The non-vacuity floor. A zero prologue is drawn occasionally and cannot be counted, so
         * this sits below [ITERATIONS] — but far enough above zero that a sweep which stopped
         * reproducing the window cannot pass.
         */
        const val MIN_IN_WINDOW = ITERATIONS * 3 / 4
    }
}
