package co.datapipelines.scripting

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.JsonEncoder
import co.datapipelines.typesystem.LogicalType
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The type gate (transform-nodes design §5.3, R1): every value a transform returns is
 * checked against the contract's declared output columns — the key set must equal the
 * column set, each value must fit its column's wire form, and numerics follow the
 * record's table. Pure: no I/O, no clock, no configuration read.
 *
 * ## Nullability is the CONTRACT's, not ColumnSchema's tri-state
 *
 * `ColumnSchema.nullable` is `Boolean?` whose absence means "the driver would not say"
 * and must never be read as false — that rule is for INGRESS schemas. At this boundary
 * the columns are the transform's own declared contract, the contract's default is
 * `nullable: false`, and the gate reads absence as exactly that: a null is refused
 * unless the declared column positively says `nullable: true`. Deliberately NOT the
 * ColumnSchema KDoc's rule; the two coexist because they answer different questions
 * (what did the driver report vs what did the author allow).
 *
 * ## R1's numeric rules
 *
 *  - `INTEGER` — a JSON number with no fractional part within int32; outside is
 *    [GateRefusal.PrecisionLost].
 *  - `BIGINTEGER` — a JSON string parsing as an int64 (the wire form), or a JSON
 *    number that is an integer within the double-safe range (2^53); anything else is
 *    [GateRefusal.PrecisionLost].
 *  - `DECIMAL(p, s)` — a JSON number, ROUNDED HALF-EVEN to scale `s` (R1: `0.1 * 3`
 *    becomes `0.30`), then checked against `p`; overflow of `p` after rounding is
 *    [GateRefusal.PrecisionLost].
 *  - `DECIMAL` without scale (approximate origin) — stored as-is.
 *  - `BIGDECIMAL(p, s)` — a JSON string, EXACT: scale above `s` or integer digits
 *    above `p` is [GateRefusal.PrecisionLost], never rounded; a value WITHIN the
 *    declaration is normalised to the declared scale (`"12.5"` → `"12.50"`). Note the
 *    direction: excess scale means MORE digits than declared, so `"12.505"` under
 *    scale 2 is lost, while `"12.5"` normalises UP to two places.
 *  - `DATE`/`TIME`/`TIMESTAMP` — strings normalised through the type system's
 *    canonical form (the egress encoder's formatting, reused — never a second one);
 *    a string that does not parse is [GateRefusal.ValueTypeMismatch].
 *  - `BINARY` and `NULL` as a declared type are refused at construction — 7b refuses
 *    them earlier at the template; this is the belt.
 */
class TypeGate private constructor(
    private val columns: List<ColumnSchema>,
    private val maxStringBytes: Long?,
) {
    private val byName: Map<String, ColumnSchema> = columns.associateBy { it.name }

    init {
        val unsupported = columns.filter { it.type == LogicalType.BINARY || it.type == LogicalType.NULL }
        require(unsupported.isEmpty()) {
            "the gate refuses BINARY and NULL as declared types (transform-nodes design §5.3), " +
                "columns: ${unsupported.map { it.name }}"
        }
    }

    /**
     * Checks one output row against the columns. Returns the normalised row — same key
     * set, values in their storable form (rounded `BigDecimal` for DECIMAL, scaled
     * string for BIGDECIMAL, canonical string for DATE/TIME/TIMESTAMP) — or the FIRST
     * refusal in column order (shape refusal first; a wrong shape makes per-value
     * verdicts meaningless).
     */
    fun gateRow(
        row: Map<String, Any?>,
        rowNumber: Int,
        batch: Int? = null,
    ): GateResult {
        val missing = byName.keys.filterNot { it in row.keys }
        val extra = row.keys.filterNot { it in byName.keys }
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            return GateResult.Refuse(
                GateRefusal.RowShapeMismatch(batch, rowNumber, missing.sorted(), extra.sorted()),
            )
        }
        val out = LinkedHashMap<String, Any?>(row.size)
        for (column in columns) {
            when (val verdict = checkValue(row[column.name], column, rowNumber)) {
                is GateResult.Refuse -> return verdict
                is GateResult.Pass -> out[column.name] = verdict.value
            }
        }
        return GateResult.Pass(out)
    }

    /** Checks one single-value output (the `value` mode's declared type). */
    fun gateValue(
        value: Any?,
        column: ColumnSchema,
    ): GateResult = checkValue(value, column, null)

    /**
     * Checks an object-valued output (`kind: object`, R4): the value must be a JSON
     * object whose canonical form fits [maxBytes]. Keys readable by a downstream
     * TRANSFORM only — that rule is 7c's to enforce; the gate bounds the bytes.
     */
    fun gateObject(
        value: Any?,
        maxBytes: Long,
    ): GateResult {
        if (value !is Map<*, *>) {
            return GateResult.Refuse(
                GateRefusal.ValueTypeMismatch(null, OBJECT_COLUMN, "object", typeName(value)),
            )
        }
        val bytes =
            CanonicalJson
                .write(value)
                .toByteArray(Charsets.UTF_8)
                .size
                .toLong()
        if (bytes > maxBytes) {
            return GateResult.Refuse(GateRefusal.ValueTooLarge(null, OBJECT_COLUMN, bytes))
        }
        return GateResult.Pass(value)
    }

    /** One value's verdict against [column]; the row number rides on the refusals. */
    private fun checkValue(
        value: Any?,
        column: ColumnSchema,
        rowNumber: Int?,
    ): GateResult {
        if (value == null) {
            return if (column.nullable == true) {
                GateResult.Pass(null)
            } else {
                GateResult.Refuse(
                    GateRefusal.ValueTypeMismatch(rowNumber, column.name, column.type.wire, "null"),
                )
            }
        }
        return when (column.type) {
            LogicalType.BOOLEAN -> requireType(value is Boolean, column, rowNumber, "boolean", value)

            LogicalType.STRING -> checkString(value, column, rowNumber)

            LogicalType.INTEGER -> checkInteger(value, column, rowNumber)

            LogicalType.BIGINTEGER -> checkBigInteger(value, column, rowNumber)

            LogicalType.DECIMAL -> checkDecimal(value, column, rowNumber)

            LogicalType.BIGDECIMAL -> checkBigDecimal(value, column, rowNumber)

            LogicalType.DATE -> checkTemporal(value, column, rowNumber)

            LogicalType.TIME -> checkTemporal(value, column, rowNumber)

            LogicalType.TIMESTAMP -> checkTemporal(value, column, rowNumber)

            LogicalType.BINARY, LogicalType.NULL -> throw IllegalArgumentException(
                "column '${column.name}' declares ${column.type.wire}, which the gate refuses at " +
                    "construction — this branch exists for the compiler's exhaustiveness",
            )
        }
    }

    private fun checkString(
        value: Any?,
        column: ColumnSchema,
        rowNumber: Int?,
    ): GateResult {
        if (value !is String) {
            return refuseType(column, rowNumber, "string", value)
        }
        val bytes = value.toByteArray(Charsets.UTF_8).size.toLong()
        if (maxStringBytes != null && bytes > maxStringBytes) {
            return GateResult.Refuse(GateRefusal.ValueTooLarge(rowNumber, column.name, bytes))
        }
        return GateResult.Pass(value)
    }

    private fun checkInteger(
        value: Any?,
        column: ColumnSchema,
        rowNumber: Int?,
    ): GateResult {
        val number =
            value as? Number
                ?: return refuseType(column, rowNumber, "number", value)
        val decimal = BigDecimal(number.toString())
        if (decimal.stripTrailingZeros().scale() > 0) {
            return refuseType(column, rowNumber, "integer number", value)
        }
        if (decimal < INT32_MIN || decimal > INT32_MAX) {
            return GateResult.Refuse(
                GateRefusal.PrecisionLost(rowNumber, column.name, "outside int32"),
            )
        }
        return GateResult.Pass(number)
    }

    private fun checkBigInteger(
        value: Any?,
        column: ColumnSchema,
        rowNumber: Int?,
    ): GateResult {
        val verdict =
            when (value) {
                is String -> {
                    when (val parsed = runCatching { BigInteger(value) }.getOrNull()) {
                        null -> {
                            refuseType(column, rowNumber, "int64 string", value)
                        }

                        else -> {
                            if (parsed.bitLength() > MAX_INT64_BITS) {
                                refuseType(column, rowNumber, "int64 string", value)
                            } else {
                                GateResult.Pass(parsed.toString())
                            }
                        }
                    }
                }

                is Number -> {
                    val decimal = BigDecimal(value.toString())
                    when {
                        decimal.stripTrailingZeros().scale() > 0 -> {
                            refuseType(column, rowNumber, "integer number", value)
                        }

                        decimal.abs() > DOUBLE_SAFE_MAX -> {
                            GateResult.Refuse(
                                GateRefusal.PrecisionLost(rowNumber, column.name, "exceeds the engine's exact range (2^53)"),
                            )
                        }

                        else -> {
                            GateResult.Pass(decimal.toBigInteger().toString())
                        }
                    }
                }

                else -> {
                    refuseType(column, rowNumber, "int64 string or number", value)
                }
            }
        return verdict
    }

    private fun checkDecimal(
        value: Any?,
        column: ColumnSchema,
        rowNumber: Int?,
    ): GateResult {
        val number =
            value as? Number
                ?: return refuseType(column, rowNumber, "number", value)
        val declaredScale =
            column.scale
                ?: return GateResult.Pass(number)
        val rounded = BigDecimal(number.toString()).setScale(declaredScale, RoundingMode.HALF_EVEN)
        val precision = column.precision ?: UNREACHABLE_PRECISION
        if (rounded.precision() > precision) {
            return GateResult.Refuse(
                GateRefusal.PrecisionLost(rowNumber, column.name, "does not fit DECIMAL($precision, $declaredScale)"),
            )
        }
        return GateResult.Pass(rounded)
    }

    private fun checkBigDecimal(
        value: Any?,
        column: ColumnSchema,
        rowNumber: Int?,
    ): GateResult {
        val verdict =
            when {
                value !is String -> {
                    refuseType(column, rowNumber, "decimal string", value)
                }

                else -> {
                    when (val parsed = runCatching { BigDecimal(value) }.getOrNull()) {
                        null -> refuseType(column, rowNumber, "decimal string", value)
                        else -> checkBigDecimalExact(parsed, column, rowNumber)
                    }
                }
            }
        return verdict
    }

    /** The exactness half of BIGDECIMAL: no rounding, ever (R1) — normalise or refuse. */
    private fun checkBigDecimalExact(
        parsed: BigDecimal,
        column: ColumnSchema,
        rowNumber: Int?,
    ): GateResult {
        val declaredScale =
            column.scale
                ?: return GateResult.Pass(parsed.toPlainString())
        val scaled =
            scaledOrLost(parsed, declaredScale)
                ?: return GateResult.Refuse(
                    GateRefusal.PrecisionLost(
                        rowNumber,
                        column.name,
                        "scale ${parsed.scale()} exceeds the declared $declaredScale",
                    ),
                )
        val precision = column.precision ?: UNREACHABLE_PRECISION
        return if (scaled.precision() > precision) {
            GateResult.Refuse(
                GateRefusal.PrecisionLost(rowNumber, column.name, "does not fit BIGDECIMAL($precision, $declaredScale)"),
            )
        } else {
            GateResult.Pass(scaled.toPlainString())
        }
    }

    /**
     * The value normalised to the declared scale, or null when its scale already
     * exceeds the declaration — the excess-digits case that is `precision_lost`, never
     * rounded (R1).
     */
    private fun scaledOrLost(
        parsed: BigDecimal,
        declaredScale: Int,
    ): BigDecimal? =
        if (parsed.scale() > declaredScale) {
            null
        } else {
            parsed.setScale(declaredScale, RoundingMode.UNNECESSARY)
        }

    /** Parses the string, then re-encodes through the type system's canonical form. */
    private fun checkTemporal(
        value: Any?,
        column: ColumnSchema,
        rowNumber: Int?,
    ): GateResult {
        if (value !is String) {
            return refuseType(column, rowNumber, "${column.type.wire} string", value)
        }
        val canonical =
            when (column.type) {
                LogicalType.DATE -> {
                    runCatching { JsonEncoder.encode(LocalDate.parse(value), column) }.getOrNull()
                }

                LogicalType.TIME -> {
                    runCatching { JsonEncoder.encode(LocalTime.parse(value), column) }.getOrNull()
                }

                LogicalType.TIMESTAMP -> {
                    runCatching { JsonEncoder.encode(parseTimestamp(value), column) }.getOrNull()
                }

                else -> {
                    null
                }
            } ?: return refuseType(column, rowNumber, "ISO-8601 ${column.type.wire} string", value)
        return GateResult.Pass(canonical)
    }

    /** A zone designator wins; a bare local timestamp is read as UTC (the canonical form's rule). */
    private fun parseTimestamp(value: String) =
        runCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrElse {
                java.time.LocalDateTime
                    .parse(value)
                    .toInstant(ZoneOffset.UTC)
            }

    private fun refuseType(
        column: ColumnSchema,
        rowNumber: Int?,
        expected: String,
        actual: Any?,
    ): GateResult =
        GateResult.Refuse(
            GateRefusal.ValueTypeMismatch(rowNumber, column.name, expected, typeName(actual)),
        )

    private fun requireType(
        ok: Boolean,
        column: ColumnSchema,
        rowNumber: Int?,
        expected: String,
        actual: Any?,
    ): GateResult = if (ok) GateResult.Pass(actual) else refuseType(column, rowNumber, expected, actual)

    private fun typeName(value: Any?): String = value?.javaClass?.simpleName ?: "null"

    /** The gate's verdict for one value: the storable form, or the structured refusal. */
    sealed interface GateResult {
        /** The value/row in its storable form. */
        data class Pass(
            val value: Any?,
        ) : GateResult

        /** The structured refusal the caller maps to its catalog code. */
        data class Refuse(
            val refusal: GateRefusal,
        ) : GateResult
    }

    companion object {
        private const val OBJECT_COLUMN = "<object>"
        private const val UNREACHABLE_PRECISION = Int.MAX_VALUE
        private const val MAX_INT64_BITS = 63
        private val INT32_MIN = BigDecimal.valueOf(Int.MIN_VALUE.toLong())
        private val INT32_MAX = BigDecimal.valueOf(Int.MAX_VALUE.toLong())

        /** The IEEE 754 double-safe integer bound, 2^53 — the engine's exact range. */
        private val DOUBLE_SAFE_MAX = BigInteger.TWO.pow(53).let { BigDecimal(it) }

        /** Builds a gate over the contract's declared output columns, in order. */
        fun over(
            columns: List<ColumnSchema>,
            maxStringBytes: Long? = null,
        ): TypeGate = TypeGate(columns, maxStringBytes)
    }
}
