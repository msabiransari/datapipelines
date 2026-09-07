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
     * `getTables` asks for and what the tables operation lists. The SQL-standard
     * floor is `TABLE` + `VIEW`; a dialect adds the types its users actually create (Postgres:
     * partitioned/materialized/foreign tables). System catalogs that arrive under other types
     * (`SYSTEM TABLE`, `SYSTEM VIEW`) are excluded by the type vocabulary itself.
     */
    val introspectionTableTypes: List<String>
        get() = listOf("TABLE", "VIEW")

    /**
     * §7A introspection: schemas that belong to the engine, not the user — rows in these are
     * dropped from every introspection result (they would otherwise ride along on the `VIEW`
     * type and eat the tables cap). Declared **lowercase**; matching is case-insensitive,
     * because drivers report the standard schema variously as `INFORMATION_SCHEMA` (H2),
     * `information_schema` (Postgres, MySQL). An entry ending in `*` matches by
     * case-insensitive prefix — Oracle's versioned `apex_*` schemas cannot be enumerated by
     * exact name.
     *
     * These lists are a **floor, deliberately known-incomplete**: they name the schemas the
     * pinned drivers verifiably report as plain user rows, not every schema an engine ships.
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
     * §7A introspection: **how deep this engine's object namespace is, and what it calls each
     * level** (datasources.md §4.2). Replaces the boolean `schemaArrivesInCatalog`, which could
     * express exactly two shapes — "MySQL" and "everyone else" — and therefore could not describe
     * any of the four connectors on the roadmap.
     *
     * A boolean was the wrong type for the question. What the introspector actually needs to know
     * is (a) how many levels a caller can name, (b) what to CALL them so an agent's prompt reads
     * `catalog.schema` on Databricks and `project.dataset` on BigQuery, and (c) which JDBC result
     * column carries the innermost one. [NamespaceShape] states all three; the old boolean is its
     * [NamespaceShape.innermostArrivesInCatalog] field, now one fact among several rather than
     * the whole model.
     */
    val namespaceShape: NamespaceShape
        get() = NamespaceShape.DATABASE_AND_SCHEMA

    /**
     * The identifier-quote vocabulary this dialect's engine accepts (the preview-rows surface,
     * datasources §7B): the SQL-standard doubled `"` by default, MySQL's backtick, MSSQL's
     * `[...]` brackets. Every caller-supplied identifier that reaches a preview statement goes
     * through [quoteIdentifier] with the quote character doubled — there is no SQL-validation
     * utility in this repo and this quoting IS the injection boundary for identifiers.
     *
     * Declared per-dialect on the interface (not inferred from the driver at runtime) so
     * `DialectAdaptersTest` can assert the enum-total mapping and the embedded-dialect tests
     * can pin each adapter against its pinned driver's
     * `DatabaseMetaData.getIdentifierQuoteString()`.
     */
    val identifierQuoteStyle: IdentifierQuoteStyle
        get() = IdentifierQuoteStyle.DOUBLE_QUOTE

    /**
     * Quotes one identifier for this dialect, doubling every embedded quote character
     * (`"weird"name"` → `"weird""name"`). The ONLY way a caller-supplied table/schema/column
     * name may enter a preview statement. A blank identifier is refused by the caller BEFORE
     * quoting — this function is deliberately dumb about it.
     */
    fun quoteIdentifier(identifier: String): String =
        when (identifierQuoteStyle) {
            IdentifierQuoteStyle.DOUBLE_QUOTE -> "\"${identifier.replace("\"", "\"\"")}\""
            IdentifierQuoteStyle.BACKTICK -> "`${identifier.replace("`", "``")}`"
            IdentifierQuoteStyle.BRACKET -> "[${identifier.replace("]", "]]")}]"
        }

    /**
     * How this dialect caps a SELECT's row count (datasources §7B): a trailing `LIMIT n`
     * (POSTGRES, MYSQL, H2, DUCKDB, SQLITE), Oracle's trailing `FETCH FIRST n ROWS ONLY`
     * (12c+), or MSSQL's `TOP (n)` — which sits AFTER `SELECT`, not at the end.
     */
    val rowLimitStyle: RowLimitStyle
        get() = RowLimitStyle.LIMIT

    /**
     * Caps a SELECT to [limit] rows — a WHOLE-STATEMENT method, not a suffix, because MSSQL's
     * `TOP (n)` sits after the `SELECT` keyword ([RowLimitStyle.TOP]).
     *
     * Oracle decision, stated per the round contract: `FETCH FIRST n ROWS ONLY` (12c+), NOT
     * `ROWNUM` — `ROWNUM` is assigned BEFORE `ORDER BY` evaluates, so `WHERE ROWNUM <= n` picks
     * n arbitrary rows and then sorts them, silently returning the wrong rows for the
     * "both ends of the data" preview contract. 12c has been the floor since 2013; the pinned
     * ojdbc fleet is 12c+.
     *
     * Only ever applied to a statement THIS MODULE built (the preview SELECT) — never to
     * author-rendered SQL, whose row caps come from JDBC `maxRows`/`fetchSize` instead.
     */
    fun applyRowLimit(
        selectSql: String,
        limit: Int,
    ): String =
        when (rowLimitStyle) {
            RowLimitStyle.LIMIT -> "$selectSql LIMIT $limit"

            RowLimitStyle.FETCH_FIRST -> "$selectSql FETCH FIRST $limit ROWS ONLY"

            // `TOP`-insertion anchors on the statement's first `SELECT` keyword,
            // case-insensitively (author SQL is not involved — the statement is module-built
            // and starts with `SELECT`); every other style appends its clause.
            RowLimitStyle.TOP -> selectSql.replaceFirst(LEADING_SELECT, "SELECT TOP ($limit)")
        }

    /** The `TOP`-insertion anchor: the statement's first `SELECT` keyword (case-insensitive). */
    private companion object {
        val LEADING_SELECT = Regex("""^(\s*)SELECT\b""", RegexOption.IGNORE_CASE)
    }

    /**
     * The [CredentialKind]s this dialect's **pinned driver** can actually authenticate with
     * (datasources.md §3.4/§4.2). Fail-closed: a kind outside this set is refused at save with
     * `datasource.validation.properties_invalid` rather than stored and silently ignored at
     * pool build — "the row says private_key and the driver has never heard of one" is exactly
     * the shape of defect this whole seam exists to stop.
     *
     * The default is `{PASSWORD, TOKEN}`: every server dialect we ship takes a login, and a
     * bearer token in the password slot is a real, deployed pattern for three of them (AWS RDS
     * IAM auth, Azure Database for PostgreSQL/MySQL AAD auth, and Snowflake's PAT when that
     * dialect lands). `PRIVATE_KEY` and `SERVICE_ACCOUNT_JSON` are in **no** shipped set: they
     * are the reference targets' kinds, catalogued in the contract and refused by every adapter
     * that exists today, which is what "design against four reference targets, implement none"
     * means at the code level.
     *
     * Embedded file engines override it to `{NONE, PASSWORD}` — nothing authenticates, and
     * `PASSWORD` stays only so the pre-V13 rows keep working.
     */
    val supportedCredentialKinds: Set<CredentialKind>
        get() = setOf(CredentialKind.PASSWORD, CredentialKind.TOKEN)

    /**
     * Places [datasource]'s credential on [config] — the one place a KIND becomes a driver slot.
     *
     * The default covers every shipped dialect: [CredentialKind.NONE] sets neither field (an
     * embedded file database has no login, and Hikari must not be handed a placeholder), and
     * every other supported kind goes into the standard `username`/`password` pair, because
     * that is where a JDBC driver reads a login OR a bearer token from.
     *
     * A dialect whose driver spells a kind differently overrides this. The two written into the
     * contract as reference shapes: **Databricks** wants the literal `UID=token` with the PAT in
     * `PWD`, and **Snowflake** key-pair auth wants `private_key_file`/a `privateKey` object
     * rather than the password slot. Neither ships today; both are one override away, and the
     * CALLER never learns the difference — it says `credential.kind`, not `properties.jdbc.PWD`.
     *
     * @param secret the decrypted plaintext, or null for [CredentialKind.NONE] and for the
     *   save-time build of an update that omitted the credential (§5.4).
     */
    fun applyCredential(
        config: HikariConfig,
        datasource: Datasource,
        secret: String?,
    ) {
        if (datasource.credentialKind == CredentialKind.NONE) return
        config.username = datasource.username
        config.password = secret
    }

    /**
     * Statements to run on **every new connection** in this datasource's pool, in order
     * (datasources.md §4.2A) — wired to HikariCP's `connectionInitSql`, which was unreferenced
     * before 087 despite being exactly the seam a warehouse or lake connector needs.
     *
     * Generated from TYPED fields ([DatasourceProperties.dialect] and the [Datasource]'s
     * credential), **never** from operator- or author-supplied SQL text. That is not a style
     * preference: on an embedded engine this SQL runs inside the app's own process, and a
     * free-text connect hook would re-open the §5.6 `INIT` / `session_init_sql_file` surface
     * under a friendlier name. Every dialect that needs no setup returns empty, which is all of
     * them except the lake adapter.
     *
     * HikariCP runs `connectionInitSql` as ONE statement per connection, so a multi-statement
     * setup is joined with `;` by [AbstractDialectAdapter.buildHikariConfig] — the engines that
     * need this accept a multi-statement init string, and the alternative (a connection-init
     * callback) would put the sequence outside the config the save-time test pool build checks.
     */
    fun connectionInit(datasource: Datasource): List<String> = emptyList()

    /**
     * Validates this dialect's `properties.dialect.*` namespace (§12.1, §3.1): unknown keys and
     * bad values are reported as [ValidationResult] errors under
     * `datasource.validation.properties_invalid`.
     *
     * The default refuses the WHOLE namespace. A dialect that declares no typed configuration has
     * no `dialect.*` keys to accept, and silently ignoring them would let a typo look like a
     * working setting — the failure mode this namespace exists to avoid.
     */
    fun validateDialectProperties(properties: Map<String, Any?>): ValidationResult =
        ValidationResult.of(
            properties.keys.map { key ->
                ValidationResult.ValidationError(
                    DatasourceErrorCodes.PROPERTIES_INVALID,
                    "properties.dialect.$key",
                    "dialect ${dialect.wire} accepts no properties.dialect.* keys; '$key' is not recognized.",
                )
            },
        )

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
     * Expects [Datasource.secret] to already hold the **plaintext** credential (decrypted by
     * the caller for a runtime pool; supplied directly for save-time validation) — or `null`
     * when [Datasource.credentialKind] is [CredentialKind.NONE], which has none.
     */
    fun buildHikariConfig(datasource: Datasource): HikariConfig
}

/** The engine's identifier-quote vocabulary — see [DialectAdapter.identifierQuoteStyle]. */
enum class IdentifierQuoteStyle {
    /** `"name"` with embedded `"` doubled — the SQL standard (POSTGRES, ORACLE, H2, DUCKDB, SQLITE). */
    DOUBLE_QUOTE,

    /** `` `name` `` with embedded backticks doubled (MySQL). */
    BACKTICK,

    /** `[name]` with embedded `]` doubled (MSSQL). */
    BRACKET,
}

/** How a dialect caps a SELECT — see [DialectAdapter.applyRowLimit]. */
enum class RowLimitStyle {
    /** Trailing `LIMIT n` (POSTGRES, MYSQL, H2, DUCKDB, SQLITE). */
    LIMIT,

    /** Trailing `FETCH FIRST n ROWS ONLY` (ORACLE 12c+ — not `ROWNUM`; see the applyRowLimit KDoc). */
    FETCH_FIRST,

    /** `TOP (n)` inserted after the `SELECT` keyword (MSSQL). */
    TOP,
}

/**
 * The shape of a dialect's object namespace (datasources.md §4.2, §7A) — the seam that lets one
 * introspector serve a two-level RDBMS, a single-level MySQL, a schemaless SQLite and a
 * three-level warehouse without a `when (dialect)` anywhere in the reader.
 *
 * ## The three fields, and why each exists
 *
 * [labels] is the engine's own vocabulary, **outermost first**, and its size is the namespace's
 * DEPTH. It is not decoration: an agent told to pass `catalog.schema` on Databricks and
 * `project.dataset` on BigQuery writes correct SQL; one told to pass "the schema" guesses.
 *
 * [levels] is how many of those a caller can actually BROWSE and filter on, which is often fewer
 * than the depth. Postgres and H2 are `[database, schema]` but a connection can only ever see the
 * database its URL named, so exactly one level is browsable; a DuckDB lake with two `ATTACH`ed
 * catalogs genuinely has two. `levels` is what the wire advertises and what a UI would render as
 * pickers; `labels` is what names them.
 *
 * [innermostArrivesInCatalog] is the old boolean, kept because it is a real per-driver fact:
 * Connector/J puts the database in TABLE_CAT and leaves TABLE_SCHEM null, so introspection must
 * route the filter to the **catalog** argument and read TABLE_CAT as the schema — otherwise the
 * filter selects nothing and every table reports a null schema.
 *
 * ## The reference shapes (datasources.md §4.2)
 *
 * The four connectors this contract was made to survive, none of them implemented here:
 * Snowflake `[database, schema]`, Databricks Unity Catalog `[catalog, schema]` (verified
 * 2026-09-07: "a three-level namespace (`catalog.schema.object`)"), BigQuery `[project, dataset]`,
 * a DuckDB-backed lake `[catalog, schema]` — every one of them two browsable levels. That is the
 * shape the wire, the filters and the introspector now carry; adding the dialect adds its
 * [NamespaceShape] and nothing else.
 */
data class NamespaceShape(
    /** The engine's word for each level, outermost first. Size = the namespace's full depth. */
    val labels: List<String>,
    /** How many levels a caller can browse and filter on. `0 <= levels <= labels.size`. */
    val levels: Int,
    /**
     * True when the driver reports the innermost level in TABLE_CAT and leaves TABLE_SCHEM null
     * (Connector/J's default). The former `schemaArrivesInCatalog`.
     */
    val innermostArrivesInCatalog: Boolean = false,
) {
    init {
        require(levels in 0..labels.size) { "levels=$levels is outside 0..${labels.size} for labels=$labels" }
    }

    /** The JDBC result column carrying the innermost level: TABLE_CAT for catalog-routing drivers. */
    val innermostResultColumn: String get() = if (innermostArrivesInCatalog) "TABLE_CAT" else "TABLE_SCHEM"

    /**
     * True when this dialect has **no namespace dimension at all** — `getSchemas()` is empty and
     * every object reports a null schema. Verified for the vendored SQLite driver (xerial
     * 3.49.1.0: `getSchema()` is hardcoded null, `getSchemas()` reports no rows); DuckDB reports a
     * real current schema (`main`), so it is NOT schemaless.
     *
     * The one behavioral consequence: a namespace-less `tables()`/`columns()` read needs no
     * current-schema default — same-named tables in different schemas cannot exist, so the
     * unqualified read cannot merge and the §7A unknown-current-schema guard does not apply.
     */
    val isFlat: Boolean get() = labels.isEmpty()

    /**
     * True when the driver reports a real OUTER level in the results (TABLE_CAT beside
     * TABLE_SCHEM) that belongs in a row's namespace — a two-deep shape that is not
     * catalog-routing. False for MySQL (whose TABLE_CAT *is* the innermost level) and for the
     * one-deep and flat shapes.
     */
    val hasOuterCatalog: Boolean get() = labels.size >= TWO_LEVELS && !innermostArrivesInCatalog

    companion object {
        private const val TWO_LEVELS = 2

        /**
         * Postgres, H2, MSSQL: `[database, schema]`, ONE browsable level. The database is fixed by
         * the JDBC URL — a connection cannot cross it — so the browsable dimension is the schema
         * alone, and the outer segment travels in a row's `namespace` as context rather than as a
         * filter dimension. (H2's catalog argument IS honoured by the pinned driver — a wrong
         * catalog matches nothing, probed 2026-09-07 against h2 2.3.232 — while pgjdbc ignores it;
         * neither can name a second database, which is what `levels = 1` states. Raising MSSQL to
         * two would need a probe of mssql-jdbc's cross-database `getTables`, which this round did
         * not run.)
         */
        val DATABASE_AND_SCHEMA = NamespaceShape(labels = listOf("database", "schema"), levels = 1)

        /**
         * MySQL: `[schema]` — one level, arriving in the JDBC catalog. The shape SAYS so; there is
         * no boolean for a reader to remember to consult.
         */
        val SCHEMA_IN_CATALOG = NamespaceShape(labels = listOf("schema"), levels = 1, innermostArrivesInCatalog = true)

        /** Oracle: `[schema]` — no catalogs at all (`getCatalogs()` is empty). */
        val SCHEMA_ONLY = NamespaceShape(labels = listOf("schema"), levels = 1)

        /** SQLite: no namespace dimension whatsoever. */
        val FLAT = NamespaceShape(labels = emptyList(), levels = 0)

        /**
         * DuckDB embedded: `[catalog, schema]` with ONE browsable level. A second catalog needs
         * `ATTACH`, which the hardened adapter's `enable_external_access = false` forbids
         * (verified 2026-09-07 against duckdb_jdbc 1.5.5.1: `ATTACH` fails with *"file system
         * operations are disabled by configuration"*), so an embedded DuckDB datasource has
         * exactly one user catalog. The lake-mode adapter is the two-level twin.
         */
        val CATALOG_AND_SCHEMA_EMBEDDED = NamespaceShape(labels = listOf("catalog", "schema"), levels = 1)

        /**
         * A lake: `[catalog, schema]` with TWO browsable levels — `ATTACH` is allowed, so
         * same-named schemas can genuinely exist under different catalogs and MUST stay distinct
         * in every listing (verified 2026-09-07 against duckdb_jdbc 1.5.5.1: two ATTACHed files
         * report `a1.sales.orders` and `a2.sales.orders`, and an unqualified `getColumns` MERGES
         * their columns — the defect this level exists to prevent).
         */
        val CATALOG_AND_SCHEMA = NamespaceShape(labels = listOf("catalog", "schema"), levels = 2)
    }
}
