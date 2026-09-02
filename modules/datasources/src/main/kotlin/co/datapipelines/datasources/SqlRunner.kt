package co.datapipelines.datasources

import co.datapipelines.typesystem.JsonEncoder
import org.springframework.jdbc.core.SqlTypeValue
import org.springframework.jdbc.core.StatementCreatorUtils
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

/** One `ORDER BY` term of a preview statement — a structured `{column, direction}`, never a free string. */
data class OrderByTerm(
    val column: String,
    val descending: Boolean = false,
)

/** A capped, decoded, wire-encoded result set — the payload shape of both §7B query surfaces. */
data class QueryRows(
    /** The canonical schema (names + canonical types + mapping warnings), as the executor derives it. */
    val schema: ResultSchema,
    /**
     * At most the caller's cap rows, each a column-name → wire-value map encoded through
     * [JsonEncoder] — the same rules as the result cursor (BIGINTEGER/BIGDECIMAL as strings,
     * BINARY base64, temporal in fixed-width forms). A map key collapses duplicate column
     * labels (author SQL can select the same label twice); last-wins, a documented limit of
     * the debug-payload shape.
     */
    val rows: List<Map<String, Any?>>,
    /** True when the cursor held more rows than the cap returned. */
    val truncated: Boolean,
)

/** A non-connection statement failure: the SQL ran and the database refused it (syntax, permission, missing object). */
class SqlExecutionException(
    val datasourceName: String,
    cause: SQLException,
) : RuntimeException(boundedMessage(cause), cause) {
    companion object {
        /**
         * The driver message, bounded at the executor's own 2000-char B1 limit — rest-api §4.2
         * sanctions driver text in the envelope (a SQL author needs to see what the database
         * said); only its length ever needed bounding.
         */
        fun boundedMessage(cause: SQLException): String = cause.message?.take(MAX_MESSAGE_CHARS) ?: cause.javaClass.simpleName

        const val MAX_MESSAGE_CHARS = 2000
    }
}

/**
 * Runs SQL directly against a registered datasource and returns decoded, capped rows —
 * the shared engine under `datasources_preview_rows` and `pipelines_execute_node`
 * (datasources.md §7B, 037 C1).
 *
 * A [SchemaIntrospector] sibling with the same discipline:
 *  - **lease**: one pooled connection per call, leased and closed inside it ([ConnectionLease] —
 *    the shared unreachable-translation boundary);
 *  - **timeout**: `statement.queryTimeout = datasource.queryTimeoutSeconds` whenever the
 *    datasource declares one (§5.5 — the per-statement execution-layer policy);
 *  - **cap**: `maxRows` AND `fetchSize` set on every SELECT, so even a misbuilt statement
 *    cannot stream a table through the wire.
 *
 * Never touches staging, the result store, or the event log — a §7B read is a debug query,
 * not an execution. **Readonly datasources are valid SELECT targets** ([select]/[previewTable]):
 * the executor's readonly refusal covers DML/DDL node types only, and [executeUpdate] is where
 * the callers' own readonly refusal lives (they know the node type; this module does not).
 *
 * Two entry points, one engine: [previewTable] builds the ENTIRE statement (the agent supplies
 * identifiers only — every one dialect-quoted with the quote character doubled, the §7B
 * injection boundary) and delegates to [select]; [select]/[executeUpdate] take already-rendered
 * SQL with positional `?` binds — the node-run path, whose named-parameter translation happened
 * at the resolver where the parameter declarations live. Bind values go through Spring's
 * [StatementCreatorUtils], the same binder the executor uses for `:name` translations.
 */
class SqlRunner(
    private val registry: DatasourceRegistry,
) {
    /**
     * The `datasources_preview_rows` engine: a `SELECT * FROM` an optionally schema-qualified,
     * dialect-quoted table plus the caller's ORDER BY terms, row-capped in the dialect's own
     * syntax.
     *
     * `schema` qualifies the table (`schema.table` — on MySQL catalog routing the qualifier is
     * the database, exactly the position the §7A catalog argument selects); when omitted the
     * ENGINE resolves the current schema at execution, which is the §7A rule for a query — the
     * introspector's explicit current-schema read exists to stop `getColumns` MERGING same-named
     * tables across schemas, a hazard a query cannot have, so the read is deliberately not
     * repeated here. Schemaless dialects ignore the qualifier (there is no schema dimension to
     * address). `order_by` absent means the top-N is engine-arbitrary — the caller documents
     * that; each term's column is quoted like the table name.
     */
    fun previewTable(
        datasource: Datasource,
        table: String,
        schema: String? = null,
        orderBy: List<OrderByTerm> = emptyList(),
        limit: Int,
    ): QueryRows {
        val adapter = DialectAdapters.forDialect(datasource.dialect)
        val qualified =
            if (!schema.isNullOrBlank() && !adapter.introspectionSchemaless) {
                adapter.quoteIdentifier(schema) + "." + adapter.quoteIdentifier(table)
            } else {
                adapter.quoteIdentifier(table)
            }
        val order =
            orderBy.joinToString(", ") { term ->
                adapter.quoteIdentifier(term.column) + if (term.descending) " DESC" else " ASC"
            }
        val sql =
            buildString {
                append("SELECT * FROM ")
                append(qualified)
                if (order.isNotEmpty()) {
                    append(" ORDER BY ")
                    append(order)
                }
            }
        // The cap is baked into the statement at limit + 1 so the truncation probe can see one
        // row past the cap (an embedded LIMIT hides the rest from the cursor), then the page is
        // sliced to the caller's limit.
        val page = select(datasource, adapter.applyRowLimit(sql, limit + 1), emptyList(), limit + 1)
        return QueryRows(
            schema = page.schema,
            rows = page.rows.take(limit),
            truncated = page.rows.size > limit,
        )
    }

    /**
     * Runs a SELECT with positional `?` binds, capped at [limit] decoded rows. The caller's
     * limit reaches the statement as BOTH `maxRows` and `fetchSize` — the engine stops
     * producing rows at the cap instead of shipping a table to be truncated here. `maxRows`
     * is [limit] + 1 so the truncation probe can see one row past the cap without fetching
     * the rest.
     */
    fun select(
        datasource: Datasource,
        sql: String,
        bindValues: List<Any?> = emptyList(),
        limit: Int,
    ): QueryRows =
        ConnectionLease.lease(registry, datasource) {
            // The prepare is inside the catch with the execute: H2 (and Oracle) compile at
            // prepare time, so a failing statement can throw from EITHER call.
            try {
                statement(it, datasource, sql, bindValues, limit).use { statement -> readRows(statement, datasource, limit) }
            } catch (e: SQLException) {
                throw SqlExecutionException(datasource.name, e)
            }
        }

    private fun readRows(
        statement: PreparedStatement,
        datasource: Datasource,
        limit: Int,
    ): QueryRows =
        statement.executeQuery().use { rs ->
            val schema = ResultRowReader.schemaOf(rs.metaData, datasource.dialect)
            val rows = ArrayList<Map<String, Any?>>(limit.coerceAtMost(PREALLOCATED_CAPACITY))
            var truncated = false
            while (rs.next()) {
                if (rows.size == limit) {
                    truncated = true
                    break
                }
                val row = LinkedHashMap<String, Any?>(schema.columns.size * 2)
                schema.columns.forEachIndexed { index, column ->
                    row[column.name] = JsonEncoder.encode(ResultRowReader.readValue(rs, index + 1, column), column)
                }
                rows.add(row)
            }
            QueryRows(schema, rows, truncated)
        }

    /** Runs DML/DDL for real (037 E4 — no dry run) and returns the affected-row count. */
    fun executeUpdate(
        datasource: Datasource,
        sql: String,
        bindValues: List<Any?> = emptyList(),
    ): Long =
        ConnectionLease.lease(registry, datasource) {
            try {
                statement(it, datasource, sql, bindValues, limit = null).use { it.executeUpdate().toLong() }
            } catch (e: SQLException) {
                throw SqlExecutionException(datasource.name, e)
            }
        }

    /** One statement, timeout applied, binds set, caps configured for the SELECT shape. */
    private fun statement(
        connection: Connection,
        datasource: Datasource,
        sql: String,
        bindValues: List<Any?>,
        limit: Int?,
    ): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        datasource.queryTimeoutSeconds?.let { statement.queryTimeout = it }
        if (limit != null) {
            statement.fetchSize = limit
            statement.maxRows = limit + 1
        }
        bindValues.forEachIndexed { index, value ->
            StatementCreatorUtils.setParameterValue(statement, index + 1, SqlTypeValue.TYPE_UNKNOWN, value)
        }
        return statement
    }

    private companion object {
        /** The initial row-buffer size — bounded so a large cap does not pre-allocate a large list. */
        const val PREALLOCATED_CAPACITY = 64
    }
}

private fun Int.coerceAtMost(max: Int): Int = if (this > max) max else this
