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
 * The §6 canonical → H2 staging table, both halves of staging.md §5.3's interface, and
 * the precision-overflow policy.
 */
class H2EgressMapperTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("stagingRows")
    fun `every §6 row produces the declared H2 DDL type`(
        label: String,
        column: ColumnSchema,
        expected: String,
    ) {
        withClue("§6 row: $label") { H2EgressMapper.toH2Type(column) shouldBe expected }
    }

    @Test
    fun `an approximate DECIMAL stages as DOUBLE and an exact one as DECIMAL`() {
        // §4.2: the omitted scale is the only discriminator. Staging an approximate value
        // as DECIMAL(15, s) would require inventing s; DOUBLE round-trips IEEE 754 exactly.
        H2EgressMapper.toH2Type(ColumnSchema("c", LogicalType.DECIMAL, precision = 15)) shouldBe "DOUBLE"
        H2EgressMapper.toH2Type(ColumnSchema("c", LogicalType.DECIMAL, precision = 7)) shouldBe "DOUBLE"
        H2EgressMapper.toH2Type(ColumnSchema("c", LogicalType.DECIMAL, 12, 2)) shouldBe "DECIMAL(12, 2)"

        H2EgressMapper.h2SqlType(ColumnSchema("c", LogicalType.DECIMAL, precision = 15)) shouldBe Types.DOUBLE
        H2EgressMapper.h2SqlType(ColumnSchema("c", LogicalType.DECIMAL, 12, 2)) shouldBe Types.DECIMAL
    }

    @Test
    fun `an unbounded BIGDECIMAL stages at the H2 ceiling`() {
        // §4 + §6: the envelope keeps reporting precision as omitted; only the storage
        // gets a bound, and the bound is H2's documented maximum.
        H2EgressMapper.toH2Type(ColumnSchema("c", LogicalType.BIGDECIMAL, precision = null, scale = 0)) shouldBe
            "DECIMAL(100000, 0)"
        H2EgressMapper.MAX_H2_DECIMAL_PRECISION shouldBe 100_000
    }

    @Test
    fun `a precision beyond the ceiling fails with the staging overflow code`() {
        // staging.md §5.2 and type-system.md §6 fix the same threshold and MUST agree;
        // the code is the catalogued pipeline-contract §13.5 one, not an ad-hoc string.
        val overflowing = ColumnSchema("huge", LogicalType.BIGDECIMAL, precision = 100_001, scale = 0)

        val thrown = shouldThrow<DatapipelinesException> { H2EgressMapper.toH2Type(overflowing) }
        thrown.code shouldBe "pipeline.staging.precision_overflow"
        thrown.message.orEmpty() shouldContain "CAST to a smaller type"
        thrown.details["column"] shouldBe "huge"

        // Exactly at the ceiling is legal — the boundary is inclusive.
        H2EgressMapper.toH2Type(ColumnSchema("edge", LogicalType.BIGDECIMAL, 100_000, 0)) shouldBe
            "DECIMAL(100000, 0)"
    }

    @Test
    fun `TIMESTAMP stages with its zone so it reads back as UTC`() {
        // staging.md §5.1: canonical TIMESTAMP is always UTC, and storing the zone stops
        // H2's own date functions from shifting it on the way back out.
        val column = ColumnSchema("first_order_at", LogicalType.TIMESTAMP)

        H2EgressMapper.toH2Type(column) shouldBe "TIMESTAMP WITH TIME ZONE"
        H2EgressMapper.h2SqlType(column) shouldBe Types.TIMESTAMP_WITH_TIMEZONE
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LogicalType::class)
    fun `every canonical type has both an H2 DDL type and a bind code`(type: LogicalType) {
        // No canonical type may be unstageable: a gap here fails a pipeline at CREATE
        // TABLE time, long after the mapping decision that caused it.
        val column =
            when (type) {
                LogicalType.DECIMAL -> ColumnSchema("c", type, precision = 12, scale = 2)
                LogicalType.BIGDECIMAL -> ColumnSchema("c", type, precision = 20, scale = 2)
                else -> ColumnSchema("c", type)
            }

        H2EgressMapper.toH2Type(column).isNotBlank() shouldBe true
        // The bind code must be one H2 actually accepts for the DDL type above, not just
        // "some int" — a stray 0 would fail at setObject time, per row, in production.
        (H2EgressMapper.h2SqlType(column) in STAGEABLE_JDBC_TYPES) shouldBe true
    }

    private companion object {
        /** The `java.sql.Types` codes the §6 table can produce. */
        val STAGEABLE_JDBC_TYPES =
            setOf(
                Types.VARCHAR,
                Types.BOOLEAN,
                Types.INTEGER,
                Types.BIGINT,
                Types.DECIMAL,
                Types.DOUBLE,
                Types.VARBINARY,
                Types.DATE,
                Types.TIME,
                Types.TIMESTAMP_WITH_TIMEZONE,
            )

        @JvmStatic
        fun stagingRows(): List<Array<Any>> =
            listOf(
                arrayOf("NULL", ColumnSchema("c", LogicalType.NULL), "VARCHAR"),
                arrayOf("BOOLEAN", ColumnSchema("c", LogicalType.BOOLEAN), "BOOLEAN"),
                arrayOf("INTEGER", ColumnSchema("c", LogicalType.INTEGER), "INTEGER"),
                arrayOf("BIGINTEGER", ColumnSchema("c", LogicalType.BIGINTEGER), "BIGINT"),
                arrayOf("DECIMAL(p, s) exact", ColumnSchema("c", LogicalType.DECIMAL, 12, 2), "DECIMAL(12, 2)"),
                arrayOf("DECIMAL(p) approximate", ColumnSchema("c", LogicalType.DECIMAL, precision = 15), "DOUBLE"),
                arrayOf("BIGDECIMAL(p, s)", ColumnSchema("c", LogicalType.BIGDECIMAL, 20, 4), "DECIMAL(20, 4)"),
                arrayOf(
                    "BIGDECIMAL unbounded",
                    ColumnSchema("c", LogicalType.BIGDECIMAL, precision = null, scale = 0),
                    "DECIMAL(100000, 0)",
                ),
                arrayOf("STRING", ColumnSchema("c", LogicalType.STRING), "VARCHAR"),
                arrayOf("BINARY", ColumnSchema("c", LogicalType.BINARY), "VARBINARY"),
                arrayOf("DATE", ColumnSchema("c", LogicalType.DATE), "DATE"),
                arrayOf("TIME", ColumnSchema("c", LogicalType.TIME), "TIME"),
                arrayOf("TIMESTAMP", ColumnSchema("c", LogicalType.TIMESTAMP), "TIMESTAMP WITH TIME ZONE"),
            )
    }
}
