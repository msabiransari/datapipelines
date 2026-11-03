package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
}
