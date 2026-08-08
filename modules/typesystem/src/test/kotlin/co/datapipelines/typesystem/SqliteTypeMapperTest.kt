package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Types

/**
 * The §5.7 SQLite affinity rules, including the two amendments the v1.1 review made:
 * the REAL-affinity value and the disambiguated BLOB rows.
 */
class SqliteTypeMapperTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("sqliteRows")
    fun `every §5-7 row maps exactly as the table declares`(case: MappingCase) {
        SqliteTypeMapper.map(case.sqlType, case.precision, case.scale, case.typeName) shouldBe case.expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sqliteRows")
    fun `every §5-7 row survives mapColumn with every nullability state`(case: MappingCase) {
        // Same table, the other entry point. mapColumn is what result-set readers
        // call, and its recognized branch was invisible to the map()-only suite.
        case.assertMapColumnMatches(SqliteTypeMapper)
    }

    @Test
    fun `a declared BLOB is BINARY but an undeclared column is STRING`() {
        // §5.7, the disambiguation: both have BLOB affinity, and the discriminator is the
        // declared string, not the affinity. Untyped SQLite columns hold text in
        // practice, and base64-encoding text as BINARY is the more damaging error.
        SqliteTypeMapper.map(Types.BLOB, 0, 0, "BLOB") shouldBe BINARY
        SqliteTypeMapper.map(Types.BLOB, 0, 0, "TINYBLOB") shouldBe BINARY
        SqliteTypeMapper.map(Types.BLOB, 0, 0, "") shouldBe STRING
        SqliteTypeMapper.map(Types.BLOB, 0, 0, "   ") shouldBe STRING
    }

    @Test
    fun `REAL affinity is DECIMAL(15), not DECIMAL(7)`() {
        // The v1.1 review fixed a typo here; the value is 15 for every approximate
        // affinity because SQLite stores all floats as 8-byte IEEE 754.
        SqliteTypeMapper.map(Types.REAL, 0, 0, "REAL") shouldBe DOUBLE
        SqliteTypeMapper.map(Types.REAL, 0, 0, "FLOAT") shouldBe DOUBLE
        SqliteTypeMapper.map(Types.REAL, 0, 0, "DOUBLE PRECISION") shouldBe DOUBLE
    }

    @Test
    fun `the INT substring rule wins even when it is surprising`() {
        // SQLite's own determination order: a column declared POINT has INTEGER affinity
        // because the string contains INT. Faithfully surprising beats quietly different.
        SqliteTypeMapper.map(Types.OTHER, 0, 0, "POINT") shouldBe INTEGER
        // SQLite's own documented example: "FLOATING POINT" gets INTEGER affinity, not
        // REAL, because the INT rule is applied before the REAL/FLOA/DOUB rule.
        SqliteTypeMapper.map(Types.REAL, 0, 0, "FLOATING POINT") shouldBe INTEGER
        SqliteTypeMapper.map(Types.INTEGER, 0, 0, "BIGINT") shouldBe INTEGER
        SqliteTypeMapper.map(Types.INTEGER, 0, 0, "UNSIGNED BIG INT") shouldBe INTEGER
    }

    @Test
    fun `temporal declarations are STRING, with no heuristic parsing`() {
        // §5.7 policy: SQLite has no temporal types and conventions vary (ISO text, epoch
        // seconds, epoch millis, Julian day). v1 guesses at none of them.
        listOf("DATE", "DATETIME", "TIMESTAMP", "TIME").forEach {
            SqliteTypeMapper.map(Types.OTHER, 0, 0, it) shouldBe STRING
        }
    }

    @Test
    fun `SQLite never reaches the §8-2 fallback`() {
        // Every declared string, including the empty one, has an answer under the affinity
        // rules — so no SQLite column can produce an unknown-type warning.
        val samples = listOf("", "WHATEVER", "CUSTOM_TYPE", "BOOLEAN", "42")

        samples.forEach { declared ->
            SqliteTypeMapper.mapColumn("c", Types.OTHER, 0, 0, declared).warnings shouldBe emptyList()
        }
    }

    @Test
    fun `a driver-reported NULL column still wins over affinity`() {
        // §8.1 outranks the dialect table for every dialect, SQLite included.
        SqliteTypeMapper.mapColumn("c", Types.NULL, 0, 0, "TEXT").column.type shouldBe LogicalType.NULL
    }

    private companion object {
        @JvmStatic
        fun sqliteRows(): List<MappingCase> =
            listOf(
                MappingCase("INTEGER affinity (contains INT)", Types.INTEGER, INTEGER, typeName = "INTEGER"),
                MappingCase("REAL affinity (contains REAL)", Types.REAL, DOUBLE, typeName = "REAL"),
                MappingCase("REAL affinity (contains FLOA)", Types.REAL, DOUBLE, typeName = "FLOAT"),
                MappingCase("REAL affinity (contains DOUB)", Types.REAL, DOUBLE, typeName = "DOUBLE"),
                MappingCase("NUMERIC affinity (mixed)", Types.NUMERIC, DOUBLE, typeName = "NUMERIC"),
                MappingCase("NUMERIC affinity (DECIMAL)", Types.NUMERIC, DOUBLE, typeName = "DECIMAL(10,5)"),
                MappingCase("TEXT affinity (contains CHAR)", Types.VARCHAR, STRING, typeName = "VARCHAR(255)"),
                MappingCase("TEXT affinity (contains CLOB)", Types.CLOB, STRING, typeName = "CLOB"),
                MappingCase("TEXT affinity (contains TEXT)", Types.VARCHAR, STRING, typeName = "TEXT"),
                MappingCase("BLOB affinity, declared BLOB", Types.BLOB, BINARY, typeName = "BLOB"),
                MappingCase("no declared type at all", Types.BLOB, STRING, typeName = ""),
            )
    }
}
