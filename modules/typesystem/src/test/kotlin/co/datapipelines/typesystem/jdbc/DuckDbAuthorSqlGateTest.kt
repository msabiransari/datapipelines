package co.datapipelines.typesystem.jdbc

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * The TEMPORARY raw-text gate's text rules (datasources.md §5.8, staging.md §8.5), on
 * [DuckDbAuthorSqlGate.check] alone — no driver. The engine-driven halves live in `datasources`,
 * where the pinned DuckDB driver is on the test classpath: `DuckDbAuthorSqlGuardSweepTest` asks the
 * engine which separators it accepts, `DuckDbCarrierCatalogTest` pins the function set.
 *
 * Falsified at birth (lane #816 handback, gate G1): with one code point removed from
 * [DuckDbAuthorSqlGate.SEPARATOR], the separator case goes red on that code point's forms.
 */
class DuckDbAuthorSqlGateTest {
    private fun refusedText(sql: String): SQLException = shouldThrow<SQLException> { DuckDbAuthorSqlGate.check(sql) }

    @Test
    fun `each refused name is refused in any case, inside strings and comments too`() {
        val forms =
            listOf(
                "CALL enable_logging()",
                "SELECT * FROM ENABLE_LOGGING()",
                "CALL disable_logging()",
                "SELECT * FROM Truncate_DuckDB_Logs()",
                "PRAGMA json_execute_serialized_sql('{}')",
                "SELECT 'enable_logging' AS s",
                "SELECT 1 -- disable_logging",
                "SELECT 1 /* truncate_duckdb_logs */",
            )
        forms.forEach { sql -> withClue(sql) { refusedText(sql).sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE } }
    }

    @Test
    fun `a call to either SQL-text carrier is refused whatever ASCII separates the name from its argument`() {
        val forms =
            listOf(
                "SELECT * FROM query('SELECT 1')",
                "SELECT * FROM QUERY ('SELECT 1')",
                "SELECT * FROM query\n\t('SELECT 1')",
                "SELECT * FROM query\u000C\r('SELECT 1')",
                "SELECT * FROM query/* c */('SELECT 1')",
                "SELECT * FROM query-- c\n('SELECT 1')",
                "SELECT * FROM \"query\"('SELECT 1')",
                "SELECT * FROM main.query('SELECT 1')",
                "SELECT * FROM query_table('t')",
                "SELECT * FROM Query_Table(['t'])",
                "CREATE MACRO m(x) AS TABLE SELECT * FROM query(x)",
                "SELECT * FROM query('SELECT * FROM enable_' || 'logging()')",
            )
        forms.forEach { sql -> withClue(sql) { refusedText(sql).message shouldContain "`query` or `query_table`" } }
    }

    @Test
    fun `every separator the pinned engine accepts is in the separator class`() {
        ENGINE_SEPARATORS.forEach { cp ->
            withClue("U+%04X".format(cp)) {
                Regex(DuckDbAuthorSqlGate.SEPARATOR).matches(String(Character.toChars(cp))) shouldBe true
            }
        }
        ENGINE_SEPARATORS.size shouldBe ENGINE_SEPARATOR_COUNT
    }

    @Test
    fun `every engine separator between a carrier and its argument is refused, quoted and unquoted`() {
        ENGINE_SEPARATORS.forEach { cp ->
            val sep = String(Character.toChars(cp))
            listOf("query", "\"query\"", "query_table", "\"QUERY_TABLE\"").forEach { name ->
                val sql = "SELECT * FROM $name$sep('SELECT 1')"
                withClue("U+%04X after %s".format(cp, name)) {
                    refusedText(sql).sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE
                }
            }
            withClue("U+%04X run before a comment opener".format(cp)) {
                refusedText("SELECT * FROM query$sep$sep/* c */('SELECT 1')").sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE
            }
        }
    }

    @Test
    fun `the four non-ASCII forms the review replayed are refused, quoted and unquoted`() {
        listOf(0x00A0, 0x200B, 0xFEFF, 0x3000).forEach { cp ->
            val sep = String(Character.toChars(cp))
            refusedText("SELECT * FROM query$sep('SELECT 1')").sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE
            refusedText("SELECT * FROM \"query\"$sep('SELECT 1')").sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE
        }
    }

    @Test
    fun `ordinary SQL that merely mentions the words passes`() {
        listOf(
            "SELECT count(*) AS query_count FROM t",
            "CREATE TABLE \"stg_out\" AS SELECT 1 AS query",
            "SELECT current_query()",
            "SELECT 'query' AS s",
            "SELECT query FROM t",
            "SELECT log(10), write_log('x')",
            "SELECT * FROM my_query('x')",
        ).forEach { sql -> withClue(sql) { shouldNotThrowAny { DuckDbAuthorSqlGate.check(sql) } } }
    }

    @Test
    fun `the refusal names what it found and never echoes the author's text`() {
        val e = refusedText("SELECT 'customer secret 4111' AS s, * FROM enable_logging()")
        (e is DuckDbAuthorSqlRefusal) shouldBe true
        e.message shouldContain "`enable_logging`"
        e.message shouldContain "temporary"
        e.message shouldNotContain "4111"
        val carrier = refusedText("SELECT 'customer secret 4111' AS s, * FROM query('x')")
        carrier.message shouldNotContain "4111"
    }

    @Test
    fun `a ten-kilobyte statement passes the gate in microseconds, with and without the carrier word`() {
        // Without the word the literal pre-filter skips the regex; with it on every column the
        // regex runs over the whole text — the worst case an ordinary statement can reach.
        listOf("plain" to "column_", "worded" to "query_").forEach { (kind, prefix) ->
            val sql = "SELECT " + (1..1_000).joinToString(", ") { "$prefix$it" } + " FROM t"
            repeat(WARMUP) { DuckDbAuthorSqlGate.check(sql) }
            val started = System.nanoTime()
            repeat(TIMED) { DuckDbAuthorSqlGate.check(sql) }
            val perCallMicros = (System.nanoTime() - started) / TIMED / NANOS_PER_MICRO
            println("event=gate_cost kind=$kind length=${sql.length} per_call_us=$perCallMicros")
            (sql.length >= TEN_KB) shouldBe true
            // A generous ceiling: the claim is microseconds, not a benchmark.
            (perCallMicros < CEILING_MICROS) shouldBe true
        }
    }

    private companion object {
        /**
         * The separators the pinned DuckDB 1.5.6.0 accepts (2026-11-13) between a function name and its `(`
         * (lane #795 review sweep, U+0001–U+2FFFF; re-measured by `DuckDbAuthorSqlGuardSweepTest`).
         */
        val ENGINE_SEPARATORS: List<Int> =
            listOf(0x09, 0x0A, 0x0C, 0x0D, 0x20, 0x00A0) + (0x2000..0x200B) + listOf(0x202F, 0x205F, 0x2060, 0x3000, 0xFEFF)

        const val ENGINE_SEPARATOR_COUNT = 23
        const val WARMUP = 2_000
        const val TIMED = 2_000
        const val NANOS_PER_MICRO = 1_000L
        const val TEN_KB = 10_000
        const val CEILING_MICROS = 2_000L
    }
}
