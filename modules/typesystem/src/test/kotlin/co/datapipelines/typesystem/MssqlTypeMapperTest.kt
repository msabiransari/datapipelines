package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Types

/**
 * Every row of the §5.3 SQL Server mapping table, plus the `sql_variant` warning policy
 * (§5.3 / §10.5) and the money scales that differ from PostgreSQL's.
 */
class MssqlTypeMapperTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("mssqlRows")
    fun `every §5-3 row maps exactly as the table declares`(case: MappingCase) {
        MssqlTypeMapper.map(case.sqlType, case.precision, case.scale, case.typeName) shouldBe case.expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mssqlRows")
    fun `every §5-3 row survives mapColumn with every nullability state`(case: MappingCase) {
        // Same table, the other entry point. mapColumn is what result-set readers
        // call, and its recognized branch was invisible to the map()-only suite.
        case.assertMapColumnMatches(MssqlTypeMapper)
    }

    @Test
    fun `sql_variant maps to STRING AND raises exactly one warning`() {
        // §5.3 / §10.5: the mapping succeeds, the fidelity does not. This is the one case
        // where a recognized type still owes the author a warning.
        val mapped = MssqlTypeMapper.mapColumn("mixed_values", Types.OTHER, 0, 0, "sql_variant")

        mapped.column shouldBe ColumnSchema("mixed_values", LogicalType.STRING)
        mapped.warnings.size shouldBe 1
        mapped.warnings.single().code shouldBe TypeMappingWarning.SQL_VARIANT
        mapped.warnings.single().column shouldBe "mixed_values"
        mapped.warnings.single().message shouldContain "CAST to a concrete type"
        // Not the unknown-type warning: sql_variant IS recognized.
        mapped.warnings.single().code shouldBe "type_mapping.sql_variant"
    }

    @Test
    fun `a non-sql_variant OTHER column takes the §8-2 fallback instead`() {
        // OTHER is sql_variant and nothing else in §5.3, so a CLR type is genuinely
        // unknown and must be reported as such rather than waved through as text.
        val mapped = MssqlTypeMapper.mapColumn("clr_col", Types.OTHER, 0, 0, "geography")

        mapped.column.type shouldBe LogicalType.STRING
        mapped.warnings.single().code shouldBe TypeMappingWarning.UNKNOWN_SOURCE_TYPE
    }

    @Test
    fun `money and smallmoney carry MSSQL's scales, not PostgreSQL's`() {
        // §8.8: MSSQL money is 19,4 where PG money is 19,2. There is no shared "money"
        // concept — reusing PG's scale here would silently drop two decimal places.
        MssqlTypeMapper.map(Types.DECIMAL, 19, 4, "money") shouldBe bigDecimal(19, 4)
        MssqlTypeMapper.map(Types.DECIMAL, 10, 4, "smallmoney") shouldBe decimal(10, 4)
        PostgresTypeMapper.map(Types.NUMERIC, 0, 0, "money") shouldBe bigDecimal(19, 2)
    }

    @Test
    fun `float splits at the declared width of 24`() {
        MssqlTypeMapper.map(Types.FLOAT, 24, 0, "float") shouldBe SINGLE
        MssqlTypeMapper.map(Types.FLOAT, 25, 0, "float") shouldBe DOUBLE
        MssqlTypeMapper.map(Types.FLOAT, 53, 0, "float") shouldBe DOUBLE
        // `float` with no p defaults to 53 → double precision.
        MssqlTypeMapper.map(Types.FLOAT, 0, 0, "float") shouldBe DOUBLE
    }

    @Test
    fun `bit is boolean here, unlike MySQL where bit(n) is a bit-string`() {
        MssqlTypeMapper.map(Types.BIT, 0, 0, "bit") shouldBe BOOLEAN
    }

    private companion object {
        @JvmStatic
        fun mssqlRows(): List<MappingCase> =
            listOf(
                MappingCase("bit", Types.BIT, BOOLEAN),
                MappingCase("tinyint (unsigned 8-bit)", Types.TINYINT, INTEGER),
                MappingCase("smallint", Types.SMALLINT, INTEGER),
                MappingCase("int / integer", Types.INTEGER, INTEGER),
                MappingCase("bigint", Types.BIGINT, BIGINTEGER),
                MappingCase("decimal(p,s) p <= 15", Types.DECIMAL, decimal(10, 2), precision = 10, scale = 2),
                MappingCase("numeric(p,s) p <= 15", Types.NUMERIC, decimal(15, 2), precision = 15, scale = 2),
                MappingCase("decimal(p,s) p > 15", Types.DECIMAL, bigDecimal(20, 2), precision = 20, scale = 2),
                MappingCase("money", Types.DECIMAL, bigDecimal(19, 4), precision = 19, scale = 4, typeName = "money"),
                MappingCase(
                    "smallmoney",
                    Types.DECIMAL,
                    decimal(10, 4),
                    precision = 10,
                    scale = 4,
                    typeName = "smallmoney",
                ),
                MappingCase("real", Types.REAL, SINGLE),
                MappingCase("float(p) p <= 24", Types.FLOAT, SINGLE, precision = 24),
                MappingCase("float(p) 24 < p <= 53", Types.FLOAT, DOUBLE, precision = 53),
                MappingCase("float (no p, defaults to 53)", Types.FLOAT, DOUBLE),
                MappingCase("date", Types.DATE, DATE),
                MappingCase("time", Types.TIME, TIME),
                MappingCase("datetime", Types.TIMESTAMP, TIMESTAMP, typeName = "datetime"),
                MappingCase("datetime2", Types.TIMESTAMP, TIMESTAMP, typeName = "datetime2"),
                MappingCase("smalldatetime", Types.TIMESTAMP, TIMESTAMP, typeName = "smalldatetime"),
                MappingCase("datetimeoffset", Types.TIMESTAMP_WITH_TIMEZONE, TIMESTAMP),
                MappingCase("char", Types.CHAR, STRING),
                MappingCase("nchar", Types.NCHAR, STRING),
                MappingCase("varchar", Types.VARCHAR, STRING),
                MappingCase("nvarchar", Types.NVARCHAR, STRING),
                MappingCase("text", Types.LONGVARCHAR, STRING),
                MappingCase("ntext", Types.LONGNVARCHAR, STRING),
                MappingCase("binary", Types.BINARY, BINARY),
                MappingCase("varbinary", Types.VARBINARY, BINARY),
                MappingCase("image", Types.LONGVARBINARY, BINARY),
                MappingCase("uniqueidentifier", Types.CHAR, STRING, typeName = "uniqueidentifier"),
                MappingCase("xml", Types.SQLXML, STRING, typeName = "xml"),
                // §5.3 gives xml the codes `LONGVARCHAR / SQLXML`; both must reach STRING.
                MappingCase("xml reported under LONGVARCHAR", Types.LONGVARCHAR, STRING, typeName = "xml"),
                MappingCase("sql_variant", Types.OTHER, STRING, typeName = "sql_variant"),
            )
    }
}
