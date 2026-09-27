package co.datapipelines.parameters

import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.time.temporal.TemporalField

/**
 * Record §3.7 as code: which `control` each kind × cardinality × type may name (the first is the
 * derived default a renderer receives when none is stated), and which `format` each type family
 * takes. Presentation never changes meaning, binding or validation of a VALUE — this is its only
 * save-time check, and [derivedControl] is its only other reader (the evaluate's echo, lane C).
 */
object PresentationCatalogue {
    /** A temporal `format.pattern` is at most this long (record §3.7). */
    const val MAX_FORMAT_PATTERN_CHARS: Int = 64

    private val NUMERIC = setOf(LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.DECIMAL, LogicalType.BIGDECIMAL)
    private val TEMPORAL = setOf(LogicalType.DATE, LogicalType.TIME, LogicalType.TIMESTAMP)

    /** The allowed controls, the derived default first (record §3.7's table, row by row). */
    fun controlsFor(
        kind: ParameterKind,
        cardinality: ParameterCardinality,
        type: LogicalType,
    ): List<PresentationControl> =
        when (kind) {
            ParameterKind.SELECT -> {
                if (cardinality == ParameterCardinality.MULTI) {
                    listOf(PresentationControl.DROPDOWN, PresentationControl.CHECKBOXES, PresentationControl.LIST)
                } else {
                    listOf(PresentationControl.DROPDOWN, PresentationControl.RADIO, PresentationControl.LIST)
                }
            }

            ParameterKind.INPUT -> {
                when (type) {
                    LogicalType.STRING, LogicalType.BINARY -> listOf(PresentationControl.TEXT, PresentationControl.TEXTAREA)
                    in NUMERIC -> listOf(PresentationControl.NUMBER, PresentationControl.TEXT)
                    LogicalType.BOOLEAN -> listOf(PresentationControl.TOGGLE, PresentationControl.CHECKBOX)
                    LogicalType.DATE -> listOf(PresentationControl.CALENDAR, PresentationControl.TEXT)
                    LogicalType.TIME -> listOf(PresentationControl.CLOCK, PresentationControl.TEXT)
                    LogicalType.TIMESTAMP -> listOf(PresentationControl.DATETIME, PresentationControl.TEXT)
                    else -> emptyList()
                }
            }
        }

    /** The control a renderer receives when the definition states none (record §3.7). */
    fun derivedControl(definition: ParameterDefinition): PresentationControl? =
        controlsFor(definition.kind, definition.cardinality, definition.type).firstOrNull()

    /** Every presentation problem of [definition], as (code, path suffix, message, details). */
    fun check(definition: ParameterDefinition): List<ExpressionProblem> {
        val presentation = definition.presentation ?: return emptyList()
        val problems = mutableListOf<ExpressionProblem>()
        presentation.control?.let { control ->
            val allowed = controlsFor(definition.kind, definition.cardinality, definition.type)
            if (control !in allowed) {
                problems +=
                    ExpressionProblem(
                        ParameterErrorCodes.CONTROL_NOT_APPLICABLE,
                        "control",
                        "'${control.wire}' does not fit a ${definition.kind.wire} ${definition.cardinality.wire} " +
                            "${definition.type.wire}; " +
                            "allowed: ${allowed.map { it.wire }}.",
                        mapOf("control" to control.wire, "allowed" to allowed.map { it.wire }),
                    )
            }
        }
        presentation.format?.let { problems += format(definition.type, it) }
        return problems
    }

    private fun format(
        type: LogicalType,
        format: DisplayFormat,
    ): List<ExpressionProblem> =
        when (type) {
            in NUMERIC -> {
                if (format.pattern != null) {
                    listOf(
                        invalid(
                            "format.pattern",
                            "A numeric parameter's format is a kind (plain, currency, percent), never a pattern.",
                            type,
                        ),
                    )
                } else {
                    emptyList()
                }
            }

            in TEMPORAL -> {
                when {
                    format.kind != null -> {
                        listOf(
                            invalid("format.kind", "A ${type.wire} parameter's format is a pattern, never a kind.", type),
                        )
                    }

                    format.pattern != null -> {
                        pattern(type, format.pattern)
                    }

                    else -> {
                        emptyList()
                    }
                }
            }

            else -> {
                listOf(invalid("format", "A ${type.wire} parameter takes no format.", type))
            }
        }

    /** Record §3.7: ≤ 64 characters, compiles, and names no field the type lacks (a `DATE` shows no time, a `TIME` no date). */
    @Suppress("ReturnCount") // one early refusal per rule
    private fun pattern(
        type: LogicalType,
        pattern: String,
    ): List<ExpressionProblem> {
        if (pattern.length > MAX_FORMAT_PATTERN_CHARS) {
            return listOf(
                patternInvalid("A format pattern is at most $MAX_FORMAT_PATTERN_CHARS characters; got ${pattern.length}.", "too_long"),
            )
        }
        val formatter =
            try {
                DateTimeFormatter.ofPattern(pattern)
            } catch (e: IllegalArgumentException) {
                return listOf(patternInvalid("The format pattern does not compile: ${e.message.safeEcho()}.", "syntax"))
            }
        val forbidden =
            when (type) {
                LogicalType.DATE -> TIME_FIELDS
                LogicalType.TIME -> DATE_FIELDS
                else -> emptySet()
            }
        val used = forbidden.filter { formatter.toString().contains(it.toString()) }
        if (used.isNotEmpty()) {
            return listOf(
                patternInvalid(
                    "A ${type.wire} format may not show ${used.joinToString { it.toString() }}.",
                    if (type == LogicalType.DATE) "time_field_on_date" else "date_field_on_time",
                ),
            )
        }
        return emptyList()
    }

    /**
     * The fields a compiled `DateTimeFormatter` prints by name (`Value(HourOfDay,2)`), read from its
     * own description — the formatter's field list is not public API. A test pins the reading
     * against every letter class, so a JDK that renames them turns red, not silently permissive.
     */
    private val TIME_FIELDS: Set<TemporalField> =
        setOf(
            ChronoField.HOUR_OF_DAY,
            ChronoField.CLOCK_HOUR_OF_DAY,
            ChronoField.HOUR_OF_AMPM,
            ChronoField.CLOCK_HOUR_OF_AMPM,
            ChronoField.AMPM_OF_DAY,
            ChronoField.MINUTE_OF_HOUR,
            ChronoField.SECOND_OF_MINUTE,
            ChronoField.NANO_OF_SECOND,
            ChronoField.MILLI_OF_DAY,
        )
    private val DATE_FIELDS: Set<TemporalField> =
        setOf(
            ChronoField.YEAR,
            ChronoField.YEAR_OF_ERA,
            ChronoField.MONTH_OF_YEAR,
            ChronoField.DAY_OF_MONTH,
            ChronoField.DAY_OF_YEAR,
            ChronoField.DAY_OF_WEEK,
            ChronoField.ALIGNED_WEEK_OF_YEAR,
        )

    private fun invalid(
        path: String,
        message: String,
        type: LogicalType,
    ) = ExpressionProblem(ParameterErrorCodes.FORMAT_INVALID, path, message, mapOf("type" to type.wire))

    private fun patternInvalid(
        message: String,
        reason: String,
    ) = ExpressionProblem(ParameterErrorCodes.FORMAT_PATTERN_INVALID, "format.pattern", message, mapOf("reason" to reason))
}
