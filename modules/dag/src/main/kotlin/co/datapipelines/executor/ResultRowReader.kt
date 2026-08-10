package co.datapipelines.executor

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.TypeMappers
import co.datapipelines.typesystem.TypeMappingWarning
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/** A caller result's canonical schema plus the non-fatal warnings its mapping raised. */
data class ResultSchema(
    val columns: List<ColumnSchema>,
    val warnings: List<TypeMappingWarning>,
)

/**
 * Reads a **source** `ResultSet` for egress to the result store (dag-executor.md §6.4.2 steps 1–2).
 *
 * ## Why this duplicates staging's readers
 *
 * `staging`'s `SourceValueReader` / `StagedValueReader` implement the identical two read rules,
 * and both are `internal` to that module — deliberately, since staging's public contract is
 * "hand me a cursor, I stage it", not "here are my primitives". `dag` cannot call them, so the
 * rules are re-implemented here rather than smuggled out by widening someone else's visibility.
 * The rules themselves are normative (staging §4.4/§5.3, type-system §8.4):
 *
 *  - **`STRING`-canonical columns are read with [ResultSet.getString], never `getObject`.** Several
 *    dialect mappings route binary-coded or driver-object columns (geometry as WKT, arrays,
 *    XMLType, CLOBs) to canonical `STRING`; `getObject(...).toString()` on those yields Java
 *    identity text (`[B@6d06d69c`) shipped to the client as a plausible value.
 *  - **Temporal columns are read with JDBC 4.2 `getObject(index, java.time-type)`**, never
 *    `getTimestamp`/`getDate`/`getTime`: the `java.sql` temporal types convert through the JVM
 *    default zone, and `JsonEncoder` rejects them by design.
 */
internal object ResultRowReader {
    /**
     * Derives the canonical schema from cursor metadata through [sourceDialect]'s mapper.
     *
     * `mapColumn` (not `map`) so an unrecognized source type's §8.2 warning can name its column.
     */
    fun schemaOf(
        metadata: ResultSetMetaData,
        sourceDialect: Dialect,
    ): ResultSchema {
        val mapper = TypeMappers.forDialect(sourceDialect)
        val mapped =
            (1..metadata.columnCount).map { index ->
                mapper.mapColumn(
                    name = metadata.getColumnLabel(index),
                    sqlType = metadata.getColumnType(index),
                    precision = metadata.getPrecision(index),
                    scale = metadata.getScale(index),
                    typeName = metadata.getColumnTypeName(index),
                    nullable = ColumnSchema.nullableFromJdbc(metadata.isNullable(index)),
                )
            }
        return ResultSchema(mapped.map { it.column }, mapped.flatMap { it.warnings })
    }

    /** Reads one value, typed so `JsonEncoder.encode(value, column)` accepts it. Null for SQL NULL. */
    fun readValue(
        rs: ResultSet,
        index: Int,
        column: ColumnSchema,
    ): Any? =
        when (column.type) {
            LogicalType.NULL -> {
                null
            }

            LogicalType.BOOLEAN -> {
                rs.getBoolean(index).nullIfNull(rs)
            }

            LogicalType.INTEGER -> {
                rs.getInt(index).nullIfNull(rs)
            }

            LogicalType.BIGINTEGER -> {
                rs.getLong(index).nullIfNull(rs)
            }

            // Scale omitted ⇒ approximate origin (type-system §4.1) ⇒ read as a Double.
            LogicalType.DECIMAL -> {
                if (column.isApproximateNumeric) rs.getDouble(index).nullIfNull(rs) else rs.getBigDecimal(index)
            }

            LogicalType.BIGDECIMAL -> {
                rs.getBigDecimal(index)
            }

            LogicalType.STRING -> {
                rs.getString(index)
            }

            LogicalType.BINARY -> {
                rs.getBytes(index)
            }

            LogicalType.DATE -> {
                rs.getObject(index, LocalDate::class.java)
            }

            LogicalType.TIME -> {
                rs.getObject(index, LocalTime::class.java)
            }

            LogicalType.TIMESTAMP -> {
                rs.getObject(index, OffsetDateTime::class.java)
            }
        }

    /**
     * The primitive getters return `0`/`false` for SQL NULL, so every one of them must be paired
     * with `wasNull()` — the check that turns a driver's zero back into a null.
     */
    private fun <T : Any> T.nullIfNull(rs: ResultSet): T? = if (rs.wasNull()) null else this
}
