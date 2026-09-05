package co.datapipelines.web.pipelines

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.CancellationFlags
import co.datapipelines.executor.CancellationRegistry
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
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.executor.SubPipelineRunner
import co.datapipelines.executor.WritebackRunner
import co.datapipelines.executor.pipelineExecutor
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.WebEventEmitter
import kotlinx.coroutines.CoroutineDispatcher
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Runs a fully-formed [ExecuteRequest] to completion **in-process**, recording it exactly as a
 * REST run is recorded, with **no SSE stream** attached.
 *
 * ## What this is, and what 074 extracted
 *
 * This body was `McpRecordingExecutionRunner`, whose only MCP-specific detail was the hardcoded
 * `ExecutionTrigger.MCP`. Published endpoints need the identical thing under a different trigger:
 * resolve a released version, run it to completion in-process, record the row and the events, and
 * hand back the result — never HTTP-to-self, never SSE parsing. So the trigger became a
 * parameter and the class became shared; [McpRecordingExecutionRunner] is now a thin adapter that
 * fixes it to `MCP`, and the endpoint surface fixes it to `ENDPOINT`.
 *
 * That is the whole extraction, and it is deliberately small. `SubPipelineExecutionRunner` — the
 * design's other candidate — could not be reused: its `run` takes an `ExecutableNode` and a
 * `NodeExecutionContext`, it carries composition lineage (`parentExecutionId`, `rootExecutionId`,
 * `compositionDepth`), it resolves `${parent_param}` references, and it remaps every failure to
 * `pipeline.node.child_execution_failed`. Every one of those is wrong for an HTTP surface, and
 * the part that is right — build an emitter, build a per-run executor, execute, record the result
 * columns — is what this class already was.
 *
 * ## Why one executor per run
 *
 * The emitter captures per-execution state, so the executor that wraps it cannot be shared. Same
 * reasoning as [ExecutionStreamLauncher.newExecutor].
 *
 * ## What it records
 *
 * - `pipeline_executions` with the caller's [ExecutionTrigger] (enums.md §18);
 * - the durable `execution_events` rows and the 1-hour Redis event log, so `GET
 *   /executions/{id}/events` replays one of these exactly like a REST run (rest-api §10.3);
 * - bookkeeping on the dedicated persistence dispatcher, never the executor's pool
 *   (dag-executor §15.2).
 *
 * Nothing is registered in [ExecutionStreamRegistry], so the emitter's stream lookup finds nobody
 * and the run simply records.
 */
@Suppress("LongParameterList")
class RecordingExecutionRunner(
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
    /**
     * The composition port (design 2026-08-13-pipeline-node-type §4.1) an MCP-run pipeline's
     * PIPELINE nodes dispatch to — passed through to the per-run executor, as in
     * [ExecutionStreamLauncher].
     */
    private val subPipelineRunner: SubPipelineRunner? = null,
    /**
     * The test seam [ExecutionStreamLauncher] and [SubPipelineExecutionRunner] established:
     * when set, builds the per-run executor INSTEAD of the production `pipelineExecutor`
     * assembly — the unit test observes the request the runner hands downstream without
     * provisioning eighteen real collaborators. Null in production (the default).
     */
    private val executorFactory: ((co.datapipelines.web.sse.WebEventEmitter) -> PipelineExecutor)? = null,
) {
    private val log = LoggerFactory.getLogger(RecordingExecutionRunner::class.java)

    suspend fun run(
        request: ExecuteRequest,
        workspaceId: UUID,
        trigger: ExecutionTrigger,
    ): ExecutionResult {
        val emitter =
            WebEventEmitter(
                context =
                    ExecutionContext(
                        pipelineId = request.pipelineId,
                        pipelineVersion = request.pipelineVersion,
                        userId = request.userId,
                        correlationId = request.correlationId ?: UUID.randomUUID(),
                        triggeredVia = trigger,
                        parametersJson = ExecutorJson.mapper.writeValueAsString(request.parameters),
                        workspaceId = workspaceId,
                    ),
                stream = null,
                streams = streams,
                eventLog = eventLog,
                eventRepository = eventRepository,
                executionRepository = executionRepository,
                persistenceDispatcher = persistenceDispatcher,
            )
        val result =
            (executorFactory?.invoke(emitter) ?: newExecutor(emitter, workspaceId))
                .execute(request.copy(triggeredVia = trigger))
        recordResultColumns(result)
        return result
    }

    /**
     * The §10.2 result-history columns — same write [ExecutionStreamLauncher] performs for REST runs;
     * the emitter's terminal event carries no result size, so they land after `execute` returns.
     * Bookkeeping only: a failure is logged, never fails the completed execution.
     */
    private fun recordResultColumns(result: ExecutionResult) {
        val ref = result.resultRef ?: return
        runCatching {
            val view = resultStore.describe(ref) ?: return
            executionRepository.recordResult(result.executionId, view.totalRows, view.bytes)
        }.onFailure { log.warn("Result columns for execution {} not recorded.", result.executionId, it) }
    }

    /** One executor per run — same reasoning as [ExecutionStreamLauncher.newExecutor]; the engine is the workspace's own (T24). */
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
            subPipelineRunner = subPipelineRunner,
        )
}
