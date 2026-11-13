package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.jdbc.DuckDbAuthorSqlGate
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.sql.SQLException

/**
 * The TEMPORARY raw-text gate (owner ruling 2026-11-03; staging.md §8.5). Two halves: the text rules
 * on [DuckDbAuthorSqlGate.check] alone, and every author route of a real staging database reaching
 * the gate — `withConnection` statements, prepared statements and batches, `withQuery` and
 * `execute` — with DP's own staging path (`stageRows`) left ungated.
 *
 * Falsified at birth (handback): with `check` reduced to a no-op, every refusal case here goes red.
 */
class DuckDbAuthorSqlGateTest {
    @TempDir
    lateinit var scratch: Path

    private lateinit var staging: DuckDbStaging

    @BeforeEach
    fun open() {
        staging = duckFactory(scratch).open()
    }

    @AfterEach
    fun close() {
        staging.close()
    }

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
        forms.forEach { sql -> refusedText(sql).sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE }
    }

    @Test
    fun `a call to either SQL-text carrier is refused whatever separates the name from its argument`() {
        val forms =
            listOf(
                "SELECT * FROM query('SELECT 1')",
                "SELECT * FROM QUERY ('SELECT 1')",
                "SELECT * FROM query\n\t('SELECT 1')",
                "SELECT * FROM query/* c */('SELECT 1')",
                "SELECT * FROM query-- c\n('SELECT 1')",
                "SELECT * FROM \"query\"('SELECT 1')",
                "SELECT * FROM main.query('SELECT 1')",
                "SELECT * FROM query_table('t')",
                "SELECT * FROM Query_Table(['t'])",
                "CREATE MACRO m(x) AS TABLE SELECT * FROM query(x)",
                "SELECT * FROM query('SELECT * FROM enable_' || 'logging()')",
            )
        forms.forEach { sql -> refusedText(sql).message shouldContain "`query` or `query_table`" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonAsciiSeparatorCalls")
    fun `a carrier call is refused when a non-ASCII separator the engine accepts precedes its argument`(
        form: String,
        sql: String,
    ) {
        // Non-vacuity: the case carries the separator its name says. Written as an escape, so an
        // editor cannot strip it unseen; without it the case would be an ASCII one the old rule refused.
        val named = form.substringBefore(' ').removePrefix("U+").toInt(HEX)
        withClue("$form carries U+%04X".format(named)) { sql.codePoints().anyMatch { it == named } shouldBe true }
        withClue(form) { refusedText(sql).message shouldContain "`query` or `query_table`" }
    }

    @Test
    fun `the widened rule still passes the bare word - a column, a string, an alias`() {
        // These pin that the separator class did not drift into refusing the word itself (owner
        // ruling 2026-11-03: only a CALL is refused).
        listOf(
            "SELECT query FROM t",
            "SELECT 'query' AS x",
            "SELECT 1 AS query",
            "SELECT query\u00A0FROM t",
            "SELECT 1 AS query\uFEFF",
        ).forEach { sql -> shouldNotThrowAny { DuckDbAuthorSqlGate.check(sql) } }
    }

    @Test
    fun `ordinary SQL that merely mentions the words passes`() {
        listOf(
            "SELECT count(*) AS query_count FROM t",
            "CREATE TABLE \"stg_out\" AS SELECT 1 AS query",
            "SELECT current_query()",
            "SELECT 'query' AS s",
            "SELECT log(10), write_log('x')",
        ).forEach { sql -> shouldNotThrowAny { DuckDbAuthorSqlGate.check(sql) } }
    }

    @Test
    fun `the refusal names what it found and never echoes the author's text`() {
        val e = refusedText("SELECT 'customer secret 4111' AS s, * FROM enable_logging()")
        e.message shouldContain "`enable_logging`"
        e.message shouldContain "temporary"
        e.message shouldNotContain "4111"
    }

    @Test
    fun `every author route reaches the gate`() {
        val sql = "SELECT * FROM query('SELECT 1')"
        runBlocking {
            staging.withConnection { c ->
                shouldThrow<SQLException> { c.createStatement().use { it.execute(sql) } }.sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE
                shouldThrow<SQLException> { c.createStatement().use { it.executeQuery(sql) } }
                shouldThrow<SQLException> { c.createStatement().use { it.executeUpdate(sql) } }
                shouldThrow<SQLException> { c.createStatement().use { it.executeLargeUpdate(sql) } }
                shouldThrow<SQLException> { c.createStatement().use { it.addBatch(sql) } }
                shouldThrow<SQLException> { c.prepareStatement(sql) }
                // The statement's connection is the guarded one: no route back to an ungated statement.
                shouldThrow<SQLException> { c.createStatement().use { st -> st.connection.createStatement().use { it.execute(sql) } } }
            }
            shouldThrow<SQLException> { staging.withQuery(sql) { it.next() } }.sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE
            shouldThrow<SQLException> { staging.execute("CALL enable_logging()") }.sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE
        }
        // The database is untouched by the refusals: logging never came on.
        staging.scalarText("SELECT count(*) FROM duckdb_logs()") shouldBe "0"
    }

    @Test
    fun `a separator-hidden carrier through execute is refused before the driver, and the database lives on`() {
        // Review F3, staging level: a no-break space between the carrier and its argument, and an
        // argument that assembles the logging call (memory storage only — lane rule). With the gate
        // out of the way the pinned engine runs this exact statement and logging comes on (round-two
        // probe); gated, it must never reach the driver.
        staging.authorExec("CREATE TABLE stg_kept AS SELECT 7 AS x")
        val sql = "CREATE TABLE stg_reach AS SELECT * FROM query\u00A0($ASSEMBLED_LOGGING_CALL)"

        val outcome = runCatching { runBlocking { staging.execute(sql) } }

        withClue("the engine's logging setting after the statement") { loggingSetting() shouldBe "0" }
        val refusal = outcome.exceptionOrNull().shouldBeInstanceOf<SQLException>()
        refusal.sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE
        refusal.message shouldContain "temporary guard"
        // A second lease answers, and what was staged before is still there: no abort, no damage.
        runBlocking { staging.withQuery("SELECT 42") { rs -> if (rs.next()) rs.getInt(1) else null } } shouldBe 42
        staging.scalarText("SELECT x FROM stg_kept") shouldBe "7"
    }

    /**
     * The setting read through the author path — the name split so the read is not itself refused.
     * The pinned engine reports it as `0` or `1` (round-two probe).
     */
    private fun loggingSetting(): String? = staging.scalarText("SELECT value FROM duckdb_settings() WHERE name = 'enable_' || 'logging'")

    @Test
    fun `DP's own staging path is not gated, so the false positive only bites author SQL`() {
        runBlocking {
            staging.stageRows("stg_cols", listOf(ColumnSchema("enable_logging", LogicalType.INTEGER)), sequenceOf(listOf(1)))
        }
        // The column exists; reading it by name from author SQL is the accepted false positive.
        shouldThrow<SQLException> { staging.scalarText("SELECT \"enable_logging\" FROM \"stg_cols\"") }
        staging.scalarText("SELECT * FROM \"stg_cols\"") shouldBe "1"
    }

    @Test
    fun `a non-ASCII spelling the gate does not fold does not resolve in the engine either`() {
        // Kotlin folds case over Unicode, the engine over ASCII only: a dotted capital I passes the
        // gate's lower-casing unchanged, and must then be an unknown function to the engine.
        val sql = "CALL ENABLE_LOGGİNG()"
        shouldNotThrowAny { DuckDbAuthorSqlGate.check(sql) }
        shouldThrow<SQLException> { staging.authorExec(sql) }.message shouldContain "does not exist"
        staging.scalarText("SELECT count(*) FROM duckdb_logs()") shouldBe "0"
    }

    private companion object {
        /** SQL text that assembles the logging call at run time, in memory storage: no refused name appears whole. */
        const val ASSEMBLED_LOGGING_CALL = "'SELECT * FROM enable_' || 'logging(level = ''info'', storage = ''memory'')'"

        const val HEX = 16

        /**
         * The lander's three named separators (#795 review F1), quoted and unquoted, plus a carrier
         * whose separator run ends in a comment opener and the second carrier's name. Each is a text
         * the pinned engine runs as a carrier call (measured, round-two evidence); the full sweep is
         * `DuckDbCarrierCatalogTest`.
         */
        @JvmStatic
        fun nonAsciiSeparatorCalls(): List<Arguments> =
            listOf(
                Arguments.of("U+00A0 unquoted", "SELECT * FROM query\u00A0('SELECT 1')"),
                Arguments.of("U+00A0 quoted", "SELECT * FROM \"query\"\u00A0('SELECT 1')"),
                Arguments.of("U+200B unquoted", "SELECT * FROM query\u200B('SELECT 1')"),
                Arguments.of("U+200B quoted", "SELECT * FROM \"query\"\u200B('SELECT 1')"),
                Arguments.of("U+FEFF unquoted", "SELECT * FROM query\uFEFF('SELECT 1')"),
                Arguments.of("U+FEFF quoted", "SELECT * FROM \"query\"\uFEFF('SELECT 1')"),
                Arguments.of("U+2003 query_table", "SELECT * FROM query_table\u2003('t')"),
                Arguments.of("U+3000 then a block comment", "SELECT * FROM query\u3000/* c */('SELECT 1')"),
                Arguments.of("U+2060 then a line comment", "SELECT * FROM query\u2060-- c\n('SELECT 1')"),
            )
    }
}
