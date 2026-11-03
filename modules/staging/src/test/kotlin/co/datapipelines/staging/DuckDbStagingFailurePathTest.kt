package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.SQLException

/**
 * The DuckDB twin of [H2StagingFailurePathTest]: each failure a stage can meet surfaces as its
 * catalogued code, rolls its partial table back and frees the name; an author's own SQL error stays
 * the driver's exception. Plus the DuckDB-only refusal (owner ruling 2026-11-03): an exact numeric
 * DuckDB cannot store exactly is refused as `precision_overflow` BEFORE any table exists.
 */
class DuckDbStagingFailurePathTest {
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

    private fun stageOne(
        table: String,
        column: ColumnSchema,
        value: Any?,
    ): StageResult = runBlocking { staging.stageRows(table, listOf(column), sequenceOf(listOf(value))) }

    private fun tableCount(): Int = runBlocking { staging.stats() }.tableCount

    @Test
    fun `a 39-digit value fails with value_overflow instead of landing as NULL`() {
        // The pinned driver binds a >38-digit BigDecimal as NULL through setObject, with no error
        // (lane #795 probe 16); the appender path checks precision before the value is appended.
        val thrown =
            shouldThrow<StagingValueOverflowException> {
                stageOne("stg_wide", ColumnSchema("n", LogicalType.BIGDECIMAL, 38, 0), BigDecimal("1".repeat(39)))
            }

        thrown.code shouldBe StagingErrorCodes.VALUE_OVERFLOW
        thrown.message.orEmpty() shouldContain "stg_wide"
        thrown.message.orEmpty() shouldContain "DECIMAL(38, 0)"
        // Never the value itself: it is user data.
        thrown.message.orEmpty() shouldNotContain "1".repeat(39)
        tableCount() shouldBe 0
    }

    @Test
    fun `a source value past the staged column capacity fails with value_overflow and frees the name`() {
        shouldThrow<StagingValueOverflowException> {
            stageOne("stg_retry", ColumnSchema("n", LogicalType.DECIMAL, 3, 0), BigDecimal("99999"))
        }
        tableCount() shouldBe 0
        runBlocking { staging.stats() }.totalRows shouldBe 0L

        stageOne("stg_retry", ColumnSchema("n", LogicalType.DECIMAL, 3, 0), BigDecimal("999")).rowsStaged shouldBe 1L
        runBlocking { staging.stats() }.totalRows shouldBe 1L
    }

    @Test
    fun `an exact-unsized numeric source is refused as precision_overflow before any table exists`() {
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (v DECFLOAT(100000))")
            src.exec("INSERT INTO t VALUES (5.09)")
            val thrown =
                shouldThrow<DatapipelinesException> {
                    runBlocking { staging.stage(src.query("SELECT v AS \"v\" FROM t"), "stg_unsized", Dialect.H2) }
                }
            thrown.code shouldBe StagingErrorCodes.PRECISION_OVERFLOW
            thrown.details["column"] shouldBe "v"
            thrown.message.orEmpty() shouldContain "CAST"
        }
        tableCount() shouldBe 0
        // The name was never taken: a CAST retry under the same name stages.
        stageOne("stg_unsized", ColumnSchema("v", LogicalType.DECIMAL, 10, 2), BigDecimal("5.09")).rowsStaged shouldBe 1L
    }

    @Test
    fun `a declared precision above 38 is refused naming the column`() {
        val thrown =
            shouldThrow<DatapipelinesException> {
                stageOne("stg_p40", ColumnSchema("amount", LogicalType.BIGDECIMAL, 40, 2), null)
            }
        thrown.code shouldBe StagingErrorCodes.PRECISION_OVERFLOW
        thrown.details["column"] shouldBe "amount"
        thrown.details["precision"] shouldBe 40
        tableCount() shouldBe 0
    }

    @Test
    fun `an author's own SQL error stays the driver's exception`() {
        val thrown = shouldThrow<SQLException> { runBlocking { staging.execute("SELEC 1") } }
        thrown.message.orEmpty() shouldContain "Parser Error"
    }

    @Test
    fun `a source that fails mid-drain rolls its partial table back and rethrows the source's failure`() {
        val rows =
            sequence {
                repeat(2500) { yield(listOf<Any?>(it)) }
                error("source cursor broke")
            }
        val thrown =
            shouldThrow<IllegalStateException> {
                runBlocking { staging.stageRows("stg_broken", listOf(ColumnSchema("id", LogicalType.INTEGER)), rows) }
            }
        thrown.message shouldBe "source cursor broke"
        tableCount() shouldBe 0
    }

    @Test
    fun `a table created through execute makes a later stage of that name a catalogued failure`() {
        runBlocking { staging.execute("CREATE TABLE \"stg_ddl\" (\"id\" INTEGER)") }
        val thrown = shouldThrow<StagingTableAlreadyExistsException> { stageOne("stg_ddl", ColumnSchema("id", LogicalType.INTEGER), 1) }
        thrown.code shouldBe StagingErrorCodes.TABLE_ALREADY_EXISTS
        thrown.tableName shouldBe "stg_ddl"
        // The author's table is untouched by the refused stage.
        staging.scalarText("SELECT count(*) FROM \"stg_ddl\"") shouldBe "0"
    }

    @Test
    fun `the duplicate-table message the mapping keys on is the one DuckDB actually reports`() {
        runBlocking { staging.execute("CREATE TABLE \"stg_state\" (\"id\" INTEGER)") }
        val observed = shouldThrow<SQLException> { staging.authorExec("CREATE TABLE \"stg_state\" (\"id\" INTEGER)") }
        observed.message.orEmpty() shouldContain "Catalog Error: Table with name \"stg_state\" already exists"
    }

    @Test
    fun `a row whose arity differs from the columns fails the stage and rolls its partial table back`() {
        val columns = listOf(ColumnSchema("a", LogicalType.INTEGER), ColumnSchema("b", LogicalType.INTEGER))
        val thrown =
            shouldThrow<IllegalArgumentException> {
                runBlocking { staging.stageRows("stg_arity", columns, sequenceOf(listOf(1, 2), listOf(3))) }
            }
        thrown.message.orEmpty() shouldContain "1 values for 2 columns"
        tableCount() shouldBe 0
    }

    @Test
    fun `stats reports tables created outside stage`() {
        runBlocking { staging.execute("CREATE TABLE \"stg_by_sql\" (\"id\" INTEGER)") }
        tableCount() shouldBe 1
    }

    @Test
    fun `execute reports affected rows, and zero for DDL where the driver says -1`() {
        runBlocking { staging.execute("CREATE TABLE \"stg_n\" AS SELECT range AS id FROM range(5)") } shouldBe 0L
        runBlocking { staging.execute("INSERT INTO \"stg_n\" SELECT range FROM range(3)") } shouldBe 3L
    }
}
