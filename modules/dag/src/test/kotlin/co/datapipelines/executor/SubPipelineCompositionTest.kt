package co.datapipelines.executor

import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeType
import io.kotest.assertions.throwables.shouldThrow
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
                val parent = h.executor.execute(Fixtures.request(parentPipeline(), userId = userId))

                parent.status shouldBe ExecutionStatus.SUCCESS
                val ctx = seen.get()
                ctx shouldNotBe null
                // D9: the child runs under the parent's principal; §5: the root IS the parent.
                ctx.userId shouldBe userId
                ctx.rootExecutionId shouldBe parent.executionId
                ctx.compositionDepth shouldBe 0

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

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 3600L
    }
}
