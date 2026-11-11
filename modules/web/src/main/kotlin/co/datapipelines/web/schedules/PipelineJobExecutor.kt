package co.datapipelines.web.schedules

import co.datapipelines.application.LakeAppendAuthorization
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.application.pipelines.PipelineInputResolver
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.SystemActorPrincipals
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionReference
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.persistence.FailureShape
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.LakeExecutionMetadata
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
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
import com.fasterxml.jackson.databind.node.TextNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * **The pipeline executor** (scheduler design revision §3.1, §5.3; executor id `pipeline`) — the
 * one production implementation of the scheduler's [JobExecutor] port, and the ONLY place the
 * scheduler's opaque payload becomes a pipeline. It lives in `web` beside the runner it reuses
 * (A3: `web/config` is the composition root), and it never creates a second pipeline runner.
 *
 * ## The payload (schema version 2 — the exact pin, #786 §D; dependency-graph record §24)
 * `{"pipeline": "<name>", "version": <int ≥ 1>}` — plus the OPTIONAL additive `parameter_bindings`
 * object (design revision §5.2): `{"<parameter>": {"source": "keyword", "name": "TODAY"|"YESTERDAY"} |
 * {"source": "literal", "value": <wire value>}}`. The version is an EXACT pipeline version: a run
 * executes exactly that version, never what the pipeline's current pointer names when the run
 * comes due. `"current"`, `"latest"`, a missing version and anything but a positive integer are
 * refused at save (`payload_invalid`, `details.reason` naming which). At save the pinned version
 * must exist and be eligible under this deployment's posture
 * ([PipelineVersionStatus.eligibleForPointer] over [AuthoringGuard.developmentPosture] — released
 * everywhere, a draft only where authoring is enabled); otherwise `target_not_released`. The same
 * policy is applied again at prepare and at launch, each time over the pinned version's status as
 * read then (`version_not_eligible`, blocking): a queued or prepared copy can outlive an explicit
 * repin of its schedule, and the now-unpinned old version can be discarded before that copy runs.
 * At launch the status, the body hash and the body that runs come from ONE read of the version.
 *
 * Schema version 1 (`"version": "current"`, the pointer-following payload before #786) is READ-
 * checked, never upgraded at read time: [validate] refuses a stored schema-1 payload with
 * `schedule.validation.payload_schema_unsupported` and [prepare] refuses its run
 * `payload_schema_unsupported`, blocking (D.2). The upgrade's startup conversion
 * ([ScheduleVersionConversion], D.5) rewrites every live schema-1 row in place first, so after
 * it a schema-1 row can only be a planted one. The schedule's literal `parameters` (R7) and every resolved
 * binding are bound by the SAME binder an interactive run uses, so a refusal is exactly an
 * interactive run's — resolution ([PipelineInputResolver]) runs BEFORE the binder, and the same
 * name in `parameters` and `parameter_bindings` is the ambiguity refusal, never a precedence.
 * A `reference*` key inside the payload or a binding is refused here: the run's time is the
 * scheduler's frozen context, never a client field (§5.1).
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
    /** #786 D.1 — the posture eligibility reads: the authoring capability, never the mail policy's posture (A37). */
    private val authoring: AuthoringGuard,
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

    /** Stateless: one shared resolver for save-time structure and run-time resolution (§5.2). */
    private val resolver = PipelineInputResolver()

    // ---------------------------------------------------------------------------- save

    @Suppress("ThrowsCount") // each refusal is a distinct catalogued code a caller must tell apart
    override fun validate(
        workspaceId: UUID,
        payload: JsonNode,
        parameters: JsonNode,
        payloadSchemaVersion: Int,
    ): String {
        // D.2: the stored schema is READ — a schema-1 payload is refused, never re-read as an exact pin.
        if (payloadSchemaVersion != PAYLOAD_SCHEMA_VERSION) throw schemaUnsupported(payloadSchemaVersion)
        val (name, version) = target(payload)
        val record =
            findTarget(workspaceId, name)
                ?: throw ScheduleException(
                    ScheduleErrorCodes.TARGET_NOT_FOUND,
                    "No pipeline named '$name' in this workspace.",
                    mapOf("pipeline" to name),
                )
        val detail =
            pipelines.findVersionDetail(workspaceId, record.id, version)
                ?: throw ScheduleException(
                    ScheduleErrorCodes.TARGET_NOT_FOUND,
                    "Pipeline '$name' has no version $version.",
                    mapOf("pipeline" to name, "version" to version),
                )
        // R3: the pin must be one this deployment runs — released, or a draft where authoring is enabled.
        if (!PipelineVersionStatus.eligibleForPointer(detail.status, authoring.developmentPosture)) {
            throw ScheduleException(
                ScheduleErrorCodes.TARGET_NOT_RELEASED,
                "Pipeline '$name' v$version is ${detail.status.name.lowercase()}, which this deployment does not run. " +
                    "Pick a released version.",
                mapOf("pipeline" to name, "version" to version, "status" to detail.status.name),
            )
        }
        val executable =
            pipelineService.findExecutable(workspaceId, ReadLens.Everything, record, version)
                ?: throw ScheduleException(
                    ScheduleErrorCodes.TARGET_NOT_FOUND,
                    "Pipeline '$name' version $version has no stored body.",
                    mapOf("pipeline" to name, "version" to version),
                )
        val literals = parametersOf(parameters)
        val bindings = parseBindings(payload)
        // §5.2: structure first — the resolver's refusals are the save's refusals, exactly as a
        // direct execution's would be. A save has no reference time yet, so keyword bindings are
        // checked structurally and bound with a placeholder DATE (below) that is never persisted.
        resolver.checkStructure(declaredTypes(executable.pipeline), literals, bindings)?.let { throw bindingRefusal(it) }
        // R7: the interactive run's binder over the literals, each literal binding's value and a
        // placeholder DATE per keyword binding (so a required bound parameter passes) — its
        // catalogued refusal is the save's refusal.
        bind(executable.pipeline, saveParameters(literals, bindings))
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
        // D.2: the run row's own copy of the stored schema — a schema-1 payload never runs (and never reads CURRENT).
        if (admission.payloadSchemaVersion != PAYLOAD_SCHEMA_VERSION) {
            return refused(
                PAYLOAD_SCHEMA_UNSUPPORTED,
                block = true,
                "The schedule's payload is stored in schema ${admission.payloadSchemaVersion}; this executor runs schema " +
                    "$PAYLOAD_SCHEMA_VERSION (an exact version) only. Edit the schedule to pick the version to run.",
            )
        }
        val (name, version) =
            try {
                target(admission.payload)
            } catch (e: ScheduleException) {
                return refused(PAYLOAD_INVALID, block = true, e.message ?: "invalid payload")
            }
        val record = findTarget(workspace.id, name) ?: return refused(TARGET_NOT_FOUND, block = true, "No pipeline named '$name'.")
        // D.1: exactly the pinned version — no CURRENT read anywhere on the run path.
        val detail =
            pipelines.findVersionDetail(workspace.id, record.id, version)
                ?: return refused(TARGET_NOT_FOUND, block = true, "Pipeline '$name' has no version $version.")
        if (!PipelineVersionStatus.eligibleForPointer(detail.status, authoring.developmentPosture)) {
            return refused(
                VERSION_NOT_ELIGIBLE,
                block = true,
                "Pipeline '$name' v$version is ${detail.status.name.lowercase()}, which this deployment does not run.",
            )
        }
        val executable =
            pipelineService.findExecutable(workspace.id, ReadLens.Everything, record, version)
                ?: return refused(TARGET_NOT_FOUND, block = true, "Pipeline '$name' version $version has no stored body.")
        // §5.2: resolve the bindings against the run's FROZEN reference (the schedule's zone, its
        // logical occurrence time — not the actual start) and the PINNED version's declarations.
        // A binding that no longer fits — the parameter removed or retyped after save — is this
        // refusal, naming the binding in the message.
        val resolved =
            when (val inputs = inputsFor(admission, executable)) {
                is Inputs.Refused -> return refused(PARAMETERS_INVALID, block = true, inputs.message)
                is Inputs.Resolved -> inputs.parameters
            }
        val snapshot =
            mapper.createObjectNode().apply {
                put("pipeline_id", record.id.toString())
                put("pipeline", record.name)
                put("version", version)
                put("version_status", detail.status.name)
                // R5: a DRAFT is mutable under its number, so the body's hash is what identifies what ran.
                put("body_sha256", detail.bodyHash)
                // The literal map that actually executes (slice 3): stored, never read, by the
                // scheduler; `start` reads it back to build exactly this request.
                replace(RESOLVED_PARAMETERS, nodeOf(resolved))
            }
        return Preparation.Prepared(snapshot)
    }

    /**
     * The run's resolved inputs: the payload's bindings parsed, envelope-checked and resolved
     * against the frozen reference and the PINNED version, then bound by the interactive
     * binder — every refusal along the way reduces to `parameters_invalid` with its message.
     */
    private fun inputsFor(
        admission: Admission,
        executable: PipelineService.ExecutablePipeline,
    ): Inputs {
        val bindings =
            try {
                parseBindings(admission.payload)
            } catch (e: ScheduleException) {
                return Inputs.Refused(e.message ?: "invalid payload")
            }
        val reference =
            referenceOf(admission)
                ?: return Inputs.Refused("The run's reference timezone '${admission.referenceTimezone}' is not a zone this instance knows.")
        return when (
            val result =
                resolver.resolve(
                    declaredTypes(executable.pipeline),
                    parametersOf(admission.parameters),
                    bindings,
                    reference,
                )
        ) {
            is PipelineInputResolver.Result.Refused -> {
                Inputs.Refused("[${result.refusal.parameter}] ${result.refusal.message}")
            }

            is PipelineInputResolver.Result.Resolved -> {
                try {
                    bind(executable.pipeline, result.parameters)
                } catch (e: DatapipelinesException) {
                    return Inputs.Refused("${e.code}: ${e.message}")
                }
                Inputs.Resolved(result.parameters)
            }
        }
    }

    /** The prepare-time resolution outcome ([Inputs.Resolved.parameters] is what will execute). */
    private sealed interface Inputs {
        data class Resolved(
            val parameters: Map<String, JsonNode>,
        ) : Inputs

        data class Refused(
            val message: String,
        ) : Inputs
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
        val executable =
            when (val pinned = pinnedVersion(workspace.id, record, version, launch.snapshot)) {
                is PinnedVersion.Ready -> pinned.executable
                is PinnedVersion.Refused -> return pinned.outcome
            }
        val reference =
            referenceOf(admission)
                ?: return notStarted(
                    PARAMETERS_INVALID,
                    block = true,
                    "The run's reference timezone '${admission.referenceTimezone}' is not a zone this instance knows.",
                )
        val frozenParameters =
            launch.snapshot
                .path(RESOLVED_PARAMETERS)
                .takeIf { it.isObject }
                ?.properties()
                ?.associate { it.key to it.value }
        if (frozenParameters == null && admission.payload.has(BINDINGS_FIELD)) {
            // Fail closed (#269): a bindings-carrying schedule whose snapshot lacks the resolved map
            // would otherwise launch the RAW parameters with bindings unapplied. The only way the
            // key can be missing on such a schedule is a rolling deploy between prepare and start
            // (slice 3's prepare froze it; an older start does not read it) — nothing about the
            // schedule is wrong, so this does not block: the next occurrence prepares afresh.
            return notStarted(
                SNAPSHOT_UNRESOLVED,
                block = false,
                "The run's frozen snapshot carries no resolved parameters for a schedule with bindings.",
            )
        }
        val request =
            ExecuteRequest(
                pipelineId = pipelineId,
                pipelineVersion = version,
                pipeline = executable.pipeline,
                lakeExecutionMetadata = LakeExecutionMetadata.from(executable),
                lakeAppendGrant = LakeAppendAuthorization.grant(principal, executable.writesManagedLake, Instant.now()),
                userId = principal.userId,
                workspaceId = workspace.id,
                // The map frozen at preparation (R5): what was resolved then is what executes now.
                // Pre-slice-3 rows (no bindings) keep the raw-parameters fallback (#269).
                parameters = frozenParameters ?: parametersOf(admission.parameters),
                // A12/L3: a scheduled run's caller result is inspection material — keep it for the maximum.
                resultTtlSeconds = executorConfig.result.ttlMaxSeconds,
                correlationId = admission.runId,
                triggeredVia = ExecutionTrigger.SCHEDULE,
                executionId = launch.executionId,
                slotLease = (launch.capacity as? SlotCapacityLease)?.slot,
                // A13: the frozen reference travels with the execution — and, through the child
                // request, with every pipeline it composes.
                reference = reference,
            )
        return launchAndAwaitRecord(request, workspace.id)
    }

    /**
     * The pinned version as it stands at launch, read ONCE (#786 R2): the body that would run, its hash and its status come
     * from the same row read, so a status read earlier can never authorize a body read later. Both checks run before the
     * runner is called.
     */
    private fun pinnedVersion(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
        snapshot: JsonNode,
    ): PinnedVersion {
        val executable = pipelineService.findExecutable(workspaceId, ReadLens.Everything, record, version)
        if (executable == null || executable.bodyHash != snapshot.path("body_sha256").asText()) {
            // D.1: the PINNED version's body changed (a draft edited under its number) or its row is gone since preparation.
            return PinnedVersion.Refused(
                notStarted(VERSION_CHANGED, block = false, "Pipeline '${record.name}' v$version changed after it was prepared."),
            )
        }
        val status = executable.status
        if (status == null || !PipelineVersionStatus.eligibleForPointer(status, authoring.developmentPosture)) {
            // R2: the pin left eligibility after preparation — e.g. prepared on v1, the live schedule repinned to v2,
            // then the now-unpinned v1 discarded. Prepare's policy, prepare's reason, and it blocks as there.
            val shown = status?.name?.lowercase() ?: "unknown"
            return PinnedVersion.Refused(
                notStarted(
                    VERSION_NOT_ELIGIBLE,
                    block = true,
                    "Pipeline '${record.name}' v$version is $shown, which this deployment does not run.",
                ),
            )
        }
        return PinnedVersion.Ready(executable)
    }

    /** What [pinnedVersion] found: the version to run, or the launch's refusal. */
    private sealed interface PinnedVersion {
        data class Ready(
            val executable: PipelineService.ExecutablePipeline,
        ) : PinnedVersion

        data class Refused(
            val outcome: StartOutcome.NotStarted,
        ) : PinnedVersion
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
            // #253: the run's trail may carry only catalogued values — the pipeline error code if
            // the refusal was one of ours, else the fixed reason. The raw exception (driver text
            // included) stays on this instance's log for the operator, never in the response.
            LOG.warn(
                "event=scheduler.start_refused run_id={} execution_id={} message=\"{}\"",
                request.correlationId,
                request.executionId,
                e.toString(),
                e,
            )
            val code = (e as? DatapipelinesException)?.code
            notStarted(START_REFUSED, block = true, code ?: LAUNCH_REFUSED_WITHOUT_CODE)
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

    /** R6's normative table (record §7.1), from the execution record — its own timing rides along (#258). */
    private fun outcomeOf(record: ExecutionRecord): ExecutionOutcome =
        when (record.status) {
            ExecutionStatus.RUNNING -> {
                ExecutionOutcome.Running
            }

            ExecutionStatus.SUCCESS -> {
                ExecutionOutcome.Finished(RunState.SUCCEEDED, null, record.startedAt, record.completedAt)
            }

            ExecutionStatus.FAILED -> {
                ExecutionOutcome.Finished(RunState.FAILED, EXECUTION_FAILED, record.startedAt, record.completedAt)
            }

            ExecutionStatus.ABORTED -> {
                abortedOutcome(record)
            }
        }

    private fun abortedOutcome(record: ExecutionRecord): ExecutionOutcome.Finished {
        // A5: the sweeper's `instance_lost` is NOT a conclusive abort — the worker may be alive.
        val errorJson = record.errorJson
        if (errorJson != null) {
            runCatching { mapper.readTree(errorJson).path("code").asText() }.fold(
                onSuccess = { code ->
                    if (code == PipelineErrorCodes.Execution.INSTANCE_LOST) {
                        return ExecutionOutcome.Finished(RunState.UNKNOWN, INSTANCE_LOST, record.startedAt, record.completedAt)
                    }
                    // Parseable but code-less: not the sweeper's marker; the abort event decides.
                },
                onFailure = { failure ->
                    // #336 D6: the row's OWN outcome evidence is unreadable, and an ABORTED
                    // row's error_json is exactly where the sweeper's `instance_lost` marker
                    // lives — the one reason that BLOCKS. Unreadable is not "not
                    // instance_lost", so the outcome falls to the state whose definition is
                    // "nobody can say whether its work happened" (scheduler.md §5), with a
                    // reason naming the unreadable evidence. A readable abort payload must
                    // never re-label it (`cancelled` from the event stream would silently
                    // un-block a run the evidence may have blocked).
                    LOG.warn(
                        "event=scheduler.outcome_evidence_unreadable execution={} evidence=error_json error={}",
                        record.executionId,
                        FailureShape.cause(failure),
                    )
                    return ExecutionOutcome.Finished(RunState.UNKNOWN, OUTCOME_UNREADABLE, record.startedAt, record.completedAt)
                },
            )
        }
        val reason =
            events
                .findByExecution(record.executionId)
                .lastOrNull { it.eventType == EXECUTION_ABORTED_EVENT }
                ?.let { readAbortReason(record.executionId, it.payloadJson) }
        return when (reason) {
            ABORT_CANCELLED -> ExecutionOutcome.Finished(RunState.CANCELLED, ABORT_CANCELLED, record.startedAt, record.completedAt)
            else -> ExecutionOutcome.Finished(RunState.ABORTED, reason?.takeIf { it.isNotBlank() }, record.startedAt, record.completedAt)
        }
    }

    /**
     * The abort event's `reason`, with an unreadable payload reported (#336 D6): the state stays
     * `aborted` — the execution record itself proves the run ended by abort, and `unknown` would
     * block a schedule on an outcome that IS known — while the lost reason gets one WARN.
     */
    private fun readAbortReason(
        executionId: UUID,
        payloadJson: String,
    ): String? =
        runCatching { mapper.readTree(payloadJson).path("reason").asText() }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                LOG.warn(
                    "event=scheduler.outcome_evidence_unreadable execution={} evidence=abort_payload error={}",
                    executionId,
                    FailureShape.cause(failure),
                )
                null
            },
        )

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

    /** Binds the schedule's stored parameters against the pipeline's declared parameters (throws on a mismatch). */
    private fun bind(
        pipeline: Pipeline,
        parameters: Map<String, JsonNode>,
    ) {
        ParameterBinder(
            pipeline.parameters,
            pipeline.calculatorOutputs(),
            pipeline.calculatorOutputGroups(),
            pipeline.scriptOutputKeys(),
        ).bindOrThrow(parameters)
    }

    private fun parametersOf(parameters: JsonNode): Map<String, JsonNode> =
        if (parameters.isObject) parameters.properties().associate { it.key to it.value } else emptyMap()

    /**
     * The payload's `parameter_bindings` entries, ENVELOPE-checked (the payload schema's job —
     * the resolver owns only the binding semantics): each value must be an object holding
     * `source` and its one payload key (`name` or `value`) and nothing else — a `reference`,
     * `reference_at` or `reference_timezone` key, past or future wire shape, is an unknown field
     * here (§5.1: the client cannot replace the schedule's time by embedding matching fields).
     * Empty when the payload carries no bindings.
     */
    @Suppress("ThrowsCount")
    private fun parseBindings(payload: JsonNode): Map<String, JsonNode> {
        if (!payload.has(BINDINGS_FIELD)) return emptyMap()
        val bindings = payload.path(BINDINGS_FIELD)
        if (!bindings.isObject) {
            throw payloadInvalid(BINDINGS_NOT_AN_OBJECT, "The payload's `parameter_bindings` must be a JSON object of bindings.")
        }
        return bindings.properties().associate { (name, node) ->
            if (!node.isObject) throw payloadInvalid(BINDINGS_NOT_AN_OBJECT, "The binding for '$name' must be a JSON object.")
            val keys = node.fieldNames().asSequence().toSet()
            if (!keys.contains(PipelineInputResolver.BINDING_SOURCE)) {
                throw payloadInvalid(BINDINGS_SOURCE_MISSING, "The binding for '$name' names no `source`.")
            }
            val unknown =
                keys - setOf(PipelineInputResolver.BINDING_SOURCE, PipelineInputResolver.BINDING_NAME, PipelineInputResolver.BINDING_VALUE)
            if (unknown.isNotEmpty()) {
                throw payloadInvalid(
                    "unknown_field",
                    "Unknown field(s) in the '$name' binding: ${unknown.joinToString()}. " +
                        "A binding carries `source` and its one payload key.",
                )
            }
            name to node
        }
    }

    /** The version's declared parameters by name, as their wire type spellings. */
    private fun declaredTypes(pipeline: Pipeline): Map<String, String> = pipeline.parameters.mapValues { it.value.type.wire }

    /** The resolver's refusal as the catalogued save-time code a surface renders (§13.19). */
    private fun bindingRefusal(refusal: PipelineInputResolver.Refusal): ScheduleException =
        when (refusal) {
            is PipelineInputResolver.Refusal.Conflict -> {
                ScheduleException(
                    ScheduleErrorCodes.BINDING_CONFLICT,
                    refusal.message,
                    mapOf("parameter" to refusal.parameter),
                )
            }

            is PipelineInputResolver.Refusal.Invalid -> {
                ScheduleException(
                    ScheduleErrorCodes.BINDING_INVALID,
                    refusal.message,
                    mapOf("reason" to refusal.reason.wire, "parameter" to refusal.parameter),
                )
            }
        }

    /**
     * The map the save binds: the literals, each literal binding's value, and a placeholder DATE
     * per keyword binding (today, UTC) so a required bound parameter passes the binder — the
     * placeholder is never persisted; each run resolves its own.
     */
    private fun saveParameters(
        literals: Map<String, JsonNode>,
        bindings: Map<String, JsonNode>,
    ): Map<String, JsonNode> {
        if (bindings.isEmpty()) return literals
        val placeholder = TextNode(LocalDate.now(ZoneOffset.UTC).toString())
        val out = LinkedHashMap<String, JsonNode>(literals)
        bindings.forEach { (name, node) ->
            out[name] =
                if (node.path(PipelineInputResolver.BINDING_SOURCE).asText() == PipelineInputResolver.SOURCE_KEYWORD) {
                    placeholder
                } else {
                    requireNotNull(node.get(PipelineInputResolver.BINDING_VALUE))
                }
        }
        return out
    }

    /** The run's frozen reference, or null when the frozen timezone is one this instance cannot name. */
    private fun referenceOf(admission: Admission): ExecutionReference? =
        try {
            ExecutionReference(admission.referenceAt, ZoneId.of(admission.referenceTimezone))
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }

    private fun nodeOf(values: Map<String, JsonNode>): JsonNode {
        val node = mapper.createObjectNode()
        values.forEach { (name, value) -> node.replace(name, value) }
        return node
    }

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

    companion object {
        /** The payload's pipeline name and its exact version (schema 2). */
        data class Target(
            val pipeline: String,
            val version: Int,
        )

        /** [target]'s pipeline name alone. */
        fun targetName(payload: JsonNode): String = target(payload).pipeline

        /** The shared save-time payload grammar (schema 2), also used before an authority check. */
        @Suppress("ThrowsCount") // each payload grammar refusal retains its established reason code
        fun target(payload: JsonNode): Target {
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
            return Target(name, exactVersion(payload.path("version")))
        }

        /** D.1 — the exact version: a positive JSON integer, nothing else (`current` and `latest` are refused by name). */
        @Suppress("ThrowsCount") // each refusal keeps its own `details.reason`
        private fun exactVersion(node: JsonNode): Int {
            if (node.isMissingNode || node.isNull) {
                throw payloadInvalid(
                    VERSION_MISSING,
                    "The payload names no version; give the exact pipeline version to run, like `\"version\": 3`.",
                )
            }
            if (node.isTextual) {
                throw when (node.asText()) {
                    VERSION_CURRENT -> {
                        payloadInvalid(
                            VERSION_CURRENT_REFUSED,
                            "`current` is no longer a version selector: a schedule runs one exact pipeline version. Give its number.",
                        )
                    }

                    "latest" -> {
                        payloadInvalid(VERSION_LATEST_REFUSED, "`latest` is not a version selector; give the exact version number.")
                    }

                    else -> {
                        payloadInvalid(VERSION_INVALID, "'${node.asText().take(MAX_ECHO)}' is not a version number.")
                    }
                }
            }
            if (!node.isIntegralNumber || !node.canConvertToInt() || node.asInt() < 1) {
                throw payloadInvalid(VERSION_INVALID, "The version must be a whole number of at least 1.")
            }
            return node.asInt()
        }

        private fun schemaUnsupported(stored: Int) =
            ScheduleException(
                ScheduleErrorCodes.PAYLOAD_SCHEMA_UNSUPPORTED,
                "This schedule's payload is stored in schema $stored; the pipeline executor accepts schema $PAYLOAD_SCHEMA_VERSION " +
                    "(an exact version) only. Edit the schedule and pick the version to run.",
                mapOf("stored" to stored, "supported" to PAYLOAD_SCHEMA_VERSION),
            )

        private fun payloadInvalid(
            reason: String,
            message: String,
        ) = ScheduleException(ScheduleErrorCodes.PAYLOAD_INVALID, message, mapOf("reason" to reason))

        private val LOG = LoggerFactory.getLogger(PipelineJobExecutor::class.java)

        const val EXECUTOR_ID = "pipeline"

        /** #786 D.1 — the exact pin. Schema 1 (`"version": "current"`) is read-checked and refused (D.2). */
        const val PAYLOAD_SCHEMA_VERSION = 2

        /** The payload's optional additive bindings key (design revision §5.2, slice 3). */
        const val BINDINGS_FIELD = "parameter_bindings"

        /** The prepared snapshot's key for the literal map that actually executed. */
        const val RESOLVED_PARAMETERS = "resolved_parameters"

        private const val BINDINGS_NOT_AN_OBJECT = "bindings_not_an_object"
        private const val BINDINGS_SOURCE_MISSING = "bindings_source_missing"

        /** `schedules.target_ref` for a pipeline (B15). */
        const val TARGET_PREFIX = "pipeline:"

        /** Schema 1's pointer-following selector — refused in schema 2 by name ([VERSION_CURRENT_REFUSED]). */
        const val VERSION_CURRENT = "current"

        /** `details.reason` values of the version refusals (D.1). */
        const val VERSION_CURRENT_REFUSED = "version_current_refused"
        const val VERSION_MISSING = "version_missing"
        const val VERSION_INVALID = "version_invalid"

        /** Schema version 2's fields — the binding key is OPTIONAL and ADDITIVE (see the class KDoc). */
        private val PAYLOAD_FIELDS = setOf("pipeline", "version", BINDINGS_FIELD)

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
        const val SNAPSHOT_UNRESOLVED = "snapshot_unresolved"

        /** #786 D.2 — the run's payload is stored in a schema this executor no longer runs (schema 1). Blocks. */
        const val PAYLOAD_SCHEMA_UNSUPPORTED = "payload_schema_unsupported"

        /** #786 D.1 — the pinned version is not one this deployment runs (discarded; a draft where authoring is off). Blocks. */
        const val VERSION_NOT_ELIGIBLE = "version_not_eligible"

        /** The `start_refused` detail when the launch threw something with no catalogued code (#253). */
        const val LAUNCH_REFUSED_WITHOUT_CODE = "launch_refused"
        const val EXECUTION_FAILED = "execution_failed"
        const val INSTANCE_LOST = "instance_lost"

        /**
         * #336 D6 — the row's outcome evidence was unreadable, so nobody can say whether the
         * work happened (an ABORTED row's `error_json` is where the sweeper's `instance_lost`
         * marker lives). State `unknown`; blocks, per scheduler.md §5.2. Catalogued in
         * scheduler.md §5's `unknown` reasons; the trail's WARN names the evidence kind.
         */
        const val OUTCOME_UNREADABLE = "outcome_unreadable"
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
