package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Where a DQL node's ResultSet goes (pipeline-contract §4.7, enums.md §3).
 *
 * This enum is the wire **discriminator** only. The modelled output is [NodeOutput], a flat
 * sealed interface whose variants carry each target's additional fields — so an impossible
 * combination (a `caller` output with a table name, a `tempdb` output with a write mode)
 * cannot be constructed at all.
 *
 * [CALLER] is the default when the `output` block is omitted on a DQL node (D1), and that
 * default is applied at **deserialization** time, never re-derived later.
 */
enum class OutputTarget(
    @JsonValue val wire: String,
) {
    /** Stage the ResultSet into the in-memory tempdb under `table`. */
    TEMPDB("tempdb"),

    /** Return the ResultSet as the pipeline's result. Default when `output` is omitted. */
    CALLER("caller"),

    /** Stream the ResultSet to an external datasource's table. */
    DATASOURCE("datasource"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWireOrNull(value: String?): OutputTarget? = entries.firstOrNull { it.wire == value }

        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): OutputTarget =
            fromWireOrNull(value)
                ?: throw IllegalArgumentException("Unknown OutputTarget: ${value.truncateForError()}")
    }
}

/**
 * How a write-back to an external datasource treats existing rows (enums.md §4).
 *
 * `replace` is TRUNCATE (or DELETE) + INSERT **in one transaction**; `append` is INSERT
 * only. There is deliberately no default: an absent `mode` is rejected at validation rather
 * than guessed, because guessing wrong in the `replace` direction destroys data.
 */
enum class WriteMode(
    @JsonValue val wire: String,
) {
    REPLACE("replace"),
    APPEND("append"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWireOrNull(value: String?): WriteMode? = entries.firstOrNull { it.wire == value }

        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): WriteMode =
            fromWireOrNull(value)
                ?: throw IllegalArgumentException("Unknown WriteMode: ${value.truncateForError()}")
    }
}

/**
 * The tempdb engine a pipeline stages through (pipeline-contract §5.1, enums.md §7).
 *
 * v1 supports `H2` only; `DUCKDB` is reserved and MUST NOT be accepted by validators until
 * the engine lands (enums.md "Values marked (reserved)").
 */
enum class StagingEngine(
    @JsonValue val wire: String,
) {
    H2("H2"),
    ;

    /**
     * The SQL dialect templates targeting `source: "tempdb"` must be written in.
     *
     * Derived, not hard-coded: §12.6 originally pinned H2 and SPEC-REVIEW 2.1.8 replaced that
     * with "the dialect of the engine declared in `settings.tempdb.engine`", so a DuckDB
     * staging engine makes DuckDB templates valid the day it lands, with no second edit.
     */
    val dialect: Dialect
        get() =
            when (this) {
                H2 -> Dialect.H2
            }

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWireOrNull(value: String?): StagingEngine? = entries.firstOrNull { it.wire == value }

        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): StagingEngine =
            fromWireOrNull(value)
                ?: throw IllegalArgumentException("Unknown StagingEngine: ${value.truncateForError()}")
    }
}

/** Longest reflected raw input allowed in an error message or error `details` value. */
internal const val MAX_REFLECTED_VALUE_LENGTH = 64

/** Longest `ValidationFailure.path`, which embeds identifiers and so is reflected input too. */
internal const val MAX_REFLECTED_PATH_LENGTH = 128

/** Replacement for an ISO control character in reflected text. */
private const val CONTROL_REPLACEMENT = '�'

/**
 * Makes a value that came from an inbound payload safe to echo into an exception message, an
 * error `details` map, or a log line.
 *
 * Two carry-forwards, both from P1, and they are different attacks:
 *  - **CF-2 — length.** `fromWire`-style messages reflect raw input; unbounded reflection of
 *    attacker-controlled text into logs and error responses is response bloat and log flooding.
 *  - **CF-1 — control characters.** A newline or a CR in a reflected value forges log records
 *    in a line-oriented log, and `` smuggles terminal escapes into an operator's console.
 *    Every ISO control character (U+0000–U+001F, U+007F–U+009F) becomes U+FFFD.
 *
 * Truncation happens **before** sanitising so the work is bounded by the cap, not by the
 * attacker's length.
 */
internal fun String?.truncateForError(): String = reflectSafely(MAX_REFLECTED_VALUE_LENGTH)

/**
 * As [truncateForError], with the wider budget a `ValidationFailure.path` needs.
 *
 * A path legitimately runs to `parameters.` plus a 63-character parameter name (§12.7), which
 * the 64-character value budget would cut in half and leave the author unable to find the
 * field. It is still reflected input, so it is still bounded and still sanitised.
 */
internal fun String?.truncateForErrorPath(): String = reflectSafely(MAX_REFLECTED_PATH_LENGTH)

private fun String?.reflectSafely(maxLength: Int): String {
    val raw = this ?: return "null"
    val clipped = if (raw.length <= maxLength) raw else raw.take(maxLength) + "…"
    return if (clipped.none { it.isISOControl() }) {
        clipped
    } else {
        clipped.map { if (it.isISOControl()) CONTROL_REPLACEMENT else it }.joinToString("")
    }
}
