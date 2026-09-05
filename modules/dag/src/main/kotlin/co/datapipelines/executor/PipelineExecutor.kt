package co.datapipelines.executor

import co.datapipelines.dag.Dag
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.events.DataReady
import co.datapipelines.events.EventEmitter
import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionEvent
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.NodeFailed
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingEngine
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.typesystem.DatapipelinesException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import co.datapipelines.pipeline.StagingEngine as PipelineStagingEngine

/**
 * The runtime engine (dag-executor.md §5): takes a validated pipeline plus parameters, walks the
 * DAG with coroutines, and produces the result dataset.
 *
 * ## The concurrency model in one paragraph
 *
 * Every node gets a coroutine **up front**; each awaits its own dependencies and only then takes
 * a parallelism permit. That is strictly more parallel than wave scheduling, and the permit
 * ordering is not a detail: taking the permit first would let `max-parallel-nodes` coroutines sit
 * blocked on dependencies while holding every permit, so the dependencies they wait for could
 * never acquire one — **any chain longer than `max-parallel-nodes` would deadlock**. Waiting
 * first costs nothing (a suspended `awaitAll` occupies no thread) and the permit then bounds only
 * nodes doing actual SQL work. `PermitAfterAwaitDeadlockTest` is the standing guard.
 *
 * Failure is fail-fast (§5.4): one node failing cancels every sibling through structured
 * concurrency, pending nodes never start, and the execution reports `FAILED` with per-node stats.
 * Cancellation (§8.3) is a separate, first-class path that reports `ABORTED` and carries no error
 * code.
 */
@Suppress("LongParameterList")
class PipelineExecutor(
    private val nodeRunner: NodeRunner,
    private val stagingFactory: StagingFactory,
    private val resultStore: ResultStore,
    private val eventEmitter: EventEmitter,
    private val cancellationRegistry: CancellationRegistry,
    private val cancellationFlags: CancellationFlags,
    private val executionSlots: ExecutionSlots,
    private val dispatcher: ExecutorDispatcher,
    private val config: ExecutorConfig,
    private val metrics: ExecutorMetrics = ExecutorMetrics.inMemory(),
    /**
     * No default (F6). `rest-api` §6.4.7 requires `data_ready.result_url` to be **absolute**, and a
     * relative default silently shipped a wire-invalid payload to every client that did not
     * override it. Making it required turns "forgot to wire the base URL" from a runtime protocol
     * violation into a compile error in `app`; [ResultUrlFactory.RELATIVE] survives as a test-only
     * fixture.
     */
    private val resultUrls: ResultUrlFactory,
) {
    init {
        metrics.bindConcurrency(executionSlots)
    }

    /**
     * Runs one execution end to end (§5.1).
     *
     * The execution slot is taken **first** and held for the whole run (§5.1 step 2), so a
     * pipeline can never run out of slots halfway through.
     *
     * @throws PipelineConcurrencyLimitException no slot was free.
     * @throws PipelineExecutionFailed a node failed.
     * @throws PipelineTimeoutException the execution timeout fired.
     * @throws ExecutionAbortedException the execution was cancelled (§8.3).
     */
    suspend fun execute(request: ExecuteRequest): ExecutionResult {
        val executionId = request.executionId ?: UUID.randomUUID()
        // Design §4.4 (corrected 2026-08-13): only ROOT executions take a concurrency slot. A
        // child holding its own slot while its parent waits on it deadlocks any family larger
        // than the cap; composition volume is bounded by depth and the per-pipeline node cap.
        return if (request.rootExecutionId != null) {
            runExecution(executionId, request)
        } else {
            executionSlots.withSlot(request.userId) { runExecution(executionId, request) }
        }
    }

    @Suppress("LongMethod")
    private suspend fun runExecution(
        executionId: UUID,
        request: ExecuteRequest,
    ): ExecutionResult {
        val startedAt = Instant.now()
        val plan =
            try {
                ExecutablePipeline.from(request.pipeline)
            } catch (e: IllegalArgumentException) {
                // Defence-in-depth: save-time validation makes this unreachable, but a DAG
                // that somehow carries a duplicate id, dangling dep, self-dep or cycle is
                // mapped to a catalogued code rather than surfacing as a raw uncatalogued 500.
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Validation.CYCLE_DETECTED,
                    message = "Pipeline DAG is malformed: ${e.message}",
                    details = mapOf("pipeline_id" to request.pipelineId.toString()),
                    cause = e,
                )
            }
        // Binding runs before the stream opens, and stays there: a rejected parameter is a 400
        // with no execution at all, and §8.2 catalogues no executor code for it. Everything the
        // catalogue DOES cover moves below the emit (F9).
        // §7.1 steps 1-4 / calculators §0.2: org config, then the platform keys, then the
        // declared parameters resolved against the request's inputs. LIVE — a CALCULATOR node
        // writes tier 5 into this same map at its DAG position, and every node scheduled after
        // it renders and binds against the value (RunContext's KDoc).
        val context = RunContext.create(config.orgContext, request.pipeline, request.parameters, executionId, startedAt)
        val run = ExecutionRun(executionId, request, plan, startedAt, context)

        // §5.1 step 4 before steps 8-10: `execution_started` precedes every allocation whose
        // failure §8.2 names (`staging.creation_failed`, `staging.engine_unavailable`). Creating
        // staging first meant such a failure escaped with ZERO events on an execution the caller
        // had already been handed an id for.
        emit(
            ExecutionStarted(
                executionId = executionId,
                pipelineId = request.pipelineId,
                pipelineVersion = request.pipelineVersion,
                parameters = context.snapshot(),
                correlationId = request.correlationId,
                startedAt = startedAt,
            ),
        )

        var staging: Staging? = null
        val handle = cancellationRegistry.register(executionId)
        try {
            // §15.2: staging creation opens JDBC connections — blocking work, so it belongs on the
            // executor's own pool, never on whatever thread the caller happened to arrive on.
            val opened = withContext(dispatcher.context) { stagingFactory.create(executionId, stagingEngineFor(request.pipeline)) }
            staging = opened
            val ctx = nodeContext(request, opened, handle, run, context)
            return withTimeout(config.executionTimeoutSeconds.seconds) {
                coroutineScope {
                    handle.bind(coroutineContext.job)
                    val poller = launch(dispatcher.context) { pollCancelFlag(run, handle) }
                    try {
                        val results = runNodes(plan.dag, ctx, run)
                        succeed(run, results)
                    } finally {
                        poller.cancel()
                    }
                }
            }
        } catch (e: NodeExecutionException) {
            failWithNode(run, e)
        } catch (e: TimeoutCancellationException) {
            // Ours, or an ancestor's? kotlinx passes a cancellation cause that is ALREADY a
            // `CancellationException` down to children **verbatim** (`getChildJobCancellationCause`),
            // so a parent's expired deadline arrives here as this exact type — see [cancelledByAncestor].
            if (cancelledByAncestor()) abortStructurally(run, e) else failWithTimeout(run, e)
        } catch (e: ExecutionAbortedException) {
            abort(run, e)
        } catch (e: DatapipelinesException) {
            // A catalogued setup failure — staging creation is the one §8.2 names (F9). It reaches
            // the stream as `pipeline_failed` on the execution the caller is already watching.
            failWithSetup(run, e)
        } catch (e: CancellationException) {
            // Everything ABOVE this clause is a cancellation this execution decided for itself.
            // This one is decided by the machinery: our scope was cancelled by an ANCESTOR — see
            // [abortStructurally].
            abortStructurally(run, e)
        } finally {
            cleanup(executionId, staging)
        }
    }

    /**
     * Schedules every node up front (§5.2). Dependencies are awaited **before** the parallelism
     * permit is taken — see the class KDoc.
     */
    private suspend fun runNodes(
        dag: Dag<ExecutableNode>,
        ctx: NodeExecutionContext,
        run: ExecutionRun,
    ): Map<String, NodeResult> =
        coroutineScope {
            val permits = Semaphore(config.maxParallelNodes)
            val scheduled = LinkedHashMap<String, Deferred<NodeResult>>()
            // Topological order guarantees a node's dependencies are already scheduled.
            dag.topologicalOrder().forEach { nodeId ->
                val node = dag.node(nodeId)
                val dependencies = node.dependsOn.map { scheduled.getValue(it) }
                scheduled[nodeId] =
                    async(dispatcher.context) {
                        // Dependencies FIRST, holding no permit; only then occupy a slot (§5.2).
                        dependencies.awaitAll()
                        permits.withPermit { executeNode(node, ctx, run) }
                    }
            }
            scheduled.mapValues { (_, deferred) -> deferred.await() }
        }

    /**
     * One node's lifecycle: `node_started`, then exactly one of `node_completed` or `node_failed`
     * (§2 principle 5, §5.2). `node_completed` is **never** emitted for a failed node.
     */
    private suspend fun executeNode(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        run: ExecutionRun,
    ): NodeResult {
        // §8.3.1: the Redis flag is checked at node boundaries as well as on the poll tick, so a
        // cross-instance cancel does not have to wait a full interval to stop the next node.
        checkCancelFlag(run)
        val startedAt = Instant.now()
        run.stats.started(node.id, startedAt)
        emit(NodeStarted(run.executionId, node.id, startedAt))
        return try {
            completeNode(node, ctx, run, startedAt)
        } catch (e: CancellationException) {
            // Cancellation is not a node failure: it emits nothing and maps to no code (§5.2).
            // But if the cancellation *replaced* a real failure — `withStatement` converts a
            // cancel-induced driver error, and carries the original as a suppressed exception —
            // the stats still record it. Suppressing the event is right; losing the reason is not.
            recordSuppressedFailure(node, run, e)
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw failNode(node, ctx, run, e)
        }
    }

    /** Records the failure a cancellation stood in front of, if there was one (F8). */
    private fun recordSuppressedFailure(
        node: ExecutableNode,
        run: ExecutionRun,
        cancellation: CancellationException,
    ) {
        val original = cancellation.suppressed.firstOrNull() ?: return
        val mapped = (original as? NodeFailedSignal)?.error ?: ErrorCodeMapper.map(original, NodePhase.EXECUTE, node.id)
        run.stats.abortedWithCause(node.id, mapped)
    }

    private suspend fun completeNode(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        run: ExecutionRun,
        startedAt: Instant,
    ): NodeResult {
        val result = nodeRunner.run(node, ctx, startedAt)
        run.stats.completed(result)
        metrics.nodeFinished(run.request.pipelineId, node.id, node.source, Duration.ofMillis(result.durationMs), result.rowsOut)
        emit(NodeCompleted(run.executionId, node.id, NodeStats.of(result)))
        return result
    }

    /**
     * Records the failure, emits `node_failed` **exactly once**, and returns the exception to throw.
     *
     * The first branch is the funnel guard: `CancellationHandle.withStatement` already converts a
     * cancel-induced driver error, but a failure raised *outside* a registered statement — staging,
     * the result-store drain, write-back — can still land here while the execution is already
     * aborting. `node_failed` must never be emitted for one of those, because §10 allows exactly
     * one terminal event and an `execution_aborted` is already on its way.
     */
    private suspend fun failNode(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        run: ExecutionRun,
        cause: Exception,
    ): Exception {
        val mapped = (cause as? NodeFailedSignal)?.error ?: ErrorCodeMapper.map(cause, NodePhase.EXECUTE, node.id)

        // F8: the reason is recorded on EVERY path, including the two suppressed ones — only the
        // *event* is suppressed. On those two the node is `ABORTED`-with-cause rather than `FAILED`:
        // it was stopped by the execution ending, not by a fault of its own.
        ctx.handle.abortReason?.let {
            run.stats.abortedWithCause(node.id, mapped)
            return ExecutionAbortedException(it)
        }
        if (run.unwinding.get()) {
            run.stats.abortedWithCause(node.id, mapped)
            // The scope is already unwinding and this node's statement was interrupted by
            // [pollCancelFlag]'s cleanup to make that unwind actually stop the source query. The
            // resulting driver error is a *consequence* of the decided outcome, not an independent
            // failure: emitting `node_failed` for it would put a spurious query_execution_failed on
            // a stream whose terminal event is already pipeline_failed(timeout) or another node's.
            return CancellationException("node ${node.id} interrupted while the execution unwound")
        }
        run.stats.failed(node.id, mapped)
        // 057/T85: the failure record, completed ONCE here — the only place that holds the
        // unwrapped original cause — and then carried unchanged into `node_failed`, the
        // terminal `pipeline_failed` and `error_json`. The node runner attached the node
        // context and rendered SQL at the failure site; the recorder fills anything still
        // missing and adds the exception detail under `error-detail=full`.
        val failure = completeRecord(mapped, node, ctx, cause)
        // The log half of the page↔log pair: one ERROR line whose correlation id is the
        // join key the screen now shows, and whose throwable prints the SAME chain the
        // record transports. A streamed failure previously logged nothing at all — the
        // record was in the event and error_json only, so grepping the log by the
        // correlation id the UI displayed found silence (found on the 057 gate stack).
        LOG.error(
            "node {} failed ({}) execution_id={} correlation_id={}",
            node.id,
            failure.code,
            run.executionId,
            run.request.correlationId,
            cause.cause ?: cause,
        )
        emit(NodeFailed(run.executionId, node.id, failure, run.stats.snapshot(listOf(node.id)).first()))
        return NodeExecutionException(node.id, mapped.code, mapped.details, cause.cause ?: cause, failure)
    }

    /**
     * Completes the 057 failure record: node context for a failure the runner never
     * decorated (a PIPELINE node fails before render), and the [ExceptionDetail] of the
     * ORIGINAL failure — `cause.cause ?: cause` unwraps a [NodeFailedSignal] to the driver
     * throwable it wraps, the same unwrapping the thrown [NodeExecutionException] performs.
     */
    private fun completeRecord(
        mapped: MappedError,
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        cause: Exception,
    ): MappedError {
        val original = cause.cause ?: cause
        val base = mapped.copy(node = mapped.node ?: NodeErrorContext.of(node, dialectOf(node, ctx)))
        return if (config.errorDetail == ErrorDetail.FULL) {
            base.copy(exception = ExceptionDetail.of(original))
        } else {
            base
        }
    }

    private fun dialectOf(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
    ): String? = if (node.source is NodeSource.Tempdb) ctx.tempdbDialect.name else null

    // -------------------------------------------------------------- outcomes

    /**
     * The success path — but the stored caller result is resolved **before** the terminal event.
     *
     * That ordering is the whole point (B3). Emitting `pipeline_completed` first and only then
     * discovering that `describe()` returns null left exactly one legal-looking outcome for a
     * lost result: `SUCCESS` with no `data_ready` — which is **wire-identical** to a legal
     * zero-caller run (§4.1, §10). A caller pipeline that silently returns no data is the worst
     * possible failure mode, and D9's "no fallback, fail loud" says so directly. Resolving first
     * means a vanished result fails the execution with `result.storage_unavailable` instead.
     */
    private suspend fun succeed(
        run: ExecutionRun,
        results: Map<String, NodeResult>,
    ): ExecutionResult {
        // §4.1: no caller node → no data_ready at all. Legal, not an error.
        val resultRef = run.plan.callerNodeId?.let { results.getValue(it).callerResultRef }
        val view = resultRef?.let { resolveStoredResult(run, it) }

        val completedAt = Instant.now()
        val stats = run.stats.snapshot(run.plan.dag.nodeIds)
        run.emitTerminal {
            emit(
                PipelineCompleted(
                    executionId = run.executionId,
                    pipelineId = run.request.pipelineId,
                    pipelineVersion = run.request.pipelineVersion,
                    startedAt = run.startedAt,
                    completedAt = completedAt,
                    durationMs = run.elapsedMsAt(completedAt),
                    nodeStats = stats,
                    contextSnapshot = run.context.snapshot(),
                ),
            )
        }
        // §6.4.2: built from the **stored** result, never from the ResultSet.
        view?.let { emit(DataReady.from(run.request.pipelineId, it, resultUrls.urlFor(run.executionId), run.resultTtlSeconds(config))) }
        metrics.executionFinished(run.request.pipelineId, ExecutionStatus.SUCCESS, run.elapsed(completedAt))
        return run.result(ExecutionStatus.SUCCESS, stats, resultRef, completedAt)
    }

    /**
     * Reads the stored caller result back, or fails the execution.
     *
     * The throw is a [NodeExecutionException] against the caller node so it lands on the existing
     * `failWithNode` path — which emits `pipeline_failed` and nothing else. Deliberately no
     * `node_failed`: the node itself succeeded and already emitted `node_completed`, and §10
     * permits exactly one of the two per node.
     */
    private suspend fun resolveStoredResult(
        run: ExecutionRun,
        resultRef: String,
    ): StoredResultView {
        // §15.2: a Redis read is blocking I/O and belongs on the executor's own pool.
        val view = withContext(dispatcher.context) { resultStore.describe(resultRef) }
        if (view == null) {
            LOG.error("Stored result {} vanished before data_ready; failing execution {}", resultRef, run.executionId)
            val vanished = IllegalStateException("stored result $resultRef is gone or expired before data_ready")
            throw NodeExecutionException(
                nodeId = run.plan.callerNodeId ?: "",
                errorCode = PipelineErrorCodes.Result.STORAGE_UNAVAILABLE,
                errorDetails = mapOf("result_ref" to resultRef),
                cause = vanished,
                // 057: this path never passed a recording site, so the record is built here —
                // node context for the caller node, exception detail per the configured level.
                errorRecord =
                    MappedError(
                        PipelineErrorCodes.Result.STORAGE_UNAVAILABLE,
                        vanished.message.orEmpty(),
                        mapOf("result_ref" to resultRef),
                        node = run.plan.callerNodeId?.let { NodeErrorContext.of(run.plan.dag.node(it)) },
                    ).let { if (config.errorDetail == ErrorDetail.FULL) it.copy(exception = ExceptionDetail.of(vanished)) else it },
            )
        }
        return view
    }

    private suspend fun failWithNode(
        run: ExecutionRun,
        e: NodeExecutionException,
    ): Nothing {
        val failedAt = Instant.now()
        // node_failed was already emitted at the failure site — not re-emitted here (§5.2).
        // 057: the terminal event carries the SAME failure record, already completed at the
        // recording site — rebuilt from code/details only on the one path that never passed
        // through failNode (the vanished-result throw in resolveStoredResult carries its own).
        val record = e.errorRecord ?: MappedError(e.errorCode, e.message.orEmpty(), e.errorDetails)
        run.emitTerminal { emit(pipelineFailed(run, failedAt, e.nodeId, record)) }
        metrics.executionFinished(run.request.pipelineId, ExecutionStatus.FAILED, run.elapsed(failedAt))
        throw PipelineExecutionFailed(e.nodeId, e.errorCode, e.errorDetails, e.errorRecord)
    }

    /** A timeout is a **failure**, not a cancellation: status `FAILED`, code `execution.timeout`. */
    private suspend fun failWithTimeout(
        run: ExecutionRun,
        cause: TimeoutCancellationException,
    ): Nothing {
        val failedAt = Instant.now()
        val timeout = PipelineTimeoutException(run.stats.runningNodeIds().firstOrNull(), run.elapsedMsAt(failedAt))
        // 057: the same exception detail a node failure carries — a timeout's stack names the
        // coroutine machinery that fired, and the record stays shape-identical across causes.
        val error =
            MappedError(timeout.code, timeout.message.orEmpty(), timeout.details)
                .let { if (config.errorDetail == ErrorDetail.FULL) it.copy(exception = ExceptionDetail.of(cause)) else it }
        run.emitTerminal { emit(pipelineFailed(run, failedAt, timeout.timedOutNodeId, error)) }
        metrics.executionFinished(run.request.pipelineId, ExecutionStatus.FAILED, run.elapsed(failedAt))
        throw timeout.also { it.addSuppressed(cause) }
    }

    /**
     * A catalogued failure from execution setup (§5.1 steps 8-10) — today only staging creation.
     *
     * It reaches the stream as `pipeline_failed` with the code the raising module chose, on an
     * execution that has already emitted `execution_started` (F9). No node ever started, so every
     * node reports `ABORTED` in the snapshot.
     */
    private suspend fun failWithSetup(
        run: ExecutionRun,
        e: DatapipelinesException,
    ): Nothing {
        val failedAt = Instant.now()
        // 057: setup failures carry the exception detail too (no node context, no SQL —
        // neither exists yet); the raised module's own cause chain walks exactly like a
        // driver's.
        val error =
            MappedError(e.code, e.message.orEmpty(), e.details)
                .let { if (config.errorDetail == ErrorDetail.FULL) it.copy(exception = ExceptionDetail.of(e)) else it }
        run.emitTerminal { emit(pipelineFailed(run, failedAt, failedNodeId = null, error = error)) }
        metrics.executionFinished(run.request.pipelineId, ExecutionStatus.FAILED, run.elapsed(failedAt))
        throw e
    }

    private suspend fun abort(
        run: ExecutionRun,
        e: ExecutionAbortedException,
    ): Nothing {
        val abortedAt = Instant.now()
        run.emitTerminal {
            emit(
                ExecutionAborted(
                    executionId = run.executionId,
                    pipelineId = run.request.pipelineId,
                    reason = e.reason,
                    abortedAt = abortedAt,
                    nodeStats = run.stats.snapshot(run.plan.dag.nodeIds),
                    contextSnapshot = run.context.snapshot(),
                ),
            )
        }
        metrics.executionAborted(e.reason)
        metrics.executionFinished(run.request.pipelineId, ExecutionStatus.ABORTED, run.elapsed(abortedAt))
        throw e
    }

    /**
     * True when this execution's scope was cancelled by something ABOVE it rather than by its own
     * deadline or its own registry (F1).
     *
     * `runExecution`'s own `coroutineContext` is its **caller's** — for a child execution, the
     * parent PIPELINE node's coroutine; for a root, the launcher's scope. Its own
     * `withTimeout`/`handle.cancel()` cancel only the scopes *inside* the `try`, so that job is
     * still active when the execution stopped itself, and inactive exactly when an ancestor stopped
     * it. That is the whole discriminator, and it needs no exception-shape guesswork: kotlinx hands
     * a cancellation cause that is already a `CancellationException` down to children **unwrapped**
     * (`JobSupport.getChildJobCancellationCause`), so a parent's `TimeoutCancellationException`
     * arrives at a child as the very type the child would raise for its own expired deadline.
     */
    private suspend fun cancelledByAncestor(): Boolean = !coroutineContext.isActive

    /**
     * The execution's scope was cancelled from **outside** it — structural cancellation (F1).
     *
     * A child execution runs inside its parent's node coroutine, so a parent that times out (or is
     * cancelled with a cause the family does not share) takes the child down with it. Before this
     * handler the child reported that as its OWN `pipeline.execution.timeout` — a `FAILED` row
     * quoting an elapsed time far below the configured timeout, for an execution that never
     * exceeded its deadline. Cancellation is not failure (§8.3), so the outcome is `ABORTED`.
     *
     * A root reaches here by the same route whenever the scope that launched it is cancelled from
     * outside (`ExecutionStreamLauncher`'s `scope` at shutdown) — the path was never child-specific, only
     * child-*reachable* in normal operation, which is why the handler lives on the shared path
     * rather than in the composition runner.
     *
     * The ORIGINAL exception is rethrown untouched, so the ancestor's structured concurrency
     * unwinds exactly as before. Persistence of the terminal event survives the cancelled scope
     * because [ExecutionRun.emitTerminal] runs its block under [NonCancellable] — see its KDoc; the
     * metrics sit inside that block so a terminal event that already won the CAS cannot be
     * double-counted.
     */
    private suspend fun abortStructurally(
        run: ExecutionRun,
        cancellation: CancellationException,
    ): Nothing {
        val reason = familyAbortReason(cancellation)
        val abortedAt = Instant.now()
        run.emitTerminal {
            emit(
                ExecutionAborted(
                    executionId = run.executionId,
                    pipelineId = run.request.pipelineId,
                    reason = reason,
                    abortedAt = abortedAt,
                    nodeStats = run.stats.snapshot(run.plan.dag.nodeIds),
                    contextSnapshot = run.context.snapshot(),
                ),
            )
            metrics.executionAborted(reason)
            metrics.executionFinished(run.request.pipelineId, ExecutionStatus.ABORTED, run.elapsed(abortedAt))
        }
        throw cancellation
    }

    /**
     * The family's own abort reason if the cancellation chain carries one, else [AbortReason.CANCELLED].
     *
     * A `DELETE /executions/{id}` against an ancestor reaches here as
     * `JobCancellationException(cause = … = ExecutionAbortedException(reason))` — one wrapping layer
     * per generation — so the reason a user asked for survives onto the descendant's row. A parent
     * *timeout* carries `TimeoutCancellationException` instead, and there is no `AbortReason` for
     * "an ancestor timed out": `cancelled` is the honest catalogued answer (§6.4.8 lists three, and
     * adding a fourth would be a wire-catalogue change this finding does not need). The walk is
     * depth-bounded so a pathological cause cycle cannot spin.
     */
    private fun familyAbortReason(cancellation: CancellationException): AbortReason =
        generateSequence(cancellation as Throwable) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .filterIsInstance<ExecutionAbortedException>()
            .firstOrNull()
            ?.reason
            ?: AbortReason.CANCELLED

    private fun pipelineFailed(
        run: ExecutionRun,
        failedAt: Instant,
        failedNodeId: String?,
        error: MappedError,
    ) = PipelineFailed(
        executionId = run.executionId,
        pipelineId = run.request.pipelineId,
        pipelineVersion = run.request.pipelineVersion,
        startedAt = run.startedAt,
        failedAt = failedAt,
        durationMs = run.elapsedMsAt(failedAt),
        failedNodeId = failedNodeId,
        error = error,
        nodeStats = run.stats.snapshot(run.plan.dag.nodeIds),
        contextSnapshot = run.context.snapshot(),
    )

    // ------------------------------------------------------------ plumbing

    /**
     * The `finally` of §5.1 step 16, which runs on **every** path — success, failure, timeout,
     * cancellation. `Staging.close()` is contractually non-throwing (staging §3.4), so tempdb
     * cleanup cannot mask the real outcome.
     */
    private suspend fun cleanup(
        executionId: UUID,
        staging: Staging?,
    ) {
        cancellationRegistry.deregister(executionId)
        // §15.2: a Redis DELETE and an H2 table sweep are both blocking; neither may run on a
        // caller thread that might be a Netty event loop. `NonCancellable` because cleanup must
        // complete even when we got here by cancellation.
        withContext(dispatcher.context + NonCancellable) {
            cancellationFlags.clear(executionId)
            staging?.close()
        }
    }

    /**
     * Re-reads the cross-instance cancel flag on the §8.3.1 tick until the execution ends — and, on
     * the way out, interrupts any statement still in flight.
     *
     * That `finally` is how a timeout reaches the source query (B4a). `withTimeout` cancels this
     * coroutine the instant the deadline passes — a suspended `delay` is cancellable, so the block
     * runs immediately, while the blocked node is still inside `executeQuery`. Hooking the poller's
     * cancellation rather than arming a second timer is what makes it race-free: there is one
     * deadline, not two that can fire in either order.
     *
     * It fires on the failure path too, which §8.3.3 asks for in as many words ("running siblings
     * have their registered `Statement.cancel()` invoked by the same handle") and which was equally
     * missing. On the clean path there is nothing running, so it is a no-op.
     */
    private suspend fun pollCancelFlag(
        run: ExecutionRun,
        handle: CancellationHandle,
    ) {
        val interval = Duration.ofSeconds(config.cancelPollIntervalSeconds).toMillis()
        try {
            while (true) {
                delay(interval)
                checkCancelFlag(run)
            }
        } finally {
            if (run.stats.runningNodeIds().isNotEmpty()) {
                run.unwinding.set(true)
                handle.cancelStatements()
            }
        }
    }

    /**
     * Reads the cross-instance flag for this execution — and, for a CHILD, for its family root
     * too (design §4.3, D8). Flags are keyed per execution id (`dp:cancel:{execution_id}`), so
     * both reads are required: the child's own flag stops it alone, the root's flag — set by
     * `DELETE /executions/{id}` against the ancestor — stops the whole family.
     */
    private fun checkCancelFlag(run: ExecutionRun) {
        cancellationFlags.read(run.executionId)?.let { cancellationRegistry.cancel(run.executionId, it) }
        val root = run.request.rootExecutionId
        if (root != null && root != run.executionId) {
            cancellationFlags.read(root)?.let { cancellationRegistry.cancel(run.executionId, it) }
        }
    }

    private fun nodeContext(
        request: ExecuteRequest,
        staging: Staging,
        handle: CancellationHandle,
        run: ExecutionRun,
        values: RunContext,
    ): NodeExecutionContext {
        // B2: the pipeline's `settings.tempdb.config.max_memory_mb` may only ever LOWER the
        // operator's global ceiling, never raise it. Save-time validation only checks `> 0`, so an
        // author could otherwise declare 1_000_000 MB and disable `checkStagingBudget` — the ONLY
        // ceiling on the `withConnection` paths — turning one `CREATE TABLE AS SELECT` over a
        // generated range into a whole-instance OOM for every tenant on the box. D6 gives the
        // pipeline an override; reading it as "may relax the operator's limit" is not a reading
        // any deployment could safely run.
        val budgetMb =
            request.pipeline.settings.tempdb.maxMemoryMb
                ?.toLong()
                ?.coerceAtMost(config.stagingMaxMemoryMb)
                ?: config.stagingMaxMemoryMb
        return NodeExecutionContext(
            executionId = run.executionId,
            staging = staging,
            handle = handle,
            values = values,
            warnings = run.warnings,
            resultTtlSeconds = run.resultTtlSeconds(config),
            renderBudgetChars = config.renderOutputBudgetChars(budgetMb),
            stagingMaxMemoryMb = budgetMb,
            tempdbDialect = request.pipeline.settings.tempdb.engine.dialect,
            userId = request.userId,
            // A null rootExecutionId on the request marks a ROOT execution — its own id is the
            // family's root, exactly as the repository persists it (metadata-db §4.6).
            rootExecutionId = request.rootExecutionId ?: run.executionId,
            compositionDepth = request.compositionDepth,
            directSink = request.directSink,
            // F5: carried so a PIPELINE node's child request can inherit it. Without it the child
            // minted a fresh random id and the family could not be joined by the one field that
            // exists to join it.
            correlationId = request.correlationId,
            workspaceId = request.workspaceId,
        )
    }

    private suspend fun emit(event: ExecutionEvent) = eventEmitter.emit(event)

    private fun stagingEngineFor(pipeline: Pipeline): StagingEngine =
        when (pipeline.settings.tempdb.engine) {
            // The two enums are separate declarations by layering necessity (staging.StagingEngine
            // KDoc): `staging` may not depend on `pipeline-contract`, so `dag` — which depends on
            // both — owns the mapping. Exhaustive, so a new engine is a compile error here.
            PipelineStagingEngine.H2 -> StagingEngine.H2
        }

    private companion object {
        val LOG = LoggerFactory.getLogger(PipelineExecutor::class.java)

        /** Bound on [familyAbortReason]'s cause walk — one link per composition generation, plus slack. */
        const val MAX_CAUSE_DEPTH = 32
    }
}

/** One execution's mutable bookkeeping, kept out of [PipelineExecutor]'s method signatures. */
internal class ExecutionRun(
    val executionId: UUID,
    val request: ExecuteRequest,
    val plan: ExecutablePipeline,
    val startedAt: Instant,
    /**
     * The execution's LIVE Context. Held here so the three terminal events can carry its final
     * state (calculators design §0.5) without the emit sites reaching back into the executor —
     * and read as a SNAPSHOT at emit time, because a calculator may still have been writing to it
     * a moment earlier.
     */
    val context: RunContext,
) {
    val stats = NodeStatsCollector()
    val warnings = WarningSink()

    /**
     * Set once the execution scope is unwinding with an outcome already decided, and the in-flight
     * statements have been interrupted to make that unwind reach the source databases (B4a).
     *
     * A node failing *after* this is failing because we stopped it, so its `node_failed` is
     * suppressed — the stats still record the real driver error (F8).
     */
    val unwinding = AtomicBoolean()
    private val terminalEmitted = AtomicBoolean()

    fun elapsed(at: Instant): Duration = Duration.between(startedAt, at)

    fun elapsedMsAt(at: Instant): Long = elapsed(at).toMillis()

    fun resultTtlSeconds(config: ExecutorConfig): Long = config.result.effectiveTtlSeconds(request.resultTtlSeconds)

    /**
     * Emits a terminal event at most once (§12.1): "whichever of `pipeline_completed` /
     * `execution_aborted` wins, the other is suppressed". A cancellation that lands while the
     * success path is already emitting must not produce two terminal events on one stream.
     *
     * ## Why [NonCancellable] (F1)
     *
     * Every terminal path reaches here *while the execution is ending*, and three of them —
     * timeout, abort, structural cancellation — reach here on a scope that is **already
     * cancelled**. The emitter's own persistence hop is a `withContext(persistenceDispatcher)`
     * (`WebEventEmitter.emit`), and `withContext` refuses to start on a cancelled job: the event
     * was therefore dropped before `ExecutionRepository.complete` ever ran, leaving the row
     * `status = RUNNING`, `completed_at = NULL` **permanently**. Measured on a real composition
     * family: a child killed by its parent's deadline stayed `RUNNING` in `pipeline_executions`
     * forever, with no event to notice it by.
     *
     * The guard belongs here rather than at each call site because it is a property of terminal
     * emission itself: the row's single terminal UPDATE is the last thing an execution owes the
     * world, and it cannot be conditional on the scope still being alive. Same reasoning as
     * [PipelineExecutor.cleanup]'s `NonCancellable`, one layer up. The work inside is bounded (one
     * SSE write plus the emitter's own JDBC/Redis persistence, which swallows its own failures), so
     * this cannot turn a cancellation into a hang.
     */
    suspend fun emitTerminal(block: suspend () -> Unit) {
        if (terminalEmitted.compareAndSet(false, true)) withContext(NonCancellable) { block() }
    }

    fun result(
        status: ExecutionStatus,
        nodeStats: List<NodeStats>,
        resultRef: String?,
        completedAt: Instant,
    ): ExecutionResult =
        ExecutionResult(
            executionId = executionId,
            status = status,
            nodeStats = nodeStats,
            resultRef = resultRef,
            startedAt = startedAt,
            completedAt = completedAt,
            durationMs = elapsedMsAt(completedAt),
            warnings = warnings.collected(),
        )
}

/**
 * Builds a [PipelineExecutor] from the collaborator list dag-executor.md §5.2 names.
 *
 * §5.2's constructor takes the template engine, datasource registry, write-back runner and result
 * store directly; this implementation groups the first three (plus the result store) into
 * [NodeRunner] so node execution is unit-testable without an event emitter or a slot pool. This
 * factory keeps the spec's construction shape available to `app`.
 */
@Suppress("LongParameterList")
fun pipelineExecutor(
    templateEngine: TemplateEngine,
    datasourceRegistry: DatasourceRegistry,
    stagingFactory: StagingFactory,
    writebackRunner: WritebackRunner,
    resultStore: ResultStore,
    eventEmitter: EventEmitter,
    cancellationRegistry: CancellationRegistry,
    cancellationFlags: CancellationFlags,
    executionSlots: ExecutionSlots,
    dispatcher: ExecutorDispatcher,
    config: ExecutorConfig,
    resultUrls: ResultUrlFactory,
    metrics: ExecutorMetrics = ExecutorMetrics.inMemory(),
    auditSink: ExecutionAwareAuditSink? = null,
    /**
     * The composition port (design §4.1) every PIPELINE node of this executor dispatches to. The
     * assembling layer (web) wires its `SubPipelineExecutionRunner`; left null, a PIPELINE node
     * fails with `pipeline.node.child_execution_failed` ("not wired in this runtime").
     */
    subPipelineRunner: SubPipelineRunner? = null,
): PipelineExecutor =
    PipelineExecutor(
        nodeRunner =
            NodeRunner(templateEngine, datasourceRegistry, writebackRunner, resultStore, config, auditSink, metrics, subPipelineRunner),
        stagingFactory = stagingFactory,
        resultStore = resultStore,
        eventEmitter = eventEmitter,
        cancellationRegistry = cancellationRegistry,
        cancellationFlags = cancellationFlags,
        executionSlots = executionSlots,
        dispatcher = dispatcher,
        config = config,
        metrics = metrics,
        resultUrls = resultUrls,
    )
