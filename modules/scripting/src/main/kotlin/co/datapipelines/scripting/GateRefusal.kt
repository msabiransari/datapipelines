package co.datapipelines.scripting

/**
 * One refusal of the type gate (transform-nodes design §5.3). The payload is the
 * structured detail the caller maps to its catalog code (`row_shape_mismatch`,
 * `value_type_mismatch`, `precision_lost`, `value_too_large`); row numbers are the
 * 1-based position in the caller's batch, or null outside row mode.
 */
sealed interface GateRefusal {
    /** The row's key set is not exactly the column set (§5.3: with batch and row). */
    data class RowShapeMismatch(
        val batch: Int?,
        val row: Int,
        val missing: List<String>,
        val extra: List<String>,
    ) : GateRefusal

    /** A value does not fit its column's wire form (type-system.md §3). */
    data class ValueTypeMismatch(
        val row: Int?,
        val column: String,
        val expected: String,
        val actual: String,
    ) : GateRefusal

    /**
     * The value fits the wire form but not the declared precision — magnitude overflow
     * for DECIMAL (R1: rounding to scale happens FIRST, this is what survives it) and
     * ANY lost digit for BIGDECIMAL (excess scale or excess integer digits — exactness
     * is the type's whole point).
     */
    data class PrecisionLost(
        val row: Int?,
        val column: String,
        val detail: String,
    ) : GateRefusal

    /** A string (or object) exceeds the declared byte cap (§4.3's `value_too_large`). */
    data class ValueTooLarge(
        val row: Int?,
        val column: String,
        val bytes: Long,
    ) : GateRefusal
}
