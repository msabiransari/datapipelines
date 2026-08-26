package co.datapipelines.executor

import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The composition runtime path through the executor (design 2026-08-13-pipeline-node-type §4): a
 * PIPELINE node's [SubPipelineRunner] receives a [NodeExecutionContext] carrying everything the
 * child request needs — the parent's principal (D9), the family root (§4.3/D8), and the depth
 * counter (§4.4) — and the node's stats link back to the child execution it spawned.
 *
 * The runners below are the *minimal honest* doubles: they re-enter the SAME executor with a
 * child request built from the context, so lineage threading and family cancellation are
 * exercised against the real engine rather than re-asserted against a mock.
 */
class SubPipelineCompositionTest {
    /** The simplest parent: one PIPELINE node and nothing else (a side-effect composition). */
    private fun parentPipeline() = Fixtures.pipeline(listOf(Fixtures.node("child_ref", type = NodeType.PIPELINE, source = "")))

    /** The simplest runnable child: one DQL caller node reading tempdb. */
    private fun childPipeline() = Fixtures.pipeline(listOf(Fixtures.node("q")))

    private fun childRequest(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
    ) = ExecuteRequest(
        pipelineId = UUID.randomUUID(),
        pipelineVersion = 1,
        pipeline = childPipeline(),
        userId = ctx.userId,
        parentExecutionId = ctx.executionId,
        parentNodeId = node.id,
        rootExecutionId = ctx.rootExecutionId,
        compositionDepth = ctx.compositionDepth + 1,
        triggeredVia = ExecutionTrigger.PIPELINE,
    )

    /**
     * The harness needs the runner at construction and the runner needs the harness's executor —
     * the reference breaks the construction cycle (web's real runner has the same shape: it
     * builds child executors from shared collaborators and passes itself as their runner).
     */
    private fun harnessWithRunner(
        flags: AtomicReference<CancellationFlags>? = null,
        runner: suspend (executor: PipelineExecutor, node: ExecutableNode, ctx: NodeExecutionContext) -> NodeResult,
    ): ExecutorHarness {
        val executorRef = AtomicReference<PipelineExecutor>()
        val harness =
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("q" to "SELECT 1 AS n")),
                subPipelineRunner = SubPipelineRunner { node, ctx -> runner(executorRef.get(), node, ctx) },
            )
        executorRef.set(harness.executor)
        flags?.set(harness.flags)
        return harness
    }

    @Test
    fun `a PIPELINE node's context carries the principal, the family root, and the depth counter`() =
        runBlocking<Unit> {
            val seen = AtomicReference<NodeExecutionContext>()
            val childExecutionId = AtomicReference<UUID>()

            harnessWithRunner { executor, node, ctx ->
                seen.set(ctx)
                val child = executor.execute(childRequest(node, ctx))
                childExecutionId.set(child.executionId)
                NodeResult.of(node.id, 0, Instant.now(), childExecutionId = child.executionId)
            }.use { h ->
                val userId = UUID.randomUUID()
                val correlationId = UUID.randomUUID()
                val parent =
                    h.executor.execute(Fixtures.request(parentPipeline(), userId = userId, correlationId = correlationId))

                parent.status shouldBe ExecutionStatus.SUCCESS
                val ctx = seen.get()
                ctx shouldNotBe null
                // D9: the child runs under the parent's principal; §5: the root IS the parent.
                ctx.userId shouldBe userId
                ctx.rootExecutionId shouldBe parent.executionId
                ctx.compositionDepth shouldBe 0
                // F5: and the id of the request that started the family, so the child's request can
                // inherit it instead of minting one nothing else in the family shares.
                ctx.correlationId shouldBe correlationId

                // §5: the parent's node stats link the PIPELINE node to the child execution.
                val stats =
                    h.emitter
                        .allOf<PipelineCompleted>()
                        .last()
                        .nodeStats
                        .single { it.nodeId == "child_ref" }
                stats.childExecutionId shouldBe childExecutionId.get()
            }
        }

    /**
     * Family cancellation (design §4.3, D8) through the composition path: the root's flag is set
     * AFTER the parent started (mid-node, where a `DELETE /executions/{id}` lands), so the child
     * — started after the flag existed — reads it at its first node boundary and never runs a
     * node; the abort then unwinds the parent too.
     */
    @Test
    fun `a child started after the root cancel flag was set aborts before running any node`() =
        runBlocking<Unit> {
            val flags = AtomicReference<CancellationFlags>()

            harnessWithRunner(flags) { executor, node, ctx ->
                // The cancel lands while the parent is mid-PIPELINE-node — the one moment the
                // flag pre-dates the child's start.
                flags.get().request(ctx.rootExecutionId, AbortReason.CANCELLED, TEST_TIMEOUT_SECONDS)
                executor.execute(childRequest(node, ctx))
                error("the child must have aborted before completing")
            }.use { h ->
                shouldThrow<ExecutionAbortedException> {
                    h.executor.execute(Fixtures.request(parentPipeline()))
                }

                // Nobody completed: the child aborted at its first boundary, and the abort then
                // unwound the parent — every aborted execution reports the family's reason.
                h.emitter.count(SseEventType.PIPELINE_COMPLETED) shouldBe 0
                val aborted = h.emitter.allOf<ExecutionAborted>()
                aborted.shouldNotBeEmpty()
                aborted.map { it.reason }.toSet() shouldBe setOf(AbortReason.CANCELLED)
            }
        }

    /**
     * F1 — the case the flag-set-before-start test above structurally cannot reach.
     *
     * ## The machinery, named
     *
     * The child runs *inside* the parent node's coroutine, so when the parent's
     * `withTimeout(execution-timeout-seconds)` fires it is **structured concurrency** — not any code
     * in this repository — that decides what reaches the child. Measured against the pinned
     * kotlinx: a cancellation cause that is already a `CancellationException` is handed down to
     * children **unwrapped** (`JobSupport.getChildJobCancellationCause`), so the parent's
     * `TimeoutCancellationException` arrives at the child as that exact type — and the child
     * reported it as *its own* `pipeline.execution.timeout`, a `FAILED` outcome for an execution
     * that never came near its deadline. `PipelineExecutor.cancelledByAncestor` is the fix's
     * discriminator, and this test is what proves it fires.
     *
     * The user-visible half of the same defect is the durable row: `WebEventEmitter.emit` persists
     * behind `withContext(persistenceDispatcher)`, which refuses to start on a cancelled job, so
     * the terminal UPDATE never ran and the child's `pipeline_executions` row stayed `RUNNING` /
     * `completed_at = NULL` forever. That is asserted where it is visible — at the database level,
     * in `PipelineCompositionE2eTest`; `ExecutionRun.emitTerminal`'s `NonCancellable` is its fix.
     *
     * ## Why two harnesses
     *
     * In production the parent and the child share one `execution-timeout-seconds`, and the parent's
     * clock starts first, so the parent's deadline always fires first. Two executors (parent 2s,
     * child 120s) make that ordering deterministic rather than a race the test would intermittently
     * lose to the child's own deadline — which is the already-handled path and would pass vacuously.
     */
    @Test
    fun `a child killed by the parent's timeout aborts, and never reports the parent's timeout as its own`() =
        runBlocking<Unit> {
            val childExecutionId = UUID.randomUUID()
            childHarness().use { child ->
                parentHarness(child, childExecutionId).use { parent ->
                    shouldThrow<PipelineTimeoutException> {
                        parent.executor.execute(Fixtures.request(parentPipeline()))
                    }

                    // The child started — otherwise the assertions below would be vacuous.
                    child.emitter.allOf<ExecutionStarted>().map { it.executionId } shouldContain childExecutionId
                    // …and it ended ABORTED: cancellation is not failure (§8.3).
                    child.emitter.allOf<ExecutionAborted>().map { it.executionId } shouldContain childExecutionId
                    // Not `pipeline_failed(execution.timeout)` — the child's own deadline never fired.
                    child.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 0
                    // The parent's own outcome is unchanged: its timeout IS a failure.
                    parent.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 1
                }
            }
        }

    /** The child's executor: a genuinely slow node and a deadline far beyond the parent's. */
    private fun childHarness() =
        ExecutorHarness(
            templateEngine = Fixtures.templateEngine(mapOf("slow" to Fixtures.SLOW_SQL)),
            registry = FakeDatasourceRegistry(mapOf(SLOW_DS to h2Datasource(SLOW_DS, listOf("CREATE TABLE unused (n INT)")))),
            config = ExecutorConfig(executionTimeoutSeconds = PARENT_TIMEOUT_SECONDS * CHILD_TIMEOUT_FACTOR),
        )

    /** The parent's executor: a PIPELINE node that hands off to [child], and a short deadline. */
    private fun parentHarness(
        child: ExecutorHarness,
        childExecutionId: UUID,
    ) = ExecutorHarness(
        templateEngine = Fixtures.templateEngine(mapOf("child_ref" to "")),
        config = ExecutorConfig(executionTimeoutSeconds = PARENT_TIMEOUT_SECONDS),
        subPipelineRunner =
            SubPipelineRunner { node, ctx ->
                child.executor.execute(
                    ExecuteRequest(
                        pipelineId = UUID.randomUUID(),
                        pipelineVersion = 1,
                        pipeline = Fixtures.pipeline(listOf(Fixtures.node("slow", source = SLOW_DS))),
                        userId = ctx.userId,
                        triggeredVia = ExecutionTrigger.PIPELINE,
                        executionId = childExecutionId,
                        parentExecutionId = ctx.executionId,
                        parentNodeId = node.id,
                        rootExecutionId = ctx.rootExecutionId,
                        compositionDepth = ctx.compositionDepth + 1,
                    ),
                )
                NodeResult.of(node.id, 0, Instant.now(), childExecutionId = childExecutionId)
            },
    )

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 3600L
        const val SLOW_DS = "slow_src"
        const val PARENT_TIMEOUT_SECONDS = 2L
        const val CHILD_TIMEOUT_FACTOR = 60L
    }
}
