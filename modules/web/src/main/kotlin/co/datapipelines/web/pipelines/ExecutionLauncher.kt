package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.events.EventEmitter
import co.datapipelines.executor.CancellationFlags
import co.datapipelines.executor.CancellationRegistry
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionAbortedException
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorDispatcher
import co.datapipelines.executor.ExecutorMetrics
import co.datapipelines.executor.IdempotencyKeys
import co.datapipelines.executor.IdempotencyOutcome
import co.datapipelines.executor.IdempotencyStore
import co.datapipelines.executor.PipelineConcurrencyLimitException
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.executor.SubPipelineRunner
import co.datapipelines.executor.WritebackRunner
import co.datapipelines.executor.pipelineExecutor
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.config.IdempotencyProperties
import co.datapipelines.web.metrics.WebMetrics
import co.datapipelines.web.sse.ExecutionContext
import co.datapipelines.web.sse.ExecutionStream
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.SseLogStreamer
import co.datapipelines.web.sse.WebEventEmitter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.UUID

/** Everything the launcher needs to start one execution — already validated, already authorized. */
data class ExecuteLaunch(
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val pipeline: Pipeline,
    val principal: AuthenticatedPrincipal,
    val parameters: Map<String, JsonNode>,
    val parametersJson: String,
    val correlationId: UUID,
    val resultTtlSeconds: Long?,
    val idempotencyKey: String?,
)

/**
 * Starts pipeline executions for the SSE endpoint (rest-api.md §6) — the one place the servlet
 * world meets the coroutine engine.
 *
 * ## Why a per-run [PipelineExecutor]
 * The executor takes its [EventEmitter] at construction, and the emitter captures per-execution
 * state (the stream, the correlation id, `triggered_via`). Building the executor per run through
 * dag's `pipelineExecutor(...)` factory is what keeps two concurrent executions' streams from
 * cross-wiring; the shared, `EventEmitter.NONE`-wired bean in `EngineConfiguration` exists only
 * as the bean-of-record `mcp-server`'s `@ConditionalOnBean` keys off — MCP runs never touch it,
 * going through [McpRecordingExecutionRunner]'s per-run pair instead (P7).
 *
 * ## Idempotency (§3.5)
 * `IdempotencyStore.reserve` is claimed **before** executing (that is the whole point of the
 * `SET NX`). The reservation's execution id is passed directly to the executor via
 * `ExecuteRequest.executionId`, so the idempotency store maps directly to the real execution id
 * with no alias layer. A retry resolves the existing reservation and follows the original's
 * events from the log ([SseLogStreamer.follow]) instead of re-executing.
 *
 * ## Failure semantics
 * Parameter binding runs **before** anything is reserved or streamed, so a bad request is a 400
 * with no execution and no reservation. A failure escaping `execute()` after events flowed has
 * already been reported on the stream as `pipeline_failed` / `execution_aborted`; before the first
 * event (the concurrency limit is the real case) the response is still uncommitted, so the
 * exception completes the emitter with error and the `@ControllerAdvice` renders the envelope.
 */
@Suppress("LongParameterList")
class ExecutionLauncher(
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
    private val streamer: SseLogStreamer,
    private val eventRepository: ExecutionEventRepository,
    private val executionRepository: ExecutionRepository,
    private val idempotencyStore: IdempotencyStore,
    private val idempotency: IdempotencyProperties,
    private val mapper: ObjectMapper,
    private val metrics: WebMetrics,
    private val scope: CoroutineScope,
    /**
     * Test seam: builds the per-run executor. Null in production wiring, where [newExecutor]
     * assembles the real one from the shared collaborators; tests substitute a fake so the launch
     * flow (streams, alias, idempotency) is exercisable without staging and datasources.
     */
    private val executorFactory: ((WebEventEmitter) -> PipelineExecutor)? = null,
    /**
     * The composition port (design 2026-08-13-pipeline-node-type §4.1) this run's PIPELINE nodes
     * dispatch to. Passed through to the per-run executor; null only in module-slice wiring, where
     * a PIPELINE node fails `pipeline.node.child_execution_failed` ("not wired in this runtime").
     */
    private val subPipelineRunner: SubPipelineRunner? = null,
) {
    private val log = LoggerFactory.getLogger(ExecutionLauncher::class.java)

    /** Starts — or attaches to — the execution, returning the SSE emitter to serve. */
    fun launch(request: ExecuteLaunch): SseEmitter {
        // §12.1 — the per-user concurrent stream cap, enforced where the streams live.
        // Check-then-register is not atomic: a bounded overshoot, documented on
        // ExecutionStreamRegistry.atStreamLimit (gate C, F11).
        if (streams.atStreamLimit(request.principal.userId)) {
            throw ApiErrors.streamLimitExceeded(streams.maxStreamsPerUser)
        }
        // Bind parameters up front (pipeline-contract §7.1): deterministic, and a rejected
        // parameter is a 400 with no execution, no reservation and no stream.
        ParameterBinder(request.pipeline.parameters).bindOrThrow(request.parameters)

        val candidate = request.idempotencyKey?.let { reserve(it, request) }
        if (candidate is Reservation.Attach) {
            metrics.idempotencyHit()
            return attachToOriginal(candidate.candidateId)
        }

        return startFresh(request, (candidate as? Reservation.Reserved)?.candidateId)
    }

    private sealed interface Reservation {
        data class Reserved(
            val candidateId: UUID,
        ) : Reservation

        data class Attach(
            val candidateId: UUID,
        ) : Reservation
    }

    private fun reserve(
        key: String,
        request: ExecuteLaunch,
    ): Reservation {
        val hash = IdempotencyKeys.requestHash(request.pipelineId, request.pipelineVersion, request.parametersJson)
        val candidate = UUID.randomUUID()
        return try {
            when (
                val outcome =
                    idempotencyStore.reserve(
                        request.principal.userId,
                        key,
                        hash,
                        candidate,
                        idempotency.ttlSeconds,
                    )
            ) {
                is IdempotencyOutcome.Reserved -> Reservation.Reserved(outcome.executionId)
                is IdempotencyOutcome.Existing -> Reservation.Attach(outcome.executionId)
            }
        } catch (e: DatapipelinesException) {
            if (e.code == PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED) metrics.idempotencyConflict()
            throw e
        }
    }

    /**
     * §3.5 — "a retried request with the same key returns the original execution instead of
     * re-executing". The original's events are served from the Redis log; if the original is still
     * running the stream follows it live. A log that has already expired (> 1h, §10.3) is the same
     * `410` the replay endpoint gives.
     */
    private fun attachToOriginal(executionId: UUID): SseEmitter {
        if (!streamer.hasLog(executionId)) {
            throw ApiException(
                PipelineErrorCodes.Result.EXPIRED,
                "The original execution '$executionId' finished and its event stream has expired; " +
                    "its record remains available via GET /executions/{id}.",
                mapOf("execution_id" to executionId.toString(), "reason" to "event_log_expired"),
            )
        }
        return streamer.follow(executionId)
    }

    private fun startFresh(
        request: ExecuteLaunch,
        executionId: UUID?,
    ): SseEmitter {
        val sse = SseEmitter(NEVER_TIMEOUT)
        val workspaceId = request.principal.requireWorkspace().id
        val context =
            ExecutionContext(
                pipelineId = request.pipelineId,
                pipelineVersion = request.pipelineVersion,
                userId = request.principal.userId,
                correlationId = request.correlationId,
                triggeredVia = ExecutionTrigger.REST,
                parametersJson = request.parametersJson,
                workspaceId = workspaceId,
            )
        val emitter =
            WebEventEmitter(
                context = context,
                stream = null,
                streams = streams,
                eventLog = eventLog,
                eventRepository = eventRepository,
                executionRepository = executionRepository,
                persistenceDispatcher = persistenceDispatcher,
            ) { onExecutionStarted(it, request, sse) }
        val executor = executorFactory?.invoke(emitter) ?: newExecutor(emitter, workspaceId)

        scope.launch {
            runExecution(executor, request, emitter, sse, executionId, workspaceId)
        }
        return sse
    }

    /** The stream registration + idempotency alias, at the first moment the real id exists. */
    private fun onExecutionStarted(
        executionId: UUID,
        request: ExecuteLaunch,
        sse: SseEmitter,
    ) {
        streams.register(ExecutionStream(executionId, request.principal.userId, sse, mapper))
    }

    private suspend fun runExecution(
        executor: PipelineExecutor,
        request: ExecuteLaunch,
        emitter: WebEventEmitter,
        sse: SseEmitter,
        executionId: UUID?,
        workspaceId: UUID,
    ) {
        try {
            val result =
                executor.execute(
                    ExecuteRequest(
                        pipelineId = request.pipelineId,
                        pipelineVersion = request.pipelineVersion,
                        pipeline = request.pipeline,
                        userId = request.principal.userId,
                        // The pipeline's workspace IS the execution's workspace (025 A5):
                        // runtime datasource resolution scopes by it, matching save-time
                        // validation. Same value the engine was built with, two lines up.
                        workspaceId = workspaceId,
                        parameters = request.parameters,
                        idempotencyKey = request.idempotencyKey,
                        resultTtlSeconds = request.resultTtlSeconds,
                        correlationId = request.correlationId,
                        triggeredVia = ExecutionTrigger.REST,
                        executionId = executionId,
                    ),
                )
            recordResultColumns(result)
        } catch (e: PipelineConcurrencyLimitException) {
            // Before the first event: the response is uncommitted, so this becomes a real 429.
            failBeforeStart(sse, emitter, e)
            return
        } catch (e: ExecutionAbortedException) {
            log.debug("Execution aborted ({}); the stream already carried the event.", e.reason.wire)
        } catch (e: DatapipelinesException) {
            // pipeline_failed / a setup failure was streamed when anything was emitted; otherwise
            // the response is still uncommitted and the advice renders the envelope.
            if (!emitter.emittedAny()) {
                failBeforeStart(sse, emitter, e)
                return
            }
            log.debug("Execution ended with {} after its events were streamed.", e.code)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (!emitter.emittedAny()) {
                failBeforeStart(sse, emitter, e)
                return
            }
            log.error("Execution failed outside the executor's mapped paths.", e)
        } finally {
            emitter.executionIdOrNull()?.let(streams::close)
        }
    }

    private fun failBeforeStart(
        sse: SseEmitter,
        emitter: WebEventEmitter,
        error: Exception,
    ) {
        emitter.executionIdOrNull()?.let(streams::close)
        sse.completeWithError(error)
    }

    /**
     * The §10.2 result-history columns (`result_row_count` / `result_size_bytes`).
     *
     * The emitter completes the row on the terminal event, which carries no result size, so these
     * land here — after `execute` returns the `resultRef` — described from the stored result
     * itself rather than inferred from the inline page (wrong for anything over one page). Without
     * them the cursor cannot tell "succeeded with a result" from "zero caller nodes". Bookkeeping
     * only: a failure here is logged, never fails the completed execution (dag-executor §10's
     * emitter policy applied to its sibling write).
     */
    private fun recordResultColumns(result: ExecutionResult) {
        val ref = result.resultRef ?: return
        runCatching {
            val view = resultStore.describe(ref) ?: return
            executionRepository.recordResult(result.executionId, view.totalRows, view.bytes)
        }.onFailure { log.warn("Result columns for execution {} not recorded.", result.executionId, it) }
    }

    /**
     * One executor per run — see the class KDoc. The engine is the workspace's own (T24):
     * template resolution and the render caches are scoped to it.
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
            subPipelineRunner = subPipelineRunner,
        )

    private companion object {
        const val NEVER_TIMEOUT = 0L
    }
}
