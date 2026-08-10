package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.H2IngressMapper
import co.datapipelines.typesystem.JsonEncoder
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.Base64
import java.util.UUID

/**
 * The value-level round-trip that could only live in this module (staging.md §12,
 * type-system.md §11.3): **source value → H2 staging → JSON wire → asserted equal to source**.
 *
 * It exercises the whole staging value path against real cursors: `H2IngressMapper` deriving
 * the source canonical types, `SourceValueReader` reading them (getString/getObject rules),
 * `H2EgressMapper` shaping the DDL and bind types, then reading the staged rows back with
 * `H2IngressMapper.fromH2` + `StagedValueReader` and encoding with the typesystem `JsonEncoder`.
 *
 * The load-bearing cases: BIGINTEGER beyond the double-safe range, BIGDECIMAL scale fidelity,
 * and a `TIMESTAMP WITH TIME ZONE` read back **unshifted** as UTC.
 */
class H2StagingRoundTripTest {
    private val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())

    @AfterEach
    fun tearDown() = staging.close()

    @Test
    fun `every canonical type round-trips losslessly to the JSON wire form`() {
        SourceDb().use { src ->
            src.exec(
                """
                CREATE TABLE t (
                  i        INTEGER,
                  big      BIGINT,
                  dec      DECIMAL(10, 2),
                  bigdec   NUMERIC(20, 4),
                  appx     DOUBLE PRECISION,
                  flag     BOOLEAN,
                  txt      VARCHAR(50),
                  bin      VARBINARY(16),
                  d        DATE,
                  tm       TIME,
                  ts       TIMESTAMP WITH TIME ZONE
                )
                """.trimIndent(),
            )
            src.exec(
                """
                INSERT INTO t VALUES (
                  42, 9007199254740993, 1234.56, 12345678901234.5678,
                  3.141592653589793, TRUE, 'hello', X'DEADBEEF',
                  DATE '2026-08-05', TIME '14:30:00',
                  TIMESTAMP WITH TIME ZONE '2026-08-05 14:30:00-05:00'
                )
                """.trimIndent(),
            )
            src.exec(
                """
                INSERT INTO t VALUES (
                  NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
                )
                """.trimIndent(),
            )

            runBlocking { staging.stage(src.query("SELECT * FROM t"), "stg_types", Dialect.H2) }
        }

        val rows = readStagedAsWire("SELECT * FROM \"stg_types\"")
        rows.size shouldBe 2

        val first = rows[0]
        first[0] shouldBe 42L // INTEGER → JSON number (Long)
        first[1] shouldBe "9007199254740993" // BIGINTEGER → string, exact beyond 2^53
        first[2] shouldBe BigDecimal("1234.56") // DECIMAL(10,2) exact
        first[3] shouldBe "12345678901234.5678" // BIGDECIMAL(20,4) → string, scale preserved
        first[4] shouldBe 3.141592653589793 // approximate DECIMAL → Double
        first[5] shouldBe true
        first[6] shouldBe "hello"
        first[7] shouldBe Base64.getEncoder().encodeToString(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        first[8] shouldBe "2026-08-05"
        first[9] shouldBe "14:30:00.000000"
        // 14:30 at -05:00 is 19:30 UTC — read back unshifted, six fractional digits, Z.
        first[10] shouldBe "2026-08-05T19:30:00.000000Z"

        // Every column of the all-NULL row encodes to JSON null, whatever its canonical type.
        rows[1].forEach { it shouldBe null }
    }

    @Test
    fun `the staged descriptors carry the expected canonical types`() {
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (big BIGINT, bigdec NUMERIC(20, 4), ts TIMESTAMP WITH TIME ZONE)")
            src.exec("INSERT INTO t VALUES (1, 2.0, TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00Z')")
            val result = runBlocking { staging.stage(src.query("SELECT * FROM t"), "stg_desc", Dialect.H2) }

            result.columns.map { it.type } shouldBe
                listOf(LogicalType.BIGINTEGER, LogicalType.BIGDECIMAL, LogicalType.TIMESTAMP)
            val bigdec = result.columns[1]
            bigdec.precision shouldBe 20
            bigdec.scale shouldBe 4
        }
    }

    /** Reads staged rows back and encodes each value to its wire form via [JsonEncoder]. */
    private fun readStagedAsWire(sql: String): List<List<Any?>> =
        runBlocking {
            staging.withQuery(sql) { rs ->
                val columns = stagedColumns(rs)
                buildList {
                    while (rs.next()) {
                        add(columns.mapIndexed { i, col -> JsonEncoder.encode(StagedValueReader.readValue(rs, i + 1, col), col) })
                    }
                }
            }
        }

    /** The canonical descriptors of a staged `ResultSet`, per the egress path (§6). */
    private fun stagedColumns(rs: ResultSet): List<ColumnSchema> {
        val meta = rs.metaData
        return (1..meta.columnCount).map { i ->
            H2IngressMapper.fromH2(meta.getColumnLabel(i), meta.getColumnType(i), meta.getPrecision(i), meta.getScale(i))
        }
    }
}
