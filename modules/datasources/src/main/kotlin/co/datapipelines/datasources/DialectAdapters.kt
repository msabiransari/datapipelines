package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.IngressTypeMapper
import co.datapipelines.typesystem.TypeMappers
import com.zaxxer.hikari.HikariConfig
import java.util.Properties

/**
 * Shared [DialectAdapter] behavior. A concrete adapter declares only what actually differs
 * per dialect — its [Dialect] and the URL [subProtocol] — and inherits its §5.6 refusal set,
 * identical URL validation, and identical `HikariConfig` construction, which is the invariant
 * §4.2 relies on (save-time and runtime pools are built the same way).
 */
abstract class AbstractDialectAdapter(
    final override val dialect: Dialect,
    /** The expected `jdbc:<subProtocol>:` scheme (e.g. `postgresql`, `sqlserver`). */
    private val subProtocol: String,
) : DialectAdapter {
    final override val jdbcDriverClassName: String get() = JdbcDrivers.classNameFor(dialect)

    override val defaultProperties: Map<String, String> = emptyMap()

    final override val typeMapper: IngressTypeMapper get() = TypeMappers.forDialect(dialect)

    /** The §5.6 set for this dialect, resolved by the enum-total lookup — never adapter-local state. */
    final override val refusedPropertyKeys: Set<String> get() = DialectRefusalSets.forDialect(dialect)

    final override fun validateJdbcUrl(url: String): ValidationResult =
        JdbcUrlGuard.validate(url, subProtocol, RefusedPropertyKeys.forDialect(dialect, this))

    final override fun buildHikariConfig(datasource: Datasource): HikariConfig {
        // §5.6 / DS-SEC-10: a pool is never built on a silently-empty credential. The save-time
        // check (§5.4) that legitimately has no password — a PUT that omits it — must pass an
        // explicit placeholder (DatasourceValidator.VALIDATION_ONLY_PASSWORD) rather than let a
        // null slip through to a real connection attempt.
        val password =
            requireNotNull(datasource.password) {
                "datasource '${datasource.name}' has no plaintext password to build a pool with"
            }

        // `hikari.*` goes through the Properties constructor so HikariCP resolves each name
        // reflectively — an unknown name or a value that will not parse to the setter's type
        // throws HERE, which is exactly the save-time signal §5.4 wants.
        val hikariProps = Properties()
        datasource.properties.hikari.forEach { (key, value) ->
            hikariProps.setProperty(key, value?.toString() ?: "")
        }
        val config = HikariConfig(hikariProps)

        // §5's documented default. HikariCP's own is `maximumPoolSize` (10 by default) — 5x the
        // number this spec publishes — so leaving it unset would silently contradict §5. Applied
        // only when the caller did not set it: 0 is a legitimate "keep nothing warm" choice, so
        // the test is key-presence, not value. (HikariCP resolves names case-sensitively, so a
        // mis-cased key never reaches here — the ignoreCase match is belt-and-braces.)
        if (datasource.properties.hikari.keys
                .none { it.equals("minimumIdle", ignoreCase = true) }
        ) {
            config.minimumIdle = DEFAULT_MINIMUM_IDLE
        }

        // Server-managed fields — derived from the entity and adapter, never from properties.*.
        config.jdbcUrl = datasource.jdbcUrl
        config.username = datasource.username
        config.password = password
        config.driverClassName = jdbcDriverClassName
        config.poolName = "ds-${datasource.name}"
        // Note: queryTimeoutSeconds is an execution-layer policy (§5.5), applied per-statement
        // by the executor — deliberately NOT a pool or connection property here.

        // Driver defaults first, then explicit jdbc.* overrides them (§4.2).
        defaultProperties.forEach { (key, value) -> config.addDataSourceProperty(key, value) }
        datasource.properties.jdbc.forEach { (key, value) ->
            config.addDataSourceProperty(key, value?.toString() ?: "")
        }
        return config
    }

    companion object {
        /** datasources.md §5: the server's documented `minimumIdle` default. */
        const val DEFAULT_MINIMUM_IDLE = 2
    }
}

/**
 * Postgres — the one adapter whose introspection vocabulary differs (datasources.md §7A):
 * users create partitioned tables, materialized views and foreign tables, and the engine's own
 * catalogs (`pg_catalog`, `information_schema`) report their contents under the plain `VIEW`
 * type, so the type vocabulary alone cannot keep them out.
 */
class PostgresDialectAdapter : AbstractDialectAdapter(Dialect.POSTGRES, "postgresql") {
    override val introspectionTableTypes: List<String> =
        listOf("TABLE", "VIEW", "PARTITIONED TABLE", "MATERIALIZED VIEW", "FOREIGN TABLE")

    override val introspectionSystemSchemas: Set<String> = setOf("pg_catalog", "information_schema")
}

class OracleDialectAdapter : AbstractDialectAdapter(Dialect.ORACLE, "oracle") {
    // The instance's administrative schemas ship as ordinary TABLE/VIEW rows in ALL_/DBA_
    // listings; the type vocabulary cannot keep them out of a user's listing. A FLOOR,
    // deliberately known-incomplete: these are the schemas every Oracle install maintains;
    // site-specific engine schemas (Spatial, Text, Java VM options beyond MDSYS/CTXSYS) are
    // additions, not omissions. No `information_schema` — Oracle has no such schema. `apex_*`
    // is the one prefix entry: Oracle versions its APEX schemas (APEX_220200, APEX_240100, …),
    // so no exact-name list could enumerate them.
    override val introspectionSystemSchemas: Set<String> =
        setOf(
            "sys",
            "system",
            "outln",
            "xdb",
            "ctxsys",
            "mdsys",
            "ordsys",
            "dbsnmp",
            "wmsys",
            "audsys",
            "olapsys",
            "xs\$null",
            "apex_*",
        )
}

class MssqlDialectAdapter : AbstractDialectAdapter(Dialect.MSSQL, "sqlserver") {
    // SQL Server hides `sys` (the resource-DB views surface under it) beside INFORMATION_SCHEMA,
    // and every database carries the built-in fixed-role/special schemas (db_owner,
    // db_accessadmin, db_securityadmin, db_ddladmin, db_backupoperator, db_datareader,
    // db_datawriter, db_denydatareader, db_denydatawriter, guest) — they list as ordinary
    // schemas an agent would then walk. A FLOOR, deliberately known-incomplete like Oracle's:
    // these are the schemas every SQL Server database maintains; site-specific server-level
    // schemas are additions, not omissions. `dbo` is deliberately ABSENT — it is the
    // database's default USER schema, not an engine schema.
    override val introspectionSystemSchemas: Set<String> =
        setOf(
            "information_schema",
            "sys",
            "db_owner",
            "db_accessadmin",
            "db_securityadmin",
            "db_ddladmin",
            "db_backupoperator",
            "db_datareader",
            "db_datawriter",
            "db_denydatareader",
            "db_denydatawriter",
            "guest",
        )
}

/**
 * MySQL — the catalog-routing dialect (datasources.md §7A): Connector/J defaults put the
 * database in TABLE_CAT and leave TABLE_SCHEM null, so introspection routes the schema filter
 * to the catalog argument and reads TABLE_CAT as the schema (see
 * [DialectAdapter.schemaArrivesInCatalog]).
 *
 * The `mysql`, `performance_schema` and `sys` schemas must be excluded **by name**: Connector/J
 * reports their contents as ordinary TABLE/VIEW rows, so the table-type vocabulary — which the
 * default exclusion story leans on for `SYSTEM TABLE`/`SYSTEM VIEW` types — cannot catch them.
 */
class MysqlDialectAdapter : AbstractDialectAdapter(Dialect.MYSQL, "mysql") {
    override val schemaArrivesInCatalog: Boolean = true

    override val introspectionSystemSchemas: Set<String> =
        setOf("information_schema", "mysql", "performance_schema", "sys")
}

class H2DialectAdapter : AbstractDialectAdapter(Dialect.H2, "h2")

/**
 * DuckDB — an **embedded, in-process** engine, hardened at the adapter per §5.6 (v1.8).
 *
 * DuckDB runs inside the server JVM, so author-authored SQL against a DuckDB datasource executes
 * *in this process*: a loaded native extension is arbitrary code in the pipeline server, not in a
 * remote database. The §5.6 refusal set governs `properties.jdbc` and `jdbc_url` keys, but it
 * cannot reach this vector at all — DuckDB **autoloads** known and community extensions with no
 * property involvement whatsoever, because `allow_community_extensions` and
 * `autoload_known_extensions` ship as `true`. Containment therefore has to happen at connect.
 *
 * ## What actually holds the line
 *
 * Only **three** of the five survive a session-SQL override, and claiming otherwise would
 * misdescribe the defense. Verified against duckdb_jdbc 1.5.5.1 by [EmbeddedDialectBehaviorTest]:
 *
 *  - `allow_unsigned_extensions`, `allow_community_extensions` and `enable_external_access` are
 *    **runtime-locked** — a `SET … = true` from author SQL fails with *"cannot change … while
 *    database is running"* / *"cannot enable external access while database is running"*.
 *  - `autoload_known_extensions` and `autoinstall_known_extensions` **are** settable at runtime,
 *    and are **inert**: they only decide whether DuckDB *attempts* an autoload. With
 *    `enable_external_access=false` locked on there is no filesystem and no network, so
 *    `INSTALL` / `LOAD` / `ATTACH` / `read_csv` / `COPY` fail regardless — the test flips both
 *    toggles back to `true` and proves every load path stays closed.
 *
 * So `enable_external_access=false` is the load-bearing control and the other four are defense in
 * depth. (A `LOAD json` *does* succeed: that extension is statically linked into the pinned jar
 * rather than fetched — DuckDB's own shipped code with no attacker input, not a hole. `INSTALL
 * json` failing on filesystem access while `LOAD json` succeeds is what proves it came from the
 * binary.)
 *
 * All five are additionally in [DialectRefusalSets.DUCKDB], because `properties.jdbc` is applied
 * **after** [defaultProperties] ([AbstractDialectAdapter.buildHikariConfig]) — without that an
 * operator could save a datasource carrying `enable_external_access=true` and re-open the surface
 * this class closes. See that set's KDoc for why refusing four of them became correct only once
 * these defaults existed.
 *
 * This is the datasource analogue of staging §9.5's de-privileging: an in-process engine must not
 * hand author SQL — or an operator's `properties.jdbc` — a code-execution primitive.
 */
class DuckdbDialectAdapter : AbstractDialectAdapter(Dialect.DUCKDB, "duckdb") {
    // DuckDB is Postgres-lineage: the pinned driver reports `information_schema` AND
    // `pg_catalog` as plain getSchemas() rows beside the user's `main` (verified against
    // duckdb_jdbc 1.5.5.1) — the bare {information_schema} default leaked pg_catalog into the
    // schemas listing. A FLOOR, deliberately known-incomplete like Oracle's.
    override val introspectionSystemSchemas: Set<String> = setOf("information_schema", "pg_catalog")

    override val defaultProperties: Map<String, String> =
        mapOf(
            // Never load an unsigned or community extension. Both are runtime-locked once set.
            "allow_unsigned_extensions" to "false",
            "allow_community_extensions" to "false",
            // Defense in depth: these two stop DuckDB *attempting* an autoload, but author SQL can
            // set them back at runtime, so they are not what the containment rests on.
            "autoload_known_extensions" to "false",
            "autoinstall_known_extensions" to "false",
            // THE load-bearing lock: no filesystem, no network — so nothing external can be
            // fetched, loaded, attached, read or written, whatever the two toggles above say.
            "enable_external_access" to "false",
        )
}

/**
 * SQLite -- an **embedded, in-process** engine, hardened at the adapter per §5.6 (v1.9).
 *
 * SQLite runs inside the server JVM, so author-authored SQL against a SQLite datasource executes
 * in this process. The existing §5.6 refusal set blocks `enable_load_extension` (native code
 * loading) and `temp_store_directory`, but `ATTACH DATABASE '/any/file.db' AS other` is NOT
 * blocked by either — an attacker can open and query ANY file on the server filesystem.
 *
 * ## What holds the line
 *
 * `SQLITE_LIMIT_ATTACHED` controls the maximum number of simultaneously attached databases.
 * The xerial driver (3.49.1.0) exposes it as the `limit_attached` pragma key. Setting it to 0
 * at connect time prevents any `ATTACH` — the driver's `sqlite3_limit(conn, SQLITE_LIMIT_ATTACHED, 0)`
 * call runs before author SQL.
 *
 * Both keys are additionally in [DialectRefusalSets.SQLITE], because `properties.jdbc` is applied
 * **after** [defaultProperties] ([AbstractDialectAdapter.buildHikariConfig]) — without that an
 * operator could save a datasource carrying `limit_attached=10` and re-open the surface this
 * class closes.
 *
 * This is the datasource analogue of staging §9.5's de-privileging: an in-process engine must not
 * hand author SQL a filesystem-access primitive.
 */
class SqliteDialectAdapter : AbstractDialectAdapter(Dialect.SQLITE, "sqlite") {
    /**
     * §7A: SQLite has no JDBC schema dimension at all — `getSchemas()` reports no rows and
     * `getSchema()` is hardcoded null in the vendored driver — so an unqualified
     * tables()/columns() read cannot merge same-named tables and is exempt from the
     * unknown-current-schema guard (see [DialectAdapter.introspectionSchemaless]).
     */
    override val introspectionSchemaless: Boolean = true

    override val defaultProperties: Map<String, String> =
        mapOf(
            "enable_load_extension" to "false",
            "limit_attached" to "0",
        )
}

/**
 * Dispatches a [Dialect] to its [DialectAdapter]. Total over the enum — every value has an
 * adapter, mirroring `TypeMappers.forDialect`; [DialectAdaptersTest] asserts completeness so a
 * newly added dialect fails a test rather than a lookup at runtime.
 */
object DialectAdapters {
    private val BY_DIALECT: Map<Dialect, DialectAdapter> =
        listOf(
            PostgresDialectAdapter(),
            OracleDialectAdapter(),
            MssqlDialectAdapter(),
            MysqlDialectAdapter(),
            H2DialectAdapter(),
            DuckdbDialectAdapter(),
            SqliteDialectAdapter(),
        ).associateBy { it.dialect }

    /** The adapter for [dialect]. Throws only if a dialect is added without an adapter. */
    fun forDialect(dialect: Dialect): DialectAdapter = BY_DIALECT[dialect] ?: error("No DialectAdapter registered for dialect $dialect")

    /** All registered adapters — the completeness-test surface. */
    fun all(): Collection<DialectAdapter> = BY_DIALECT.values
}
