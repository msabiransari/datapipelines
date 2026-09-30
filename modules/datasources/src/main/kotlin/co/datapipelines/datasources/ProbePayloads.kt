package co.datapipelines.datasources

import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCoercion
import co.datapipelines.typesystem.ParameterLift
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
 * The value is judged by the ONE strict coercion every parameter surface shares
 * ([ParameterCoercion], parameter-engine record P10/P28), through [ParameterLift] — the same
 * strictness rest-api's change log v2.36 recorded for every other surface: nothing is trimmed,
 * booleans are `true`/`false` only, and the §6.3 digit cap bounds any big-number parse (#278).
 * The typed-string shape exists because the wire cannot carry a bigint or a decimal natively;
 * the lift turns the text into the wire node the shared judge reads.
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
    val node = ParameterLift.lift(type, text) ?: throw SqlProbeParameterException.coercion(parameter, type)
    return when (val outcome = ParameterCoercion.coerce(type, node)) {
        is ParameterCoercion.Outcome.Coerced -> jdbcForm(outcome.value)
        is ParameterCoercion.Outcome.Rejected -> throw SqlProbeParameterException.coercion(parameter, type)
    }
}

/**
 * The JDBC form of a canonical value — the [ReadOnlyStatementLease] rule verbatim: spring-jdbc's
 * `TYPE_UNKNOWN` path hands anything but a String, a `java.util.Date` or a Calendar to `setObject`,
 * and pgjdbc cannot infer a SQL type for an `Instant` ("Can't infer the SQL type to use for an
 * instance of java.time.Instant"), so an `Instant` binds as an `OffsetDateTime` at UTC — what the
 * coercion produces for TIMESTAMP, and what every pinned driver maps to `timestamptz`. Every other
 * canonical value the coercion returns (String, BigDecimal, BigInteger, Boolean, Int, ByteArray,
 * LocalDate, LocalTime) binds through the drivers' own table. The conversion changes only the
 * value's Java form, never what is bound.
 */
private fun jdbcForm(value: Any?): Any? = if (value is Instant) OffsetDateTime.ofInstant(value, ZoneOffset.UTC) else value

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
