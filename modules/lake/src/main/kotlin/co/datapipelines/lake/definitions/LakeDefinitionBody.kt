package co.datapipelines.lake.definitions

import co.datapipelines.typesystem.LogicalType
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types

/**
 * A managed table's definition body (lake.md §1, dp-lake design record §5.2): the schema intent (fields with stable
 * logical ids, types from the closed list, required, doc), the partition intent and the column tags. Not in the
 * body: sort order, retention/maintenance policy, Iceberg properties. The table's name and namespace are the row's,
 * never the body's (V54's `chk_lake_table_definition_versions_body`).
 *
 * Built only by [LakeDefinitionBodies.parse] (or [LakeDefinitionBodies.fromIceberg]), which validate it; the
 * constructor itself checks nothing.
 */
data class LakeDefinitionBody(
    val fields: List<LakeFieldDefinition>,
    val partition: List<LakePartitionField> = emptyList(),
    val identifierFieldIds: List<Int> = emptyList(),
) {
    fun field(id: Int): LakeFieldDefinition? = fields.firstOrNull { it.id == id }
}

/** One column of the intent. [id] is the Iceberg field id the column has, or will be given, on every environment. */
data class LakeFieldDefinition(
    val id: Int,
    val name: String,
    val type: LogicalType,
    /** DECIMAL (1..15) or BIGDECIMAL (16..38) precision; null on every other type, and on approximate DECIMAL. */
    val precision: Int? = null,
    /** The scale, 0..[precision], present exactly when [precision] is. */
    val scale: Int? = null,
    val required: Boolean = false,
    val doc: String? = null,
    val tags: List<String> = emptyList(),
) {
    /** The Iceberg type this column is stored as (lake.md §1's closed list). */
    val icebergType: Type.PrimitiveType get() = LakeTypes.icebergType(type, precision, scale)

    /** The DP type as one string — `DECIMAL(12,2)`, `DECIMAL`, `STRING` — for the semantics projection. */
    val typeLabel: String get() = if (precision != null) "${type.wire}($precision,$scale)" else type.wire
}

/** One partition field: the source column's [fieldId] and an Iceberg transform string (lake.md §1). */
data class LakePartitionField(
    val fieldId: Int,
    val transform: String,
)

/**
 * The closed type list (lake.md §1): every DP `LogicalType` but NULL, each to exactly one Iceberg primitive, and the
 * reverse for [LakeDefinitionBodies.fromIceberg]. Iceberg types outside the list (float, timestamp without zone, uuid,
 * fixed, nested types) have no DP spelling: [logicalOf] answers null for them.
 */
object LakeTypes {
    /** DECIMAL's exact range; a wider decimal is BIGDECIMAL (typesystem `LogicalType` KDoc). */
    const val MAX_DECIMAL_PRECISION = 15

    /** Iceberg's (and Parquet's) decimal ceiling. */
    const val MAX_BIGDECIMAL_PRECISION = 38

    fun icebergType(
        type: LogicalType,
        precision: Int?,
        scale: Int?,
    ): Type.PrimitiveType =
        when (type) {
            LogicalType.BOOLEAN -> {
                Types.BooleanType.get()
            }

            LogicalType.INTEGER -> {
                Types.IntegerType.get()
            }

            LogicalType.BIGINTEGER -> {
                Types.LongType.get()
            }

            LogicalType.DECIMAL -> {
                if (precision == null) Types.DoubleType.get() else Types.DecimalType.of(precision, requireNotNull(scale))
            }

            LogicalType.BIGDECIMAL -> {
                Types.DecimalType.of(requireNotNull(precision), requireNotNull(scale))
            }

            LogicalType.STRING -> {
                Types.StringType.get()
            }

            LogicalType.BINARY -> {
                Types.BinaryType.get()
            }

            LogicalType.DATE -> {
                Types.DateType.get()
            }

            LogicalType.TIME -> {
                Types.TimeType.get()
            }

            LogicalType.TIMESTAMP -> {
                Types.TimestampType.withZone()
            }

            LogicalType.NULL -> {
                throw IllegalArgumentException("NULL is not a column type of a managed lake table")
            }
        }

    /** The DP spelling of an Iceberg [type] as (type, precision, scale), or null when the closed list has none. */
    fun logicalOf(type: Type): Triple<LogicalType, Int?, Int?>? =
        when (type) {
            is Types.BooleanType -> {
                Triple(LogicalType.BOOLEAN, null, null)
            }

            is Types.IntegerType -> {
                Triple(LogicalType.INTEGER, null, null)
            }

            is Types.LongType -> {
                Triple(LogicalType.BIGINTEGER, null, null)
            }

            is Types.DoubleType -> {
                Triple(LogicalType.DECIMAL, null, null)
            }

            is Types.DecimalType -> {
                Triple(
                    if (type.precision() <= MAX_DECIMAL_PRECISION) LogicalType.DECIMAL else LogicalType.BIGDECIMAL,
                    type.precision(),
                    type.scale(),
                )
            }

            is Types.StringType -> {
                Triple(LogicalType.STRING, null, null)
            }

            is Types.BinaryType -> {
                Triple(LogicalType.BINARY, null, null)
            }

            is Types.DateType -> {
                Triple(LogicalType.DATE, null, null)
            }

            is Types.TimeType -> {
                Triple(LogicalType.TIME, null, null)
            }

            is Types.TimestampType -> {
                if (type.shouldAdjustToUTC()) Triple(LogicalType.TIMESTAMP, null, null) else null
            }

            else -> {
                null
            }
        }
}
