package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Types

/**
 * Every row of the §5.4 MySQL / MariaDB mapping table, plus the three name- and
 * width-discriminated rows the table folds together.
 */
class MysqlTypeMapperTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("mysqlRows")
    fun `every §5-4 row maps exactly as the table declares`(case: MappingCase) {
        MysqlTypeMapper.map(case.sqlType, case.precision, case.scale, case.typeName) shouldBe case.expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mysqlRows")
    fun `every §5-4 row survives mapColumn with every nullability state`(case: MappingCase) {
        // Same table, the other entry point. mapColumn is what result-set readers
        // call, and its recognized branch was invisible to the map()-only suite.
        case.assertMapColumnMatches(MysqlTypeMapper)
    }

    @Test
    fun `the boolean alias is honored however the driver reports it`() {
        // §5.4 preamble: map by what the driver reports, not by the declared name.
        // tinyint(1)/bool surface as BIT, BOOLEAN or TINYINT depending on driver options.
        MysqlTypeMapper.map(Types.BIT, 1, 0, "BIT") shouldBe BOOLEAN
        MysqlTypeMapper.map(Types.BOOLEAN, 0, 0, "BOOLEAN") shouldBe BOOLEAN
        MysqlTypeMapper.map(Types.TINYINT, 3, 0, "TINYINT") shouldBe INTEGER
    }

    @Test
    fun `bit(n) above width 1 is a bit-string, not a boolean`() {
        MysqlTypeMapper.map(Types.BIT, 1, 0, "BIT") shouldBe BOOLEAN
        MysqlTypeMapper.map(Types.BIT, 2, 0, "BIT") shouldBe BINARY
        MysqlTypeMapper.map(Types.BIT, 64, 0, "BIT") shouldBe BINARY
    }

    @Test
    fun `year is an INTEGER even though the driver reports it under DATE`() {
        MysqlTypeMapper.map(Types.DATE, 0, 0, "YEAR") shouldBe INTEGER
        // …and a real date still reaches canonical DATE.
        MysqlTypeMapper.map(Types.DATE, 0, 0, "DATE") shouldBe DATE
    }

    @Test
    fun `geometry is WKT text even though the driver reports it under BINARY`() {
        // §5.6/§12: geospatial is the v1 STRING fallback. The name is the discriminator,
        // so genuine binary columns are unaffected.
        MysqlTypeMapper.map(Types.BINARY, 0, 0, "GEOMETRY") shouldBe STRING
        MysqlTypeMapper.map(Types.BINARY, 0, 0, "POINT") shouldBe STRING
        MysqlTypeMapper.map(Types.BINARY, 0, 0, "BINARY") shouldBe BINARY
    }

    @Test
    fun `an unrecognized MySQL type falls back to STRING with one warning`() {
        val mapped = MysqlTypeMapper.mapColumn("vec", Types.JAVA_OBJECT, 0, 0, "VECTOR")

        mapped.column.type shouldBe LogicalType.STRING
        mapped.warnings.map { it.code } shouldBe listOf(TypeMappingWarning.UNKNOWN_SOURCE_TYPE)
    }

    private companion object {
        @JvmStatic
        fun mysqlRows(): List<MappingCase> =
            listOf(
                MappingCase("boolean / bool / tinyint(1) as BIT", Types.BIT, BOOLEAN, precision = 1),
                MappingCase("boolean reported as BOOLEAN", Types.BOOLEAN, BOOLEAN),
                MappingCase("tinyint (signed 8-bit)", Types.TINYINT, INTEGER),
                MappingCase("smallint", Types.SMALLINT, INTEGER),
                MappingCase("mediumint (24-bit)", Types.INTEGER, INTEGER, typeName = "MEDIUMINT"),
                MappingCase("int / integer", Types.INTEGER, INTEGER),
                MappingCase("bigint", Types.BIGINT, BIGINTEGER),
                MappingCase("decimal(p,s) p <= 15", Types.DECIMAL, decimal(10, 2), precision = 10, scale = 2),
                MappingCase("numeric(p,s) p <= 15", Types.NUMERIC, decimal(15, 0), precision = 15, scale = 0),
                MappingCase("decimal(p,s) p > 15", Types.DECIMAL, bigDecimal(30, 6), precision = 30, scale = 6),
                MappingCase("float", Types.REAL, SINGLE, typeName = "FLOAT"),
                MappingCase("double / double precision / real", Types.DOUBLE, DOUBLE, typeName = "DOUBLE"),
                MappingCase("date", Types.DATE, DATE, typeName = "DATE"),
                MappingCase("time (with fsp)", Types.TIME, TIME, typeName = "TIME"),
                MappingCase("datetime", Types.TIMESTAMP, TIMESTAMP, typeName = "DATETIME"),
                MappingCase("timestamp", Types.TIMESTAMP, TIMESTAMP, typeName = "TIMESTAMP"),
                MappingCase("year(4)", Types.DATE, INTEGER, typeName = "YEAR"),
                // §5.4 gives year the codes `INTEGER / DATE`; both must reach INTEGER.
                MappingCase("year(2) reported under INTEGER", Types.INTEGER, INTEGER, typeName = "YEAR"),
                MappingCase("char", Types.CHAR, STRING, typeName = "CHAR"),
                MappingCase("varchar", Types.VARCHAR, STRING, typeName = "VARCHAR"),
                MappingCase("tinytext", Types.LONGVARCHAR, STRING, typeName = "TINYTEXT"),
                MappingCase("text", Types.LONGVARCHAR, STRING, typeName = "TEXT"),
                MappingCase("mediumtext", Types.LONGVARCHAR, STRING, typeName = "MEDIUMTEXT"),
                MappingCase("longtext", Types.LONGVARCHAR, STRING, typeName = "LONGTEXT"),
                MappingCase("enum", Types.CHAR, STRING, typeName = "ENUM"),
                MappingCase("set", Types.CHAR, STRING, typeName = "SET"),
                MappingCase("binary", Types.BINARY, BINARY, typeName = "BINARY"),
                MappingCase("varbinary", Types.VARBINARY, BINARY, typeName = "VARBINARY"),
                MappingCase("tinyblob", Types.LONGVARBINARY, BINARY, typeName = "TINYBLOB"),
                MappingCase("blob", Types.LONGVARBINARY, BINARY, typeName = "BLOB"),
                MappingCase("mediumblob", Types.LONGVARBINARY, BINARY, typeName = "MEDIUMBLOB"),
                MappingCase("longblob", Types.LONGVARBINARY, BINARY, typeName = "LONGBLOB"),
                MappingCase("bit(n) n > 1", Types.BIT, BINARY, precision = 8, typeName = "BIT"),
                MappingCase("json", Types.LONGVARCHAR, STRING, typeName = "JSON"),
                MappingCase("geometry types", Types.BINARY, STRING, typeName = "GEOMETRY"),
            )
    }
}
