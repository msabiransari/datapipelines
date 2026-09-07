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
        // check (§5.4) that legitimately has no secret — a PUT that omits it — must pass an
        // explicit placeholder (DatasourceValidator.VALIDATION_ONLY_SECRET) rather than let a
        // null slip through to a real connection attempt.
        //
        // CredentialKind.NONE is the ONE exemption, and it is not a weakening: there is no
        // credential to be silently empty. An embedded file database, an IAM role or OS auth
        // supplies the authority, and V13's CHECK makes "kind = none" and "no stored
        // ciphertext" the same fact — so requiring a secret here would demand the dummy
        // password this round exists to delete (datasources.md §8A.1).
        val secret =
            if (datasource.credentialKind == CredentialKind.NONE) {
                null
            } else {
                requireNotNull(datasource.secret) {
                    "datasource '${datasource.name}' has no plaintext credential to build a pool with"
                }
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
        // The kind→slot decision is the ADAPTER's (§3.4): the default pair for a password or a
        // token, nothing at all for NONE, and an override for a driver that spells it its own way.
        applyCredential(config, datasource, secret)
        config.driverClassName = jdbcDriverClassName
        config.poolName = "ds-${datasource.name}"
        // Workspaces design §6 layer 2b (D6): a readonly datasource's pools are built with
        // Hikari `readOnly = true`. Defense in depth, not containment — JDBC read-only
        // enforcement varies by driver, and even pool→connection propagation is not guaranteed
        // (verified against the pinned HikariCP 6.3.0 + H2: the flag reaches the pool, not the
        // leased connection) — and `properties.hikari.readOnly` is §5.6-refused so an operator
        // cannot flip it either way. The flag's real boundary is the SELECT-only DB user of
        // datasources.md §5.7.
        if (datasource.isReadonly) config.isReadOnly = true
        // Note: queryTimeoutSeconds is an execution-layer policy (§5.5), applied per-statement
        // by the executor — deliberately NOT a pool or connection property here.

        // §4.2A: the adapter's TYPED connect-time setup, joined into HikariCP's single
        // `connectionInitSql` slot. Empty for every dialect that needs none — and it is set
        // AFTER the hikari passthrough deliberately: `connectionInitSql` is not in the §5.6
        // SERVER_MANAGED set today, so the assignment here is what makes it server-owned in
        // practice, and a `properties.hikari.connectionInitSql` can never survive it.
        val init = connectionInit(datasource)
        if (init.isNotEmpty()) config.connectionInitSql = init.joinToString("; ")

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
    // §4.2: `[database, schema]`, one browsable level — a connection cannot leave the database
    // its URL named. (pgjdbc ignores the getTables catalog argument entirely; the outer segment
    // travels in a row's namespace as context, not as a filter dimension.)
    override val namespaceShape: NamespaceShape = NamespaceShape.DATABASE_AND_SCHEMA

    override val introspectionTableTypes: List<String> =
        listOf("TABLE", "VIEW", "PARTITIONED TABLE", "MATERIALIZED VIEW", "FOREIGN TABLE")

    override val introspectionSystemSchemas: Set<String> = setOf("pg_catalog", "information_schema")
}

class OracleDialectAdapter : AbstractDialectAdapter(Dialect.ORACLE, "oracle") {
    // §4.2: Oracle has schemas (= users) and no catalogs at all — `getCatalogs()` is empty.
    override val namespaceShape: NamespaceShape = NamespaceShape.SCHEMA_ONLY

    // Datasources §7B: 12c+ `FETCH FIRST`, never `ROWNUM` (pre-sort assignment would preview
    // the wrong rows — the applyRowLimit KDoc on [DialectAdapter] records the decision).
    override val rowLimitStyle: RowLimitStyle = RowLimitStyle.FETCH_FIRST

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
    // §4.2: `[database, schema]`. ONE browsable level, stated conservatively: mssql-jdbc may well
    // enumerate every database on the server through getCatalogs(), but this round ran no probe
    // against a live SQL Server, and advertising a level the driver might not honour is exactly
    // the kind of unverified claim the shape exists to replace.
    override val namespaceShape: NamespaceShape = NamespaceShape.DATABASE_AND_SCHEMA

    // Datasources §7B: `[...]` brackets with `]]` escaping, and `TOP (n)` sits after SELECT —
    // the two shapes that make quoting and row-limiting whole-statement concerns.
    override val identifierQuoteStyle: IdentifierQuoteStyle = IdentifierQuoteStyle.BRACKET
    override val rowLimitStyle: RowLimitStyle = RowLimitStyle.TOP

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
    // §4.2: `[schema]` — one level, arriving in the JDBC catalog. The SHAPE says so; the reader
    // consults no boolean.
    override val namespaceShape: NamespaceShape = NamespaceShape.SCHEMA_IN_CATALOG

    // Datasources §7B: backtick quoting with `` doubling — ANSI_QUOTES is NOT assumed, since
    // the adapter cannot know a server's sql_mode.
    override val identifierQuoteStyle: IdentifierQuoteStyle = IdentifierQuoteStyle.BACKTICK

    override val introspectionSystemSchemas: Set<String> =
        setOf("information_schema", "mysql", "performance_schema", "sys")
}

/**
 * H2 — an embedded engine like SQLite and DuckDB, but one that DOES have a login: an H2 database
 * may be opened with a user and password, or with neither. So its credential set is
 * `{NONE, PASSWORD}` (§3.4) and never `TOKEN`: no H2 deployment authenticates with a bearer
 * token, and declaring a kind the pinned driver cannot use is the defect this seam removes.
 */
class H2DialectAdapter : AbstractDialectAdapter(Dialect.H2, "h2") {
    // §4.2: `[database, schema]`, one browsable level. H2's catalog argument IS honoured by the
    // pinned driver (a wrong catalog matches nothing — probed 2026-09-07 against h2 2.3.232), but
    // one connection sees exactly one database, so there is one level to browse.
    override val namespaceShape: NamespaceShape = NamespaceShape.DATABASE_AND_SCHEMA

    override val supportedCredentialKinds: Set<CredentialKind> = setOf(CredentialKind.NONE, CredentialKind.PASSWORD)
}

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
    /**
     * §4.2: `[catalog, schema]` with ONE browsable level. DuckDB genuinely has catalogs — two
     * `ATTACH`ed files are two of them — but this adapter's `enable_external_access = false` lock
     * makes `ATTACH` impossible (verified 2026-09-07: *"file system operations are disabled by
     * configuration"*), so an embedded DuckDB datasource has exactly one user catalog. The
     * lake-mode adapter is the two-level twin, and the lock is the whole difference.
     */
    override val namespaceShape: NamespaceShape = NamespaceShape.CATALOG_AND_SCHEMA_EMBEDDED

    /**
     * §3.4: a DuckDB file has no login. `NONE` is the truthful kind and the one the demo's
     * `sample-trade-us` entry now uses; `PASSWORD` stays supported only because pre-V13 rows
     * carry it (the backfill cannot know that a stored dummy was never a credential).
     */
    override val supportedCredentialKinds: Set<CredentialKind> = setOf(CredentialKind.NONE, CredentialKind.PASSWORD)

    // DuckDB is Postgres-lineage: the pinned driver reports `information_schema` AND
    // `pg_catalog` as plain getSchemas() rows beside the user's `main` (verified against
    // duckdb_jdbc 1.5.5.1) — the bare {information_schema} default leaked pg_catalog into the
    // schemas listing. A FLOOR, deliberately known-incomplete like Oracle's.
    //
    // The two DOTTED entries are 087's: once the listing carries catalogs, DuckDB's engine
    // catalogs become visible, and each of them holds a schema called `main` (probed
    // 2026-09-07 against duckdb_jdbc 1.5.5.1: `getSchemas()` reports `system.main`,
    // `system.information_schema`, `system.pg_catalog` and `temp.main` beside the user's
    // `memory.main`). `main` alone cannot be excluded — it is the ordinary user schema of every
    // DuckDB database — so only the qualified name identifies the engine's own.
    override val introspectionSystemSchemas: Set<String> =
        setOf("information_schema", "pg_catalog", "system.main", "temp.main")

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
     * §3.4: a SQLite file has no login — the xerial driver ignores both values entirely. `NONE`
     * is the truthful kind and the one the demo's `sample-reference`/`sample-fx` entries now use
     * (datasources.md §8A.1's dummy password is gone); `PASSWORD` stays supported only because
     * pre-V13 rows carry it.
     */
    override val supportedCredentialKinds: Set<CredentialKind> = setOf(CredentialKind.NONE, CredentialKind.PASSWORD)

    /**
     * §4.2/§7A: SQLite has no namespace dimension at all — `getSchemas()` reports no rows and
     * `getSchema()` is hardcoded null in the vendored driver — so an unqualified
     * tables()/columns() read cannot merge same-named tables and is exempt from the
     * unknown-current-schema guard (see [NamespaceShape.isFlat]).
     */
    override val namespaceShape: NamespaceShape = NamespaceShape.FLAT

    override val defaultProperties: Map<String, String> =
        mapOf(
            "enable_load_extension" to "false",
            "limit_attached" to "0",
        )
}

/**
 * **LAKE** — object storage read in place (Parquet, Iceberg on S3), with DuckDB as the engine
 * (datasources.md §4.1/§4.2A, round 087). The offering is `dp-lake`; DuckDB is the engine
 * underneath and is named once.
 *
 * ## The one difference from [DuckdbDialectAdapter], and why it needs its own dialect
 *
 * The embedded adapter's `enable_external_access = false` is a **runtime lock**: DuckDB refuses
 * to change it once the database is running, which is what makes it the load-bearing control
 * there. A lake cannot read S3 — or a local Parquet file — with that lock on (verified
 * 2026-09-07 against duckdb_jdbc 1.5.5.1: `ATTACH` of a file fails with *"file system operations
 * are disabled by configuration"*). So this adapter does not set it, and the engine default
 * (`true`) applies.
 *
 * **This is the correction the warehouse-and-lake design note needed.** Its §4 described Round B
 * as running `INSTALL/LOAD httpfs, aws, iceberg` and `ATTACH` on "a DuckDB database", which is
 * impossible on the shipped adapter — the lock forbids every one of those statements. Round B
 * runs on THIS adapter; the note now says so.
 *
 * ## What is still refused
 *
 *  - `allow_unsigned_extensions` and `allow_community_extensions` stay `false` and stay
 *    runtime-locked. Opening the filesystem is not the same as accepting unsigned native code,
 *    and a lake needs the first and never the second.
 *  - The full [DialectRefusalSets.DUCKDB] set applies unchanged (`Dialect.LAKE -> DUCKDB`),
 *    including `enable_external_access` itself: the adapter opens it by NOT defaulting it,
 *    never by letting `properties.jdbc` set it, because `properties.jdbc` is applied AFTER
 *    `defaultProperties` and a settable sandbox switch is row data deciding a security posture.
 *  - `autoload_known_extensions` / `autoinstall_known_extensions` stay `false`. Extensions are
 *    loaded by [connectionInit]'s EXPLICIT `INSTALL`/`LOAD`, which is a list this adapter
 *    generates — never an implicit fetch triggered by whatever function author SQL happens to
 *    call.
 *
 * ## The typed configuration
 *
 * Everything the setup block needs arrives as `properties.dialect.*` (§12.1) and is validated
 * key by key here. There is deliberately no free-text setup field: this SQL runs in the app's own
 * process, and an operator-supplied connect hook would be the §5.6 `session_init_sql_file`
 * surface under a friendlier name.
 *
 * | Key | Meaning |
 * |---|---|
 * | `catalog.kind` | `s3`, `glue`, `s3_tables` or `rest` — how the data is addressed. |
 * | `catalog.ref` | The catalog's identifier: an ARN, an account id, or a REST endpoint. |
 * | `region` | The AWS region for the S3 secret. |
 * | `endpoint` | An S3-compatible endpoint (MinIO, on-prem) — host[:port], no scheme. |
 * | `url_style` | `path` or `vhost`, for S3-compatible endpoints. |
 * | `attach` | `alias=path` pairs, comma-separated: catalogs to ATTACH read-only. |
 *
 * ## Credentials (§3.4)
 *
 * `kind: none` = the IAM credential chain (`CREATE SECRET … PROVIDER credential_chain`) — the
 * self-hosted answer, with nothing to encrypt or rotate. `kind: password` = an explicit key pair,
 * the access key id as `username` and the secret as the credential; it reaches the engine through
 * [connectionInit], never through `properties.jdbc`, which §5.6 refuses precisely because it is
 * stored plaintext and returned to `read` scope.
 */
class LakeDialectAdapter : AbstractDialectAdapter(Dialect.LAKE, "duckdb") {
    /** §4.2: two BROWSABLE levels — `ATTACH` is allowed here, so catalogs are real. */
    override val namespaceShape: NamespaceShape = NamespaceShape.CATALOG_AND_SCHEMA

    /** §3.4: the IAM chain (`none`) or an explicit key id + secret (`password`). */
    override val supportedCredentialKinds: Set<CredentialKind> = setOf(CredentialKind.NONE, CredentialKind.PASSWORD)

    /** DuckDB's engine catalogs, as [DuckdbDialectAdapter] excludes them. */
    override val introspectionSystemSchemas: Set<String> =
        setOf("information_schema", "pg_catalog", "system.main", "temp.main")

    /**
     * The embedded five, MINUS `enable_external_access` — the one setting a lake exists to have
     * on. Native-code loading stays closed in both directions.
     */
    override val defaultProperties: Map<String, String> =
        mapOf(
            "allow_unsigned_extensions" to "false",
            "allow_community_extensions" to "false",
            "autoload_known_extensions" to "false",
            "autoinstall_known_extensions" to "false",
        )

    /**
     * §4.2A — the per-connection setup, generated from typed fields in a fixed order: extensions,
     * then the credential secret, then the catalogs. Nothing here interpolates author SQL, and
     * every value that reaches a string literal is single-quote-escaped.
     */
    override fun connectionInit(datasource: Datasource): List<String> =
        buildList {
            val dialectProperties = datasource.properties.dialect
            addAll(extensionStatements(dialectProperties))
            secretStatement(datasource, dialectProperties)?.let { add(it) }
            addAll(attachStatements(dialectProperties))
        }

    override fun validateDialectProperties(properties: Map<String, Any?>): ValidationResult {
        val errors =
            properties.keys
                .filter { it !in DIALECT_KEYS }
                .map {
                    ValidationResult.ValidationError(
                        DatasourceErrorCodes.PROPERTIES_INVALID,
                        "properties.dialect.$it",
                        "dialect LAKE does not recognize properties.dialect.$it; accepted keys are ${DIALECT_KEYS.sorted()}.",
                    )
                }.toMutableList()
        enumValue(properties, "catalog.kind", CATALOG_KINDS)?.let { errors += it }
        enumValue(properties, "url_style", URL_STYLES)?.let { errors += it }
        return ValidationResult.of(errors)
    }

    /**
     * `INSTALL`/`LOAD` for the extensions the declared catalog kind needs — and NOTHING when no
     * `catalog.kind` is declared.
     *
     * That conditional is the difference between two real deployments, not a convenience.
     * `catalog.kind` present means the data is on S3, which needs `httpfs` + `aws` (and `iceberg`
     * for the Iceberg catalog kinds — loading an extension nothing will call is surface for
     * nothing). `catalog.kind` ABSENT means a lake over paths the process can already reach: a
     * mounted volume, an NFS export, files a sidecar syncs. Emitting `INSTALL httpfs` there
     * would make the pool build require **egress to DuckDB's extension repository** for a
     * datasource that never touches the network — turning an air-gapped deployment's working
     * configuration into a connect failure.
     *
     * These are EXPLICIT statements and not autoloads on purpose: the adapter decides what the
     * engine may fetch, rather than whatever function name reaches the parser.
     *
     * **Not proven against a live S3 in this round.** Whether `duckdb_jdbc` can `INSTALL`/`LOAD`
     * these three from the app's container, what egress that needs, and whether they should be
     * bundled into the image instead, is the spike the warehouse-and-lake design note §7.3 names.
     * What IS proven here is the local path — see `LakeDialectAdapterTest`.
     */
    private fun extensionStatements(properties: Map<String, Any?>): List<String> {
        val kind = properties["catalog.kind"]?.toString()?.lowercase() ?: return emptyList()
        val extensions = if (kind in ICEBERG_KINDS) listOf("httpfs", "aws", "iceberg") else listOf("httpfs", "aws")
        return extensions.flatMap { listOf("INSTALL $it", "LOAD $it") }
    }

    /**
     * The S3 secret, from the CREDENTIAL and never from `properties.*` — `kind: none` is the IAM
     * chain, `kind: password` is an explicit key id (`username`) plus secret.
     *
     * Null when the credential is a password whose secret has not been decrypted yet: the
     * save-time test pool build (§5.4) never connects, so a statement naming a placeholder
     * credential would be noise in the config it checks.
     */
    private fun secretStatement(
        datasource: Datasource,
        properties: Map<String, Any?>,
    ): String? {
        // No declared catalog kind = no S3 target, so no S3 secret. `TYPE s3` is registered by
        // the httpfs extension, which the branch above deliberately did not load, so emitting
        // one here would fail the connection of a purely local lake.
        properties["catalog.kind"] ?: return null
        val region = properties["region"]?.toString()
        val endpoint = properties["endpoint"]?.toString()
        val urlStyle = properties["url_style"]?.toString()
        val options =
            buildList {
                add("TYPE s3")
                when (datasource.credentialKind) {
                    CredentialKind.NONE -> {
                        add("PROVIDER credential_chain")
                    }

                    else -> {
                        val secret = datasource.secret ?: return null
                        add("KEY_ID ${quoted(datasource.username.orEmpty())}")
                        add("SECRET ${quoted(secret)}")
                    }
                }
                region?.let { add("REGION ${quoted(it)}") }
                endpoint?.let { add("ENDPOINT ${quoted(it)}") }
                urlStyle?.let { add("URL_STYLE ${quoted(it)}") }
                // MinIO and most on-prem S3 endpoints are plain HTTP; a declared endpoint is a
                // deliberate non-AWS target, so TLS is not assumed for it.
                if (endpoint != null) add("USE_SSL false")
            }
        return "CREATE OR REPLACE SECRET dp_lake (${options.joinToString(", ")})"
    }

    /**
     * `ATTACH … (READ_ONLY)` for each `alias=location` pair in `dialect.attach`. Read-only is not
     * configurable: a lake datasource is a READ connector, and the §5.7 readonly flag is a
     * datapipelines-level rule that a driver-level one should back rather than contradict.
     */
    private fun attachStatements(properties: Map<String, Any?>): List<String> =
        properties["attach"]
            ?.toString()
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { pair ->
                val alias = pair.substringBefore('=').trim()
                val location = pair.substringAfter('=').trim()
                "ATTACH ${quoted(location)} AS ${quoteIdentifier(alias)} (READ_ONLY)"
            }

    private fun enumValue(
        properties: Map<String, Any?>,
        key: String,
        allowed: Set<String>,
    ): ValidationResult.ValidationError? {
        val value = properties[key]?.toString()?.lowercase() ?: return null
        if (value in allowed) return null
        return ValidationResult.ValidationError(
            DatasourceErrorCodes.PROPERTIES_INVALID,
            "properties.dialect.$key",
            "properties.dialect.$key must be one of ${allowed.sorted()}; '$value' is not.",
        )
    }

    /** A SQL string literal with embedded single quotes doubled — the only interpolation here. */
    private fun quoted(value: String): String = "'" + value.replace("'", "''") + "'"

    private companion object {
        val DIALECT_KEYS = setOf("catalog.kind", "catalog.ref", "region", "endpoint", "url_style", "attach")
        val CATALOG_KINDS = setOf("s3", "glue", "s3_tables", "rest")
        val ICEBERG_KINDS = setOf("glue", "s3_tables", "rest")
        val URL_STYLES = setOf("path", "vhost")
    }
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
            LakeDialectAdapter(),
        ).associateBy { it.dialect }

    /** The adapter for [dialect]. Throws only if a dialect is added without an adapter. */
    fun forDialect(dialect: Dialect): DialectAdapter = BY_DIALECT[dialect] ?: error("No DialectAdapter registered for dialect $dialect")

    /** All registered adapters — the completeness-test surface. */
    fun all(): Collection<DialectAdapter> = BY_DIALECT.values
}
