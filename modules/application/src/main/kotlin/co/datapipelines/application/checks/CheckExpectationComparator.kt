package co.datapipelines.application.checks

import co.datapipelines.datasources.QueryRows
import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunVerdict
import java.math.BigDecimal
import java.math.BigInteger

/**
 * What [CheckExpectationComparator.compare] decided (140): the verdict, the observed value as
 * its wire string (null when no value could be read), and the human sentence for a fail/error
 * (null on a pass). [observedKind] tells the persisting caller which `observed_json` shape the
 * value belongs in — `{"value": …}` for a single cell, `{"rows": …}` for a count.
 */
data class CheckComparison(
    val verdict: CheckRunVerdict,
    val observed: String?,
    val observedKind: ObservedKind?,
    val message: String?,
) {
    /** The `observed_json` shape a comparison's observed value serializes under (metadata-db §4.20). */
    enum class ObservedKind {
        VALUE,
        ROWS,
    }

    companion object {
        /** An `error` comparison — no verdict could be formed; [message] says why. */
        fun error(message: String) = CheckComparison(CheckRunVerdict.ERROR, observed = null, observedKind = null, message)
    }
}

/**
 * The comparison half of a release check run (pipeline-contract §3.3, 140): one probe result
 * against one [CheckExpectation], producing `pass` / `fail` / `error`.
 *
 * Pure and JDBC-free by construction — it reads the probe's already-decoded [QueryRows], so the
 * whole verdict logic is unit-testable without a database, a datasource or a connection.
 *
 * ## The shape rule
 *
 * `value` and `range` require EXACTLY one row and one column. Anything else is `error` with the
 * shape named ("expected one row and one column, got 3 rows x 2 columns") — never a comparison
 * against an arbitrary cell, which would be a verdict about a value the author did not ask for.
 * The single cell must then coerce numerically (a canonical numeric cell, or a numeric string —
 * the BIGINTEGER/BIGDECIMAL wire form); a non-numeric or NULL cell is `error`, not `fail`.
 *
 * ## The three kinds
 *
 * - `value` — `|observed − expected| ≤ tolerance`, [BigDecimal] math end to end; a null
 *   tolerance is 0 (§12.12 already refused a negative one at save).
 * - `range` — `min ≤ observed ≤ max`, inclusive.
 * - `rows` — the ROW COUNT equals `expected.rows`.
 *
 * ## The rows-count truncation decision
 *
 * A `rows` check runs with `limit = (expected.rows + 1) coerceAtMost SqlProbe.MAX_LIMIT` (the
 * caller passes it in; see `PipelineCheckRunner`), so a truthful answer needs at most one row
 * past the expectation. The verdict then reads the returned row list plus the truncation flag:
 *
 * - **not truncated** — the count is exact: `rows.size == expected.rows`.
 * - **truncated, and `rows.size ≥ expected.rows`** — the true count is `> rows.size`, hence
 *   provably `≠ expected.rows`: a clean `fail` ("expected 6 rows, observed more than 6"), not
 *   an error. This is the common case — the probe's `maxRows = limit + 1` proves the overflow.
 * - **truncated, and `rows.size < expected.rows`** — reachable only when the expectation met the
 *   probe's 500-row cap: the true count could still equal the expectation, and no honest verdict
 *   exists. `error`, with the message saying the count exceeded the probe cap — never a `fail`,
 *   because nothing was shown to be false, and never a silent undercount.
 */
object CheckExpectationComparator {
    /** Compares one probe result against one expectation. Unknown or incoherent kinds are `error`. */
    fun compare(
        expected: CheckExpectation,
        rows: QueryRows,
    ): CheckComparison =
        when (expected.kind) {
            CheckExpectation.KIND_VALUE, CheckExpectation.KIND_RANGE -> compareSingleCell(expected, rows)
            CheckExpectation.KIND_ROWS -> compareRows(expected, rows)
            else -> CheckComparison.error("expected.kind '${expected.kind}' is not one of value | range | rows.")
        }

    private fun compareSingleCell(
        expected: CheckExpectation,
        rows: QueryRows,
    ): CheckComparison {
        val columnCount = rows.schema.columns.size
        val rowCount = rows.rows.size
        if (rowCount != 1 || columnCount != 1) {
            return CheckComparison.error(
                "expected one row and one column, got $rowCount ${"row".plural(rowCount)} x " +
                    "$columnCount ${"column".plural(columnCount)}",
            )
        }
        val cell =
            rows.rows
                .single()
                .values
                .single()
        val observed =
            numeric(cell)
                ?: return CheckComparison.error(
                    if (cell == null) {
                        "the single cell is NULL; a NULL value has no numeric reading"
                    } else {
                        "the single cell ('${cell.toString().take(MAX_CELL_CHARS)}') is not numeric"
                    },
                )
        return when (expected.kind) {
            CheckExpectation.KIND_VALUE -> compareValue(expected, observed)
            else -> compareRange(expected, observed)
        }
    }

    /** `|observed − expected.value| ≤ tolerance` (tolerance null → 0), [BigDecimal] throughout. */
    private fun compareValue(
        expected: CheckExpectation,
        observed: BigDecimal,
    ): CheckComparison {
        val target =
            expected.value?.let(BigDecimal::valueOf)
                ?: return CheckComparison.error("a 'value' expectation declares no expected.value.")
        val tolerance = expected.tolerance?.let(BigDecimal::valueOf) ?: BigDecimal.ZERO
        val within = observed.subtract(target).abs() <= tolerance
        val observedText = observed.toPlainString()
        return if (within) {
            CheckComparison(CheckRunVerdict.PASS, observedText, CheckComparison.ObservedKind.VALUE, message = null)
        } else {
            val expectation =
                if (tolerance.signum() == 0) "expected ${target.render()}" else "expected ${target.render()} ± ${tolerance.render()}"
            CheckComparison(CheckRunVerdict.FAIL, observedText, CheckComparison.ObservedKind.VALUE, "$expectation, observed $observedText")
        }
    }

    /** `min ≤ observed ≤ max`, inclusive. */
    private fun compareRange(
        expected: CheckExpectation,
        observed: BigDecimal,
    ): CheckComparison {
        val min = expected.min?.let(BigDecimal::valueOf)
        val max = expected.max?.let(BigDecimal::valueOf)
        if (min == null || max == null) {
            return CheckComparison.error("a 'range' expectation declares no expected.min / expected.max.")
        }
        val observedText = observed.toPlainString()
        return if (observed >= min && observed <= max) {
            CheckComparison(CheckRunVerdict.PASS, observedText, CheckComparison.ObservedKind.VALUE, message = null)
        } else {
            CheckComparison(
                CheckRunVerdict.FAIL,
                observedText,
                CheckComparison.ObservedKind.VALUE,
                "expected a value in [${min.render()}, ${max.render()}], observed $observedText",
            )
        }
    }

    /** The row-count comparison — see the class KDoc for the truncation decision. */
    private fun compareRows(
        expected: CheckExpectation,
        rows: QueryRows,
    ): CheckComparison {
        val expectedRows =
            expected.rows ?: return CheckComparison.error("a 'rows' expectation declares no expected.rows.")
        val count = rows.rows.size
        if (rows.truncated) {
            if (count >= expectedRows) {
                // The true count is > count ≥ expectedRows: provably unequal — a clean fail.
                // `observed` stays what was literally observed (the capped list's size); the
                // message carries the "more than" half.
                return CheckComparison(
                    CheckRunVerdict.FAIL,
                    count.toString(),
                    CheckComparison.ObservedKind.ROWS,
                    "expected $expectedRows rows, observed more than $count",
                )
            }
            return CheckComparison.error(
                "the row count exceeded the probe's row cap ($count rows returned, truncated); " +
                    "expected $expectedRows — the count is unknowable through a bounded probe",
            )
        }
        return if (count.toLong() == expectedRows) {
            CheckComparison(CheckRunVerdict.PASS, count.toString(), CheckComparison.ObservedKind.ROWS, message = null)
        } else {
            CheckComparison(
                CheckRunVerdict.FAIL,
                count.toString(),
                CheckComparison.ObservedKind.ROWS,
                "expected $expectedRows rows, observed $count",
            )
        }
    }

    /**
     * The single cell as a [BigDecimal], or null. Canonical numeric cells convert exactly
     * (integer, long, big-integer, big-decimal, and the approximate family through
     * [BigDecimal.valueOf] — the double's shortest round-trip decimal, never its binary
     * expansion); a numeric STRING is the BIGINTEGER/BIGDECIMAL wire form reaching back. A NULL
     * cell and anything else (boolean, binary, temporal) is no number at all.
     */
    private fun numeric(cell: Any?): BigDecimal? =
        when (cell) {
            null -> null
            is BigDecimal -> cell
            is BigInteger -> BigDecimal(cell)
            is Int -> BigDecimal(cell)
            is Long -> BigDecimal(cell)
            is Double -> if (cell.isFinite()) BigDecimal.valueOf(cell) else null
            is Float -> if (cell.isFinite()) BigDecimal.valueOf(cell.toDouble()) else null
            is String -> cell.trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
            else -> null
        }

    /** An expectation member rendered for a message: `74.62`, `5` — never `5.0` or `5E+2`. */
    private fun BigDecimal.render(): String = stripTrailingZeros().toPlainString()

    private fun String.plural(count: Int): String = if (count == 1) this else this + "s"

    /** A non-numeric cell's text is bounded in the error message, never echoed whole. */
    private const val MAX_CELL_CHARS = 200
}
