package co.datapipelines.web.pipelines

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.CancellationFlags
import co.datapipelines.executor.CancellationRegistry
import co.datapipelines.executor.DirectResultSink
import co.datapipelines.executor.ExecutableNode
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorDispatcher
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.ExecutorMetrics
import co.datapipelines.executor.NodeExecutionContext
import co.datapipelines.executor.NodeResult
import co.datapipelines.executor.PipelineExecutionFailed
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.executor.SubPipelineRunner
import co.datapipelines.executor.WritebackRunner
import co.datapipelines.executor.pipelineExecutor
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.ParameterWireEncoder
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.staging.StagingFactory
import co.datapipelines.staging.StagingMemoryLimitException
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.WebEventEmitter
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * dag's [SubPipelineRunner] port, implemented where the execution service lives (design
 * 2026-08-13-pipeline-node-type §4.1): a PIPELINE node runs its pinned child as a **real child
 * execution** through a per-run [PipelineExecutor] — own execution record, own tempdb, own
 * stats — and this class is the bridge between the parent node's context and that child request.
 *
 * The shape mirrors [McpRecordingExecutionRunner]: one executor per child run, built from the
 * shared engine collaborators, with a recording [WebEventEmitter] and no SSE stream attached.
 * Three differences are the whole point of the type:
 *
 * - **Lineage.** The child request carries `parentExecutionId` / `parentNodeId` /
 *   `rootExecutionId` / `compositionDepth` and `triggered_via = PIPELINE`; the emitter's
 *   [ExecutionContext] carries the same three lineage columns so the child's
 *   `pipeline_executions` row links the family (metadata-db §4.6, D6: children appear in
 *   execution history). The child's id is minted HERE, before execution starts, so the parent
 *   node's error detail and stats can name it even when the child fails.
 * - **The principal is the parent's** (D9) — composition carries no new scopes; authorization
 *   was checked on the parent's execute call.
 * - **`direct` delivery.** When the node declares an `output`, the child request carries a
 *   [DirectResultSink] that lands the child's caller rows exactly where a DQL node's own
 *   ResultSet would go (design §4.2): staged into the PARENT's tempdb, re-published as the
 *   parent's caller result — or streamed onward to the parent's own invoker when the parent
 *   execution is itself a `direct` child — or written back to a datasource. A node that
 *   declares no `output` sends no sink and simply awaits the child's terminal state (D3's
 *   side-effect child).
 *
 * ## The runtime depth backstop
 *
 * Save-time validation bounds the reference tree statically (pipeline-contract §12.9); this
 * runner refuses a child whose depth would exceed `datapipelines.pipelines.max-composition-depth`
 * anyway (design §4.4 — defense in depth, because a request can in principle arrive that no
 * validator saw). `pipeline.node.composition_depth_exceeded` is the refusal.
 *
 * Both guards count the same unit — **pipelines**, the unit `CompositionRules.referenceDepth` uses
 * and the unit configuration.md documents ("the deepest admitted chain of pipelines"). See
 * [childPipelineDepth] for why, and for the off-by-one that made the backstop admit chains the
 * primary guard rejects.
 *
 * ## Failure mapping
 *
 * A failed child fails the parent node fail-fast with
 * `pipeline.node.child_execution_failed`; the detail carries the child's error code, its failed
 * node, and the child execution id, so the debugging trail leads to a real execution record
 * (design §4.3). Cancellation is **not** mapped: [kotlinx.coroutines.CancellationException]
 * (including an aborted child) propagates untouched, because family cancellation works by
 * cancelling the whole coroutine tree under the root's flag.
 */
@Suppress("LongParameterList")
class SubPipelineExecutionRunner(
    private val pipelines: PipelineRepository,
    private val templateEngines: WorkspaceTemplateEngines,
    private val datasourceRegistry: DatasourceRegistry,
    private val stagingFactory: StagingFactory,
    private val writebackRunner: WritebackRunner,
    private val resultStore: ResultStore,
    private val cancellationRegistry: CancellationRegistry,
    private val cancellationFlags: CancellationFlags,
    private val executionSlots: ExecutionSlots,
    private val executorDispatcher: ExecutorDispatcher,
    private val executorConfig: ExecutorConfig,
    private val resultUrls: ResultUrlFactory,
    private val executorMetrics: ExecutorMetrics,
    private val persistenceDispatcher: CoroutineDispatcher,
    private val streams: ExecutionStreamRegistry,
    private val eventLog: SseEventLog,
    private val eventRepository: ExecutionEventRepository,
    private val executionRepository: ExecutionRepository,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
    /**
     * Test seam, same reasoning as [ExecutionStreamLauncher]'s: builds the per-child executor. Null in
     * production wiring, where [newExecutor] assembles the real one from the shared
     * collaborators; tests substitute a mock so the request the runner builds can be captured.
     */
    private val executorFactory: ((WebEventEmitter) -> PipelineExecutor)? = null,
) : SubPipelineRunner {
    private val log = LoggerFactory.getLogger(SubPipelineExecutionRunner::class.java)

    override suspend fun run(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
    ): NodeResult {
        val startedAt = Instant.now()
        val ref = requireRef(node)
        checkDepth(node, childPipelineDepth(ctx))
        // The child resolves in the PARENT execution's workspace (design §3: cross-workspace
        // references do not exist) — carried down on the context since 025 A5, so no lookup.
        val workspaceId = ctx.workspaceId
        val (record, child) = loadChild(node, ref, workspaceId)

        // Minted before execution starts: the parent's node stats and any failure detail must be
        // able to name the child execution even when it never completes.
        val childExecutionId = UUID.randomUUID()
        val outcome = SinkOutcome()
        val request = childRequest(node, ctx, ref, record, child, childExecutionId, outcome)
        val result = executeChild(node, record, request, workspaceId)
        recordResultColumns(result)
        ensureOutputDelivered(node, request.executionId, outcome)

        return NodeResult.of(
            nodeId = node.id,
            rowsOut = outcome.rowsOut,
            startedAt = startedAt,
            callerResultRef = outcome.callerResultRef,
            bytesOutEstimate = outcome.bytesOutEstimate,
            childExecutionId = childExecutionId,
        )
    }

    /**
     * The pinned reference — non-null by save-time validation (§12.9); the guard keeps a
     * hand-built body from surfacing as a raw NPE instead of the catalogued code.
     */
    private fun requireRef(node: ExecutableNode) =
        node.pipeline
            ?: throw childFailure(
                node,
                childExecutionId = null,
                message = "PIPELINE node '${node.id}' carries no pipeline reference.",
                cause = null,
            )

    /**
     * The child's depth **in pipelines** — the unit both guards now count in (F4).
     *
     * `max-composition-depth` is documented as "the deepest admitted chain of pipelines"
     * (configuration.md §3.16) and `CompositionRules.referenceDepth` computes exactly that: a
     * pipeline with no PIPELINE nodes has depth 1, and `checkDepth` rejects `depth > max`. So
     * `max = 5` admits 5 pipelines / 4 hops, at save time.
     *
     * [NodeExecutionContext.compositionDepth] counts **hops** above this execution (a root is 0),
     * so the parent's own depth in pipelines is `compositionDepth + 1` and the child's is one more.
     * The backstop previously passed `compositionDepth + 1` — the child's HOP count — against the
     * same `> max` test, so it admitted 6 pipelines / 5 hops: the "defense in depth" never agreed
     * with the guard it backs, and in the one case it is documented for (a request no validator
     * saw) it admitted a chain save-time validation rejects.
     *
     * Pipelines, not hops, is the unit that keeps the documented `max = 5` meaning what it has
     * always meant: nothing about save-time validation, the config doc, or any existing pipeline
     * changes — only the runtime backstop moves, and it moves onto the primary guard's number.
     */
    private fun childPipelineDepth(ctx: NodeExecutionContext): Int = ctx.compositionDepth + PARENT_AND_CHILD

    /** The runtime depth backstop (design §4.4) — §12.9's static check is the primary guard. */
    private fun checkDepth(
        node: ExecutableNode,
        childDepth: Int,
    ) {
        if (childDepth <= executorConfig.maxCompositionDepth) return
        throw DatapipelinesException(
            code = PipelineErrorCodes.Node.COMPOSITION_DEPTH_EXCEEDED,
            message =
                "Executing node '${node.id}' would reach a reference-tree depth of $childDepth pipelines, beyond the " +
                    "configured maximum of ${executorConfig.maxCompositionDepth} " +
                    "(datapipelines.pipelines.max-composition-depth).",
            details =
                mapOf(
                    "node" to node.id,
                    "depth" to childDepth,
                    "max" to executorConfig.maxCompositionDepth,
                ),
        )
    }

    /**
     * The pinned body load: same repository sequence as `pipelines_execute`
     * (PipelineExecuteTool), by name — the cross-pipeline identifier (§3.2, D5) — resolved in
     * [workspaceId]. Soft-delete does not affect an existing pinned reference (D7), so the
     * read includes deleted rows.
     */
    private fun loadChild(
        node: ExecutableNode,
        ref: PipelineNodeRef,
        workspaceId: UUID,
    ): Pair<PipelineRecord, Pipeline> {
        val record = pipelines.findByNameIncludingDeleted(workspaceId, ref.name)
        val body = record?.let { pipelines.findVersionBody(workspaceId, it.id, ref.version) }
        if (record == null || body == null) {
            throw childFailure(
                node,
                childExecutionId = null,
                message =
                    "The pinned child pipeline '${ref.name}' version ${ref.version} is not in the registry; " +
                        "a saved reference cannot vanish, so this indicates corruption.",
                cause = null,
            )
        }
        return record to deserializer.readOrThrow(body)
    }

    /** The child request: parent's principal (D9), lineage, PIPELINE trigger, depth + 1, sink. */
    private fun childRequest(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        ref: PipelineNodeRef,
        record: PipelineRecord,
        child: Pipeline,
        childExecutionId: UUID,
        outcome: SinkOutcome,
    ) = ExecuteRequest(
        pipelineId = record.id,
        pipelineVersion = ref.version,
        pipeline = child,
        userId = ctx.userId,
        // Composition inherits the parent's workspace (design §5.3): the child runs where
        // its invoker runs, so its datasource resolution scopes identically (025 A5).
        workspaceId = ctx.workspaceId,
        parameters = resolveParameters(node, child, ctx),
        triggeredVia = ExecutionTrigger.PIPELINE,
        executionId = childExecutionId,
        directSink = sinkFor(node, ctx, outcome),
        parentExecutionId = ctx.executionId,
        parentNodeId = node.id,
        rootExecutionId = ctx.rootExecutionId,
        compositionDepth = ctx.compositionDepth + 1,
        // F5: the family shares the id of the request that started it. Carried on the REQUEST, not
        // just handed to the emitter, so it reaches the child's own `execution_started` payload and
        // is inherited again by every grandchild. Roots always carry one (`ExecutionStreamLauncher`,
        // `McpRecordingExecutionRunner`); the fallback only covers a request built without one.
        correlationId = ctx.correlationId ?: UUID.randomUUID(),
    )

    /**
     * Runs the child through a per-run executor with a recording emitter (no SSE stream — the
     * McpRecordingExecutionRunner shape), and maps a child failure onto the parent node's
     * catalogued code. Cancellation propagates untouched (design §4.3, D8).
     */
    private suspend fun executeChild(
        node: ExecutableNode,
        record: PipelineRecord,
        request: ExecuteRequest,
        workspaceId: UUID,
    ): ExecutionResult {
        val emitter =
            WebEventEmitter(
                context =
                    ExecutionContext(
                        pipelineId = record.id,
                        pipelineVersion = request.pipelineVersion,
                        userId = request.userId,
                        // Non-null by `childRequest`; the elvis is the type's, not a second policy.
                        correlationId = request.correlationId ?: UUID.randomUUID(),
                        triggeredVia = ExecutionTrigger.PIPELINE,
                        parametersJson = ExecutorJson.mapper.writeValueAsString(request.parameters),
                        workspaceId = workspaceId,
                        parentExecutionId = request.parentExecutionId,
                        parentNodeId = request.parentNodeId,
                        rootExecutionId = request.rootExecutionId,
                    ),
                stream = null,
                streams = streams,
                eventLog = eventLog,
                eventRepository = eventRepository,
                executionRepository = executionRepository,
                persistenceDispatcher = persistenceDispatcher,
            )
        return try {
            (executorFactory?.invoke(emitter) ?: newExecutor(emitter, workspaceId)).execute(request)
        } catch (e: CancellationException) {
            // Family cancellation (design §4.3, D8) — never relabelled as a node failure.
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw childFailure(node, request.executionId, e)
        }
    }

    /**
     * §12.9 makes a missing delivery unreachable (output is only legal on a child WITH a caller
     * node, and such a child delivers through the sink) — this guard keeps a silently absent
     * result from reading as a legal zero-caller SUCCESS.
     *
     * It keys on **any** declared output, not on `caller` alone (F3). The scenario the guard exists
     * for is a body reaching the runtime without §12.9 validation, which is exactly what
     * [requireRef] and [loadChild] defend against — and the two non-`caller` targets fail *more*
     * quietly than `caller` does: a `tempdb` node completed `SUCCESS` with `rowsOut = 0` and no
     * table created, so the first symptom was a downstream node's unrelated "table not found"
     * naming the wrong node; a `datasource` node silently wrote nothing at all. Every branch of
     * [sinkFor] therefore sets [SinkOutcome.delivered], and this reads the same flag for all three.
     */
    private fun ensureOutputDelivered(
        node: ExecutableNode,
        childExecutionId: UUID?,
        outcome: SinkOutcome,
    ) {
        val output = node.output ?: return
        if (outcome.delivered) return
        throw childFailure(
            node,
            childExecutionId,
            "Child execution completed without delivering the result this node's output " +
                "(target '${output.target.wire}') requires.",
            cause = null,
        )
    }

    /**
     * Node `parameters` become the child request's parameters: literals pass through untouched;
     * the `${parent_param}` form (§12.9's exact reference shape — a value is a literal or this,
     * nothing in between) resolves against the parent's BOUND runtime parameters and is
     * re-encoded to the child parameter's §6.3 wire form.
     */
    private fun resolveParameters(
        node: ExecutableNode,
        child: Pipeline,
        ctx: NodeExecutionContext,
    ): Map<String, JsonNode> =
        node.parameters.orEmpty().mapValues { (key, value) ->
            val reference =
                value.takeIf { it.isTextual }?.asText()?.let(PARAMETER_REFERENCE::matchEntire)
                    ?: return@mapValues value
            val declared =
                child.parameters[key]
                    ?: throw childFailure(
                        node,
                        childExecutionId = null,
                        message =
                            "Node '${node.id}' supplies parameter '$key', which the pinned child pipeline " +
                                "does not declare; save-time validation should have rejected this (§12.9).",
                        cause = null,
                    )
            ParameterWireEncoder.encode(declared.type, ctx.values[reference.groupValues[1]])
        }

    /**
     * The `direct` delivery adapter for the node's own `output` target (design §4.2) — or null,
     * when the node declares no output: the child is then awaited as a side effect and its
     * caller result (if any) materializes under the CHILD's own execution, untouched here.
     *
     * Each branch routes the child's decoded schema+rows through the exact writer a DQL node's
     * own ResultSet would reach (`NodeRunner.dispatchOutput`'s three branches): the parent's
     * staging, the result store — or the parent's own upstream sink, when this execution is
     * itself a `direct` child — or the write-back runner. A sink failure fails the CHILD's
     * caller node, so the parent sees it as a child failure with the real code — and staging's
     * partial-table rollback means a child that dies mid-stream never leaves a half-written
     * tempdb table behind to be read as success.
     */
    private fun sinkFor(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        outcome: SinkOutcome,
    ): DirectResultSink? =
        when (val output = node.output) {
            null -> {
                null
            }

            is NodeOutput.Tempdb -> {
                DirectResultSink { schema, rows ->
                    val staged = ctx.staging.stageRows(output.table, schema, rows)
                    ctx.warnings.addAll(staged.warnings)
                    checkStagingBudget(ctx)
                    // F6: the same instrument `NodeRunner.dispatchOutput`'s Tempdb branch records.
                    // Without it every row staged through a PIPELINE node was invisible to
                    // `datapipelines.staging.rows` — the metric this repo already had to rescue
                    // once from being permanently 0 (009/F10).
                    executorMetrics.rowsStaged(staged.rowsStaged)
                    outcome.rowsOut = staged.rowsStaged
                    outcome.delivered = true
                }
            }

            NodeOutput.Caller -> {
                val upstream = ctx.directSink
                if (upstream != null) {
                    // This execution is itself a child: the rows stream onward to its invoker,
                    // exactly like a DQL caller node under `direct` delivery (design §4.2 —
                    // identical post-node behavior). Nothing is stored under this execution.
                    DirectResultSink { schema, rows ->
                        var count = 0L
                        upstream.accept(schema, rows.onEach { count++ })
                        outcome.rowsOut = count
                        outcome.delivered = true
                    }
                } else {
                    DirectResultSink { schema, rows ->
                        val stored = resultStore.materializeRows(ctx.executionId, schema, rows, ctx.resultTtlSeconds)
                        ctx.warnings.addAll(stored.warnings)
                        outcome.rowsOut = stored.totalRows
                        outcome.callerResultRef = stored.key
                        outcome.bytesOutEstimate = stored.bytes
                        outcome.delivered = true
                    }
                }
            }

            is NodeOutput.Datasource -> {
                DirectResultSink { schema, rows ->
                    outcome.rowsOut = writebackRunner.writebackRows(schema, rows, output, ctx.workspaceId)
                    outcome.delivered = true
                }
            }
        }

    /**
     * The per-execution budget re-check [co.datapipelines.executor.NodeRunner] applies after
     * every staged write: `StagingFactory.create` has no budget parameter, so the possibly-lower
     * per-pipeline budget is enforced here, after the sink's stage, exactly as it is after a DQL
     * node's own stage.
     */
    private suspend fun checkStagingBudget(ctx: NodeExecutionContext) {
        val usedBytes = ctx.staging.stats().memoryUsedBytes
        if (usedBytes / BYTES_PER_KB > ctx.stagingMaxMemoryMb * KB_PER_MB) {
            throw StagingMemoryLimitException(usedBytes, ctx.stagingMaxMemoryMb)
        }
    }

    /** §4.3 — the parent node fails fail-fast, and the detail leads to the child's record. */
    private fun childFailure(
        node: ExecutableNode,
        childExecutionId: UUID?,
        cause: Exception,
    ): DatapipelinesException = childFailure(node, childExecutionId, messageOf(childExecutionId, cause), cause)

    private fun childFailure(
        node: ExecutableNode,
        childExecutionId: UUID?,
        message: String,
        cause: Exception?,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED,
            message = message,
            details =
                buildMap {
                    put("node", node.id)
                    childExecutionId?.let { put("child_execution_id", it.toString()) }
                    (cause as? DatapipelinesException)?.let { put("child_error_code", it.code) }
                    (cause as? PipelineExecutionFailed)?.let { put("child_failed_node_id", it.failedNodeId) }
                },
            cause = cause,
        )

    private fun messageOf(
        childExecutionId: UUID?,
        cause: Exception,
    ): String {
        val childCode = (cause as? DatapipelinesException)?.code
        return buildString {
            append("Child execution")
            childExecutionId?.let { append(" ").append(it) }
            append(" failed")
            childCode?.let { append(" (").append(it).append(")") }
            append(": ").append(cause.message?.take(MAX_CAUSE_MESSAGE_CHARS) ?: cause.javaClass.simpleName)
        }
    }

    /**
     * The §10.2 result-history columns, as [McpRecordingExecutionRunner] records them: a child
     * whose result streamed `direct` has no stored result (early return); a side-effect child's
     * own materialized result lands here. Bookkeeping only — a failure is logged, never fails
     * the completed child.
     */
    private fun recordResultColumns(result: ExecutionResult) {
        val ref = result.resultRef ?: return
        runCatching {
            val view = resultStore.describe(ref) ?: return
            executionRepository.recordResult(result.executionId, view.totalRows, view.bytes)
        }.onFailure { log.warn("Result columns for child execution {} not recorded.", result.executionId, it) }
    }

    /**
     * One executor per child run — the [McpRecordingExecutionRunner] reasoning, plus one
     * addition: `subPipelineRunner = this`, so a child's own PIPELINE nodes (grandchildren)
     * dispatch through the same machinery with the depth counter they carry.
     */
    private fun newExecutor(
        emitter: WebEventEmitter,
        workspaceId: UUID,
    ): PipelineExecutor =
        pipelineExecutor(
            templateEngine = templateEngines.engineFor(workspaceId),
            datasourceRegistry = datasourceRegistry,
            stagingFactory = stagingFactory,
            writebackRunner = writebackRunner,
            resultStore = resultStore,
            eventEmitter = emitter,
            cancellationRegistry = cancellationRegistry,
            cancellationFlags = cancellationFlags,
            executionSlots = executionSlots,
            dispatcher = executorDispatcher,
            config = executorConfig,
            resultUrls = resultUrls,
            metrics = executorMetrics,
            subPipelineRunner = this,
        )

    /** What the sink delivered — written by the child's caller node, read after `execute` returns. */
    private class SinkOutcome {
        var rowsOut: Long = 0
        var callerResultRef: String? = null
        var bytesOutEstimate: Long = NodeResult.NOT_MEASURED

        /**
         * Set by **every** [sinkFor] branch — both `caller` paths (stored or streamed onward),
         * `tempdb` and `datasource` (F3). It is the guard's only evidence that the child actually
         * reached the sink, so a branch that forgets it reports a silent zero-delivery SUCCESS.
         */
        var delivered: Boolean = false
    }

    private companion object {
        /** §12.9's whole `${parent_param}` reference form — the same pattern CompositionRules validates. */
        val PARAMETER_REFERENCE = Regex("^\\$\\{([a-z_][a-z0-9_]*)\\}$")

        /**
         * Hops-above → pipelines: the parent's own pipeline plus the child's (see
         * [childPipelineDepth]). A root parent (`compositionDepth = 0`) spawns a depth-2 tree.
         */
        const val PARENT_AND_CHILD = 2

        const val BYTES_PER_KB = 1024L
        const val KB_PER_MB = 1024L

        /** ErrorCodeMapper's bound, applied here too: a driver message can quote an entire statement. */
        const val MAX_CAUSE_MESSAGE_CHARS = 2000
    }
}
