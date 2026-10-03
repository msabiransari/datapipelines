package co.datapipelines.parameters

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/** One row of the History tab — ids, names, codes, counts and instants; never a value. */
data class EvaluationSummary(
    val id: UUID,
    val version: Int,
    val caller: EvaluationCaller,
    val principalUserId: UUID?,
    /** The user's display name when the principal is a person; null for a key. */
    val principalName: String?,
    val principalKeyId: String?,
    val correlationId: String?,
    val status: ParameterEvaluationStatus,
    val outcomeCode: String?,
    val valid: Boolean?,
    val outcomeCount: Int,
    val queryCount: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
) {
    /** Wall-clock milliseconds from admission to the terminal write; null while running or never finished. */
    val tookMillis: Long? get() = finishedAt?.let { it.toEpochMilli() - startedAt.toEpochMilli() }
}

/** One record with its per-parameter outcomes (the detail partial's header and outcome list). */
data class EvaluationDetail(
    val summary: EvaluationSummary,
    val outcomes: List<ParameterOutcome>,
)

/** One statement attempt as stored — [outcome] is null while the statement is in flight (or its evaluation was lost). */
data class QueryAttemptRecord(
    val id: UUID,
    val parameter: String,
    val datasource: String,
    val templateId: String,
    val templateVersion: Int,
    val queuedAt: Instant?,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val outcome: QueryAttemptOutcome?,
    val refusalCode: String?,
    val errorCode: String?,
    val rowCount: Int?,
)

/**
 * `outcomes_json`'s one encoding (spec §2.1): an array of `{name, outcome, error_code?, detail?}`, bounded so the
 * terminal write always fits `chk_parameter_evaluations_outcomes_size` (8 KiB stored). The CHECK is the backstop, never
 * the bound: a refused terminal write would leave the record RUNNING until the sweep calls it INCOMPLETE, so a large
 * set (up to `max-parameters-per-set` = 256 names of 63 characters) must degrade here instead. The text budget is
 * [BUDGET_BYTES] — JSONB's binary form spends a few bytes more per key than the text does, measured on the worst
 * shape by `ParameterEvaluationHistoryIntegrationTest` — and the degradation keeps what diagnoses first:
 *
 * 1. every entry, as recorded;
 * 2. every entry without its `detail` word;
 * 3. only the `error` and `reset` entries;
 * 4. only the `error` entries, as many as fit, in declaration order.
 */
internal object OutcomesJson {
    /** The text bytes one encoding may take — well under the 8,192-byte CHECK once JSONB's overhead is added. */
    const val BUDGET_BYTES = 5_120

    fun encode(outcomes: List<ParameterOutcome>): String {
        val full = write(outcomes, withDetail = true)
        if (fits(full)) return full
        val bare = write(outcomes, withDetail = false)
        if (fits(bare)) return bare
        val notable = outcomes.filter { it.outcome != ParameterOutcome.RESOLVED }
        write(notable, withDetail = false).let { if (fits(it)) return it }
        val errors = notable.filter { it.outcome == ParameterOutcome.ERROR }
        var kept = errors.size
        while (kept > 0 && !fits(write(errors.take(kept), withDetail = false))) kept--
        return write(errors.take(kept), withDetail = false)
    }

    fun decode(json: String?): List<ParameterOutcome> {
        if (json == null) return emptyList()
        return ParameterSetJson.mapper.readTree(json).map { entry ->
            ParameterOutcome(
                name = entry.text("name").orEmpty(),
                outcome = entry.text("outcome").orEmpty(),
                errorCode = entry.text("error_code"),
                detail = entry.text("detail"),
            )
        }
    }

    private fun write(
        outcomes: List<ParameterOutcome>,
        withDetail: Boolean,
    ): String {
        val array = ParameterSetJson.mapper.createArrayNode()
        outcomes.forEach { entry(array.addObject(), it, withDetail) }
        return ParameterSetJson.mapper.writeValueAsString(array)
    }

    private fun entry(
        node: ObjectNode,
        outcome: ParameterOutcome,
        withDetail: Boolean,
    ) {
        node.put("name", outcome.name)
        node.put("outcome", outcome.outcome)
        outcome.errorCode?.let { node.put("error_code", it) }
        if (withDetail) outcome.detail?.let { node.put("detail", it) }
    }

    private fun fits(json: String): Boolean = json.toByteArray(Charsets.UTF_8).size <= BUDGET_BYTES

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()
}
