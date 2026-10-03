package co.datapipelines.parameters

import co.datapipelines.pipeline.TemplateRef
import java.time.Instant
import java.util.UUID

/**
 * The durable evaluation history's port (#376; the parameter-set workspace spec R2, §2, §4.3) — a CONSTRUCTOR
 * collaborator of [ParameterEvaluator], never part of the observer: every evaluation records, observed or not, page or
 * dashboard or REST or MCP. The evaluator calls it at four points of one attempt, in this order:
 *
 * 1. [started] when it admits the attempt (after the whole-request key check — a request refused before admission
 *    writes nothing). `false` means the attempt proceeds UNRECORDED: the evaluator then makes no further call;
 * 2. [queryQueued] each time a template-backed selector's statement asks the `SelectorPool` for admission;
 * 3. [queryEnded] when that statement ends — at most once per [queryQueued];
 * 4. [ended] exactly once, at the one point that owns the evaluation's end, whatever the ending — and it closes every
 *    statement still in flight then (the deadline's or an abort's abandoned work).
 *
 * Plain data only — no servlet, SSE or web type crosses (`ArchitectureGuardTest` keeps this module free of them).
 * Nothing here carries a value, a selection, SQL, a bind, a result row or a driver message (spec §2.2's never-stored
 * list): the data classes below have no field that could.
 *
 * **Persistence failure never fails an evaluation** (spec §2.3): an implementation answers a storage fault with one
 * structured log line and returns — it throws only for a caller's defect (the dormant `PIPELINE` caller, §11.10).
 */
interface ParameterEvaluationRecorder {
    /** Opens the record — the `RUNNING` row. True when it landed; false means "proceed unrecorded". */
    fun started(evaluation: EvaluationStarted): Boolean

    /** One statement attempt's row, `queued_at` stamped, its outcome still open. */
    fun queryQueued(query: QueryQueued)

    /** The statement attempt's end: its outcome, its code, its row count and its worker-side stamps. */
    fun queryEnded(query: QueryEnded)

    /** The terminal write: the status, the per-parameter outcomes and every statement still in flight, closed. */
    fun ended(evaluation: EvaluationEnded)

    companion object {
        /** Records nothing — the evaluator's default, and what a unit suite without a database gets. */
        val NONE: ParameterEvaluationRecorder =
            object : ParameterEvaluationRecorder {
                override fun started(evaluation: EvaluationStarted): Boolean = false

                override fun queryQueued(query: QueryQueued) = Unit

                override fun queryEnded(query: QueryEnded) = Unit

                override fun ended(evaluation: EvaluationEnded) = Unit
            }
    }
}

/** Which record a write belongs to — the three ids every structured log line of the history names. */
data class EvaluationKey(
    val evaluationId: UUID,
    val workspaceId: UUID,
    val parameterSetId: UUID,
)

/** The `RUNNING` row: who evaluated which version, and when the attempt was admitted. */
data class EvaluationStarted(
    val key: EvaluationKey,
    val attempt: EvaluationAttempt,
    val parameterSetVersion: Int,
    val startedAt: Instant,
)

/** A statement attempt asks the bulkhead for admission — the pin that renders it and the datasource it runs on. */
data class QueryQueued(
    val key: EvaluationKey,
    val id: UUID,
    val parameter: String,
    val datasource: String,
    val template: TemplateRef,
    val queuedAt: Instant,
)

/**
 * A statement attempt's end. [code] is the refusal code when [outcome] is `REFUSED`, the owning subsystem's code when
 * it is `FAILED`, and null otherwise; [rowCount] is set on `EXECUTED` only. [startedAt] is when the worker began it
 * (null when it never started); [endedAt] is when the worker returned it — null for `TIMEOUT` and `ABORTED`, whose
 * worker may still be running: the row never claims it stopped (spec §2.2).
 */
data class QueryEnded(
    val key: EvaluationKey,
    val id: UUID,
    val outcome: QueryAttemptOutcome,
    val code: String?,
    val rowCount: Int?,
    val startedAt: Instant?,
    val endedAt: Instant?,
)

/** The terminal write — [abandoned] closes the statements still in flight when the evaluation ended. */
data class EvaluationEnded(
    val key: EvaluationKey,
    val status: ParameterEvaluationStatus,
    /** The catalogued whole-request code (`parameter.evaluate.timeout`, `…response_too_large`); never a message. */
    val outcomeCode: String?,
    /** The response's `valid` flag — set exactly when [status] is `COMPLETED`. */
    val valid: Boolean?,
    /** One entry per parameter that finished, in declaration order. */
    val outcomes: List<ParameterOutcome>,
    val abandoned: List<QueryEnded>,
    val finishedAt: Instant,
)

/**
 * One parameter's end in the record's `outcomes_json` (spec §2.1): [outcome] is `resolved`, `reset` or `error`;
 * [errorCode] the first error's catalogued code; [detail] that error's own `details.reason` word, ≤ 200 characters —
 * never its message (which may quote a driver) and never a value.
 */
data class ParameterOutcome(
    val name: String,
    val outcome: String,
    val errorCode: String?,
    val detail: String?,
) {
    companion object {
        const val RESOLVED = "resolved"
        const val RESET = "reset"
        const val ERROR = "error"
    }
}

/**
 * A history record's state (`parameter_evaluations.status`, enums.md §40). The four terminal words of
 * [EvaluationOutcome] plus [RUNNING] (admitted, not yet ended) and [INCOMPLETE] — written ONLY by the stale sweep, for a
 * row whose terminal write never landed (an instance crash, a persistence failure); it never claims a timeout that
 * may not have happened.
 */
enum class ParameterEvaluationStatus {
    RUNNING,
    COMPLETED,
    ABORTED,
    TIMEOUT,
    FAILED,
    INCOMPLETE,
    ;

    companion object {
        fun of(outcome: EvaluationOutcome): ParameterEvaluationStatus =
            when (outcome) {
                EvaluationOutcome.COMPLETED -> COMPLETED
                EvaluationOutcome.TIMEOUT -> TIMEOUT
                EvaluationOutcome.FAILED -> FAILED
                EvaluationOutcome.ABORTED -> ABORTED
            }
    }
}

/**
 * One statement attempt's outcome (`parameter_evaluation_queries.outcome`, enums.md §41). [REFUSED] — the statement
 * never ran (a render failure, an invisible or unreachable datasource, a missing bind, too many binds, the read-only
 * gate, a full bulkhead) — is a value of the column, never an absence (R2). [TIMEOUT] is the evaluate deadline's
 * abandonment, [ABORTED] the abandonment of an evaluation its caller stopped (the owner's §11.7 ruling): both say
 * only that the evaluation stopped waiting.
 */
enum class QueryAttemptOutcome {
    EXECUTED,
    REFUSED,
    FAILED,
    TIMEOUT,
    ABORTED,
}
