package co.datapipelines.staging

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins the function catalog [DuckDbAuthorSqlGate] was reasoned over (owner ruling 2026-11-03): the
 * gate refuses the logging functions and the two SQL-text carriers by NAME, which is sound only while
 * the engine has no other function that runs SQL text or touches logging. A driver bump that adds one
 * — or any function at all — turns this red, and the bump must re-review the gate before it is green.
 *
 * Measured on a staging AUTHOR connection (extensions not autoloaded), with the filter of lane #795
 * probe 11: every function whose name or description speaks of logging, SQL, queries, execution,
 * evaluation or serialization. Of these, `query`, `query_table` and `json_execute_serialized_sql`
 * execute SQL text; `list_aggregate` and its aliases take a function NAME but resolve aggregates only;
 * the rest read logs or text without executing it.
 */
class DuckDbCarrierCatalogTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `the pinned driver's SQL-text and logging functions are exactly the reviewed set`() {
        val staging = duckFactory(scratch).open()
        val (filtered, total) =
            try {
                runBlocking {
                    staging.withConnection { c ->
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
                }
            } finally {
                staging.close()
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

        /** Distinct function names on DuckDB 1.5.5 (driver 1.5.5.1), probe 11. */
        const val REVIEWED_TOTAL = 943L

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
