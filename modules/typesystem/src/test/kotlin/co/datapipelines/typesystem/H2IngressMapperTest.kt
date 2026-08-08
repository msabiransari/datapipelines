package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Types

/**
 * Every row of the §5.5 H2 mapping table, plus the staging.md §5.3 `fromH2` entry point.
 *
 * The §5.5 table is keyed on H2 **type names**, so the cases carry names; the `fromH2`
 * cases exercise the code-only path staging actually uses.
 */
class H2IngressMapperTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("h2Rows")
    fun `every §5-5 row maps exactly as the table declares`(case: MappingCase) {
        H2IngressMapper.map(case.sqlType, case.precision, case.scale, case.typeName) shouldBe case.expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("h2Rows")
    fun `every §5-5 row survives mapColumn with every nullability state`(case: MappingCase) {
        // Same table, the other entry point. mapColumn is what result-set readers
        // call, and its recognized branch was invisible to the map()-only suite.
        case.assertMapColumnMatches(H2IngressMapper)
    }

    @Test
    fun `UUID stays STRING even though H2 does not report it as a text code`() {
        // §8.6: canonical UUID text form, no canonical UUID type in v1. The name is the
        // discriminator — dispatching on the code alone would risk base64-ing a UUID.
        H2IngressMapper.map(Types.BINARY, 0, 0, "UUID") shouldBe STRING
        H2IngressMapper.map(Types.OTHER, 0, 0, "UUID") shouldBe STRING
    }

    @Test
    fun `every INTERVAL variant is text form`() {
        H2IngressMapper.map(Types.OTHER, 0, 0, "INTERVAL YEAR") shouldBe STRING
        H2IngressMapper.map(Types.OTHER, 0, 0, "INTERVAL DAY TO SECOND") shouldBe STRING
    }

    @Test
    fun `fromH2 recovers the descriptor from metadata alone`() {
        // staging.md §5.3's signature: no type name, because a staged table can only hold
        // the eleven §6 types and each is unambiguous by JDBC code.
        H2IngressMapper.fromH2("customer_id", Types.INTEGER, 10, 0) shouldBe
            ColumnSchema("customer_id", LogicalType.INTEGER)
        H2IngressMapper.fromH2("total_amount", Types.DECIMAL, 18, 2) shouldBe
            ColumnSchema("total_amount", LogicalType.BIGDECIMAL, 18, 2)
        H2IngressMapper.fromH2("measurement", Types.DOUBLE, 17, 0) shouldBe
            ColumnSchema("measurement", LogicalType.DECIMAL, precision = 15)
        H2IngressMapper.fromH2("first_order_at", Types.TIMESTAMP_WITH_TIMEZONE, 26, 6) shouldBe
            ColumnSchema("first_order_at", LogicalType.TIMESTAMP)
        H2IngressMapper.fromH2("logo", Types.VARBINARY, 0, 0) shouldBe
            ColumnSchema("logo", LogicalType.BINARY)
    }

    @Test
    fun `fromH2 never reports nullability it was not given`() {
        // §7.3: the staging signature carries no nullability, so the field must be absent
        // rather than defaulted to false.
        H2IngressMapper.fromH2("c", Types.VARCHAR, 0, 0).nullable shouldBe null
    }

    @Test
    fun `every canonical type survives a full egress-then-ingress round trip`() {
        // The two H2 mappers are not inverses (staging.md §5.3), but staging a canonical
        // column and reading it back must not change its canonical identity — that is the
        // property the executor depends on.
        val staged =
            listOf(
                ColumnSchema("c", LogicalType.BOOLEAN) to Types.BOOLEAN,
                ColumnSchema("c", LogicalType.INTEGER) to Types.INTEGER,
                ColumnSchema("c", LogicalType.BIGINTEGER) to Types.BIGINT,
                ColumnSchema("c", LogicalType.DECIMAL, 12, 2) to Types.DECIMAL,
                ColumnSchema("c", LogicalType.STRING) to Types.VARCHAR,
                ColumnSchema("c", LogicalType.BINARY) to Types.VARBINARY,
                ColumnSchema("c", LogicalType.DATE) to Types.DATE,
                ColumnSchema("c", LogicalType.TIME) to Types.TIME,
                ColumnSchema("c", LogicalType.TIMESTAMP) to Types.TIMESTAMP_WITH_TIMEZONE,
            )

        staged.forEach { (column, expectedH2Code) ->
            H2EgressMapper.h2SqlType(column) shouldBe expectedH2Code
            H2IngressMapper
                .fromH2(column.name, expectedH2Code, column.precision ?: 0, column.scale ?: 0)
                .type shouldBe column.type
        }
    }

    @Test
    fun `a bounded BIGDECIMAL keeps its exact precision across staging`() {
        // The control for the unbounded case below: a real declared bound must survive
        // the round trip untouched, or the §6 rule would be erasing precisions that mean
        // something rather than only the synthetic ceiling.
        val source = ColumnSchema("total_amount", LogicalType.BIGDECIMAL, precision = 20, scale = 2)

        H2EgressMapper.toH2Type(source) shouldBe "DECIMAL(20, 2)"
        H2IngressMapper.fromH2(source.name, H2EgressMapper.h2SqlType(source), 20, 2) shouldBe source
    }

    @Test
    fun `an unbounded BIGDECIMAL comes back unbounded, not at the storage ceiling`() {
        // §6 round-trip rule (normative). Staging must impose a bound to create the H2
        // column, but that bound is a storage fact, not a fact about the source. Reading
        // it back as BIGDECIMAL(100000, 0) would republish the synthetic ceiling §4
        // forbids — the fabricated bound would look identical on the wire to a real one,
        // and a client sizing a decimal buffer from it would be misled.
        val source = ColumnSchema("unbounded_total", LogicalType.BIGDECIMAL, precision = null, scale = 0)

        H2EgressMapper.toH2Type(source) shouldBe "DECIMAL(100000, 0)"

        val readBack =
            H2IngressMapper.fromH2(
                label = source.name,
                jdbcType = H2EgressMapper.h2SqlType(source),
                // What H2's ResultSetMetaData reports for the column the DDL above created.
                precision = H2EgressMapper.MAX_H2_DECIMAL_PRECISION,
                scale = 0,
            )

        readBack.precision shouldBe null
        readBack.isUnboundedPrecision shouldBe true
        readBack shouldBe source
    }

    @Test
    fun `the ceiling rule applies at the boundary and not one digit below`() {
        // 99999 is a bound H2 could genuinely have been told to store, so it survives;
        // the ceiling itself, and anything at or above it, reads as unbounded (§6's
        // accepted consequence for a real H2 source column declared at the maximum).
        H2IngressMapper.fromH2("c", Types.DECIMAL, 99_999, 0).precision shouldBe 99_999
        H2IngressMapper.fromH2("c", Types.DECIMAL, 100_000, 0).precision shouldBe null
        H2IngressMapper.fromH2("c", Types.DECIMAL, 100_001, 0).precision shouldBe null
        // Same rule via the name-dispatched path, not just the code-dispatched one.
        H2IngressMapper.map(Types.OTHER, 100_000, 2, "DECIMAL") shouldBe unbounded(scale = 2)
    }

    private companion object {
        @JvmStatic
        fun h2Rows(): List<MappingCase> = tableRows() + synonymRows()

        /** The §5.5 table, one case per row, in document order. */
        @JvmStatic
        private fun tableRows(): List<MappingCase> =
            listOf(
                MappingCase("TINYINT", Types.TINYINT, INTEGER, typeName = "TINYINT"),
                MappingCase("SMALLINT", Types.SMALLINT, INTEGER, typeName = "SMALLINT"),
                MappingCase("INTEGER / INT", Types.INTEGER, INTEGER, typeName = "INTEGER"),
                MappingCase("MEDIUMINT", Types.INTEGER, INTEGER, typeName = "MEDIUMINT"),
                MappingCase("BIGINT", Types.BIGINT, BIGINTEGER, typeName = "BIGINT"),
                MappingCase(
                    "NUMERIC(p,s) p <= 15",
                    Types.NUMERIC,
                    decimal(12, 2),
                    precision = 12,
                    scale = 2,
                    typeName = "NUMERIC",
                ),
                MappingCase(
                    "DECIMAL(p,s) p > 15",
                    Types.DECIMAL,
                    bigDecimal(20, 2),
                    precision = 20,
                    scale = 2,
                    typeName = "DECIMAL",
                ),
                MappingCase("REAL", Types.REAL, SINGLE, typeName = "REAL"),
                MappingCase("DOUBLE", Types.DOUBLE, DOUBLE, typeName = "DOUBLE"),
                MappingCase("DOUBLE PRECISION", Types.DOUBLE, DOUBLE, typeName = "DOUBLE PRECISION"),
                MappingCase("FLOAT (aliases DOUBLE in H2)", Types.DOUBLE, DOUBLE, typeName = "FLOAT"),
                MappingCase("BOOLEAN", Types.BOOLEAN, BOOLEAN, typeName = "BOOLEAN"),
                MappingCase("BIT", Types.BIT, BOOLEAN, typeName = "BIT"),
                MappingCase("DATE", Types.DATE, DATE, typeName = "DATE"),
                MappingCase("TIME", Types.TIME, TIME, typeName = "TIME"),
                MappingCase("TIME WITHOUT TIME ZONE", Types.TIME, TIME, typeName = "TIME WITHOUT TIME ZONE"),
                MappingCase("TIMESTAMP", Types.TIMESTAMP, TIMESTAMP, typeName = "TIMESTAMP"),
                MappingCase(
                    "TIMESTAMP WITHOUT TIME ZONE",
                    Types.TIMESTAMP,
                    TIMESTAMP,
                    typeName = "TIMESTAMP WITHOUT TIME ZONE",
                ),
                MappingCase(
                    "TIMESTAMP WITH TIME ZONE",
                    Types.TIMESTAMP_WITH_TIMEZONE,
                    TIMESTAMP,
                    typeName = "TIMESTAMP WITH TIME ZONE",
                ),
                MappingCase("VARCHAR", Types.VARCHAR, STRING, typeName = "VARCHAR"),
                MappingCase("VARCHAR_IGNORECASE", Types.VARCHAR, STRING, typeName = "VARCHAR_IGNORECASE"),
                MappingCase("CHAR / CHARACTER", Types.CHAR, STRING, typeName = "CHARACTER"),
                MappingCase("CLOB", Types.CLOB, STRING, typeName = "CLOB"),
                MappingCase("BINARY", Types.BINARY, BINARY, typeName = "BINARY"),
                MappingCase("VARBINARY", Types.VARBINARY, BINARY, typeName = "VARBINARY"),
                MappingCase("BLOB", Types.BLOB, BINARY, typeName = "BLOB"),
                MappingCase("UUID", Types.OTHER, STRING, typeName = "UUID"),
                MappingCase("JSON", Types.OTHER, STRING, typeName = "JSON"),
                MappingCase("ENUM", Types.OTHER, STRING, typeName = "ENUM"),
                MappingCase("GEOMETRY", Types.OTHER, STRING, typeName = "GEOMETRY"),
                MappingCase("INTERVAL DAY", Types.OTHER, STRING, typeName = "INTERVAL DAY"),
            )

        /**
         * Alternate spellings the §5.5 rows fold together.
         *
         * Each is a name a real H2 catalog can report, and each reaches its row through a
         * different key — so a typo in any one of them silently sends that spelling to the
         * §8.2 fallback while every other case still passes.
         */
        @JvmStatic
        private fun synonymRows(): List<MappingCase> =
            listOf(
                // Each is a distinct spelling a real H2 catalog can report.
                MappingCase("BOOL", Types.BOOLEAN, BOOLEAN, typeName = "BOOL"),
                MappingCase("TRUE (boolean alias)", Types.BOOLEAN, BOOLEAN, typeName = "TRUE"),
                MappingCase("FALSE (boolean alias)", Types.BOOLEAN, BOOLEAN, typeName = "FALSE"),
                MappingCase("TEXT", Types.VARCHAR, STRING, typeName = "TEXT"),
                MappingCase("STRING", Types.VARCHAR, STRING, typeName = "STRING"),
                MappingCase("LONGVARCHAR", Types.LONGVARCHAR, STRING, typeName = "LONGVARCHAR"),
                MappingCase("CHARACTER VARYING", Types.VARCHAR, STRING, typeName = "CHARACTER VARYING"),
                MappingCase("BINARY VARYING", Types.VARBINARY, BINARY, typeName = "BINARY VARYING"),
                MappingCase("BINARY LARGE OBJECT", Types.BLOB, BINARY, typeName = "BINARY LARGE OBJECT"),
                MappingCase("LONGVARBINARY", Types.LONGVARBINARY, BINARY, typeName = "LONGVARBINARY"),
                MappingCase("DEC (decimal alias)", Types.DECIMAL, decimal(12, 2), precision = 12, scale = 2, typeName = "DEC"),
            )
    }
}
