package co.datapipelines.typesystem

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The eight supported source database dialects (enums.md §5; authored by
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

    /**
     * A **lake**: object storage read in place (Parquet, Iceberg), with DuckDB as the engine
     * (datasources.md §4.1/§4.2A, round 087). Named for the offering — the owner named it
     * `dp-lake` on 2026-09-07 — not for the engine, which the docs name once.
     *
     * ## Why a distinct dialect and not a `mode` on [DUCKDB]
     *
     * The two need DIFFERENT §5.6 postures: the embedded adapter locks
     * `enable_external_access = false` (no filesystem, no network — the load-bearing control that
     * stops author SQL loading a native extension inside the app's own process), and a lake
     * cannot read S3 with that lock on. A per-datasource `mode` would therefore make the refusal
     * set and the connect-time hardening a function of ROW DATA.
     *
     * That is precisely what §5.6 forbids: `DialectRefusalSets.forDialect` is an exhaustive
     * `when` over this enum *"so there is no code path that can yield an empty set for an
     * unrecognized dialect or a non-conforming adapter, because the lookup never consults the
     * adapter instance."* A mode flag puts the adapter instance — and behind it a database row —
     * back in that lookup. Every other per-dialect total map (`TypeMappers.forDialect`,
     * `JdbcDrivers`, `DialectAdapters`, the `chk_datasource_dialect` CHECK, the MCP tool's enum)
     * is keyed the same way, so a mode would need a second dispatch key threaded through all of
     * them. A dialect value is one line in each.
     */
    LAKE("LAKE"),
    ;

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): Dialect =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown Dialect: $value")
    }
}
