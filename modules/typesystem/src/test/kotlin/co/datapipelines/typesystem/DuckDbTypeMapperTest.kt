package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Types

/**
 * Every row of the §5.6 DuckDB mapping table, plus the 128-bit and nested-type policies.
 */
class DuckDbTypeMapperTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("duckDbRows")
    fun `every §5-6 row maps exactly as the table declares`(case: MappingCase) {
        DuckDbTypeMapper.map(case.sqlType, case.precision, case.scale, case.typeName) shouldBe case.expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("duckDbRows")
    fun `every §5-6 row survives mapColumn with every nullability state`(case: MappingCase) {
        // Same table, the other entry point. mapColumn is what result-set readers
        // call, and its recognized branch was invisible to the map()-only suite.
        case.assertMapColumnMatches(DuckDbTypeMapper)
    }

    @Test
    fun `128-bit integers become BIGDECIMAL, never BIGINTEGER`() {
        // §5.6: BIGINTEGER promises int64. Mapping a 128-bit column to it would overflow
        // silently; BIGDECIMAL(38,0) is a decimal string on the wire and stays lossless.
        DuckDbTypeMapper.map(Types.OTHER, 0, 0, "HUGEINT") shouldBe bigDecimal(38, 0)
        DuckDbTypeMapper.map(Types.OTHER, 0, 0, "UHUGEINT") shouldBe bigDecimal(38, 0)
    }

    @Test
    fun `a list column is text, not its element type`() {
        // DuckDB names a list after its element (`INTEGER[]`). Reading the prefix would
        // declare the column INTEGER and every value would fail to parse client-side.
        DuckDbTypeMapper.map(Types.ARRAY, 0, 0, "INTEGER[]") shouldBe STRING
        DuckDbTypeMapper.map(Types.ARRAY, 0, 0, "VARCHAR[]") shouldBe STRING
        DuckDbTypeMapper.map(Types.INTEGER, 0, 0, "INTEGER") shouldBe INTEGER
    }

    @Test
    fun `a parameterised decimal name still splits at precision 15`() {
        DuckDbTypeMapper.map(Types.DECIMAL, 12, 3, "DECIMAL(12,3)") shouldBe decimal(12, 3)
        DuckDbTypeMapper.map(Types.DECIMAL, 38, 3, "DECIMAL(38,3)") shouldBe bigDecimal(38, 3)
    }

    @Test
    fun `DuckDB FLOAT is single precision, unlike Oracle's FLOAT`() {
        // Same JDBC code, opposite meaning — which is exactly why the tables are
        // per-dialect rather than one shared switch.
        DuckDbTypeMapper.map(Types.FLOAT, 0, 0, "FLOAT") shouldBe SINGLE
        OracleTypeMapper.map(Types.FLOAT, 0, 0, "FLOAT") shouldBe DOUBLE
    }

    @Test
    fun `an unknown DuckDB type falls back to STRING with one warning`() {
        val mapped = DuckDbTypeMapper.mapColumn("bits", Types.JAVA_OBJECT, 0, 0, "BITSTRING")

        mapped.column.type shouldBe LogicalType.STRING
        mapped.warnings.map { it.code } shouldBe listOf(TypeMappingWarning.UNKNOWN_SOURCE_TYPE)
    }

    private companion object {
        @JvmStatic
        fun duckDbRows(): List<MappingCase> =
            listOf(
                MappingCase("tinyint", Types.TINYINT, INTEGER, typeName = "TINYINT"),
                MappingCase("smallint", Types.SMALLINT, INTEGER, typeName = "SMALLINT"),
                MappingCase("integer / int / signed", Types.INTEGER, INTEGER, typeName = "INTEGER"),
                MappingCase("bigint", Types.BIGINT, BIGINTEGER, typeName = "BIGINT"),
                MappingCase("hugeint", Types.OTHER, bigDecimal(38, 0), typeName = "HUGEINT"),
                MappingCase("uhugeint", Types.OTHER, bigDecimal(38, 0), typeName = "UHUGEINT"),
                MappingCase(
                    "decimal(p,s) p <= 15",
                    Types.DECIMAL,
                    decimal(10, 2),
                    precision = 10,
                    scale = 2,
                    typeName = "DECIMAL",
                ),
                MappingCase(
                    "decimal(p,s) p > 15",
                    Types.DECIMAL,
                    bigDecimal(38, 2),
                    precision = 38,
                    scale = 2,
                    typeName = "DECIMAL",
                ),
                MappingCase("float", Types.FLOAT, SINGLE, typeName = "FLOAT"),
                MappingCase("double", Types.DOUBLE, DOUBLE, typeName = "DOUBLE"),
                MappingCase("boolean / bool / logical", Types.BOOLEAN, BOOLEAN, typeName = "BOOLEAN"),
                MappingCase("date", Types.DATE, DATE, typeName = "DATE"),
                MappingCase("time", Types.TIME, TIME, typeName = "TIME"),
                MappingCase(
                    "time with time zone (zone dropped)",
                    Types.TIME_WITH_TIMEZONE,
                    TIME,
                    typeName = "TIME WITH TIME ZONE",
                ),
                MappingCase("timestamp", Types.TIMESTAMP, TIMESTAMP, typeName = "TIMESTAMP"),
                MappingCase(
                    "timestamp with time zone",
                    Types.TIMESTAMP_WITH_TIMEZONE,
                    TIMESTAMP,
                    typeName = "TIMESTAMP WITH TIME ZONE",
                ),
                MappingCase("timestamptz", Types.TIMESTAMP_WITH_TIMEZONE, TIMESTAMP, typeName = "TIMESTAMPTZ"),
                MappingCase("varchar / text / string / bpchar", Types.VARCHAR, STRING, typeName = "VARCHAR"),
                MappingCase("blob / bytea", Types.BLOB, BINARY, typeName = "BLOB"),
                MappingCase("uuid", Types.OTHER, STRING, typeName = "UUID"),
                MappingCase("json", Types.OTHER, STRING, typeName = "JSON"),
                MappingCase("interval", Types.OTHER, STRING, typeName = "INTERVAL"),
                MappingCase("struct (nested)", Types.STRUCT, STRING, typeName = "STRUCT"),
                MappingCase("map (nested)", Types.OTHER, STRING, typeName = "MAP"),
                MappingCase("union (nested)", Types.OTHER, STRING, typeName = "UNION"),
                MappingCase("list (nested)", Types.ARRAY, STRING, typeName = "LIST"),
            )
    }
}
