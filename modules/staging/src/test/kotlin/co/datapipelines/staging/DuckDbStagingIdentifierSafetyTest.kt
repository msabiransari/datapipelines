package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.ResultSet
import java.sql.ResultSetMetaData

/**
 * The DuckDB twin of [H2StagingIdentifierSafetyTest]: an injection-shaped column label never reaches
 * generated DDL (§4.5), and a table name — quoted, never validated, as on H2 — names exactly one
 * table however it is spelled, through the DDL and through the appender alike.
 */
class DuckDbStagingIdentifierSafetyTest {
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

    @Test
    fun `every injection-shaped label is rejected and mutates no object`() {
        listOf(
            "",
            "A".repeat(64),
            "1leading_digit",
            "has space",
            "has\"quote",
            "ends;semicolon",
            "sql--comment",
            "x; DROP TABLE stg_victim; --",
        ).forEach { label ->
            val thrown =
                shouldThrow<StagingInvalidColumnNameException> {
                    runBlocking { staging.stage(singleLabelResultSet(label), "stg_target", Dialect.H2) }
                }
            thrown.code shouldBe StagingErrorCodes.INVALID_COLUMN_NAME
            runBlocking { staging.stats() }.tableCount shouldBe 0
        }
    }

    @Test
    fun `a quote-bearing table name names one table through DDL and appender`() {
        val name = "stg\"; DROP TABLE x; --"
        val staged =
            runBlocking { staging.stageRows(name, listOf(ColumnSchema("id", LogicalType.INTEGER)), sequenceOf(listOf(1), listOf(2))) }
        staged.rowsStaged shouldBe 2L
        staging.scalarText("SELECT table_name FROM duckdb_tables() WHERE NOT temporary") shouldBe name
        runBlocking { staging.stats() }.tableCount shouldBe 1
    }

    private fun singleLabelResultSet(label: String): ResultSet {
        val meta =
            mockk<ResultSetMetaData> {
                every { columnCount } returns 1
                every { getColumnLabel(1) } returns label
            }
        return mockk { every { metaData } returns meta }
    }
}
