package co.datapipelines.web.schedules

import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleRepository
import co.datapipelines.scheduler.ScheduleRun
import co.datapipelines.scheduler.ScheduleRunRepository
import co.datapipelines.web.endpoints.StartupConversionConflict
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The in-place conversion of existing schedules (#786 §D.5; owner ruling 2026-11-08: one server, convert in place —
 * ruling 2 changes the pin, never the binding semantics). A pipeline schedule saved before #786 stores payload schema
 * 1, `{"pipeline": "<name>", "version": "current"}`, which followed the pipeline's CURRENT pointer at every run. This
 * step turns each live one into schema 2 — the exact pin of the version it runs today — and does the same, from ITS
 * OWN frozen payload, for each of its queued runs (#786 round 7, R1 contract).
 *
 * - **Targets.** A schedule's target is the pipeline its `target_ref` names. A QUEUED run's target is the pipeline ITS
 *   payload copy names — frozen when the run was materialised, so after an ordinary edit of the schedule from pipeline
 *   A to B a run queued before the edit still names A. The schedule's resolved version is never reused for a run: each
 *   target resolves its own pipeline's CURRENT. Only runs in state `queued` are converted
 *   ([ScheduleRunRepository.listQueuedAtSchema]): a `starting` run is never launched again ([co.datapipelines.scheduler.ScheduledRunWorker]
 *   recovers it from its execution reference) and every other state is terminal, so their payload copies are history.
 * - **E.0 precondition, before any write** ([precondition]): every target being converted — each live schema-1
 *   schedule's pipeline AND each of its schema-1 queued runs' pipelines — must exist, have a version and have a CURRENT
 *   pointer. Each failure is one structured ERROR line (`kind=schedule` with the schedule's `id`, or `kind=run` with the
 *   run's `id` and its `schedule`; `name`, `pipeline`, `reason=missing_pipeline|no_versions|no_current`) plus a summary
 *   naming the operator's fix; the startup runner refuses the server after every step's precondition was read, so
 *   nothing is written. A CURRENT the posture makes ineligible (a DRAFT pointer in a hardened deployment) is NOT a
 *   failure: it converts to that exact pin and keeps refusing at run exactly as before (`version_not_eligible`).
 * - **Inside the runner's ONE transaction** (#895: every API, schedule and queued run of one startup converts in one
 *   transaction; a failure anywhere rolls every family back). This family owns no transaction and takes no lock:
 *   [plan] reports every workspace it would write in — each candidate schedule's and each of its queued runs' — and
 *   the runner acquires the workspace guard (G0 class 2, the one every pin writer and the version discard take) ONCE,
 *   sorted, over the union of every family's workspaces, then the endpoint registry key. [Attempt.convert] then
 *   re-reads the candidates, their queued runs and every target's CURRENT under those locks and converts each schedule
 *   in workspace/name order: ONLY the payload's `version` selector is replaced (`"current"` → the integer) and
 *   `payload_schema_version` set to 2 — the schedule's under its revision CAS, each run's while it is still queued at
 *   schema 1. Parameters, bindings, the frozen reference, every other payload key and every other column are
 *   untouched. A CAS conflict (a concurrent pin-unchanged edit), or a candidate or run that appeared in a workspace the
 *   union does not cover, is a [StartupConversionConflict]: the runner rolls the WHOLE transaction back — the APIs
 *   included — and replays every family from fresh rows; it never overwrites. A target that lost its CURRENT between
 *   the precondition and the guarded re-read refuses, and so does any write that fails: the one transaction rolls back
 *   every API, schedule and queued run this startup had converted, and the startup fails with the store exactly as it
 *   found it.
 * - **Commit-time effects.** The converted lines and the summary are [ScheduleConversionOutcome.publish], which the
 *   runner calls once, after the joint commit — never for an attempt that rolled back.
 * - **Idempotent**: the selector is schema 1, so a rerun selects nothing, repins nothing and logs the zero summary.
 *
 * A deleted schedule is not selected, nor are its runs: the worker skips them (`schedule_deleted`) before preparing,
 * and a startup must not refuse over rows nobody can use.
 */
@Suppress("TooManyFunctions")
class ScheduleVersionConversion(
    private val schedules: ScheduleRepository,
    private val runs: ScheduleRunRepository,
    private val pipelines: PipelineRepository,
) {
    private val log = LoggerFactory.getLogger(ScheduleVersionConversion::class.java)

    /**
     * E.0 — every target that cannot be pinned, in workspace/name order: each live schema-1 schedule, then its schema-1
     * queued runs (each with its OWN pipeline). Empty = convertible.
     */
    fun precondition(): List<ScheduleConversionRefusal> =
        candidates().flatMap { schedule ->
            listOfNotNull(scheduleRefusal(schedule)) +
                runs.listQueuedAtSchema(schedule.id, FROM_SCHEMA).mapNotNull { runRefusal(schedule, it) }
        }

    /** [precondition], each refusal logged as its structured ERROR line plus the summary; writes nothing. */
    fun logPrecondition(): Int {
        val refusals = precondition()
        logRefusals(refusals)
        return refusals.size
    }

    /** One attempt's plan, read inside the runner's transaction before any lock: every workspace it would write in. */
    fun plan(): Attempt = Attempt(workspacesOf(candidates()))

    /** This family's part of one attempt of the runner's transaction. */
    inner class Attempt internal constructor(
        /** Every candidate schedule's workspace and each of its queued runs' — this family's share of the union. */
        val workspaces: Set<UUID>,
    ) {
        /**
         * Re-reads under the runner's guard and converts every candidate. Throws [StartupConversionConflict] when a CAS
         * lost or a candidate or run sits in a workspace outside [guarded] (the runner rolls back and re-plans), and
         * [ScheduleConversionRefusedException] (after logging it) when a target lost its CURRENT since the precondition;
         * any other failure propagates as thrown. Either way the runner's transaction rolls back every write.
         */
        fun convert(guarded: Set<UUID>): ScheduleConversionOutcome {
            val locked = candidates()
            if (!guarded.containsAll(workspacesOf(locked))) {
                throw StartupConversionConflict(
                    "a schedule or queued run appeared outside the guarded workspaces; re-planning from fresh rows",
                )
            }
            val converted = locked.map { convertLocked(it) }
            return ScheduleConversionOutcome(ScheduleConversionReport(converted), this@ScheduleVersionConversion::publish)
        }
    }

    /** The commit-time effects of [report]: one line per converted schedule and queued run, then the summary. */
    private fun publish(report: ScheduleConversionReport) {
        val converted = report.converted
        converted.forEach(::logConverted)
        log.info(
            "event=scheduler.conversion.summary converted={} released={} draft={} queued_runs={}",
            converted.size,
            converted.count { it.status == PipelineVersionStatus.RELEASED },
            converted.count { it.status != PipelineVersionStatus.RELEASED },
            converted.sumOf { it.queuedRuns },
        )
    }

    /** Converts [schedule] and its queued runs inside the runner's transaction, under the guard the runner holds. */
    private fun convertLocked(schedule: Schedule): ConvertedSchedule {
        val target = pinOf(schedule.workspaceId, scheduleTarget(schedule)) { scheduleRefusalOf(schedule, it) }
        if (!schedules.convertPayload(schedule.id, schedule.revision, FROM_SCHEMA, pinned(schedule.payload, target.version), TO_SCHEMA)) {
            throw StartupConversionConflict(
                "schedule ${schedule.id} lost its revision CAS to a concurrent edit; re-planning from fresh rows",
            )
        }
        // Each run from ITS OWN payload copy and ITS OWN pipeline's CURRENT — never the schedule's version. A refusal
        // here throws after this and every earlier schedule's write: the whole transaction rolls back.
        val converted =
            queuedOf(schedule).mapNotNull { run ->
                val pin = pinOf(run.workspaceId, runTarget(run)) { runRefusalOf(schedule, run, it) }
                ConvertedRun(run.id, pin.pipeline, pin.version, pin.status).takeIf {
                    runs.convertQueuedPayload(run.id, FROM_SCHEMA, pinned(run.payload, pin.version), TO_SCHEMA)
                }
            }
        return ConvertedSchedule(schedule.id, schedule.name, target.pipeline, target.version, target.status, converted)
    }

    private fun workspacesOf(candidates: List<Schedule>): Set<UUID> =
        candidates.flatMapTo(sortedSetOf()) { schedule -> listOf(schedule.workspaceId) + queuedOf(schedule).map { it.workspaceId } }

    /** The exact pin for [pipeline] in [workspaceId] right now, or a refusal (built by [refusalOf]) thrown. */
    private fun pinOf(
        workspaceId: UUID,
        pipeline: String,
        refusalOf: (PipelineState) -> ScheduleConversionRefusal?,
    ): Pin {
        val state = state(workspaceId, pipeline)
        refusalOf(state)?.let { refuse(listOf(it)) }
        val version = requireNotNull(state.currentVersion) { "refusal answered null, so CURRENT exists" }
        val status = requireNotNull(state.currentStatus) { "refusal answered null, so CURRENT's row exists" }
        return Pin(pipeline, version, status)
    }

    private fun candidates(): List<Schedule> = schedules.listLiveAtSchema(PipelineJobExecutor.EXECUTOR_ID, FROM_SCHEMA)

    private fun queuedOf(schedule: Schedule): List<ScheduleRun> = runs.listQueuedAtSchema(schedule.id, FROM_SCHEMA)

    private fun scheduleRefusal(schedule: Schedule) = scheduleRefusalOf(schedule, state(schedule.workspaceId, scheduleTarget(schedule)))

    private fun runRefusal(
        schedule: Schedule,
        run: ScheduleRun,
    ) = runRefusalOf(schedule, run, state(run.workspaceId, runTarget(run)))

    private fun scheduleRefusalOf(
        schedule: Schedule,
        state: PipelineState,
    ) = reasonOf(state)?.let { ScheduleConversionRefusal(schedule.id, schedule.name, state.pipeline, it) }

    private fun runRefusalOf(
        schedule: Schedule,
        run: ScheduleRun,
        state: PipelineState,
    ) = reasonOf(state)?.let { ScheduleConversionRefusal(schedule.id, schedule.name, state.pipeline, it, runId = run.id) }

    /** What [pipeline] holds in [workspaceId] right now: whether it exists, its CURRENT and that row's status. */
    private fun state(
        workspaceId: UUID,
        pipeline: String,
    ): PipelineState {
        val record =
            pipeline.takeIf { it.isNotEmpty() }?.let { pipelines.findByNameAnyStatus(workspaceId, it) }
                ?: return PipelineState(pipeline, exists = false)
        val current = pipelines.findCurrentVersionDetail(workspaceId, record.id)
        return PipelineState(
            pipeline = pipeline,
            exists = true,
            hasVersions = { pipelines.listVersions(workspaceId, record.id).isNotEmpty() },
            currentVersion = current?.version,
            currentStatus = current?.status,
        )
    }

    private fun reasonOf(state: PipelineState): ScheduleConversionRefusal.Reason? =
        when {
            !state.exists -> ScheduleConversionRefusal.Reason.MISSING_PIPELINE

            state.currentVersion != null &&
                state.currentStatus != null &&
                state.currentStatus != PipelineVersionStatus.DISCARDED -> null

            // A pointer at a DISCARDED row runs nothing today; pinning it would invent a version.
            !state.hasVersions() -> ScheduleConversionRefusal.Reason.NO_VERSIONS

            else -> ScheduleConversionRefusal.Reason.NO_CURRENT
        }

    private fun refuse(refusals: List<ScheduleConversionRefusal>): Nothing {
        logRefusals(refusals)
        throw ScheduleConversionRefusedException(refusals)
    }

    private fun logRefusals(refusals: List<ScheduleConversionRefusal>) {
        if (refusals.isEmpty()) return
        refusals.forEach {
            when (val runId = it.runId) {
                null -> {
                    log.error(
                        "event=scheduler.conversion.refused kind=schedule id={} name={} pipeline={} reason={}",
                        it.scheduleId,
                        it.name,
                        it.pipeline,
                        it.reason.wire,
                    )
                }

                else -> {
                    log.error(
                        "event=scheduler.conversion.refused kind=run id={} schedule={} name={} pipeline={} reason={}",
                        runId,
                        it.scheduleId,
                        it.name,
                        it.pipeline,
                        it.reason.wire,
                    )
                }
            }
        }
        log.error(
            "event=scheduler.conversion.refused_summary refused={} fix=\"{}\"",
            refusals.size,
            ScheduleConversionRefusedException.OPERATOR_FIX,
        )
    }

    private fun logConverted(it: ConvertedSchedule) {
        log.info(
            "event=scheduler.conversion.converted id={} name={} pipeline={} pipeline_version={} status={} queued_runs={}",
            it.scheduleId,
            it.name,
            it.pipeline,
            it.pipelineVersion,
            it.status,
            it.queuedRuns,
        )
        it.runs.forEach { run ->
            log.info(
                "event=scheduler.conversion.converted_run id={} schedule={} pipeline={} pipeline_version={} status={}",
                run.runId,
                it.scheduleId,
                run.pipeline,
                run.pipelineVersion,
                run.status,
            )
        }
    }

    private data class PipelineState(
        val pipeline: String,
        val exists: Boolean,
        val hasVersions: () -> Boolean = { false },
        val currentVersion: Int? = null,
        val currentStatus: PipelineVersionStatus? = null,
    )

    private data class Pin(
        val pipeline: String,
        val version: Int,
        val status: PipelineVersionStatus,
    )

    companion object {
        /** The schema this step converts from (the pointer-following payload) and to (the exact pin). */
        const val FROM_SCHEMA = 1
        const val TO_SCHEMA = PipelineJobExecutor.PAYLOAD_SCHEMA_VERSION

        /** A schedule's target: the pipeline its `target_ref` names. */
        private fun scheduleTarget(schedule: Schedule): String = schedule.targetRef.removePrefix(PipelineJobExecutor.TARGET_PREFIX)

        /** A queued run's target: the pipeline ITS payload copy names (empty when the copy names none — a refusal). */
        private fun runTarget(run: ScheduleRun): String =
            run.payload
                .path("pipeline")
                .takeIf { it.isTextual }
                ?.asText()
                ?.trim()
                .orEmpty()

        /**
         * The conversion's ONE payload change: a copy of [payload] whose `version` member is [version]. Every other
         * member — `pipeline`, `parameter_bindings`, any future additive key — is carried over untouched, in order.
         */
        fun pinned(
            payload: JsonNode,
            version: Int,
        ): ObjectNode {
            require(payload.isObject) { "a schema-1 pipeline payload is a JSON object" }
            return (payload.deepCopy() as ObjectNode).put("version", version)
        }
    }
}

/**
 * One E.0 failure: a live schema-1 schedule, or one of its schema-1 queued runs ([runId] set), whose OWN pipeline
 * cannot be pinned exactly. [name] is the schedule's name in both cases; [pipeline] is the target that failed.
 */
data class ScheduleConversionRefusal(
    val scheduleId: UUID,
    val name: String,
    val pipeline: String,
    val reason: Reason,
    val runId: UUID? = null,
) {
    /** The `reason=` words of the structured ERROR line (brief E.0) — the API step's words, one vocabulary. */
    enum class Reason(
        val wire: String,
    ) {
        MISSING_PIPELINE("missing_pipeline"),
        NO_VERSIONS("no_versions"),
        NO_CURRENT("no_current"),
    }
}

/** One converted schedule — a line of the conversion report. */
data class ConvertedSchedule(
    val scheduleId: UUID,
    val name: String,
    val pipeline: String,
    val pipelineVersion: Int,
    val status: PipelineVersionStatus,
    /** Its QUEUED runs whose payload copy was pinned in the same transaction, each to its OWN pipeline's version. */
    val runs: List<ConvertedRun>,
) {
    val queuedRuns: Int get() = runs.size
}

/** One converted queued run: the pipeline its own payload copy names and the exact version it was pinned to. */
data class ConvertedRun(
    val runId: UUID,
    val pipeline: String,
    val pipelineVersion: Int,
    val status: PipelineVersionStatus,
)

/** What one run converted, in workspace/name order; empty on a rerun. */
data class ScheduleConversionReport(
    val converted: List<ConvertedSchedule>,
)

/** One attempt's [report] and its commit-time effects, which only the runner [publish]es. */
class ScheduleConversionOutcome internal constructor(
    val report: ScheduleConversionReport,
    private val effects: (ScheduleConversionReport) -> Unit,
) {
    /** Logs the converted lines and the summary — call only after the joint COMMIT. */
    fun publish() = effects(report)
}

/** E.0 refused: the server must not start. [refusals] names every row; nothing was written for them. */
class ScheduleConversionRefusedException(
    val refusals: List<ScheduleConversionRefusal>,
) : IllegalStateException(
        "The schedule version conversion refused to start: ${refusals.size} schedule(s) or queued run(s) target a pipeline " +
            "that has no current version to pin (${refusals.joinToString { describe(it) }}). $OPERATOR_FIX",
    ) {
    companion object {
        private fun describe(it: ScheduleConversionRefusal): String =
            (if (it.runId == null) it.name else "${it.name} queued run ${it.runId}") + " -> ${it.pipeline}: ${it.reason.wire}"

        /** The operator's fix, named in the summary line and in docs/deployment.md §8. */
        const val OPERATOR_FIX =
            "On the previous version, release the pipeline or delete the schedule, then upgrade again."
    }
}
