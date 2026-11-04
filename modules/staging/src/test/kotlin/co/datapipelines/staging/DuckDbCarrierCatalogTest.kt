package co.datapipelines.staging

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
 *
 * The name-level pin is not enough on its own: the gate matches a carrier CALL in raw text, so it is
 * sound only while it recognises every spelling of that call the engine runs (#795 round two,
 * review F2). The engine-driven tripwires below ask the pinned engine, through raw duplicates of a
 * real staging database's locked root ([EngineUnderTest]), and fail with the class they found:
 *
 *  - **separators**: every code point U+0001–U+2FFFF between a carrier's name (bare or quoted) and
 *    its argument; whenever the engine runs the text, the gate must refuse it (non-vacuity: at least
 *    the 46 `query` forms measured on 1.5.5.1, 23 separators × bare/quoted, and 23 `query_table`);
 *  - **A**, unicode-escape identifiers, **B**, letter variants of a guarded name, and **D**, a
 *    separator inside one: the gate does not refuse these, so the ENGINE must keep refusing them —
 *    a driver bump that resolves one goes red here and the gate needs widening first.
 *
 * Labelled limit: the separator sweep stops at U+2FFFF (the orchestrator measured the engine
 * accepting nothing above it, 2026-11-03); A, B and D are negatives on the pinned engine only.
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

    @Test
    @Timeout(SWEEP_TIMEOUT_SECONDS)
    fun `every text the pinned engine runs as a carrier call is refused by the gate, code points U+0001 to U+2FFFF`() {
        EngineUnderTest(scratch).use { engine ->
            val forms =
                codePointsUpTo(SWEEP_LAST_CODE_POINT).flatMap { cp ->
                    listOf(CarrierForm("query", false, cp), CarrierForm("query", true, cp), CarrierForm("query_table", false, cp))
                }
            val started = System.nanoTime()
            val accepted = engine.accepted(forms, THREADS) { it.text }
            val wallMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            val bypasses = accepted.filter { passesGate(it.text) }.toSet()
            println("carrier sweep: statements=${forms.size} threads=$THREADS wall_ms=$wallMs accepted=${accepted.size}")
            accepted.forEach { println("  engine ran ${it.label}; gate ${if (it in bypasses) "PASSED IT (bypass)" else "refused"}") }

            val byCarrier = bypasses.groupingBy { if (it.quoted) "${it.carrier} quoted" else it.carrier }.eachCount()
            withClue("${bypasses.size} carrier text(s) the engine runs pass the gate $byCarrier: ${bypasses.map { it.label }}") {
                bypasses shouldBe emptySet()
            }
            accepted.count { it.carrier == "query" } shouldBeGreaterThanOrEqual MEASURED_QUERY_FORMS
            accepted.count { it.carrier == "query_table" } shouldBeGreaterThanOrEqual MEASURED_SEPARATORS
        }
    }

    @Test
    fun `the guarded calls run in their plain spelling, so a spelling the engine resolved would be seen`() {
        EngineUnderTest(scratch).use { engine ->
            GUARDED_CALLS.forEach { (name, args) ->
                withClue(name) { engine.runs("SELECT * FROM $name$args") shouldBe true }
            }
            engine.runs("SELECT * FROM disable_logging()") shouldBe true
        }
    }

    @Test
    fun `class A - a unicode-escape identifier spelling a guarded name stays refused by the engine`() {
        val forms =
            listOf(
                "SELECT * FROM U&\"\\0071uery\"('SELECT 1')",
                "SELECT * FROM U&\"\\0071uery_table\"('t')",
                "SELECT * FROM U&\"\\+000071uery\"('SELECT 1')",
                "SELECT * FROM U&\"!0071uery\" UESCAPE '!'('SELECT 1')",
                "SELECT * FROM U&\"truncate_duckdb_\\006Cogs\"()",
                "SELECT * FROM U&\"disable_\\006Cogging\"()",
                "SELECT * FROM U&\"enable_\\006Cogging\"(level = 'info', storage = 'memory')",
            )
        EngineUnderTest(scratch).use { engine ->
            val resolved = engine.accepted(forms, THREADS) { it }
            withClue("class A: the engine now resolves unicode-escape identifiers: $resolved") { resolved shouldBe emptyList() }
        }
    }

    @Test
    fun `class B - a letter variant of a guarded name stays refused by the engine`() {
        val variants = letterVariants()
        EngineUnderTest(scratch).use { engine ->
            val resolved = engine.accepted(variants, THREADS) { it.second }.map { it.first }
            println("class B: ${variants.size} letter variants of ${GUARDED_CALLS.size} names, resolved ${resolved.size}")
            withClue("class B: the engine resolves letter variants ${resolved.take(LISTED)}") { resolved shouldBe emptyList() }
        }
        variants.size shouldBeGreaterThanOrEqual ORCHESTRATOR_LETTER_VARIANTS
    }

    @Test
    fun `class D - a separator inside a guarded name stays refused by the engine`() {
        val forms = separatorsInsideNames()
        EngineUnderTest(scratch).use { engine ->
            val resolved = engine.accepted(forms, THREADS) { it.second }.map { it.first }
            println("class D: ${forms.size} forms, resolved ${resolved.size}")
            withClue("class D: the engine strips a separator inside a name ${resolved.take(LISTED)}") { resolved shouldBe emptyList() }
        }
        // Non-vacuity: the 18 non-ASCII separators the engine accepts are all inserted.
        forms.size shouldBeGreaterThanOrEqual MEASURED_NON_ASCII_SEPARATORS * GUARDED_CALLS.keys.sumOf { it.length - 1 }
    }

    private fun passesGate(sql: String): Boolean = runCatching { DuckDbAuthorSqlGate.check(sql) }.isSuccess

    private companion object {
        /** Separator sweep parallelism: duplicates of one root, at most eight. */
        val THREADS = minOf(MAX_SWEEP_THREADS, Runtime.getRuntime().availableProcessors())
        const val MAX_SWEEP_THREADS = 8
        const val SWEEP_TIMEOUT_SECONDS = 180L
        const val NANOS_PER_MILLI = 1_000_000L
        const val LISTED = 20

        /** Measured on 1.5.5.1 by the lander and the orchestrator (2026-11-03): 5 ASCII + 18 non-ASCII. */
        const val MEASURED_SEPARATORS = 23
        const val MEASURED_NON_ASCII_SEPARATORS = 18
        const val MEASURED_QUERY_FORMS = 2 * MEASURED_SEPARATORS

        /** The orchestrator's class-B count over four of these six names (2026-11-03 probe). */
        const val ORCHESTRATOR_LETTER_VARIANTS = 2_918

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
