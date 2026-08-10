package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException

/*
 * Staging-layer exceptions. Every one extends DatapipelinesException (module-structure.md
 * §4.3 — the shared base lives in `typesystem`) and carries a StagingErrorCodes code plus
 * structured `details` for the unified error response. Details never carry secrets
 * (observability.md §9): staging details hold identifiers, ordinals, and sizes only.
 */

/**
 * A source column label failed the §4.5 identifier rule, or duplicated another label in the
 * same result set (case-insensitively). Raised **before** any DDL is generated, so a bad
 * label never reaches a `CREATE TABLE` — sanitising by rename is explicitly forbidden.
 *
 * @param ordinal the 1-based position of the offending column in the result set.
 * @param label the offending label, or `null` when the driver reported a null label.
 */
class StagingInvalidColumnNameException(
    val ordinal: Int,
    val label: String?,
) : DatapipelinesException(
        code = StagingErrorCodes.INVALID_COLUMN_NAME,
        message =
            "Source column #$ordinal has an invalid or duplicate label " +
                "(${label?.let { "'$it'" } ?: "null"}); it must match [A-Za-z_][A-Za-z0-9_]{0,62} " +
                "and be unique case-insensitively. Fix the alias in the source SQL.",
        details = mapOf("ordinal" to ordinal, "label" to label),
    )

/**
 * A bare `CREATE TABLE` targeted a name already staged in this execution (staging.md §4.5).
 * Save-time uniqueness (§4.1) is the primary guard; reaching this is a defect worth surfacing
 * loudly rather than papering over by overwriting a table another node may be about to read.
 */
class StagingTableAlreadyExistsException(
    val tableName: String,
) : DatapipelinesException(
        code = StagingErrorCodes.TABLE_ALREADY_EXISTS,
        message = "Table '$tableName' is already staged in this execution; refusing to overwrite it.",
        details = mapOf("table" to tableName),
    )

/**
 * The measured staged footprint (in-process JVM heap, staging.md §8.2) exceeded the effective
 * budget. Carries the measured value and the budget so the failure is self-explaining and so
 * a test can assert the *measurement* drove the decision rather than an estimate.
 */
class StagingMemoryLimitException(
    val memoryUsedBytes: Long,
    val maxMemoryMb: Long,
) : DatapipelinesException(
        code = StagingErrorCodes.MEMORY_LIMIT_EXCEEDED,
        message =
            "Staging footprint ${memoryUsedBytes / 1024} KB exceeds the $maxMemoryMb MB budget " +
                "for this execution (measured JVM heap).",
        details =
            mapOf(
                "memory_used_bytes" to memoryUsedBytes,
                "max_memory_mb" to maxMemoryMb,
            ),
    )

/**
 * A source value exceeded the staged column's capacity on insert (staging.md §4.3) — an H2
 * SQL data-exception (class `22`, e.g. numeric out of range or value too long) surfaced as a
 * staging error rather than a raw driver exception.
 */
class StagingValueOverflowException(
    message: String,
    cause: Throwable? = null,
) : DatapipelinesException(
        code = StagingErrorCodes.VALUE_OVERFLOW,
        message = message,
        cause = cause,
    )
