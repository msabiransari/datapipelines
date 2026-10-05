package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.SourceLaunch
import co.datapipelines.application.dashboards.SourceOutcome
import co.datapipelines.application.dashboards.SourceStarter
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionAbortedException
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import co.datapipelines.web.sse.ExecutionRecordUnwritableException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * [SourceStarter] over [RecordingExecutionRunner] — the seam MCP, published endpoints and schedules already use — with
 * `triggered_via = DASHBOARD` (the implementation spec's §9 step 4, §18 premise 4). The emitter, the persistence path
 * and the per-run executor are the runner's; nothing is copied.
 *
 * ## What is deliberately different from the REST launcher
 * - `ExecutionLauncher.decide` is NOT consulted: a refresh's executions are never idempotency-aliased across refreshes.
 *   That call also does the parameter pre-bind, which is why THIS class runs the pre-bind itself — a literal the binder
 *   refuses is a clean `source_failed` before any execution exists, never an executor-internal failure after one.
 * - `failClosed = true`: a source whose RUNNING row cannot be written does not run, so every execution is LINKABLE to
 *   its refresh (D52).
 * - `directSink` is the refresh's bounded collector: the caller node's rows stream there, the result store is never
 *   written, and `resultTtlSeconds` is moot (spec §18 premise 5).
 * - The lease is one of the refresh's reserved instance slots ([SourceLaunch.slot]); no per-user slot is taken.
 *
 * ## What it answers
 * A failure is an OUTCOME carrying a catalogued code, never an exception: the runtime turns each into `source_failed`.
 * The executor's own message (a driver's text, a datasource's) stays on the execution's events — this class hands back
 * the code only. Cancellation of the calling coroutine is NOT swallowed: `ExecutionAbortedException` is a
 * `CancellationException`, and it is an execution-level abort only while this coroutine is still active.
 */
class RecordingSourceStarter(
    private val runner: RecordingExecutionRunner,
    private val readOnly: ReadOnlyPipelineRule,
    private val pipelines: PipelineRepository,
) : SourceStarter {
    private val log = LoggerFactory.getLogger(RecordingSourceStarter::class.java)

    override suspend fun run(
        launch: SourceLaunch,
        onRecorded: (UUID) -> Unit,
    ): SourceOutcome {
        val pipeline = launch.pipeline
        val admission: (Pipeline, UUID, Int) -> Unit = { body, id, version ->
            val status = pipelines.findVersionDetail(launch.workspaceId, id, version)?.status
            if (status == null || !PipelineVersionStatus.eligibleForPointer(status, draftsEligible = launch.allowDraftDependencies)) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Dashboard.RUNTIME_DEPENDENCY_MISSING,
                    message = "A dashboard source pipeline version is no longer eligible for execution.",
                )
            }
            if (!readOnly.check(body, launch.workspaceId).isValid) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Dashboard.SOURCE_NOT_READ_ONLY,
                    message = "Dashboard source pipelines must remain read-only, including every child pipeline.",
                )
            }
        }
        try {
            admission(pipeline, launch.pipelineId, launch.pipelineVersion)
            ParameterBinder(
                pipeline.parameters,
                pipeline.calculatorOutputs(),
                pipeline.calculatorOutputGroups(),
                pipeline.transformOutputKeys(),
            ).bindOrThrow(launch.parameters)
        } catch (e: DatapipelinesException) {
            return SourceOutcome.Failed(null, e.code)
        }
        val request =
            ExecuteRequest(
                pipelineId = launch.pipelineId,
                pipelineVersion = launch.pipelineVersion,
                pipeline = pipeline,
                userId = launch.userId,
                workspaceId = launch.workspaceId,
                parameters = launch.parameters,
                correlationId = launch.refreshId,
                triggeredVia = ExecutionTrigger.DASHBOARD,
                directSink = launch.sink,
                slotLease = launch.slot,
                executedByKeyKind = launch.executedByKeyKind,
                pipelineAdmission = admission,
            )
        return try {
            SourceOutcome.Succeeded(
                runner.run(request, launch.workspaceId, ExecutionTrigger.DASHBOARD, failClosed = true, onRecorded).executionId,
            )
        } catch (e: ExecutionAbortedException) {
            // Cancelled through the executor's own path (a flag, the shutdown drain) while this coroutine lives.
            if (currentCoroutineContext().isActive) SourceOutcome.Aborted(null) else throw e
        } catch (e: ExecutionRecordUnwritableException) {
            log.warn(
                "event=dashboard.source_not_started refresh_id={} source={} error={}",
                launch.refreshId,
                launch.sourceName,
                e.javaClass.simpleName,
            )
            SourceOutcome.Failed(null, PipelineErrorCodes.Execution.INSTANCE_LOST)
        } catch (e: DatapipelinesException) {
            SourceOutcome.Failed(null, e.code)
        }
    }
}
