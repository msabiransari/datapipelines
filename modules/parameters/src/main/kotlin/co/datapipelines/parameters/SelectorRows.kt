package co.datapipelines.parameters

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterValueOutcome
import co.datapipelines.typesystem.ParameterValueRule
import co.datapipelines.typesystem.ParameterValueValidator
import co.datapipelines.typesystem.ParameterWireEncoder
import co.datapipelines.typesystem.TypeDescriptor
import co.datapipelines.typesystem.TypeWidening
import java.math.BigDecimal
import java.math.BigInteger

/**
 * What a selector's rows mean at EVALUATE (record §6.2, §6.2a, §6.4, P31) — the save-time dry run
 * proved the shape against two rows; this re-proves it on every run (schemas drift) and enforces the
 * invariants the two-row check never could:
 *
 *  - **`SELECT`**: at most `max-options-per-selector` rows (`too_many_options` — never truncated);
 *    exactly `value`, `display_value`, `is_default` (case-insensitively, C12), `display_value` a
 *    `STRING` and `is_default` a `BOOLEAN` (`selector_rows_invalid`, `reason = columns`); `value`'s
 *    type passes §6.4 from the METADATA (`selector_value_type_mismatch`); then every row, after
 *    canonical conversion, exactly as `constants` are judged at save: `value` non-null
 *    (`null_value`), of the declared type by the SHARED validator (P28 — a driver that under-reports
 *    its metadata still refuses), unique by canonical equality (`duplicate_value`), within
 *    `max-option-value-chars` (`value_too_long`); `display_value` non-empty (`empty_label`) and within
 *    `max-option-label-chars` (`label_too_long`); at most one `is_default` (`multiple_defaults`; a
 *    SQL `NULL` there is `false` — three-valued logic, not an author error). One violation refuses
 *    the whole list: the parameter's options are empty and its children see `NULL`.
 *  - **`INPUT`**: exactly `value`, its type by §6.4; two rows are `input_source_multiple_rows`
 *    (`default_value` is used); one row's value is judged by the input's OWN declaration — its
 *    constraints included — and a refusal leaves the parameter without a sourced value. A single row
 *    whose value is `NULL` is no row (`SELECT MIN(x) … WHERE nothing` answers exactly that).
 */
internal class SelectorRows(
    private val config: ParametersConfig,
    private val validator: ParameterValueValidator,
) {
    /** A `SELECT`'s options, or the one refusal. */
    sealed interface Options {
        data class Accepted(
            val options: List<EvaluatedOption>,
        ) : Options

        data class Refused(
            val error: ParameterError,
        ) : Options
    }

    /** An `INPUT`'s sourced value: present, absent, or refused with an error. */
    sealed interface Sourced {
        data class Value(
            val value: Any,
        ) : Sourced

        data object None : Sourced

        data class Refused(
            val error: ParameterError,
        ) : Sourced
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one early answer per invariant, in the order the KDoc lists them
    fun options(
        parameter: ParameterDefinition,
        run: SelectorRun.Rows,
    ): Options {
        val cap = config.maxOptionsPerSelector
        if (run.rows.size > cap) {
            return refuse(
                parameter,
                ParameterErrorCodes.EVALUATE_TOO_MANY_OPTIONS,
                "The selector returned more than $cap options (datapipelines.parameters.max-options-per-selector); " +
                    "options are never truncated.",
                mapOf("max" to cap),
            )
        }
        val columns = columns(parameter, run.columns, SELECT_COLUMNS)
        if (columns is Columns.Refused) return Options.Refused(columns.error)
        val index = (columns as Columns.Found).index
        typeMismatch(parameter, run.columns[index.getValue(VALUE)])?.let { return Options.Refused(it) }
        columnTypeMismatch(parameter, run.columns[index.getValue(DISPLAY_VALUE)], LogicalType.STRING)?.let { return Options.Refused(it) }
        columnTypeMismatch(parameter, run.columns[index.getValue(IS_DEFAULT)], LogicalType.BOOLEAN)?.let { return Options.Refused(it) }
        val from = run.columns[index.getValue(VALUE)].type
        val seen = HashSet<Any>()
        var defaults = 0
        val options = ArrayList<EvaluatedOption>(run.rows.size)
        run.rows.forEachIndexed { row, cells ->
            val value =
                when (val judged = judge(parameter, cells[index.getValue(VALUE)], from, withConstraints = false)) {
                    is Judged.Value -> judged.value

                    Judged.Null -> return rowInvalid(
                        parameter,
                        row,
                        "null_value",
                        "A selector row's value is NULL; option values are never NULL (§6.2).",
                    )

                    is Judged.Refused -> return Options.Refused(mismatch(parameter, judged.message, judged.reason, row))
                }
            if (wireLength(parameter, value) > config.maxOptionValueChars) {
                return rowInvalid(
                    parameter,
                    row,
                    "value_too_long",
                    "An option value is longer than ${config.maxOptionValueChars} characters.",
                )
            }
            val label = cells[index.getValue(DISPLAY_VALUE)] as String?
            if (label.isNullOrEmpty()) return rowInvalid(parameter, row, "empty_label", "An option needs a non-empty display_value.")
            if (label.length > config.maxOptionLabelChars) {
                return rowInvalid(parameter, row, "label_too_long", "A display_value is at most ${config.maxOptionLabelChars} characters.")
            }
            if (!seen.add(canonicalKey(value))) {
                return rowInvalid(parameter, row, "duplicate_value", "Two selector rows share a value; option values are unique (§6.2).")
            }
            val isDefault = cells[index.getValue(IS_DEFAULT)] == true
            if (isDefault && ++defaults > 1) {
                return rowInvalid(parameter, row, "multiple_defaults", "More than one selector row is marked is_default (P7).")
            }
            options += EvaluatedOption(value, label, isDefault)
        }
        return Options.Accepted(options)
    }

    @Suppress("ReturnCount")
    fun sourced(
        parameter: ParameterDefinition,
        run: SelectorRun.Rows,
    ): Sourced {
        val columns = columns(parameter, run.columns, INPUT_COLUMNS)
        if (columns is Columns.Refused) return Sourced.Refused(columns.error)
        val column = run.columns.single()
        typeMismatch(parameter, column)?.let { return Sourced.Refused(it) }
        if (run.rows.size > 1) {
            return Sourced.Refused(
                ParameterError(
                    ParameterErrorCodes.EVALUATE_INPUT_SOURCE_MULTIPLE_ROWS,
                    "A database-fed INPUT's template returns at most one row; it returned more — default_value is used.",
                    mapOf("parameter" to parameter.name.safeEcho()),
                ),
            )
        }
        val cell = run.rows.singleOrNull()?.firstOrNull() ?: return Sourced.None
        return when (val judged = judge(parameter, cell, column.type, withConstraints = true)) {
            is Judged.Value -> {
                Sourced.Value(judged.value)
            }

            Judged.Null -> {
                Sourced.None
            }

            is Judged.Refused -> {
                Sourced.Refused(
                    if (judged.rule == ParameterValueRule.CONSTRAINT_VIOLATION) {
                        ParameterError(
                            ParameterErrorCodes.EVALUATE_CONSTRAINT_VIOLATION,
                            "The row the source returned breaks the input's own rules: ${judged.message}.",
                            mapOf("parameter" to parameter.name.safeEcho(), "reason" to judged.reason, "source" to "row"),
                        )
                    } else {
                        mismatch(parameter, judged.message, judged.reason, row = 0)
                    },
                )
            }
        }
    }

    // ---- the pieces --------------------------------------------------------------------------------

    private sealed interface Columns {
        data class Found(
            val index: Map<String, Int>,
        ) : Columns

        data class Refused(
            val error: ParameterError,
        ) : Columns
    }

    /** The result's columns by lower-cased name when they are EXACTLY [expected] (C12); else the refusal. */
    private fun columns(
        parameter: ParameterDefinition,
        columns: List<ColumnSchema>,
        expected: Set<String>,
    ): Columns {
        val names = columns.map { it.name.lowercase() }
        val missing = (expected - names.toSet()).sorted()
        val extra = (names.filter { it !in expected } + names.groupBy { it }.filter { it.value.size > 1 }.keys).distinct().sorted()
        if (missing.isEmpty() && extra.isEmpty()) return Columns.Found(names.withIndex().associate { it.value to it.index })
        return Columns.Refused(
            ParameterError(
                ParameterErrorCodes.EVALUATE_SELECTOR_ROWS_INVALID,
                "The source's columns changed since the set was saved: a ${parameter.kind.wire} source returns exactly " +
                    "${expected.sorted()}; missing $missing, unexpected ${extra.map { it.safeEcho() }}.",
                mapOf(
                    "parameter" to parameter.name.safeEcho(),
                    "reason" to "columns",
                    "missing" to missing,
                    "extra" to extra.map { it.safeEcho() },
                ),
            ),
        )
    }

    /** Record §6.4 from the metadata, as at save — schemas drift between a release and an evaluate. */
    private fun typeMismatch(
        parameter: ParameterDefinition,
        column: ColumnSchema,
    ): ParameterError? {
        val from = TypeDescriptor(column.type, column.precision, column.scale)
        val to = TypeDescriptor(parameter.type, parameter.precision, parameter.scale)
        if (TypeWidening.isLossless(from, to)) return null
        return ParameterError(
            ParameterErrorCodes.EVALUATE_SELECTOR_VALUE_TYPE_MISMATCH,
            "The selector's value column is ${describe(
                from,
            )}; the parameter declares ${describe(to)} — only a lossless widening is accepted (§6.4).",
            buildMap {
                put("parameter", parameter.name.safeEcho())
                put("source_type", describe(from))
                put("declared_type", describe(to))
                if (column.type == LogicalType.NULL) put("hint", "CAST the column")
            },
        )
    }

    private fun columnTypeMismatch(
        parameter: ParameterDefinition,
        column: ColumnSchema,
        expected: LogicalType,
    ): ParameterError? =
        if (column.type == expected) {
            null
        } else {
            ParameterError(
                ParameterErrorCodes.EVALUATE_SELECTOR_ROWS_INVALID,
                "The selector's ${column.name.lowercase().safeEcho()} column must be ${expected.wire}; it is ${column.type.wire}.",
                mapOf("parameter" to parameter.name.safeEcho(), "reason" to "columns", "column" to column.name.lowercase().safeEcho()),
            )
        }

    private sealed interface Judged {
        data class Value(
            val value: Any,
        ) : Judged

        data object Null : Judged

        data class Refused(
            val rule: ParameterValueRule,
            val message: String,
            val reason: String?,
        ) : Judged
    }

    /**
     * A cell of the `value` column as the DECLARED type's canonical value, judged by the shared
     * validator (P28): the declared type/precision/scale, and for an `INPUT` its constraints too.
     */
    private fun judge(
        parameter: ParameterDefinition,
        cell: Any?,
        from: LogicalType,
        withConstraints: Boolean,
    ): Judged {
        cell ?: return Judged.Null
        val declared =
            toDeclared(cell, from, parameter.type)
                ?: return Judged.Refused(
                    ParameterValueRule.INVALID_VALUE_TYPE,
                    "a ${from.wire} value cannot be read as ${parameter.type.wire}",
                    null,
                )
        val declaration =
            parameter.declaration.copy(
                required = false,
                default = null,
                cardinality = ParameterCardinality.SINGLE,
                constraints = if (withConstraints) parameter.constraints else null,
            )
        return when (val outcome = validator.validate(declaration, ParameterWireEncoder.encode(parameter.type, declared))) {
            is ParameterValueOutcome.Accepted -> Judged.Value(outcome.value)
            is ParameterValueOutcome.Refused -> Judged.Refused(outcome.refusal.rule, outcome.refusal.message, outcome.refusal.reason)
            ParameterValueOutcome.Unsupplied -> Judged.Null
        }
    }

    private fun mismatch(
        parameter: ParameterDefinition,
        message: String,
        reason: String?,
        row: Int,
    ) = ParameterError(
        ParameterErrorCodes.EVALUATE_SELECTOR_VALUE_TYPE_MISMATCH,
        "A selector row's value does not fit the declared ${describeDeclared(parameter)}: $message.",
        buildMap {
            put("parameter", parameter.name.safeEcho())
            put("row", row)
            reason?.let { put("reason", it) }
        },
    )

    private fun rowInvalid(
        parameter: ParameterDefinition,
        row: Int,
        reason: String,
        message: String,
    ): Options = refuse(parameter, ParameterErrorCodes.EVALUATE_SELECTOR_ROWS_INVALID, message, mapOf("reason" to reason, "row" to row))

    private fun refuse(
        parameter: ParameterDefinition,
        code: String,
        message: String,
        details: Map<String, Any?>,
    ): Options = Options.Refused(ParameterError(code, message, mapOf("parameter" to parameter.name.safeEcho()) + details))

    /** The option value's length on the wire — what `max-option-value-chars` bounds (a string's characters, a number's digits). */
    private fun wireLength(
        parameter: ParameterDefinition,
        value: Any,
    ): Int = ParameterWireEncoder.encode(parameter.type, value).let { if (it.isTextual) it.asText().length else it.toString().length }

    companion object {
        const val VALUE = "value"
        const val DISPLAY_VALUE = "display_value"
        const val IS_DEFAULT = "is_default"
        val SELECT_COLUMNS = setOf(VALUE, DISPLAY_VALUE, IS_DEFAULT)
        val INPUT_COLUMNS = setOf(VALUE)

        /** The §7.3 equality as a hash key: decimals scale-free (`1.0` = `1.00`), bytes by content, the rest themselves. */
        fun canonicalKey(value: Any): Any =
            when (value) {
                is BigDecimal -> value.stripTrailingZeros()
                is ByteArray -> value.toList()
                else -> value
            }

        fun describe(descriptor: TypeDescriptor): String =
            when {
                descriptor.precision != null -> "${descriptor.type.wire}(${descriptor.precision},${descriptor.scale})"
                descriptor.scale != null -> "${descriptor.type.wire}(scale ${descriptor.scale})"
                else -> descriptor.type.wire
            }

        /**
         * A canonical value of the COLUMN's type as the DECLARED type's — the §6.4 widenings only
         * (`INTEGER` into the bigger integers and the decimals, `BIGINTEGER` into `BIGDECIMAL`, a decimal
         * into a decimal); the same type is itself; anything else cannot convert (null). The save-time
         * dry run's rule (`SelectorDryRun`), for the evaluator's canonical rows.
         */
        fun toDeclared(
            value: Any,
            from: LogicalType,
            to: LogicalType,
        ): Any? =
            when {
                from == to -> value
                to == LogicalType.BIGINTEGER && value is Int -> BigInteger.valueOf(value.toLong())
                (to == LogicalType.DECIMAL || to == LogicalType.BIGDECIMAL) && value is Int -> BigDecimal.valueOf(value.toLong())
                to == LogicalType.BIGDECIMAL && value is BigInteger -> BigDecimal(value)
                (to == LogicalType.DECIMAL || to == LogicalType.BIGDECIMAL) && value is BigDecimal -> value
                else -> null
            }
    }
}
