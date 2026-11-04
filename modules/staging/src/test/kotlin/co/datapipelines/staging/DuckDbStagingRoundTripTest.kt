package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.JsonEncoder
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.LogicalTypeMapping
import co.datapipelines.typesystem.TypeMappers
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.ResultSet
import java.util.Base64

/**
 * The DuckDB twin of [H2StagingRoundTripTest]: every canonical type staged into DuckDB reads back
 * to the same JSON wire form. Read back the way the executor reads a tempdb result — DuckDB's
 * ingress mapper for the descriptors (`ctx.tempdbDialect` = DUCKDB) and the two source read rules
 * — so the test covers the egress mapping (type-system.md §6a) AND its inverse.
 */
class DuckDbStagingRoundTripTest {
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
    fun `every canonical type round-trips losslessly to the JSON wire form`() {
        SourceDb().use { src ->
            src.exec(
                """
                CREATE TABLE t (
                  i INTEGER, big BIGINT, dec DECIMAL(10, 2), bigdec NUMERIC(20, 4), wide NUMERIC(38, 6),
                  appx DOUBLE PRECISION, flag BOOLEAN, txt VARCHAR(50), bin VARBINARY(16),
                  d DATE, tm TIME, ts TIMESTAMP WITH TIME ZONE
                )
                """.trimIndent(),
            )
            src.exec(
                """
                INSERT INTO t VALUES (
                  42, 9007199254740993, 1234.56, 12345678901234.5678, 12345678901234567890123456789012.123456,
                  3.141592653589793, TRUE, 'hello', X'DEADBEEF',
                  DATE '2026-08-05', TIME '14:30:00', TIMESTAMP WITH TIME ZONE '2026-08-05 14:30:00-05:00'
                )
                """.trimIndent(),
            )
            src.exec("INSERT INTO t VALUES (NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
            runBlocking { staging.stage(src.query("SELECT * FROM t"), "stg_types", Dialect.H2) }
        }

        val rows = readStagedAsWire("SELECT * FROM \"stg_types\" ORDER BY i NULLS LAST")
        rows.size shouldBe 2
        val first = rows[0]
        first[0] shouldBe 42L
        first[1] shouldBe "9007199254740993"
        first[2] shouldBe BigDecimal("1234.56")
        first[3] shouldBe "12345678901234.5678"
        first[4] shouldBe "12345678901234567890123456789012.123456"
        first[5] shouldBe 3.141592653589793
        first[6] shouldBe true
        first[7] shouldBe "hello"
        first[8] shouldBe Base64.getEncoder().encodeToString(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        first[9] shouldBe "2026-08-05"
        first[10] shouldBe "14:30:00.000000"
        // 14:30 at -05:00 is 19:30 UTC; the driver reads at the JVM's offset, the instant is what counts.
        first[11] shouldBe "2026-08-05T19:30:00.000000Z"
        rows[1].forEach { it shouldBe null }
    }

    @Test
    fun `a staged table restaged from a DuckDB cursor keeps every value - tempdb to tempdb`() {
        val columns =
            listOf(
                ColumnSchema("id", LogicalType.BIGINTEGER),
                ColumnSchema("amount", LogicalType.DECIMAL, 12, 2),
                ColumnSchema("at", LogicalType.TIMESTAMP),
            )
        runBlocking {
            staging.stageRows(
                "stg_src",
                columns,
                sequenceOf(listOf(1L, BigDecimal("10.25"), "2026-01-02T03:04:05Z"), listOf(2L, BigDecimal("-0.01"), null)),
            )
            // A nested lease: the cursor holds one connection while the restage takes others.
            staging.withQuery("SELECT * FROM \"stg_src\" ORDER BY id") { rs -> staging.stage(rs, "stg_copy", Dialect.DUCKDB) }
        }.rowsStaged shouldBe 2L

        readStagedAsWire("SELECT * FROM \"stg_copy\" ORDER BY id") shouldBe
            listOf(listOf("1", BigDecimal("10.25"), "2026-01-02T03:04:05.000000Z"), listOf("2", BigDecimal("-0.01"), null))
    }

    @Test
    fun `the staged descriptors carry the expected canonical types`() {
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (big BIGINT, bigdec NUMERIC(20, 4), ts TIMESTAMP WITH TIME ZONE)")
            src.exec("INSERT INTO t VALUES (1, 2.0, TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00Z')")
            val result = runBlocking { staging.stage(src.query("SELECT * FROM t"), "stg_desc", Dialect.H2) }
            result.columns.map { it.type } shouldBe listOf(LogicalType.BIGINTEGER, LogicalType.BIGDECIMAL, LogicalType.TIMESTAMP)
        }
        // The DuckDB column types the egress mapper chose (§6a), read from the engine's catalog.
        staging.scalarText(
            "SELECT string_agg(data_type, ',' ORDER BY column_index) FROM duckdb_columns() WHERE table_name = 'stg_desc'",
        ) shouldBe "BIGINT,DECIMAL(20,4),TIMESTAMP WITH TIME ZONE"
    }

    /** Reads staged rows back as the executor does, encoding each value to its wire form. */
    private fun readStagedAsWire(sql: String): List<List<Any?>> =
        runBlocking {
            staging.withQuery(sql) { rs ->
                val columns = stagedColumns(rs)
                buildList {
                    while (rs.next()) {
                        add(
                            columns.mapIndexed { i, col ->
                                val mapping = LogicalTypeMapping(col.type, col.precision, col.scale)
                                JsonEncoder.encode(SourceValueReader.readValue(rs, i + 1, mapping), col)
                            },
                        )
                    }
                }
            }
        }

    private fun stagedColumns(rs: ResultSet): List<ColumnSchema> {
        val meta = rs.metaData
        return (1..meta.columnCount).map { i ->
            TypeMappers
                .forDialect(Dialect.DUCKDB)
                .mapColumn(meta.getColumnLabel(i), meta.getColumnType(i), meta.getPrecision(i), meta.getScale(i), meta.getColumnTypeName(i))
                .column
        }
    }
}
