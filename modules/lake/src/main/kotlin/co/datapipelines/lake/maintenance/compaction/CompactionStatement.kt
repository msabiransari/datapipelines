package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.append.AppendColumnBinding
import co.datapipelines.lake.append.AppendOrder
import co.datapipelines.pipeline.LakeAppendInput
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types

/**
 * D6's one statement for a rewrite group (brief 969 D6; `DeliveryMaterializer`'s shape): read the group's files BY
 * FIELD ID through `read_parquet`'s `schema` map, ordered in DuckDB by the table's sort order, and hand the rows to the
 * Java writer.
 *
 * ```
 * SELECT c<id>, … FROM read_parquet(?, schema = MAP {<id>: {name: 'c<id>', type: '<type>', default_value: NULL}, …},
 *   hive_partitioning = false) ORDER BY <position> ASC NULLS FIRST, …
 * ```
 *
 * The map is the table's CURRENT schema: a file written before an added column reads it as null, a narrower type is
 * widened by the engine. The text is built from integers and one closed vocabulary of types only ([duckDbType]); no
 * column name, path or definition text is ever part of it, and the file list is the statement's one bound parameter.
 * The order is 968's ([AppendOrder.keys], which refuses a floating sort column; [LakeAppendInput.orderByClause] renders
 * it); a legacy table gets no `ORDER BY`, is merged only, and its files are stamped unsorted ([stamp]).
 *
 * [bindings] are the append path's: column `i` of the select list (1-based) is the schema's `i`-th top-level field,
 * read by `AppendValues` into the writer's record.
 */
class CompactionStatement private constructor(
    val sql: String,
    val schema: Schema,
    val bindings: List<AppendColumnBinding>,
    val stamp: SortOrder,
) {
    companion object {
        fun of(
            schema: Schema,
            order: SortOrder,
        ): CompactionStatement {
            val fields = schema.columns()
            require(fields.isNotEmpty()) { "a table schema has at least one column" }
            val bindings =
                fields.mapIndexed { index, field ->
                    val type = duckDbType(field.type())
                    AppendColumnBinding(field, index + 1, field.type().asPrimitiveType(), type)
                }
            val appendOrder = AppendOrder.of(order, bindings)
            val select = fields.joinToString(", ") { "c${it.fieldId()}" }
            val map =
                fields.joinToString(", ", prefix = "MAP {", postfix = "}") { field ->
                    "${field.fieldId()}: {name: 'c${field.fieldId()}', type: '${duckDbType(field.type())}', default_value: NULL}"
                }
            val sql =
                "SELECT $select FROM read_parquet(?, schema = $map, hive_partitioning = false)" +
                    LakeAppendInput.orderByClause(appendOrder.keys, fields.size)
            return CompactionStatement(sql, schema, bindings, appendOrder.stamp)
        }

        /**
         * The closed vocabulary: the Iceberg types a managed column can hold (the append path's, `AppendValues`) to the
         * engine's type names. Any other type is a defect: the definition rules keep it from existing.
         */
        @Suppress("CyclomaticComplexMethod") // D6: one closed `when`, a branch per Iceberg type the lake admits
        fun duckDbType(type: Type): String =
            when (type.typeId()) {
                Type.TypeID.BOOLEAN -> "BOOLEAN"
                Type.TypeID.INTEGER -> "INTEGER"
                Type.TypeID.LONG -> "BIGINT"
                Type.TypeID.FLOAT -> "FLOAT"
                Type.TypeID.DOUBLE -> "DOUBLE"
                Type.TypeID.DECIMAL -> (type as Types.DecimalType).let { "DECIMAL(${it.precision()},${it.scale()})" }
                Type.TypeID.STRING -> "VARCHAR"
                Type.TypeID.BINARY -> "BLOB"
                Type.TypeID.DATE -> "DATE"
                Type.TypeID.TIME -> "TIME"
                Type.TypeID.TIMESTAMP -> if ((type as Types.TimestampType).shouldAdjustToUTC()) TIMESTAMPTZ else "TIMESTAMP"
                else -> error("a managed column of type $type cannot be compacted")
            }

        /** `AppendValues` reads a value of this native type as an instant with an offset. */
        const val TIMESTAMPTZ = "TIMESTAMPTZ"
    }
}
