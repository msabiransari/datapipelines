package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Types

/**
 * Every row of the §5.1 PostgreSQL mapping table, plus the policies that table points at.
 *
 * The [postgresRows] list is a transcription of the spec table — one case per row, in the
 * document's order, labelled with the PG type name — so it can be diffed against §5.1 by
 * eye.
 */
class PostgresTypeMapperTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @ParameterizedTest(name = "{0}")
    @MethodSource("postgresRows")
    fun `every §5-1 row maps exactly as the table declares`(case: MappingCase) {
        PostgresTypeMapper.map(case.sqlType, case.precision, case.scale, case.typeName) shouldBe case.expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("postgresRows")
    fun `every §5-1 row survives mapColumn with every nullability state`(case: MappingCase) {
        // Same table, the other entry point. mapColumn is what result-set readers
        // call, and its recognized branch was invisible to the map()-only suite.
        case.assertMapColumnMatches(PostgresTypeMapper)
    }

    @Test
    fun `an unsized numeric produces a BIGDECIMAL descriptor with NO precision key`() {
        // §4, the v1.1 adjudication: PG's unsized numeric is unbounded, and omitted
        // precision is the normative encoding for that. The wire shape is the assertion
        // that matters — a synthetic ceiling (PG's internal max, Oracle's 38) would
        // serialize identically to a real bound and quietly lie to every client sizing
        // a local decimal buffer from it.
        val mapped =
            PostgresTypeMapper.mapColumn(
                name = "unbounded_total",
                sqlType = Types.NUMERIC,
                precision = 0,
                scale = 0,
                typeName = "numeric",
            )

        mapped.column.type shouldBe LogicalType.BIGDECIMAL
        mapped.column.precision shouldBe null
        mapped.column.scale shouldBe 0
        mapped.warnings shouldBe emptyList()

        val keys =
            mapper
                .readTree(mapper.writeValueAsString(mapped.column))
                .fieldNames()
                .asSequence()
                .toList()
        keys shouldContainExactly listOf("name", "type", "scale")
    }

    @Test
    fun `a sized numeric splits at precision 15, not 16`() {
        // §3.3 boundary, frozen by §9.1. 15 stays a JSON number; 16 becomes a string.
        PostgresTypeMapper.map(Types.NUMERIC, 15, 4, "numeric") shouldBe decimal(15, 4)
        PostgresTypeMapper.map(Types.NUMERIC, 16, 4, "numeric") shouldBe bigDecimal(16, 4)
    }

    @Test
    fun `bool and bit(n) share a JDBC code and are told apart by name`() {
        PostgresTypeMapper.map(Types.BIT, 0, 0, "bool") shouldBe BOOLEAN
        PostgresTypeMapper.map(Types.BIT, 8, 0, "bit") shouldBe STRING
        PostgresTypeMapper.map(Types.BIT, 8, 0, "varbit") shouldBe STRING
    }

    @Test
    fun `money is name-dispatched because the table gives it no JDBC code`() {
        // §8.8: PG money is fixed at 19,2 — and differs from MSSQL money (19,4).
        PostgresTypeMapper.map(Types.DOUBLE, 0, 0, "money") shouldBe bigDecimal(19, 2)
        PostgresTypeMapper.map(Types.NUMERIC, 0, 0, "money") shouldBe bigDecimal(19, 2)
    }

    @Test
    fun `an all-NULL column reported by the driver becomes canonical NULL`() {
        // §8.1: trust the driver. Types.NULL is an answer, not an unknown type, so it
        // must NOT raise the §8.2 warning.
        val mapped = PostgresTypeMapper.mapColumn("only_nulls", Types.NULL, 0, 0, "unknown")

        mapped.column.type shouldBe LogicalType.NULL
        mapped.warnings shouldBe emptyList()
    }

    @Test
    fun `an extension type falls back to STRING with exactly one warning`() {
        // §8.2: pgvector and friends. The pipeline does not fail.
        val mapped = PostgresTypeMapper.mapColumn("weird_column", UNKNOWN_JDBC_TYPE, 0, 0, "pgvector")

        mapped.column shouldBe ColumnSchema("weird_column", LogicalType.STRING)
        mapped.warnings.size shouldBe 1
        mapped.warnings.single().code shouldBe TypeMappingWarning.UNKNOWN_SOURCE_TYPE
        mapped.warnings.single().sourceType shouldBe "pgvector"
    }

    private companion object {
        /** A code no dialect table lists, used to exercise the §8.2 fallback. */
        const val UNKNOWN_JDBC_TYPE = 9999

        @JvmStatic
        @Suppress("LongMethod")
        fun postgresRows(): List<MappingCase> =
            listOf(
                MappingCase("int2 / smallint / int2vector", Types.SMALLINT, INTEGER),
                MappingCase("int4 / integer / serial", Types.INTEGER, INTEGER),
                MappingCase("int8 / bigint / bigserial", Types.BIGINT, BIGINTEGER),
                MappingCase("real / float4", Types.REAL, SINGLE),
                MappingCase("float8 / double precision", Types.DOUBLE, DOUBLE),
                MappingCase(
                    "numeric (no precision) -> unbounded",
                    Types.NUMERIC,
                    unbounded(scale = 0),
                    typeName = "numeric",
                ),
                MappingCase("numeric(p,s) p <= 15", Types.NUMERIC, decimal(12, 2), precision = 12, scale = 2),
                MappingCase("numeric(p,s) p > 15", Types.NUMERIC, bigDecimal(18, 2), precision = 18, scale = 2),
                MappingCase("money", Types.NUMERIC, bigDecimal(19, 2), typeName = "money"),
                MappingCase("boolean / bool", Types.BOOLEAN, BOOLEAN, typeName = "bool"),
                MappingCase("boolean reported as BIT", Types.BIT, BOOLEAN, typeName = "bool"),
                MappingCase("char / bpchar / character", Types.CHAR, STRING, typeName = "bpchar"),
                MappingCase("varchar / character varying", Types.VARCHAR, STRING, typeName = "varchar"),
                MappingCase("text", Types.VARCHAR, STRING, typeName = "text"),
                MappingCase("bytea", Types.BINARY, BINARY, typeName = "bytea"),
                MappingCase("uuid", Types.OTHER, STRING, typeName = "uuid"),
                MappingCase("json", Types.OTHER, STRING, typeName = "json"),
                MappingCase("jsonb", Types.OTHER, STRING, typeName = "jsonb"),
                MappingCase("xml", Types.OTHER, STRING, typeName = "xml"),
                MappingCase("date", Types.DATE, DATE),
                MappingCase("time", Types.TIME, TIME, typeName = "time"),
                MappingCase("timetz (zone dropped)", Types.TIME, TIME, typeName = "timetz"),
                MappingCase("timestamp", Types.TIMESTAMP, TIMESTAMP, typeName = "timestamp"),
                MappingCase("timestamptz", Types.TIMESTAMP_WITH_TIMEZONE, TIMESTAMP, typeName = "timestamptz"),
                MappingCase("interval (all variants)", Types.OTHER, STRING, typeName = "interval"),
                MappingCase("bit(n)", Types.BIT, STRING, precision = 8, typeName = "bit"),
                MappingCase("varbit(n)", Types.BIT, STRING, precision = 8, typeName = "varbit"),
                MappingCase("enum types", Types.OTHER, STRING, typeName = "mood"),
                MappingCase("array types", Types.ARRAY, STRING, typeName = "_int4"),
                MappingCase("oid / system integers", Types.BIGINT, BIGINTEGER, typeName = "oid"),
                MappingCase("geometric / network types", Types.OTHER, STRING, typeName = "inet"),
            )
    }
}
