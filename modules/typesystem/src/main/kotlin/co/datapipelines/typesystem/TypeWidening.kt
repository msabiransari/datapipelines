package co.datapipelines.typesystem

/**
 * A type as a value flowing somewhere must be judged by: the canonical type with its precision
 * and scale ([ColumnSchema]'s descriptor without the name and nullability).
 */
data class TypeDescriptor(
    val type: LogicalType,
    val precision: Int? = null,
    val scale: Int? = null,
)

/**
 * The lossless-widening rule of parameter-engine record §6.4 (P11, P31): may a value of type
 * [from] flow into a declaration of type [to] without losing anything?
 *
 * **Same type ⇒ accept; an explicitly listed lossless widening ⇒ accept; anything lossy,
 * ambiguous or value-inferred ⇒ refuse.** Decided from DESCRIPTORS — result-set metadata on the
 * engine's selector path — never by inspecting values; an author changes the outcome with an
 * explicit `CAST`. The value-level twin is [ParameterValueValidator], which judges every value
 * against the declared precision and scale, so a driver that under-reports its metadata still
 * cannot slip a value past the declaration.
 *
 * A decimal widening must keep **both** the fractional digits and the integer digits: for
 * `(p,s) → (P,S)`, `S ≥ s` AND `P − S ≥ p − s`. `P ≥ p, S ≥ s` alone is not enough —
 * `DECIMAL(6,2)` into `(6,4)` passes it and cannot hold `9999.99` (Astra's counterexample).
 *
 * The record's table names `DECIMAL(p,s)` as the exact source; a `BIGDECIMAL(p,s)` source is
 * read the same way (it is exact with a declared scale), except that `BIGDECIMAL → DECIMAL` is
 * refused outright, as the table lists it lossy.
 */
object TypeWidening {
    /** Int32's decimal digits: an `INTEGER` fits a decimal only with at least this many integer digits. */
    private const val INT32_DIGITS = 10

    fun isLossless(
        from: TypeDescriptor,
        to: TypeDescriptor,
    ): Boolean =
        when {
            // An all-null column carries no type to widen from (type-system §8.1): the author casts.
            from.type == LogicalType.NULL || to.type == LogicalType.NULL -> false

            from == to -> true

            else -> widens(from, to)
        }

    /** The listed widenings, by source type; every other pair is refused. */
    private fun widens(
        from: TypeDescriptor,
        to: TypeDescriptor,
    ): Boolean =
        when (from.type) {
            LogicalType.INTEGER -> {
                to.type == LogicalType.BIGINTEGER || (to.isExactDecimal() && (to.integerDigits()?.let { it >= INT32_DIGITS } ?: true))
            }

            LogicalType.BIGINTEGER -> {
                to.type == LogicalType.BIGDECIMAL && to.precision == null
            }

            LogicalType.DECIMAL, LogicalType.BIGDECIMAL -> {
                val narrowsToDecimal = from.type == LogicalType.BIGDECIMAL && to.type == LogicalType.DECIMAL
                !narrowsToDecimal && from.isExactDecimal() && to.isExactDecimal() && decimalWidens(from, to)
            }

            else -> {
                false
            }
        }

    /** Both exact (scales declared): keep the fraction (`S ≥ s`) AND the integer part (`P − S ≥ p − s`). */
    private fun decimalWidens(
        from: TypeDescriptor,
        to: TypeDescriptor,
    ): Boolean {
        val keepsFraction = (to.scale ?: 0) >= (from.scale ?: 0)
        val toDigits = to.integerDigits()
        val fromDigits = from.integerDigits()
        // An unbounded target holds any integer part; an unbounded source fits only another unbounded one.
        val keepsIntegerPart = toDigits == null || (fromDigits != null && toDigits >= fromDigits)
        return keepsFraction && keepsIntegerPart
    }

    /** Exact decimal: a `DECIMAL` with a declared scale (an omitted one is a float, §7.3), or a `BIGDECIMAL`. */
    private fun TypeDescriptor.isExactDecimal(): Boolean =
        (type == LogicalType.DECIMAL && scale != null) || (type == LogicalType.BIGDECIMAL && scale != null)

    /** `P − S`, or null when precision is unbounded (a `BIGDECIMAL` with no precision, type-system §4). */
    private fun TypeDescriptor.integerDigits(): Int? = precision?.let { it - (scale ?: 0) }
}
