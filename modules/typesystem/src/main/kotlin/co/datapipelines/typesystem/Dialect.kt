package co.datapipelines.typesystem

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The seven supported source database dialects (enums.md §5; authored by
 * type-system.md §5, which holds one mapping table per value).
 *
 * ## Why this type lives in `typesystem`
 *
 * type-system.md §11.2 places `TypeMappers.forDialect(dialect: Dialect)` in this
 * module, while module-structure.md §5.4 lists `Dialect` under the `datasources`
 * public API — and §4.2 forbids `typesystem` from depending on anything internal.
 * All three cannot hold. enums.md §5 names **Type System §5** as the single
 * authoring authority for this enum, so it is declared here, at layer 0, where the
 * dispatch that needs it lives. `datasources` depends on `typesystem` and can still
 * present it as part of its own surface; nothing has to declare it twice. Reported
 * to the orchestrator as a docs fix (§5.1 gains `Dialect`, §5.4 becomes a pointer).
 *
 * Wire values are UPPER and coincide with the constant names, but the `@JsonValue`
 * mapping is explicit anyway per the enums.md serialization convention.
 *
 * Values reserved for future use (`SNOWFLAKE`, `BIGQUERY`, `REDSHIFT`) are
 * deliberately absent — enums.md: reserved values MUST NOT appear in v1 code.
 */
enum class Dialect(
    @JsonValue val wire: String,
) {
    POSTGRES("POSTGRES"),
    ORACLE("ORACLE"),
    MSSQL("MSSQL"),
    MYSQL("MYSQL"),
    H2("H2"),
    DUCKDB("DUCKDB"),
    SQLITE("SQLITE"),
    ;

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): Dialect =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown Dialect: $value")
    }
}
