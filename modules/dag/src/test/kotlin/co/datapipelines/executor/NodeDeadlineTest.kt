package co.datapipelines.executor

import co.datapipelines.events.PipelineFailed
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The per-node WALL-CLOCK deadline (dag-executor.md §5.3, 108 §A).
 *
 * The promise under test is one sentence: **no node runs past its budget, whatever the driver.**
 * Every test here is therefore written so that the driver cannot be what rescues it — the
 * statement timeout is set far above the assertion budget, and the hardest case uses a statement
 * that hears no `cancel()` at all. A test a working `queryTimeout` could also have passed would
 * prove nothing about the mechanism this round added.
 */
class NodeDeadlineTest {
    /**
     * The falsifying case: the driver **drops the cancel**, so the executor's own deadline is the
     * only thing in the system that can end this node.
     *
     * [DriverLikeStatement] is the fixture this module already uses for that behaviour — a
     * `cancel()` arriving while the driver holds no registered command is silently dropped, as
     * `StatementCancelDialectTest` measured on all five bundled drivers. Given a prologue longer
     * than the node's entire budget it becomes a driver that ignores cancellation outright,
     * without pretending to be one: the cancel is really issued, really delivered, and really
     * dropped. Both halves are asserted below, and the second is what makes this a test of the
     * DEADLINE rather than of the cancel.
     *
     * The budget is `deadline + cancel-grace + 1 s`. The grace is in it because the executor
     * genuinely waits that long before abandoning the statement; the +1 s is scheduling slack on
     * a loaded box, not a hedge — the driver call underneath runs ~8 s, so anything near it fails.
     *
     * Revert `runWithNodeDeadline` and this goes red on the elapsed-time assertion, not on the
     * code — the right way round: the node WOULD fail eventually, about eight seconds late.
     */
    @Test
    fun `a node whose driver drops the cancel still fails on schedule with node timeout`() =
        runBlocking<Unit> {
            val driver = BlockingDriver(prologueMs = IGNORED_CANCEL_PROLOGUE_MS)
            val source = h2Datasource("deaf", listOf("CREATE TABLE deaf (n INT)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to "DELETE FROM deaf")),
                registry = FakeDatasourceRegistry(mapOf("deaf" to source), blockingDriver = driver),
                config =
                    ExecutorConfig(
                        // Far above both the node deadline and the budget: `queryTimeout` cannot be
                        // what ends this node, and the double would not honour it anyway.
                        nodeQueryTimeoutSeconds = NO_RESCUE_QUERY_TIMEOUT_SECONDS,
                        nodeTimeoutSeconds = DEADLINE_SECONDS,
                        cancelGraceSeconds = GRACE_SECONDS,
                        executionTimeoutSeconds = NO_RESCUE_EXECUTION_TIMEOUT_SECONDS,
                        cancelPollIntervalSeconds = NO_RESCUE_EXECUTION_TIMEOUT_SECONDS,
                    ),
            ).use { h ->
                val nodes = listOf(Fixtures.node("slow", type = NodeType.DML, source = "deaf"))

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        val failed =
                            shouldThrow<PipelineExecutionFailed> {
                                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                            }
                        failed.errorCode shouldBe PipelineErrorCodes.Node.TIMEOUT
                        failed.errorDetails["timeout_seconds"] shouldBe DEADLINE_SECONDS
                        // The phase is the point of carrying one: this node died mid-query, and an
                        // author reading `execute` knows to make the QUERY cheaper — not the
                        // staged result smaller, which is what `stage` would have told them.
                        failed.errorDetails["phase"] shouldBe "execute"
                    }

                (elapsed < DEADLINE_SECONDS * MILLIS + GRACE_SECONDS * MILLIS + SLACK_MS).shouldBeTrue()
                // The executor DID try to stop the driver, and the driver DID drop it. Without
                // both, "the deadline fired on schedule" could be the cancel working after all.
                (driver.statement.cancels.get() >= 1).shouldBeTrue()
                driver.statement.interrupted
                    .get()
                    .shouldBeFalse()
                // A deadline is a node FAILURE, not a cancellation (§5.3, the same split the
                // execution timeout has): one `node_failed`, one terminal `pipeline_failed`,
                // and no `execution_aborted` anywhere.
                h.emitter
                    .allOf<PipelineFailed>()
                    .single()
                    .error.code shouldBe PipelineErrorCodes.Node.TIMEOUT
                h.emitter.count(co.datapipelines.events.SseEventType.NODE_FAILED) shouldBe 1
                h.emitter.count(co.datapipelines.events.SseEventType.EXECUTION_ABORTED) shouldBe 0
                h.slots.inFlight shouldBe 0
                h.cancellations.liveExecutions shouldBe 0
            }
        }

    /**
     * The node's own `settings.timeout_seconds` (contract §4.11) wins over the executor default.
     *
     * Asserted behaviourally, the way the datasource `query_timeout_seconds` precedence is: the
     * executor default here is 300 s, so a node that dies at ~1 s can only have used its own.
     */
    @Test
    fun `a node's own timeout_seconds overrides the executor default`() =
        runBlocking<Unit> {
            val source = h2Datasource("own", listOf("CREATE TABLE own (n INT)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("slow" to "DELETE FROM own")),
                registry =
                    FakeDatasourceRegistry(
                        mapOf("own" to source),
                        blockingDriver = BlockingDriver(prologueMs = IGNORED_CANCEL_PROLOGUE_MS),
                    ),
                config =
                    ExecutorConfig(
                        nodeQueryTimeoutSeconds = NO_RESCUE_QUERY_TIMEOUT_SECONDS,
                        nodeTimeoutSeconds = NO_RESCUE_NODE_TIMEOUT_SECONDS,
                        cancelGraceSeconds = GRACE_SECONDS,
                        executionTimeoutSeconds = NO_RESCUE_EXECUTION_TIMEOUT_SECONDS,
                        cancelPollIntervalSeconds = NO_RESCUE_EXECUTION_TIMEOUT_SECONDS,
                    ),
            ).use { h ->
                val nodes =
                    listOf(Fixtures.node("slow", type = NodeType.DML, source = "own", timeoutSeconds = DEADLINE_SECONDS.toInt()))

                val elapsed =
                    kotlin.system.measureTimeMillis {
                        shouldThrow<PipelineExecutionFailed> {
                            h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                        }.errorDetails["timeout_seconds"] shouldBe DEADLINE_SECONDS
                    }

                (elapsed < DEADLINE_SECONDS * MILLIS + GRACE_SECONDS * MILLIS + SLACK_MS).shouldBeTrue()
            }
        }

    /**
     * A node inside its budget is untouched — the non-vacuity floor.
     *
     * Without it every assertion above is satisfied by "the deadline fires on everything", which
     * is a broken executor that passes a timeout suite.
     */
    @Test
    fun `a node that finishes inside its deadline is unaffected`() =
        runBlocking<Unit> {
            val source = h2Datasource("fast", listOf("CREATE TABLE fast (n INT)", "INSERT INTO fast VALUES (1)"))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("quick" to "SELECT n FROM fast")),
                registry = FakeDatasourceRegistry(mapOf("fast" to source)),
                config = ExecutorConfig(nodeTimeoutSeconds = DEADLINE_SECONDS * 2, executionTimeoutSeconds = 60),
            ).use { h ->
                val nodes = listOf(Fixtures.node("quick", source = "fast", output = NodeOutput.Caller))
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
                h.emitter.count(co.datapipelines.events.SseEventType.NODE_FAILED) shouldBe 0
            }
        }

    private companion object {
        /**
         * Longer than the node's whole budget, so every cancel the executor issues lands in the
         * driver's prologue and is dropped. Long enough to be unmistakable against a ~2 s budget,
         * short enough that the abandoned thread does not outlive the test class.
         */
        const val IGNORED_CANCEL_PROLOGUE_MS = 8_000L

        const val DEADLINE_SECONDS = 1L
        const val GRACE_SECONDS = 1L
        const val MILLIS = 1_000L

        /** Scheduling slack for a box running several lanes — far below the query's ~9 s runtime. */
        const val SLACK_MS = 4_000L

        /** All three well above the budget, so none of them can be what ends the node. */
        const val NO_RESCUE_QUERY_TIMEOUT_SECONDS = 300
        const val NO_RESCUE_NODE_TIMEOUT_SECONDS = 300L
        const val NO_RESCUE_EXECUTION_TIMEOUT_SECONDS = 300L
    }
}
