package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.IngressTypeMapper
import com.zaxxer.hikari.HikariConfig

/**
 * Per-dialect JDBC behavior (datasources.md §4.2): driver class, URL validation, the
 * canonical type mapper, and `HikariConfig` construction.
 *
 * [buildHikariConfig] is the **single** place the two passthrough maps
 * ([DatasourceProperties.hikari] / [DatasourceProperties.jdbc]) are applied, so the
 * save-time test pool build (§5.4) and the runtime pool build (§5.2) cannot diverge —
 * whatever the validator accepts is exactly what the running pool gets.
 */
interface DialectAdapter {
    /** The dialect this adapter serves. */
    val dialect: Dialect

    /** The JDBC driver class name, resolved reflectively at pool build (never compiled against). */
    val jdbcDriverClassName: String

    /**
     * Driver-level connection-property defaults, applied before [DatasourceProperties.jdbc]
     * so callers can override them (§4.2). Values are strings — driver properties always are.
     */
    val defaultProperties: Map<String, String>

    /** JDBC column metadata → canonical types (type-system.md §5), via `TypeMappers.forDialect`. */
    val typeMapper: IngressTypeMapper

    /**
     * §7A introspection: the JDBC table types this dialect treats as **user data** — what
     * `getTables` asks for and what the tables/snapshot operations list. The SQL-standard
     * floor is `TABLE` + `VIEW`; a dialect adds the types its users actually create (Postgres:
     * partitioned/materialized/foreign tables). System catalogs that arrive under other types
     * (`SYSTEM TABLE`, `SYSTEM VIEW`) are excluded by the type vocabulary itself.
     */
    val introspectionTableTypes: List<String>
        get() = listOf("TABLE", "VIEW")

    /**
     * §7A introspection: schemas that belong to the engine, not the user — rows in these are
     * dropped from every introspection result (they would otherwise ride along on the `VIEW`
     * type and eat the snapshot cap). Declared **lowercase**; matching is case-insensitive,
     * because drivers report the standard schema variously as `INFORMATION_SCHEMA` (H2),
     * `information_schema` (Postgres, MySQL).
     */
    val introspectionSystemSchemas: Set<String>
        get() = setOf("information_schema")

    /**
     * This dialect's **refusal set** (§5.6): property keys the pinned driver would read as a class
     * name to instantiate, a file path, connect-time SQL, or a TLS-verification switch. Lowercase;
     * matching is case-insensitive.
     *
     * On the interface — not on [AbstractDialectAdapter] — precisely because §5.6 requires the
     * validation path to **fail closed**: there is no downcast that can miss, and an adapter is a
     * defect if it declares an unreviewed set. It is additionally a floor, not the authority: the
     * validator unions this with [DialectRefusalSets.forDialect]`(dialect)`, so an implementation
     * returning an empty set grants no exemption.
     */
    val refusedPropertyKeys: Set<String>

    /**
     * §7A introspection: true when this dialect's driver reports the database in the JDBC
     * **catalog** (TABLE_CAT) and leaves the schema (TABLE_SCHEM) null — Connector/J's default
     * behavior, where `jdbc:mysql://host/db` puts `db` in TABLE_CAT. Introspection then routes
     * the schema filter to the **catalog** argument of `getTables`/`getColumns` and reads
     * TABLE_CAT as the schema — otherwise the filter selects nothing and every table reports
     * a null schema.
     */
    val schemaArrivesInCatalog: Boolean
        get() = false

    /**
     * Validates a JDBC URL for this dialect (§6.1): scheme match, basic parse, and the §5.6
     * refusal guard — the same union applied to `properties.jdbc`, refusing class-loading /
     * local-file / connect-time-SQL properties and credentials smuggled into the URL (H2
     * `INIT=RUNSCRIPT`, PG `socketFactory`, MySQL `allowLoadLocalInfile`, DuckDB
     * `session_init_sql_file`, `user=`/`password=`).
     */
    fun validateJdbcUrl(url: String): ValidationResult

    /**
     * Builds a `HikariConfig` from the entity fields, [defaultProperties], and the two
     * passthrough maps. May throw a `RuntimeException` from HikariCP when a `hikari` key is
     * unknown or its value is the wrong type — the caller (test pool build) translates that
     * into [DatasourceErrorCodes.PROPERTIES_INVALID].
     *
     * Expects [Datasource.password] to already hold the **plaintext** credential (decrypted by
     * the caller for a runtime pool; supplied directly for save-time validation).
     */
    fun buildHikariConfig(datasource: Datasource): HikariConfig
}
