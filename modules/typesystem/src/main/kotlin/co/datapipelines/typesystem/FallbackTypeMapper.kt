package co.datapipelines.typesystem

import java.sql.Types

/**
 * The §8.2 unknown-type policy, and the §11.2 dispatch fallback.
 *
 * Two callers, one behavior:
 *  - [TypeMappers.forDialect] returns this object for a [Dialect] value this build has
 *    no mapper for (e.g. one added to the enum ahead of its mapper). Every column then
 *    degrades to `STRING` instead of the dispatch throwing.
 *  - Every [DialectTypeMapper] delegates here for a JDBC type code its own §5.x table
 *    does not list.
 *
 * **The contract (§8.2, restated by §11.2):** unrecognized input never fails an
 * execution. It maps to canonical `STRING`, values serialize via `toString()`, and
 * exactly one `type_mapping.unknown_source_type` warning names the column and the
 * source type. The author sees the warning and usually fixes it with a `CAST` in the
 * source template.
 *
 * `Types.NULL` is still honored here (§8.1): an all-NULL column reported by the driver
 * is a known answer, not an unknown type, even when the dialect itself is unknown.
 */
object FallbackTypeMapper : IngressTypeMapper {
    override fun map(
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
    ): LogicalTypeMapping =
        if (sqlType == Types.NULL) {
            LogicalTypeMapping(LogicalType.NULL)
        } else {
            LogicalTypeMapping(LogicalType.STRING)
        }

    override fun mapColumn(
        name: String,
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
        nullable: Boolean?,
    ): MappedColumn {
        val mapping = map(sqlType, precision, scale, typeName)
        val warnings =
            if (mapping.type == LogicalType.NULL) {
                emptyList()
            } else {
                listOf(
                    TypeMappingWarning.unknownSourceType(
                        column = name,
                        sourceType = typeName.ifBlank { "JDBC type $sqlType" },
                    ),
                )
            }
        return MappedColumn(mapping.toColumnSchema(name, nullable), warnings)
    }
}
