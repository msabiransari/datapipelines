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
import co.datapipelines.mcp.McpExecutionRunner
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.WebEventEmitter
import kotlinx.coroutines.CoroutineDispatcher
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * `mcp-server`'s [McpExecutionRunner] (P7): runs an agent-initiated execution through the same
 * recording emitter REST executions use, with **no SSE stream** attached.
 *
 * The shape mirrors [ExecutionLauncher]'s per-run assembly — one [PipelineExecutor] per run,
 * because the emitter captures per-execution state — minus everything stream-shaped: no
 * `SseEmitter`, no stream registration, no idempotency reservation (the MCP execute tool has no
 * `Idempotency-Key` carrier). Nothing is registered in [ExecutionStreamRegistry], so the
 * emitter's stream lookup finds nobody and the run simply records:
 *
 * - `pipeline_executions` with `triggered_via = MCP` (enums.md §18) — the tool also sets it on
 *   the request; the emitter's own [ExecutionContext] is what the recorder actually writes;
 * - the durable `execution_events` rows and the 1-hour Redis event log, so `GET
 *   /executions/{id}/events` replays an MCP run exactly like a REST one (rest-api §10.3);
 * - bookkeeping still lands on the dedicated persistence dispatcher, never the executor's pool
 *   (dag-executor §15.2) — the caller blocks in the tool's `runBlocking`, so there is no extra
 *   scope to manage.
 */
@Suppress("LongParameterList")
class McpRecordingExecutionRunner(
    private val templateEngine: TemplateEngine,
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
     * [ExecutionLauncher].
     */
    private val subPipelineRunner: SubPipelineRunner? = null,
) : McpExecutionRunner {
    private val log = LoggerFactory.getLogger(McpRecordingExecutionRunner::class.java)

    override suspend fun run(request: ExecuteRequest): ExecutionResult {
        val emitter =
            WebEventEmitter(
                context =
                    ExecutionContext(
                        pipelineId = request.pipelineId,
                        pipelineVersion = request.pipelineVersion,
                        userId = request.userId,
                        correlationId = request.correlationId ?: UUID.randomUUID(),
                        triggeredVia = ExecutionTrigger.MCP,
                        parametersJson = ExecutorJson.mapper.writeValueAsString(request.parameters),
                    ),
                stream = null,
                streams = streams,
                eventLog = eventLog,
                eventRepository = eventRepository,
                executionRepository = executionRepository,
                persistenceDispatcher = persistenceDispatcher,
            )
        val result = newExecutor(emitter).execute(request.copy(triggeredVia = ExecutionTrigger.MCP))
        recordResultColumns(result)
        return result
    }

    /**
     * The §10.2 result-history columns — same write [ExecutionLauncher] performs for REST runs;
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

    /** One executor per run — same reasoning as [ExecutionLauncher.newExecutor]. */
    private fun newExecutor(emitter: WebEventEmitter): PipelineExecutor =
        pipelineExecutor(
            templateEngine = templateEngine,
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
