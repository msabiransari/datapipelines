package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.Properties

/**
 * #816 A4 — pins the function catalog [co.datapipelines.typesystem.jdbc.DuckDbAuthorSqlGate] was
 * reasoned over, on a datasource connection (owner ruling 2026-11-03; lane #795's staging copy is
 * the same design): the gate refuses the logging functions and the two SQL-text carriers by NAME,
 * which is sound only while the engine has no other function that runs SQL text or touches
 * logging. A driver bump that adds one — or any function at all — turns this red, and the bump
 * must re-review the gate before it is green.
 *
 * Measured on a connection opened with [DuckdbDialectAdapter]'s own open properties (extensions
 * not autoloaded), with the filter of lane #795 probe 11: every function whose name or description
 * speaks of logging, SQL, queries, execution, evaluation or serialization. Of these, three
 * execute SQL text — the gate's two carriers and one of its refused names; one aggregate family
 * takes a function NAME but resolves aggregates only; the rest read or serialize text without
 * executing it.
 *
 * Scope, stated: a `LAKE` instance also LOADs `httpfs`, `aws` and (Iceberg kinds) `avro` +
 * `iceberg`, whose functions are not in this catalog — they need the extension files, which a CI
 * runner does not have offline. The lane measured them once (handback, evidence
 * `lake-extension-catalog.out`); a driver or extension bump re-measures by hand.
 */
class DuckDbCarrierCatalogTest {
    @Test
    fun `the pinned driver's SQL-text and logging functions are exactly the reviewed set`() {
        val properties =
            Properties().apply {
                DialectAdapters.forDialect(Dialect.DUCKDB).defaultProperties.forEach { (k, v) -> setProperty(k, v) }
            }
        val (filtered, total) =
            DriverManager.getConnection("jdbc:duckdb:", properties).use { c ->
                val rows =
                    c.createStatement().use { st ->
                        st.executeQuery(FILTER).use { rs ->
                            buildList { while (rs.next()) add("${rs.getString(1)} | ${rs.getString(2)}") }
                        }
                    }
                val count =
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT count(DISTINCT function_name) FROM duckdb_functions()").use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
                rows to count
            }

        filtered shouldBe REVIEWED
        total shouldBe REVIEWED_TOTAL
    }

    private companion object {
        const val FILTER =
            "SELECT DISTINCT function_name, function_type FROM duckdb_functions() " +
                "WHERE function_name ILIKE '%log%' OR function_name ILIKE '%sql%' OR function_name ILIKE '%query%' " +
                "OR function_name ILIKE '%exec%' OR function_name ILIKE '%eval%' OR function_name ILIKE '%serializ%' " +
                "OR alias_of ILIKE '%logging%' OR description ILIKE '%SQL string%' OR description ILIKE '%query string%' " +
                "OR description ILIKE '%execute%' ORDER BY 1, 2"

        /** Distinct function names on DuckDB 1.5.6 (driver 1.5.6.0), re-measured 2026-11-13 (lane #936; five ICU collations left). */
        const val REVIEWED_TOTAL = 938L

        val REVIEWED =
            listOf(
                "aggregate | scalar",
                "array_aggr | scalar",
                "array_aggregate | scalar",
                "current_catalog | macro",
                "current_query | macro",
                "current_query | scalar",
                "current_query_id | scalar",
                "disable_logging | table",
                "disable_verify_serializer | pragma",
                "duckdb_log_contexts | table",
                "duckdb_logs | table",
                "duckdb_logs_parsed | table_macro",
                "enable_logging | table",
                "json_deserialize_sql | scalar",
                "json_execute_serialized_sql | pragma",
                "json_execute_serialized_sql | table",
                "json_serialize_plan | scalar",
                "json_serialize_sql | scalar",
                "list_aggr | scalar",
                "list_aggregate | scalar",
                "log | scalar",
                "log10 | scalar",
                "log2 | scalar",
                "parse_duckdb_log_message | scalar",
                "query | table",
                "query_table | table",
                "truncate_duckdb_logs | table",
                "verify_serializer | pragma",
                "write_log | scalar",
            )
    }
}
