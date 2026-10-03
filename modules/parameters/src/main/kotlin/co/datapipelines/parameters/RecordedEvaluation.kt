package co.datapipelines.parameters

import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The evaluator's side of one attempt's durable record (#376) — what [ParameterEvaluator] tells its
 * [ParameterEvaluationRecorder], kept in ONE place so the call sites inside the cascade stay one line each.
 *
 * Nothing is written until [open] (the evaluator calls it once the attempt is admitted). If the recorder answers
 * "unrecorded" — the recorder is [ParameterEvaluationRecorder.NONE], or its START write failed — every later call is a
 * no-op and [queued] hands back null, so the evaluator wraps no task: an unrecorded evaluation costs nothing.
 *
 * The stamps come from the evaluator's [clock]: `queued_at` on the evaluating coroutine just before the bulkhead's
 * admission, `started_at`/`ended_at` ON THE WORKER THREAD at the start and the end of the task's run (in memory — the
 * worker writes nothing to the metadata database), so two statements that ran together carry overlapping ranges.
 */
internal class RecordedEvaluation(
    private val recorder: ParameterEvaluationRecorder,
    private val attempt: EvaluationAttempt,
    private val set: ParameterSetVersion,
    private val clock: Clock,
) {
    private val key = EvaluationKey(attempt.evaluationId, set.record.workspaceId, set.record.id)

    @Volatile
    private var recorded = false

    /** Statements asked for admission and not yet ended — closed by the terminal write if the evaluation ends first. */
    private val inFlight = ConcurrentHashMap<UUID, QueryAttempt>()

    private val outcomes = ConcurrentHashMap<String, ParameterOutcome>()

    /** The START write — the attempt was admitted. */
    fun open() {
        recorded = recorder.started(EvaluationStarted(key, attempt, set.detail.version, clock.instant()))
    }

    /** A statement asks the bulkhead for admission: its row, or null when this evaluation is unrecorded. */
    fun queued(
        parameter: String,
        datasource: String,
        template: TemplateRef,
    ): QueryAttempt? {
        if (!recorded) return null
        val query = QueryAttempt(UUID.randomUUID(), clock)
        inFlight[query.id] = query
        recorder.queryQueued(QueryQueued(key, query.id, parameter, datasource, template, clock.instant()))
        return query
    }

    /** The bulkhead answered: a full queue is a `REFUSED` attempt, a completed run its outcome by variant. */
    fun ended(
        query: QueryAttempt,
        admission: SelectorAdmission,
    ) {
        inFlight.remove(query.id)
        recorder.queryEnded(query.ended(key, admission))
    }

    /** The task itself threw (a defect, never an author's problem): `FAILED`, no code — the evaluation fails with it. */
    fun defect(query: QueryAttempt) {
        inFlight.remove(query.id)
        recorder.queryEnded(query.close(key, QueryAttemptOutcome.FAILED, ended = true))
    }

    /** [parameter] finished with [state] — its `outcomes_json` entry. */
    fun parameterEnded(
        parameter: ParameterDefinition,
        state: ParameterState,
    ) {
        if (recorded) outcomes[parameter.name] = outcomeOf(parameter.name, state)
    }

    /**
     * The terminal write, once, from the evaluator's `finally`: [outcome] and [code] as the evaluation decided them,
     * [response] when it completed. A statement still in flight was abandoned by the evaluation's end — `TIMEOUT` when
     * the deadline ended it, `ABORTED` otherwise (the caller stopped it, or a sibling's defect tore the cascade down).
     */
    fun end(
        outcome: EvaluationOutcome,
        code: String?,
        response: EvaluateResponse?,
    ) {
        if (!recorded) return
        val abandonedAs = if (outcome == EvaluationOutcome.TIMEOUT) QueryAttemptOutcome.TIMEOUT else QueryAttemptOutcome.ABORTED
        recorder.ended(
            EvaluationEnded(
                key = key,
                status = ParameterEvaluationStatus.of(outcome),
                outcomeCode = code,
                valid = response?.valid,
                outcomes = set.body.parameters.mapNotNull { outcomes[it.name] },
                abandoned = inFlight.values.map { it.close(key, abandonedAs, ended = false) },
                finishedAt = clock.instant(),
            ),
        )
    }

    private fun outcomeOf(
        name: String,
        state: ParameterState,
    ): ParameterOutcome {
        val error = state.errors.firstOrNull()
        return when {
            error != null -> {
                ParameterOutcome(
                    name,
                    ParameterOutcome.ERROR,
                    error.code,
                    (error.details["reason"] as? String)?.safeEcho(MAX_DETAIL_CHARS),
                )
            }

            state.reset -> {
                ParameterOutcome(name, ParameterOutcome.RESET, null, null)
            }

            else -> {
                ParameterOutcome(name, ParameterOutcome.RESOLVED, null, null)
            }
        }
    }

    private companion object {
        const val MAX_DETAIL_CHARS = 200
    }
}

/** One statement attempt's worker-side stamps — set on the worker thread, read by the evaluating coroutine. */
internal class QueryAttempt(
    val id: UUID,
    private val clock: Clock,
) {
    private val startedAt = AtomicReference<Instant?>()
    private val endedAt = AtomicReference<Instant?>()

    /** [task], stamping when the worker begins it and when it returns — nothing else changes. */
    fun stamping(task: SelectorTask): SelectorTask =
        object : SelectorTask {
            override fun run(): SelectorRun {
                startedAt.set(clock.instant())
                try {
                    return task.run()
                } finally {
                    endedAt.set(clock.instant())
                }
            }

            override fun abandon() = task.abandon()
        }

    fun ended(
        key: EvaluationKey,
        admission: SelectorAdmission,
    ): QueryEnded =
        when (admission) {
            SelectorAdmission.Saturated -> {
                QueryEnded(
                    key,
                    id,
                    QueryAttemptOutcome.REFUSED,
                    ParameterErrorCodes.EVALUATE_SELECTORS_SATURATED,
                    rowCount = null,
                    startedAt = null,
                    endedAt = clock.instant(),
                )
            }

            is SelectorAdmission.Completed -> {
                val (outcome, code) = QueryOutcomes.of(admission.run)
                val rows = (admission.run as? SelectorRun.Rows)?.rows?.size
                QueryEnded(key, id, outcome, code, rows, startedAt.get(), endedAt.get())
            }
        }

    /** Closed without a run to judge: a defect ([ended] = the worker returned), or an abandonment (it may still run). */
    fun close(
        key: EvaluationKey,
        outcome: QueryAttemptOutcome,
        ended: Boolean,
    ): QueryEnded =
        QueryEnded(key, id, outcome, code = null, rowCount = null, startedAt = startedAt.get(), endedAt = endedAt.get().takeIf { ended })
}

/**
 * The per-variant outcome of a run that came back (spec §2.2; the handback's table). A statement that never reached the
 * database is `REFUSED` with its catalogued code: a render failure (the template engine's two codes), a datasource the
 * workspace cannot see or reach, a bind the context lacks, too many binds, the read-only gate or a placeholder text the
 * binder refused (`SelectorRunner`'s two `details.reason` words). Everything else the run reports — a driver failure,
 * a statement timeout, a forbidden table — ran, and is `FAILED` with the owning subsystem's code. Rows are `EXECUTED`.
 */
internal object QueryOutcomes {
    private val REFUSAL_CODES =
        setOf(
            PipelineErrorCodes.Node.TEMPLATE_NOT_FOUND,
            PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED,
            PipelineErrorCodes.Node.SQL_PARAMETER_MISSING,
            DatasourceErrorCodes.NOT_FOUND,
            ParameterErrorCodes.EVALUATE_TOO_MANY_BINDS,
        )

    /** `SelectorRunner`'s `details.reason` words for a statement refused before execution (`READ_ONLY_GATE`, the binder's). */
    private val REFUSAL_REASONS = setOf("read_only_gate", "placeholders")

    fun of(run: SelectorRun): Pair<QueryAttemptOutcome, String?> =
        when (run) {
            is SelectorRun.Rows -> {
                QueryAttemptOutcome.EXECUTED to null
            }

            is SelectorRun.Unreachable -> {
                QueryAttemptOutcome.REFUSED to PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
            }

            is SelectorRun.Failed -> {
                if (run.code in REFUSAL_CODES || run.details["reason"] in REFUSAL_REASONS) {
                    QueryAttemptOutcome.REFUSED to run.code
                } else {
                    QueryAttemptOutcome.FAILED to run.code
                }
            }
        }
}
