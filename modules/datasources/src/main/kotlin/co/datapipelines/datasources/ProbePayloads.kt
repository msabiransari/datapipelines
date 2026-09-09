package co.datapipelines.datasources

import co.datapipelines.typesystem.LogicalType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

/*
 * The §7D SQL-probe payloads, one home per the `SchemaPayloads` precedent. The wire projections
 * live in `SchemaWire.kt` beside them.
 */

/**
 * One named probe parameter, in the pipeline parameter grammar (pipeline-contract §6.3 read
 * through the wire conventions): [type] is a canonical [LogicalType] and [value] its WIRE
 * string — BIGINTEGER/BIGDECIMAL as plain decimal text, temporal in ISO forms, BINARY as padded
 * base64 (type-system §7.3/§3.5). Null [value] binds SQL NULL, the pipeline's own rule for a
 * supplied-but-null parameter.
 *
 * The typed-string shape exists because the wire cannot carry a bigint or a decimal natively,
 * and the coercion below mirrors `pipeline-contract`'s `ParameterCoercion` rather than importing
 * it: `datasources` may depend on `typesystem` only (module-structure §5.4), and the conversion
 * is small enough to duplicate under that fence.
 */
data class SqlProbeParameter(
    val type: LogicalType,
    val value: String?,
)

/**
 * A probe parameter could not be used: the SQL references a name the caller did not supply, or
 * a supplied value does not parse as its declared [type]. STATIC message (the
 * [SqlProbeRefusalException] precedent) — the parameter NAME travels as a property, the
 * never-trusted value text does not.
 */
class SqlProbeParameterException(
    val parameter: String,
    val declaredType: LogicalType?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        /** The SQL's `:name` has no supplied value — never bound as null silently. */
        fun missing(
            parameter: String,
            cause: Throwable? = null,
        ) = SqlProbeParameterException(
            parameter,
            declaredType = null,
            message = "The probe SQL references a bind parameter that was not supplied.",
            cause = cause,
        )

        /** [parameter]'s wire value does not parse as [type]. */
        fun coercion(
            parameter: String,
            type: LogicalType,
        ) = SqlProbeParameterException(
            parameter,
            declaredType = type,
            message = "A probe parameter could not be coerced to its declared type ${type.wire}.",
        )
    }
}

/** Coerces this parameter's wire value to the Java object the JDBC bind expects. */
internal fun SqlProbeParameter.toJdbcValue(parameter: String): Any? {
    val text = value ?: return null

    fun reject(): Nothing = throw SqlProbeParameterException.coercion(parameter, type)
    return when (type) {
        // Unreachable through the tool surface (NULL is not a declarable parameter type,
        // pipeline-contract §6.2); refused here rather than coerced to a guessed null.
        LogicalType.NULL -> {
            reject()
        }

        LogicalType.STRING -> {
            text
        }

        LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.DECIMAL, LogicalType.BIGDECIMAL, LogicalType.BOOLEAN -> {
            coerceScalar(type, text) ?: reject()
        }

        LogicalType.BINARY, LogicalType.DATE, LogicalType.TIME, LogicalType.TIMESTAMP -> {
            coerceEncoded(type, text) { reject() }
        }
    }
}

/** The numeric/boolean family, or null when the wire text does not parse. */
private fun coerceScalar(
    type: LogicalType,
    text: String,
): Any? =
    when (type) {
        LogicalType.INTEGER -> text.trim().toIntOrNull()

        // int64, like the pipeline grammar's BIGINTEGER range rule.
        LogicalType.BIGINTEGER -> text.trim().toLongOrNull()

        LogicalType.DECIMAL, LogicalType.BIGDECIMAL -> runCatching { BigDecimal(text.trim()) }.getOrNull()

        else -> coerceBoolean(text)
    }

private fun coerceBoolean(text: String): Boolean? =
    when (text.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

/** The encoded family — base64 BINARY and the ISO temporals. */
private fun coerceEncoded(
    type: LogicalType,
    text: String,
    reject: () -> Nothing,
): Any =
    when (type) {
        LogicalType.BINARY -> coerceBinary(text) ?: reject()
        LogicalType.DATE -> coerceTemporal(text, DATE_SHAPE, { reject() }) { LocalDate.parse(it) }
        LogicalType.TIME -> coerceTemporal(text, TIME_SHAPE, { reject() }) { LocalTime.parse(it) }
        else -> coerceTimestamp(text) ?: reject()
    }

/** Padded standard base64 (the §6.3 symmetric-contract rule), or null. */
private fun coerceBinary(text: String): ByteArray? {
    if (text.length % BASE64_QUANTUM != 0) return null
    return runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}

/**
 * What the executor binds for a TIMESTAMP parameter: an Instant — the offset is the caller's
 * statement, per §6.3's "the server never guesses the client's timezone".
 */
private fun coerceTimestamp(text: String): java.time.Instant? =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

/** A shape-checked ISO temporal parse, or the rejection — one rule for DATE and TIME. */
private fun <T : Any> coerceTemporal(
    text: String,
    shape: Regex,
    reject: () -> Nothing,
    parse: (String) -> T,
): T {
    if (!shape.matches(text)) reject()
    return try {
        parse(text)
    } catch (_: DateTimeParseException) {
        reject()
    }
}

private const val BASE64_QUANTUM = 4

/** The pipeline grammar's exact DATE shape — four digits, no sign (§6.3). */
private val DATE_SHAPE = Regex("""^\d{4}-\d{2}-\d{2}$""")

/** The pipeline grammar's exact TIME shape — HH:MM:SS with up to 6 fractional digits (§6.3). */
private val TIME_SHAPE = Regex("""^\d{2}:\d{2}:\d{2}(\.\d{1,6})?$""")

/**
 * A summarized EXPLAIN plan. [scan] is the short per-dialect access description (`seq`,
 * `index:<name>`, `partition_prune x/y`, ...), [estimatedRows] the planner's row estimate as a
 * string (BIGINTEGER wire convention), [raw] the full plan text capped at
 * [SqlProbe.EXPLAIN_RAW_MAX_CHARS]. [partitionsScanned]/[partitionsTotal] are the LAKE pruning
 * signal — DuckDB's `Scanning Files: x/y` marker — null when the plan carries none (no static
 * partition filter, or a dialect without the marker). Every field but [raw] is a best-effort
 * extraction; a plan that parses to nothing still reports its [raw].
 */
data class ExplainPlanSummary(
    val scan: String?,
    val estimatedRows: String?,
    val raw: String,
    val partitionsScanned: Int?,
    val partitionsTotal: Int?,
)

/**
 * One probe's outcome: the capped, wire-encoded rows (the [QueryRows] shape the §7B surfaces
 * already emit), [wallMs] of QUERY execution only (prepare through last row read; the EXPLAIN
 * is excluded), and the plan — captured BEFORE the query ran, so it survives a timeout.
 */
data class SqlProbeResult(
    val rows: QueryRows,
    val wallMs: Long,
    val plan: ExplainPlanSummary?,
)
