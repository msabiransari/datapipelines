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
     * §7A introspection: true when this dialect has **no JDBC schema dimension at all** —
     * `getSchemas()` is empty and every object reports a null schema. Verified for the
     * vendored SQLite driver (xerial 3.49.1.0: `getSchema()` is hardcoded null,
     * `getSchemas()` reports no rows); DuckDB reports a real current schema (`main`), so it
     * is NOT schemaless despite the single-database framing of the schemas listing.
     *
     * The one behavioral consequence: a no-schema `tables()`/`columns()` read needs no
     * current-schema default — same-named tables in different schemas cannot exist, so the
     * unqualified read cannot merge and the §7A unknown-current-schema guard does not apply
     * (the caller has no schema to pass; the schemas listing is empty).
     */
    val introspectionSchemaless: Boolean
        get() = false

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
