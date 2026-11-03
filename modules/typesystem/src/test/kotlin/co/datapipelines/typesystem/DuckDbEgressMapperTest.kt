package co.datapipelines.typesystem

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Types

/**
 * The §6a canonical → DuckDB staging table, its 38-digit ceiling, and the round trip back
 * through the §5.6 ingress table that reads staged columns.
 */
class DuckDbEgressMapperTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("stagingRows")
    fun `every §6a row produces the declared DuckDB DDL type`(
        label: String,
        column: ColumnSchema,
        expected: String,
    ) {
        withClue("§6a row: $label") { DuckDbEgressMapper.toDuckDbType(column) shouldBe expected }
    }

    @Test
    fun `BIGINTEGER stages as int64 BIGINT, not the 128-bit HUGEINT`() {
        // §3: BIGINTEGER is int64. HUGEINT reads back through §5.6 as BIGDECIMAL(38, 0), so
        // staging a BIGINTEGER column as HUGEINT would change its canonical type mid-pipeline.
        DuckDbEgressMapper.toDuckDbType(ColumnSchema("c", LogicalType.BIGINTEGER)) shouldBe "BIGINT"
    }

    @Test
    fun `an approximate DECIMAL stages as DOUBLE and an exact one as DECIMAL`() {
        DuckDbEgressMapper.toDuckDbType(ColumnSchema("c", LogicalType.DECIMAL, precision = 15)) shouldBe "DOUBLE"
        DuckDbEgressMapper.toDuckDbType(ColumnSchema("c", LogicalType.DECIMAL, precision = 7)) shouldBe "DOUBLE"
        DuckDbEgressMapper.toDuckDbType(ColumnSchema("c", LogicalType.DECIMAL, 12, 2)) shouldBe "DECIMAL(12, 2)"
    }

    @Test
    fun `a BIGDECIMAL at the 38-digit ceiling stages and one digit more is refused`() {
        DuckDbEgressMapper.toDuckDbType(ColumnSchema("edge", LogicalType.BIGDECIMAL, 38, 10)) shouldBe "DECIMAL(38, 10)"

        val thrown =
            shouldThrow<DatapipelinesException> {
                DuckDbEgressMapper.toDuckDbType(ColumnSchema("wide", LogicalType.BIGDECIMAL, 39, 0))
            }
        thrown.code shouldBe "pipeline.staging.precision_overflow"
        thrown.message.orEmpty() shouldContain "'wide'"
        thrown.message.orEmpty() shouldContain "CAST it to DECIMAL(p, s) with p ≤ 38"
        thrown.details["column"] shouldBe "wide"
        thrown.details["precision"] shouldBe 39
        thrown.details["max_precision"] shouldBe 38
    }

    @Test
    fun `an exact-unsized BIGDECIMAL is refused - DuckDB has no exact unbounded decimal`() {
        // Owner ruling 2026-11-03 (#795): no DECFLOAT twin exists, DOUBLE would round exact
        // values and DECIMAL(38, s) would invent the scale §4 forbids, so the author CASTs.
        val unsized = ColumnSchema("total_fare", LogicalType.BIGDECIMAL, precision = null, scale = null)

        val thrown = shouldThrow<DatapipelinesException> { DuckDbEgressMapper.toDuckDbType(unsized) }

        thrown.code shouldBe "pipeline.staging.precision_overflow"
        thrown.message.orEmpty() shouldContain "'total_fare'"
        thrown.message.orEmpty() shouldContain "no declared precision"
        thrown.message.orEmpty() shouldContain "CAST it to DECIMAL(p, s) with p ≤ 38"
        thrown.details shouldBe mapOf("column" to "total_fare", "max_precision" to 38)
    }

    @Test
    fun `the refusal code is the catalogued one H2 staging raises`() {
        DuckDbEgressMapper.PRECISION_OVERFLOW_CODE shouldBe H2EgressMapper.PRECISION_OVERFLOW_CODE
        DuckDbEgressMapper.MAX_DUCKDB_DECIMAL_PRECISION shouldBe 38
    }

    @Test
    fun `TIMESTAMP stages as an instant with its zone`() {
        DuckDbEgressMapper.toDuckDbType(ColumnSchema("first_order_at", LogicalType.TIMESTAMP)) shouldBe
            "TIMESTAMP WITH TIME ZONE"
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LogicalType::class)
    fun `every canonical type has a DuckDB DDL type`(type: LogicalType) {
        // No canonical type may be unstageable when it is bounded: a gap here fails a pipeline at
        // CREATE TABLE time, long after the mapping decision that caused it.
        DuckDbEgressMapper.toDuckDbType(stageable(type)).isNotBlank() shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LogicalType::class)
    fun `every staged type reads back through the DuckDB ingress name table as the same canonical type`(type: LogicalType) {
        // The staged table is read with DuckDbTypeMapper, so the DDL type must be a NAME §5.6
        // recognizes. Types.OTHER keeps the code backstop from rescuing a name the table lacks.
        val source = stageable(type)
        val readBack =
            DuckDbTypeMapper.map(
                sqlType = Types.OTHER,
                precision = source.precision ?: 0,
                scale = source.scale ?: 0,
                typeName = DuckDbEgressMapper.toDuckDbType(source),
            )

        val expected =
            when (type) {
                // An all-NULL column needs a storage type; it reads back as the STRING it is stored as,
                // exactly as on H2 (§6).
                LogicalType.NULL -> LogicalTypeMapping(LogicalType.STRING)

                else -> LogicalTypeMapping(source.type, source.precision, source.scale)
            }
        withClue("$type staged as ${DuckDbEgressMapper.toDuckDbType(source)}") { readBack shouldBe expected }
    }

    @Test
    fun `an approximate DECIMAL widens to DECIMAL(15) across staging, as on H2`() {
        // §4.2's accepted widening: DOUBLE metadata cannot tell a float32 origin from a float64 one.
        val readBack = DuckDbTypeMapper.map(Types.OTHER, 0, 0, DuckDbEgressMapper.toDuckDbType(ColumnSchema("c", LogicalType.DECIMAL, 7)))

        readBack shouldBe LogicalTypeMapping(LogicalType.DECIMAL, 15)
    }

    private companion object {
        /** A column of [type] that DuckDB staging accepts (bounded decimals, exact DECIMAL). */
        fun stageable(type: LogicalType): ColumnSchema =
            when (type) {
                LogicalType.DECIMAL -> ColumnSchema("c", type, precision = 12, scale = 2)
                LogicalType.BIGDECIMAL -> ColumnSchema("c", type, precision = 20, scale = 4)
                else -> ColumnSchema("c", type)
            }

        @JvmStatic
        fun stagingRows(): List<Array<Any>> =
            listOf(
                arrayOf("NULL", ColumnSchema("c", LogicalType.NULL), "VARCHAR"),
                arrayOf("BOOLEAN", ColumnSchema("c", LogicalType.BOOLEAN), "BOOLEAN"),
                arrayOf("INTEGER", ColumnSchema("c", LogicalType.INTEGER), "INTEGER"),
                arrayOf("BIGINTEGER", ColumnSchema("c", LogicalType.BIGINTEGER), "BIGINT"),
                arrayOf("DECIMAL(p, s) exact", ColumnSchema("c", LogicalType.DECIMAL, 12, 2), "DECIMAL(12, 2)"),
                arrayOf("DECIMAL(p) approximate", ColumnSchema("c", LogicalType.DECIMAL, precision = 15), "DOUBLE"),
                arrayOf("BIGDECIMAL(p, s) p ≤ 38", ColumnSchema("c", LogicalType.BIGDECIMAL, 20, 4), "DECIMAL(20, 4)"),
                arrayOf("STRING", ColumnSchema("c", LogicalType.STRING), "VARCHAR"),
                arrayOf("BINARY", ColumnSchema("c", LogicalType.BINARY), "BLOB"),
                arrayOf("DATE", ColumnSchema("c", LogicalType.DATE), "DATE"),
                arrayOf("TIME", ColumnSchema("c", LogicalType.TIME), "TIME"),
                arrayOf("TIMESTAMP", ColumnSchema("c", LogicalType.TIMESTAMP), "TIMESTAMP WITH TIME ZONE"),
            )
    }
}
