package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Types

/**
 * Every row of the §5.2 Oracle mapping table, plus the two named policies it carries:
 * the `DATE` gotcha and the `NUMBER(1)` non-promotion.
 */
class OracleTypeMapperTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("oracleRows")
    fun `every §5-2 row maps exactly as the table declares`(case: MappingCase) {
        OracleTypeMapper.map(case.sqlType, case.precision, case.scale, case.typeName) shouldBe case.expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oracleRows")
    fun `every §5-2 row survives mapColumn with every nullability state`(case: MappingCase) {
        // Same table, the other entry point. mapColumn is what result-set readers
        // call, and its recognized branch was invisible to the map()-only suite.
        case.assertMapColumnMatches(OracleTypeMapper)
    }

    @Test
    fun `Oracle DATE becomes TIMESTAMP, never canonical DATE`() {
        // §5.2 policy, normative: Oracle DATE carries a time component, so canonical DATE
        // would silently truncate it. Both JDBC codes a driver may report land on
        // TIMESTAMP, and canonical DATE is unreachable from Oracle by design.
        OracleTypeMapper.map(Types.TIMESTAMP, 0, 0, "DATE") shouldBe TIMESTAMP
        OracleTypeMapper.map(Types.DATE, 0, 0, "DATE") shouldBe TIMESTAMP

        // Nothing Oracle can report reaches canonical DATE — swept over the whole JDBC
        // code range rather than asserted for the two codes we happened to think of.
        val everyCode = (-20..2020).map { OracleTypeMapper.map(it, 0, 0, "") }
        everyCode.none { it.type == LogicalType.DATE } shouldBe true
    }

    @Test
    fun `NUMBER(1) stays INTEGER and is never promoted to BOOLEAN`() {
        // §8.5: map by source type, not by inferred intent. Frameworks that guess
        // boolean from NUMBER(1) are lossy of the author's intent; authors who want
        // boolean write CASE WHEN col = 1 THEN true ELSE false END.
        OracleTypeMapper.map(Types.NUMERIC, 1, 0, "NUMBER") shouldBe INTEGER
    }

    @Test
    fun `the NUMBER precision ladder splits at 9 and 18`() {
        // int32 boundary, then int64, then BIGDECIMAL — §5.2, frozen by §9.1.
        OracleTypeMapper.map(Types.NUMERIC, 9, 0, "NUMBER") shouldBe INTEGER
        OracleTypeMapper.map(Types.NUMERIC, 10, 0, "NUMBER") shouldBe BIGINTEGER
        OracleTypeMapper.map(Types.NUMERIC, 18, 0, "NUMBER") shouldBe BIGINTEGER
        OracleTypeMapper.map(Types.NUMERIC, 19, 0, "NUMBER") shouldBe bigDecimal(19, 0)
    }

    @Test
    fun `an unsized NUMBER reports Oracle's documented 38-digit default, not unbounded`() {
        // §4: Oracle DOES define a default, so — unlike PG — the precision is reported.
        // Emitting the unbounded (precision-omitted) encoding here would be wrong.
        val mapping = OracleTypeMapper.map(Types.NUMERIC, 0, 0, "NUMBER")

        mapping shouldBe bigDecimal(38, 0)
        mapping.precision shouldBe 38
    }

    @Test
    fun `a negative scale falls back to the integer-shaped branch`() {
        // Oracle allows NUMBER(10, -2); the §7.1 envelope's scale is `minimum: 0`, so the
        // column joins the scale-0 ladder rather than emitting an illegal descriptor.
        OracleTypeMapper.map(Types.NUMERIC, 10, -2, "NUMBER") shouldBe BIGINTEGER
    }

    @Test
    fun `an exotic object type falls back to STRING with one warning`() {
        val mapped = OracleTypeMapper.mapColumn("weird", Types.JAVA_OBJECT, 0, 0, "SDO_GEOMETRY")

        mapped.column.type shouldBe LogicalType.STRING
        mapped.warnings.map { it.code } shouldBe listOf(TypeMappingWarning.UNKNOWN_SOURCE_TYPE)
    }

    private companion object {
        @JvmStatic
        fun oracleRows(): List<MappingCase> =
            listOf(
                MappingCase("NUMBER(p) p <= 9, scale 0", Types.NUMERIC, INTEGER, precision = 9),
                MappingCase("INTEGER / INT / SMALLINT pseudo-types", Types.INTEGER, INTEGER),
                MappingCase("NUMBER(p) 9 < p <= 18, scale 0", Types.NUMERIC, BIGINTEGER, precision = 18),
                MappingCase("NUMBER(p) p > 18, scale 0", Types.NUMERIC, bigDecimal(20, 0), precision = 20),
                MappingCase("NUMBER(p,s) s > 0, p <= 15", Types.NUMERIC, decimal(12, 4), precision = 12, scale = 4),
                MappingCase("NUMBER(p,s) s > 0, p > 15", Types.NUMERIC, bigDecimal(20, 4), precision = 20, scale = 4),
                MappingCase("NUMBER (no precision/scale)", Types.NUMERIC, bigDecimal(38, 0), typeName = "NUMBER"),
                MappingCase("FLOAT(p) — binary bits, double precision", Types.FLOAT, DOUBLE, precision = 126),
                MappingCase("BINARY_FLOAT", Types.REAL, SINGLE),
                MappingCase("BINARY_DOUBLE", Types.DOUBLE, DOUBLE),
                MappingCase("DATE (has a time component!)", Types.TIMESTAMP, TIMESTAMP, typeName = "DATE"),
                MappingCase("TIMESTAMP", Types.TIMESTAMP, TIMESTAMP, typeName = "TIMESTAMP"),
                MappingCase("TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE, TIMESTAMP),
                MappingCase("TIMESTAMP WITH LOCAL TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE, TIMESTAMP),
                MappingCase("INTERVAL YEAR TO MONTH", Types.OTHER, STRING, typeName = "INTERVALYM"),
                MappingCase("INTERVAL DAY TO SECOND", Types.OTHER, STRING, typeName = "INTERVALDS"),
                MappingCase("CHAR", Types.CHAR, STRING),
                MappingCase("NCHAR", Types.NCHAR, STRING),
                MappingCase("VARCHAR2", Types.VARCHAR, STRING),
                MappingCase("NVARCHAR2", Types.NVARCHAR, STRING),
                MappingCase("CLOB", Types.CLOB, STRING),
                MappingCase("NCLOB", Types.NCLOB, STRING),
                MappingCase("LONG", Types.LONGVARCHAR, STRING),
                MappingCase("BLOB", Types.BLOB, BINARY),
                MappingCase("RAW", Types.VARBINARY, BINARY),
                MappingCase("LONG RAW", Types.LONGVARBINARY, BINARY),
                MappingCase("BFILE", Types.BINARY, BINARY),
                MappingCase("ROWID / UROWID", Types.ROWID, STRING),
                MappingCase("XMLType", Types.STRUCT, STRING, typeName = "XMLTYPE"),
                MappingCase("BOOLEAN (23c+)", Types.BOOLEAN, BOOLEAN),
            )
    }
}
