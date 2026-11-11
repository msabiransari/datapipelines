package co.datapipelines.web.schedules

import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleRepository
import co.datapipelines.scheduler.ScheduleRunRepository
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/**
 * The in-place conversion of existing schedules (#786 §D.5; owner ruling 2026-11-08: one server, convert in place —
 * ruling 2 changes the pin, never the binding semantics). A pipeline schedule saved before #786 stores payload schema
 * 1, `{"pipeline": "<name>", "version": "current"}`, which followed the pipeline's CURRENT pointer at every run. This
 * step turns each live one into schema 2 — the exact pin of the version it runs today.
 *
 * - **E.0 precondition, before any write** ([precondition]): every live schema-1 schedule's pipeline must exist, have a
 *   version and have a CURRENT pointer. Each failure is one structured ERROR line (`kind=schedule`, `id`, `name`,
 *   `pipeline`, `reason=missing_pipeline|no_versions|no_current`) plus a summary naming the operator's fix; the startup
 *   runner refuses the server after every step's precondition was read, so nothing is written. A CURRENT the posture
 *   makes ineligible (a DRAFT pointer in a hardened deployment) is NOT a failure: it converts to that exact pin and
 *   keeps refusing at run exactly as before (`version_not_eligible`).
 * - **Per schedule, under the pinned pipeline's workspace guard** (G0 class 2 — the guard every pin writer and the
 *   version discard take): the schedule and the pipeline's CURRENT are re-read under the guard; ONLY the payload's
 *   `version` selector is replaced (`"current"` → the integer) and `payload_schema_version` set to 2 under the
 *   schedule's revision CAS. `parameter_bindings`, every other payload key, `parameters`, timezone, cron, missed-run
 *   policy and notification settings are untouched. A CAS conflict (a concurrent pin-unchanged edit) retries from the
 *   fresh row; it never overwrites. The schedule's QUEUED runs, whose payload copies are still schema 1, are pinned to
 *   the same version in the same transaction, so the upgrade does not block a schedule over a run that was waiting.
 * - **Idempotent**: the selector is schema 1, so a rerun selects nothing and repins nothing.
 *
 * A deleted schedule is not selected: it never runs again, and a startup must not refuse over a row nobody can use.
 */
@Suppress("TooManyFunctions")
class ScheduleVersionConversion(
    private val schedules: ScheduleRepository,
    private val runs: ScheduleRunRepository,
    private val pipelines: PipelineRepository,
    private val transactions: TransactionOperations,
    private val writeGuard: MetadataWriterGuard,
) {
    private val log = LoggerFactory.getLogger(ScheduleVersionConversion::class.java)

    /** E.0 — every live schema-1 schedule whose pipeline cannot be pinned, in workspace/name order; empty = convertible. */
    fun precondition(): List<ScheduleConversionRefusal> = candidates().mapNotNull { refusal(it, state(it)) }

    /** [precondition], each refusal logged as its structured ERROR line plus the summary; writes nothing. */
    fun logPrecondition(): Int {
        val refusals = precondition()
        logRefusals(refusals)
        return refusals.size
    }

    /**
     * E.0 then the conversion. Throws [ScheduleConversionRefusedException] (after logging) when E.0 fails — before any
     * write — or when a schedule's pipeline lost its CURRENT between the precondition and the guarded re-read (that
     * schedule's transaction rolls back; earlier ones stay converted, each exactly as a rerun would convert it).
     */
    fun run(): ScheduleConversionReport {
        precondition().takeIf { it.isNotEmpty() }?.let { refuse(it) }
        val converted = candidates().mapNotNull { convertOne(it.id) }
        val report = ScheduleConversionReport(converted)
        log.info(
            "event=scheduler.conversion.summary converted={} released={} draft={} queued_runs={}",
            converted.size,
            converted.count { it.status == PipelineVersionStatus.RELEASED },
            converted.count { it.status != PipelineVersionStatus.RELEASED },
            converted.sumOf { it.queuedRuns },
        )
        return report
    }

    private fun convertOne(scheduleId: UUID): ConvertedSchedule? {
        repeat(MAX_ATTEMPTS) {
            when (val attempt = attempt(scheduleId)) {
                Attempt.Conflict -> Unit
                Attempt.Skipped -> return null
                is Attempt.Done -> return attempt.converted.also(::logConverted)
            }
        }
        // Every attempt lost its CAS to a concurrent edit. At startup nothing else writes schedules; refuse loudly.
        error("schedule $scheduleId: the conversion lost its revision CAS $MAX_ATTEMPTS times; nothing was overwritten")
    }

    private fun attempt(scheduleId: UUID): Attempt =
        requireNotNull(
            transactions.execute {
                val first = schedules.findAny(scheduleId)?.takeIf(::isCandidate) ?: return@execute Attempt.Skipped
                writeGuard.workspaces(listOf(first.workspaceId), ScheduleErrorCodes.WRITE_BUSY)
                // Re-read UNDER the guard: what a concurrent writer committed while this transaction waited counts.
                val locked = schedules.findAny(scheduleId)?.takeIf(::isCandidate) ?: return@execute Attempt.Skipped
                val state = state(locked)
                refusal(locked, state)?.let { refuse(listOf(it)) }
                val version = requireNotNull(state.currentVersion) { "refusal() answered null, so CURRENT exists" }
                val status = requireNotNull(state.currentStatus) { "refusal() answered null, so CURRENT's row exists" }
                if (!schedules.convertPayload(locked.id, locked.revision, FROM_SCHEMA, pinned(locked.payload, version), TO_SCHEMA)) {
                    return@execute Attempt.Conflict
                }
                val queued =
                    runs.listQueuedAtSchema(locked.id, FROM_SCHEMA).count {
                        runs.convertQueuedPayload(it.id, FROM_SCHEMA, pinned(it.payload, version), TO_SCHEMA)
                    }
                Attempt.Done(ConvertedSchedule(locked.id, locked.name, state.pipeline, version, status, queued))
            },
        ) { "the conversion transaction returned no outcome" }

    private fun isCandidate(schedule: Schedule) =
        schedule.deletedAt == null &&
            schedule.executorId == PipelineJobExecutor.EXECUTOR_ID &&
            schedule.payloadSchemaVersion == FROM_SCHEMA

    private fun candidates(): List<Schedule> = schedules.listLiveAtSchema(PipelineJobExecutor.EXECUTOR_ID, FROM_SCHEMA)

    /** What the schedule's pipeline holds right now: the name it targets, its CURRENT and that row's status. */
    private fun state(schedule: Schedule): PipelineState {
        val name = schedule.targetRef.removePrefix(PipelineJobExecutor.TARGET_PREFIX)
        val record = pipelines.findByNameAnyStatus(schedule.workspaceId, name) ?: return PipelineState(name, exists = false)
        val current = pipelines.findCurrentVersionDetail(schedule.workspaceId, record.id)
        return PipelineState(
            pipeline = name,
            exists = true,
            hasVersions = { pipelines.listVersions(schedule.workspaceId, record.id).isNotEmpty() },
            currentVersion = current?.version,
            currentStatus = current?.status,
        )
    }

    private fun refusal(
        schedule: Schedule,
        state: PipelineState,
    ): ScheduleConversionRefusal? {
        val reason =
            when {
                !state.exists -> ScheduleConversionRefusal.Reason.MISSING_PIPELINE

                state.currentVersion != null &&
                    state.currentStatus != null &&
                    state.currentStatus != PipelineVersionStatus.DISCARDED -> return null

                // A pointer at a DISCARDED row runs nothing today; pinning it would invent a version.
                !state.hasVersions() -> ScheduleConversionRefusal.Reason.NO_VERSIONS

                else -> ScheduleConversionRefusal.Reason.NO_CURRENT
            }
        return ScheduleConversionRefusal(schedule.id, schedule.name, state.pipeline, reason)
    }

    private fun refuse(refusals: List<ScheduleConversionRefusal>): Nothing {
        logRefusals(refusals)
        throw ScheduleConversionRefusedException(refusals)
    }

    private fun logRefusals(refusals: List<ScheduleConversionRefusal>) {
        if (refusals.isEmpty()) return
        refusals.forEach {
            log.error(
                "event=scheduler.conversion.refused kind=schedule id={} name={} pipeline={} reason={}",
                it.scheduleId,
                it.name,
                it.pipeline,
                it.reason.wire,
            )
        }
        log.error(
            "event=scheduler.conversion.refused_summary refused={} fix=\"{}\"",
            refusals.size,
            ScheduleConversionRefusedException.OPERATOR_FIX,
        )
    }

    private fun logConverted(it: ConvertedSchedule) =
        log.info(
            "event=scheduler.conversion.converted id={} name={} pipeline={} pipeline_version={} status={} queued_runs={}",
            it.scheduleId,
            it.name,
            it.pipeline,
            it.pipelineVersion,
            it.status,
            it.queuedRuns,
        )

    private sealed interface Attempt {
        data object Skipped : Attempt

        data object Conflict : Attempt

        data class Done(
            val converted: ConvertedSchedule,
        ) : Attempt
    }

    private data class PipelineState(
        val pipeline: String,
        val exists: Boolean,
        val hasVersions: () -> Boolean = { false },
        val currentVersion: Int? = null,
        val currentStatus: PipelineVersionStatus? = null,
    )

    companion object {
        /** The schema this step converts from (the pointer-following payload) and to (the exact pin). */
        const val FROM_SCHEMA = 1
        const val TO_SCHEMA = PipelineJobExecutor.PAYLOAD_SCHEMA_VERSION

        private const val MAX_ATTEMPTS = 3

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

/** One E.0 failure: a live schema-1 schedule whose pipeline cannot be pinned exactly. */
data class ScheduleConversionRefusal(
    val scheduleId: UUID,
    val name: String,
    val pipeline: String,
    val reason: Reason,
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
    /** How many of its QUEUED runs had their payload copy pinned in the same transaction. */
    val queuedRuns: Int,
)

/** What one run converted, in workspace/name order; empty on a rerun. */
data class ScheduleConversionReport(
    val converted: List<ConvertedSchedule>,
)

/** E.0 refused: the server must not start. [refusals] names every row; nothing was written for them. */
class ScheduleConversionRefusedException(
    val refusals: List<ScheduleConversionRefusal>,
) : IllegalStateException(
        "The schedule version conversion refused to start: ${refusals.size} schedule(s) target a pipeline that has no " +
            "current version to pin (${refusals.joinToString { "${it.name} -> ${it.pipeline}: ${it.reason.wire}" }}). $OPERATOR_FIX",
    ) {
    companion object {
        /** The operator's fix, named in the summary line and in docs/deployment.md §8. */
        const val OPERATOR_FIX =
            "On the previous version, release the pipeline or delete the schedule, then upgrade again."
    }
}
