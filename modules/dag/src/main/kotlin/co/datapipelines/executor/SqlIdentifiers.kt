package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/**
 * Identifier validation and quoting for the SQL this module generates — the write-back INSERT
 * (§6.4.3) and the tempdb `CREATE TABLE … AS` (§6.4.1).
 *
 * The rule is staging §4.5's, verbatim, and it is normative for the same reason there: **source
 * column labels come from author SQL**, so they are attacker-adjacent, and a table name that is
 * interpolated into DDL must be proven safe rather than assumed safe. Sanitising by rename is
 * forbidden — an invalid label is refused.
 *
 * staging's own `StagingIdentifiers` is `internal` (its public contract is "hand me a cursor"),
 * so the rule is re-implemented here rather than someone else's visibility being widened. The
 * regex and the doubling quote rule are identical by construction; the assertions that pin them —
 * including the injection-shaped payloads that must fail `matches()` even though they would pass
 * `find()` — live in `ExecutorPrimitivesTest`.
 */
internal object SqlIdentifiers {
    /** staging §4.5: `[A-Za-z_][A-Za-z0-9_]{0,62}` — ASCII, ≤ 63 chars, no leading digit. */
    val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,62}")

    /**
     * Validates result-set column labels, rejecting invalid or case-insensitively duplicated ones.
     *
     * @param runtimeCode the code to report under. Defaults to `pipeline.staging.invalid_column_name`,
     *   the catalogued code §8.2 names for exactly this condition; the write-back path overrides it
     *   with its own phase code, since a failure there is a write-back failure, not a staging one.
     *   The executor defines no codes of its own either way.
     */
    fun validateColumnNames(
        labels: List<String?>,
        runtimeCode: String = PipelineErrorCodes.Staging.INVALID_COLUMN_NAME,
    ): List<String> {
        val seen = mutableSetOf<String>()
        return labels.mapIndexed { index, label ->
            val ordinal = index + 1
            if (label == null || !IDENTIFIER.matches(label) || !seen.add(label.lowercase())) {
                throw invalidColumnName(ordinal, label, runtimeCode)
            }
            label
        }
    }

    /**
     * Validates a generated-SQL table name, reporting under [runtimeCode].
     *
     * ## Why the code is a parameter
     *
     * This used to raise `pipeline.validation.invalid_identifier` — a **save-time, HTTP-400**
     * code — from inside a running execution. Save-time validation (pipeline-contract §10) is the
     * primary guard and no valid pipeline can reach here with a bad name, so this is defence in
     * depth; but when defence in depth does fire, it must speak the executor's vocabulary. A
     * validation-domain code surfacing mid-execution makes §8.2 incoherent and tells an operator
     * to look for a bad request that does not exist. Callers therefore pass the code for the phase
     * they are in: `pipeline.node.staging_failed` for the tempdb CTAS, `pipeline.node.writeback_failed`
     * for the write-back INSERT.
     */
    fun requireValidTable(
        name: String,
        runtimeCode: String,
    ): String {
        if (!IDENTIFIER.matches(name)) {
            throw DatapipelinesException(
                code = runtimeCode,
                message = "Table name '$name' is not a valid SQL identifier; it must match ${IDENTIFIER.pattern}.",
                details = mapOf("table" to name),
            )
        }
        return name
    }

    /** Double-quotes [identifier], doubling any embedded quote. Applied after validation, not instead. */
    fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    private fun invalidColumnName(
        ordinal: Int,
        label: String?,
        code: String,
    ) = DatapipelinesException(
        code = code,
        message =
            "Source column #$ordinal has an invalid or duplicate label " +
                "(${label?.let { "'$it'" } ?: "null"}); it must match ${IDENTIFIER.pattern} " +
                "and be unique case-insensitively. Fix the alias in the source SQL.",
        details = mapOf("ordinal" to ordinal, "label" to label),
    )
}
