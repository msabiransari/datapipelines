package co.datapipelines.web.schedules

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.SystemActorPrincipals
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.scheduler.Admission
import co.datapipelines.scheduler.ExecutionOutcome
import co.datapipelines.scheduler.JobExecutor
import co.datapipelines.scheduler.Launch
import co.datapipelines.scheduler.Preparation
import co.datapipelines.scheduler.RunState
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.StartOutcome
import co.datapipelines.scheduler.TargetViewer
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * **The pipeline executor** (scheduler design revision §3.1, §5.3; executor id `pipeline`) — the
 * one production implementation of the scheduler's [JobExecutor] port, and the ONLY place the
 * scheduler's opaque payload becomes a pipeline. It lives in `web` beside the runner it reuses
 * (A3: `web/config` is the composition root), and it never creates a second pipeline runner.
 *
 * ## The payload (schema version 1)
 * `{"pipeline": "<name>", "version": "current"}` — nothing else. `current` follows the pipeline's
 * sticky pointer wherever it points, drafts included (R5); `latest` is refused at save, and exact
 * numeric versions are not accepted in v1. The schedule's literal `parameters` (R7) are bound by
 * the SAME binder an interactive run uses, so a refusal is exactly an interactive run's.
 *
 * ## Authority (R2)
 * Every admission and launch acts as the system identity ([SystemActorPrincipals]) in the
 * schedule's workspace and ASKS for what it does — `pipeline.read` to read the version,
 * `pipeline.execute` to launch, `execution.read` to reconcile — through the resolver's system
 * arm. A launch is not exempt from permissions because it is internal (record §7.2).
 *
 * ## Start fails closed (A14)
 * [start] launches through [RecordingExecutionRunner] with `failClosed`: the execution runs only
 * once its `pipeline_executions` RUNNING row exists, under the pre-minted id, and [start] returns
 * [StartOutcome.Started] only when that row does — so "no row" means "no node ran", which is what
 * lets the scheduler call such a run definitively not started.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class PipelineJobExecutor(
    private val pipelines: PipelineRepository,
    private val pipelineService: PipelineService,
    private val workspaces: WorkspaceRepository,
    private val users: UserService,
    private val runner: RecordingExecutionRunner,
    private val executions: ExecutionRepository,
    private val events: ExecutionEventRepository,
    private val lens: PromoterLens,
    private val executorConfig: ExecutorConfig,
    private val scope: CoroutineScope,
    private val mapper: ObjectMapper,
    /** How long [start] waits for the RUNNING row before it reports "cannot know" (the run becomes `unknown`). */
    private val startWait: Duration = DEFAULT_START_WAIT,
) : JobExecutor {
    override val id: String = EXECUTOR_ID
    override val payloadSchemaVersion: Int = PAYLOAD_SCHEMA_VERSION

    // ---------------------------------------------------------------------------- save

    override fun validate(
        workspaceId: UUID,
        payload: JsonNode,
        parameters: JsonNode,
    ): String {
        val name = targetName(payload)
        val record =
            findTarget(workspaceId, name)
                ?: throw ScheduleException(
                    ScheduleErrorCodes.TARGET_NOT_FOUND,
                    "No pipeline named '$name' in this workspace.",
                    mapOf("pipeline" to name),
                )
        val version =
            record.currentVersion
                ?: throw payloadInvalid(
                    POINTER_NULL,
                    "Pipeline '$name' has no current version to follow — release it (or switch its current version) first.",
                )
        val executable =
            pipelineService.findExecutable(workspaceId, ReadLens.Everything, record, version)
                ?: throw ScheduleException(
                    ScheduleErrorCodes.TARGET_NOT_FOUND,
                    "Pipeline '$name' version $version has no stored body.",
                    mapOf("pipeline" to name, "version" to version),
                )
        // R7: the interactive run's binder — its catalogued refusal is the save's refusal.
        bind(executable.pipeline, parameters)
        return TARGET_PREFIX + record.name
    }

    // ---------------------------------------------------------------------------- admission

    // One return per refusal reason of record §7.1, in the order the checks must run.
    @Suppress("ReturnCount")
    override fun prepare(admission: Admission): Preparation {
        val workspace =
            activeWorkspace(admission.workspaceId) ?: return refused(WORKSPACE_INACTIVE, block = false, "The workspace is not active.")
        val principal = systemPrincipal(workspace)
        if (!principal.holds(Permission.PIPELINE_READ) || !principal.holds(Permission.PIPELINE_EXECUTE)) {
            return refused(AUTHORITY_REFUSED, block = true, "The system identity may not read and execute pipelines here.")
        }
        val name =
            try {
                targetName(admission.payload)
            } catch (e: ScheduleException) {
                return refused(PAYLOAD_INVALID, block = true, e.message ?: "invalid payload")
            }
        val record = findTarget(workspace.id, name) ?: return refused(TARGET_NOT_FOUND, block = true, "No pipeline named '$name'.")
        val version = record.currentVersion ?: return refused(POINTER_NULL, block = true, "Pipeline '$name' has no current version.")
        val detail =
            pipelines.findVersionDetail(workspace.id, record.id, version)
                ?: return refused(POINTER_NULL, block = true, "Pipeline '$name' names version $version, which does not exist.")
        val executable =
            pipelineService.findExecutable(workspace.id, ReadLens.Everything, record, version)
                ?: return refused(TARGET_NOT_FOUND, block = true, "Pipeline '$name' version $version has no stored body.")
        try {
            bind(executable.pipeline, admission.parameters)
        } catch (e: DatapipelinesException) {
            return refused(PARAMETERS_INVALID, block = true, "${e.code}: ${e.message}")
        }
        val snapshot =
            mapper.createObjectNode().apply {
                put("pipeline_id", record.id.toString())
                put("pipeline", record.name)
                put("version", version)
                put("version_status", detail.status.name)
                // R5: a DRAFT is mutable under its number, so the body's hash is what identifies what ran.
                put("body_sha256", detail.bodyHash)
            }
        return Preparation.Prepared(snapshot)
    }

    // ---------------------------------------------------------------------------- launch

    // One return per refusal reason of record §7.1, in the order the checks must run.
    @Suppress("ReturnCount")
    override fun start(launch: Launch): StartOutcome {
        val admission = launch.admission
        val workspace =
            activeWorkspace(admission.workspaceId) ?: return notStarted(WORKSPACE_INACTIVE, block = false, "The workspace is not active.")
        val principal = systemPrincipal(workspace)
        if (!principal.holds(Permission.PIPELINE_EXECUTE)) {
            return notStarted(AUTHORITY_REFUSED, block = true, "The system identity may not execute pipelines here.")
        }
        val pipelineId = UUID.fromString(launch.snapshot.path("pipeline_id").asText())
        val version = launch.snapshot.path("version").asInt()
        val record =
            pipelines.findById(workspace.id, pipelineId) ?: return notStarted(TARGET_NOT_FOUND, block = true, "The pipeline is gone.")
        val detail = pipelines.findVersionDetail(workspace.id, pipelineId, version)
        if (detail?.bodyHash != launch.snapshot.path("body_sha256").asText()) {
            // A draft edited between preparation and launch: the frozen body no longer exists.
            return notStarted(VERSION_CHANGED, block = false, "Pipeline '${record.name}' v$version changed after it was prepared.")
        }
        val executable =
            pipelineService.findExecutable(workspace.id, ReadLens.Everything, record, version)
                ?: return notStarted(TARGET_NOT_FOUND, block = true, "Pipeline '${record.name}' v$version has no stored body.")
        val request =
            ExecuteRequest(
                pipelineId = pipelineId,
                pipelineVersion = version,
                pipeline = executable.pipeline,
                userId = principal.userId,
                workspaceId = workspace.id,
                parameters = parametersOf(admission.parameters),
                // A12/L3: a scheduled run's caller result is inspection material — keep it for the maximum.
                resultTtlSeconds = executorConfig.result.ttlMaxSeconds,
                correlationId = admission.runId,
                triggeredVia = ExecutionTrigger.SCHEDULE,
                executionId = launch.executionId,
                slotLease = (launch.capacity as? SlotCapacityLease)?.slot,
            )
        return launchAndAwaitRecord(request, workspace.id)
    }

    /** Launches asynchronously and waits only for the RUNNING row (A14) — never for the run. */
    private fun launchAndAwaitRecord(
        request: ExecuteRequest,
        workspaceId: UUID,
    ): StartOutcome {
        val recorded = CompletableDeferred<UUID>()
        scope.launch {
            try {
                runner.run(request, workspaceId, ExecutionTrigger.SCHEDULE, failClosed = true) { recorded.complete(it) }
            } catch (e: CancellationException) {
                recorded.completeExceptionally(e)
                throw e
            } catch (
                // Everything the run can throw lands here; before the row exists it means "not
                // started", after it the executor already recorded the terminal state itself.
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (!recorded.completeExceptionally(e)) LOG.debug("Scheduled execution {} ended with {}", request.executionId, e.toString())
            } finally {
                recorded.completeExceptionally(IllegalStateException("the execution ended before its record was written"))
            }
        }
        return try {
            runBlocking { withTimeout(startWait.toMillis()) { recorded.await() } }
            StartOutcome.Started(request.executionId!!)
        } catch (e: TimeoutCancellationException) {
            // Cannot know: the row may still be written. The worker records `unknown`; the reconciler
            // watches the reference and records a later real terminal as an update (record §2.1).
            throw IllegalStateException("no execution record for ${request.executionId} within ${startWait.seconds} s", e)
        } catch (e: co.datapipelines.web.sse.ExecutionRecordUnwritableException) {
            // The fail-closed emitter refused to run without its RUNNING row: no row, no node (A14).
            // Not the schedule's fault — one database write failed — so it does not block.
            notStarted(RECORD_UNWRITABLE, block = false, e.message ?: "the execution record could not be written")
        } catch (
            // The deferred completed exceptionally before the row existed: the launch path refused.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            val code = (e as? DatapipelinesException)?.code
            notStarted(START_REFUSED, block = true, listOfNotNull(code, e.message).joinToString(": "))
        }
    }

    // ---------------------------------------------------------------------------- reconcile

    override fun inspect(
        workspaceId: UUID,
        executionIds: Collection<UUID>,
    ): Map<UUID, ExecutionOutcome> {
        val workspace = workspaces.findById(workspaceId)
        val principal = workspace?.let(::systemPrincipal)
        if (principal == null || !principal.holds(Permission.EXECUTION_READ)) {
            // No authority to read: decide nothing (a missing answer would read as "absent").
            LOG.error(
                "event=scheduler.inspect_refused workspace_id={} message=\"the system identity may not read executions\"",
                workspaceId,
            )
            return executionIds.associateWith { ExecutionOutcome.Running }
        }
        return executionIds.associateWith { id ->
            executions.findById(workspaceId, id)?.let(::outcomeOf) ?: ExecutionOutcome.Absent
        }
    }

    /** R6's normative table (record §7.1), from the execution record. */
    private fun outcomeOf(record: ExecutionRecord): ExecutionOutcome =
        when (record.status) {
            ExecutionStatus.RUNNING -> ExecutionOutcome.Running
            ExecutionStatus.SUCCESS -> ExecutionOutcome.Finished(RunState.SUCCEEDED, null)
            ExecutionStatus.FAILED -> ExecutionOutcome.Finished(RunState.FAILED, EXECUTION_FAILED)
            ExecutionStatus.ABORTED -> abortedOutcome(record)
        }

    private fun abortedOutcome(record: ExecutionRecord): ExecutionOutcome.Finished {
        // A5: the sweeper's `instance_lost` is NOT a conclusive abort — the worker may be alive.
        val code = record.errorJson?.let { runCatching { mapper.readTree(it).path("code").asText() }.getOrNull() }
        if (code == PipelineErrorCodes.Execution.INSTANCE_LOST) return ExecutionOutcome.Finished(RunState.UNKNOWN, INSTANCE_LOST)
        val reason =
            events
                .findByExecution(record.executionId)
                .lastOrNull { it.eventType == EXECUTION_ABORTED_EVENT }
                ?.let { runCatching { mapper.readTree(it.payloadJson).path("reason").asText() }.getOrNull() }
        return when (reason) {
            ABORT_CANCELLED -> ExecutionOutcome.Finished(RunState.CANCELLED, ABORT_CANCELLED)
            else -> ExecutionOutcome.Finished(RunState.ABORTED, reason?.takeIf { it.isNotBlank() })
        }
    }

    // ---------------------------------------------------------------------------- lens

    override fun visibleTargets(
        viewer: TargetViewer,
        workspaceId: UUID,
        targetRefs: Collection<String>,
    ): Set<String> {
        val principal = (viewer as? PrincipalTargetViewer)?.principal ?: return targetRefs.toSet()
        val view = lens.viewFor(principal)
        if (view.pipelines.isEverything) return targetRefs.toSet()
        return targetRefs.filter { it.startsWith(TARGET_PREFIX) && view.pipelines.admits(it.removePrefix(TARGET_PREFIX)) }.toSet()
    }

    // ---------------------------------------------------------------------------- helpers

    /**
     * The payload's pipeline name (schema version 1), or `schedule.validation.payload_invalid` — one
     * throw per distinct `details.reason`, so a caller is told exactly what to fix.
     */
    @Suppress("ThrowsCount")
    private fun targetName(payload: JsonNode): String {
        if (!payload.isObject) throw payloadInvalid("not_an_object", "The payload must be a JSON object.")
        val unknown =
            payload
                .fieldNames()
                .asSequence()
                .filter { it !in PAYLOAD_FIELDS }
                .toList()
        if (unknown.isNotEmpty()) throw payloadInvalid("unknown_field", "Unknown payload field(s): ${unknown.joinToString()}.")
        val name =
            payload
                .path("pipeline")
                .takeIf { it.isTextual }
                ?.asText()
                ?.trim()
        if (name.isNullOrEmpty()) throw payloadInvalid("pipeline_missing", "The payload names no pipeline (`pipeline`).")
        if (!PipelineNameGrammar.matches(
                name,
            )
        ) {
            throw payloadInvalid("pipeline_name_invalid", "'${name.take(MAX_ECHO)}' is not a pipeline name.")
        }
        when (val selector = payload.path("version").takeIf { it.isTextual }?.asText()) {
            VERSION_CURRENT -> Unit
            "latest" -> throw payloadInvalid(VERSION_LATEST_REFUSED, "`latest` is not a version selector; use `current` (record §3.1).")
            null -> throw payloadInvalid("version_missing", "The payload names no version selector; use `\"version\": \"current\"`.")
            else -> throw payloadInvalid("version_unsupported", "'${selector.take(MAX_ECHO)}' is not supported; v1 accepts `current` only.")
        }
        return name
    }

    private fun bind(
        pipeline: Pipeline,
        parameters: JsonNode,
    ) {
        ParameterBinder(
            pipeline.parameters,
            pipeline.calculatorOutputs(),
            pipeline.calculatorOutputGroups(),
            pipeline.transformOutputKeys(),
        ).bindOrThrow(parametersOf(parameters))
    }

    private fun parametersOf(parameters: JsonNode): Map<String, JsonNode> =
        if (parameters.isObject) parameters.properties().associate { it.key to it.value } else emptyMap()

    /**
     * The payload's pipeline, DISCARDED entities included: a pipeline whose every version was discarded
     * still exists with a NULL pointer, and versioning §3.4 answers that as the pointer refusal
     * (`pointer_null`), not as "no such pipeline" — only a name nothing holds is `target_not_found`.
     */
    private fun findTarget(
        workspaceId: UUID,
        name: String,
    ) = pipelines.findByNameAnyStatus(workspaceId, name)

    private fun activeWorkspace(id: UUID): Workspace? = workspaces.findById(id)?.takeIf { it.isActive }

    private fun systemPrincipal(workspace: Workspace): AuthenticatedPrincipal =
        SystemActorPrincipals.forWorkspace(users.systemActor(), workspace.id, workspace.name)

    private fun refused(
        reason: String,
        block: Boolean,
        message: String,
    ) = Preparation.Refused(reason, block, message)

    private fun notStarted(
        reason: String,
        block: Boolean,
        message: String,
    ) = StartOutcome.NotStarted(reason, block, message)

    private fun payloadInvalid(
        reason: String,
        message: String,
    ) = ScheduleException(ScheduleErrorCodes.PAYLOAD_INVALID, message, mapOf("reason" to reason))

    companion object {
        private val LOG = LoggerFactory.getLogger(PipelineJobExecutor::class.java)

        const val EXECUTOR_ID = "pipeline"
        const val PAYLOAD_SCHEMA_VERSION = 1

        /** `schedules.target_ref` for a pipeline (B15). */
        const val TARGET_PREFIX = "pipeline:"

        const val VERSION_CURRENT = "current"
        private val PAYLOAD_FIELDS = setOf("pipeline", "version")

        /** Reasons this executor reports (record §7.1). */
        const val POINTER_NULL = "pointer_null"
        const val TARGET_NOT_FOUND = "target_not_found"
        const val PARAMETERS_INVALID = "parameters_invalid"
        const val PAYLOAD_INVALID = "payload_invalid"
        const val VERSION_CHANGED = "version_changed"
        const val WORKSPACE_INACTIVE = "workspace_inactive"
        const val AUTHORITY_REFUSED = "authority_refused"
        const val RECORD_UNWRITABLE = "record_unwritable"
        const val START_REFUSED = "start_refused"
        const val EXECUTION_FAILED = "execution_failed"
        const val INSTANCE_LOST = "instance_lost"
        const val VERSION_LATEST_REFUSED = "version_latest_refused"

        private const val EXECUTION_ABORTED_EVENT = "execution_aborted"
        private const val ABORT_CANCELLED = "cancelled"
        private const val MAX_ECHO = 64

        /** The start wait's default — a RUNNING-row insert takes milliseconds; 30 s is a database in trouble. */
        val DEFAULT_START_WAIT: Duration = Duration.ofSeconds(30)
    }
}

/** A [TargetViewer] over the signed-in principal; narrowed exactly when the promoter lens applies (#178). */
class PrincipalTargetViewer(
    val principal: AuthenticatedPrincipal,
) : TargetViewer {
    override val narrowed: Boolean get() = principal.isLensed
}
