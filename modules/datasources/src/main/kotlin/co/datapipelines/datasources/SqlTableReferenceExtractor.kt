package co.datapipelines.datasources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * One table position the DuckDB parser found in a rendered statement — the serializer's `BASE_TABLE` node, written
 * form only (reference-scanner slice D-A, #778). [catalog] and [schema] are null when the statement did not spell
 * them. [cte] is true when the name is a common table expression in scope at that position, so it names no table at
 * all; [timeTravel] is the node's `at_clause` (`AT (VERSION => n)`).
 */
data class WrittenTableReference(
    val catalog: String?,
    val schema: String?,
    val table: String,
    val statementIndex: Int,
    val timeTravel: Boolean,
    val cte: Boolean,
)

/** A table function in a FROM position (`read_parquet(...)`, `query_table(...)`): recorded, never resolved to a table. */
data class TableFunctionReference(
    val function: String,
    val statementIndex: Int,
)

/** What [SqlTableReferenceExtractor.extract] made of one rendered text. */
sealed interface SqlTableExtraction {
    /** Every statement serialized; [tables] and [functions] are in document order. */
    data class Parsed(
        val statementCount: Int,
        val tables: List<WrittenTableReference>,
        val functions: List<TableFunctionReference>,
    ) : SqlTableExtraction

    /**
     * The serializer refused the text — a parser error, or a statement it cannot serialize (DML, DDL, `PIVOT`). The
     * text is UNSCANNABLE, never "no references". [errorType] is the serializer's own word (`parser`,
     * `not implemented`); [message] is bounded and may quote a token of the text, never the whole of it.
     */
    data class Unscannable(
        val errorType: String,
        val message: String,
    ) : SqlTableExtraction
}

/**
 * The engine's own parser as the oracle for which tables a rendered LAKE statement names (#778, slice D-A): the text
 * is handed to an embedded in-memory DuckDB as a BOUND parameter of `json_serialize_sql`, and the returned parse tree
 * is walked for `BASE_TABLE` and `TABLE_FUNCTION` nodes through CTEs, joins, subqueries, set operations and every
 * statement of a multi-statement text.
 *
 * ## What the connection can do
 *
 * Nothing but parse. It is `jdbc:duckdb:` (its own anonymous in-memory instance, sharing nothing with any LAKE pool),
 * opened with `enable_external_access=false` and extension auto-install and auto-load off, then `lock_configuration`
 * so no statement can turn them back on. The serializer parses; it never binds or executes the text — and if it ever
 * did, the connection could read no file, URL or extension. No customer connection is opened, so the scan may run
 * inside a metadata transaction (`ConnectionLease`'s rule does not apply).
 *
 * The rendered text is never logged: it may carry §12.6 sample values.
 *
 * ## Concurrency and lifetime
 *
 * One connection per extractor, opened lazily, every call serialized on it (DuckDB connections are not safe for
 * concurrent statements, and a parse takes well under a millisecond). [close] releases it; a later call reopens.
 */
class SqlTableReferenceExtractor(
    private val connect: () -> Connection = ::openParseOnlyConnection,
) : AutoCloseable {
    private val lock = Any()
    private var connection: Connection? = null
    private var engineFacts: EngineFacts? = null

    /**
     * The engine's internal view names. No default catalog (#790): this parse-only engine's own is `memory`, which says
     * nothing about the datasource a statement runs on — the scanner reads that datasource's ([DuckDbDefaultCatalog]).
     */
    data class EngineFacts(
        val internalViews: Set<String>,
    )

    /** The parsed table and function positions of [sql], or why the engine could not serialize it. */
    fun extract(sql: String): SqlTableExtraction =
        synchronized(lock) {
            val tree =
                connection().prepareStatement(SERIALIZE).use { statement ->
                    statement.setString(1, sql)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "json_serialize_sql returned no row" }
                        MAPPER.readTree(rows.getString(1))
                    }
                }
            read(tree)
        }

    /** The engine's own facts the resolver needs: its internal (system) view names. */
    fun engineFacts(): EngineFacts =
        synchronized(lock) {
            engineFacts ?: readEngineFacts(connection()).also { engineFacts = it }
        }

    override fun close() {
        synchronized(lock) {
            connection?.close()
            connection = null
        }
    }

    private fun connection(): Connection = connection?.takeUnless { it.isClosed } ?: connect().also { connection = it }

    private fun read(tree: JsonNode): SqlTableExtraction {
        if (tree.path("error").asBoolean(false)) {
            return SqlTableExtraction.Unscannable(
                errorType = tree.path("error_type").asText("unknown"),
                message = tree.path("error_message").asText("").take(MAX_MESSAGE),
            )
        }
        val tables = ArrayList<WrittenTableReference>()
        val functions = ArrayList<TableFunctionReference>()
        val statements = tree.path("statements")
        statements.forEachIndexed { index, statement -> walk(statement, emptySet(), index, tables, functions) }
        return SqlTableExtraction.Parsed(statements.size(), tables, functions)
    }

    /** Depth-first over the whole parse tree; a node's `cte_map` names are in scope for that node's subtree. */
    private fun walk(
        node: JsonNode,
        outerCtes: Set<String>,
        statementIndex: Int,
        tables: MutableList<WrittenTableReference>,
        functions: MutableList<TableFunctionReference>,
    ) {
        when {
            node.isArray -> {
                node.forEach { walk(it, outerCtes, statementIndex, tables, functions) }
            }

            node.isObject -> {
                val ctes = outerCtes + cteNames(node)
                when (node.path("type").asText()) {
                    BASE_TABLE -> tables += baseTable(node, ctes, statementIndex)
                    TABLE_FUNCTION -> functionName(node)?.let { functions += TableFunctionReference(it, statementIndex) }
                }
                node.properties().forEach { (_, child) -> walk(child, ctes, statementIndex, tables, functions) }
            }
        }
    }

    private fun cteNames(node: JsonNode): Set<String> =
        node
            .path("cte_map")
            .path("map")
            .mapNotNull { entry -> entry.path("key").textValue()?.lowercase() }
            .toSet()

    private fun baseTable(
        node: JsonNode,
        ctes: Set<String>,
        statementIndex: Int,
    ): WrittenTableReference {
        val catalog = node.path("catalog_name").textValue()?.takeIf { it.isNotEmpty() }
        val schema = node.path("schema_name").textValue()?.takeIf { it.isNotEmpty() }
        val table = node.path("table_name").asText()
        return WrittenTableReference(
            catalog = catalog,
            schema = schema,
            table = table,
            statementIndex = statementIndex,
            timeTravel = !node.path("at_clause").isMissingNode && !node.path("at_clause").isNull,
            cte = catalog == null && schema == null && table.lowercase() in ctes,
        )
    }

    private fun functionName(node: JsonNode): String? = node.path("function").path("function_name").textValue()

    companion object {
        /** The bound-parameter form — the text is a VALUE, never part of the statement (the probe's cast is required). */
        private const val SERIALIZE = "SELECT json_serialize_sql(CAST(? AS VARCHAR))"

        private const val BASE_TABLE = "BASE_TABLE"
        private const val TABLE_FUNCTION = "TABLE_FUNCTION"

        /** A serializer error message is bounded: it can quote a token of the rendered text. */
        const val MAX_MESSAGE = 200

        private val MAPPER = ObjectMapper()

        /**
         * The parse-only connection. Each setting is a connection-time option, so it holds before any statement runs;
         * `lock_configuration` last, so nothing can undo the others.
         */
        fun openParseOnlyConnection(): Connection {
            val properties =
                Properties().apply {
                    setProperty("enable_external_access", "false")
                    setProperty("autoinstall_known_extensions", "false")
                    setProperty("autoload_known_extensions", "false")
                    setProperty("lock_configuration", "true")
                }
            return DriverManager.getConnection("jdbc:duckdb:", properties)
        }

        private fun readEngineFacts(connection: Connection): EngineFacts =
            connection.createStatement().use { statement ->
                val views =
                    statement.executeQuery("SELECT view_name FROM duckdb_views() WHERE internal").use { rows ->
                        buildSet { while (rows.next()) add(rows.getString(1).lowercase()) }
                    }
                EngineFacts(views)
            }
    }
}
