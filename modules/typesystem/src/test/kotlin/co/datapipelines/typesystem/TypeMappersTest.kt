package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Dispatch (§11.2) and the `Dialect` enum (enums.md §5).
 *
 * ## This class is the missing compiler check
 *
 * §11.2 requires dispatch to **degrade** for a dialect with no mapper rather than throw,
 * and both ways of writing that — a `when` with `else`, or the table lookup used here —
 * switch off Kotlin's exhaustiveness checking. So nothing at compile time notices a new
 * `Dialect` value with no mapper behind it. [`every Dialect resolves to its own mapper`]
 * is what notices: add a dialect, and this test fails before the silent-STRING behavior
 * can reach production.
 */
class TypeMappersTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @ParameterizedTest(name = "{0}")
    @EnumSource(Dialect::class)
    fun `every Dialect resolves to its own mapper, never the fallback`(dialect: Dialect) {
        TypeMappers.forDialect(dialect) shouldNotBe FallbackTypeMapper
    }

    @Test
    fun `each dialect gets the mapper §11-2 names for it`() {
        TypeMappers.forDialect(Dialect.POSTGRES) shouldBe PostgresTypeMapper
        TypeMappers.forDialect(Dialect.ORACLE) shouldBe OracleTypeMapper
        TypeMappers.forDialect(Dialect.MSSQL) shouldBe MssqlTypeMapper
        TypeMappers.forDialect(Dialect.MYSQL) shouldBe MysqlTypeMapper
        TypeMappers.forDialect(Dialect.H2) shouldBe H2IngressMapper
        TypeMappers.forDialect(Dialect.DUCKDB) shouldBe DuckDbTypeMapper
        TypeMappers.forDialect(Dialect.SQLITE) shouldBe SqliteTypeMapper
    }

    @Test
    fun `the seven supported dialects are exactly the enums-md §5 list`() {
        // Reserved values (SNOWFLAKE, BIGQUERY, REDSHIFT) MUST NOT appear in v1 code.
        Dialect.entries.map { it.wire } shouldContainExactly
            listOf("POSTGRES", "ORACLE", "MSSQL", "MYSQL", "H2", "DUCKDB", "SQLITE")
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Dialect::class)
    fun `every Dialect round-trips through its wire value`(dialect: Dialect) {
        mapper.writeValueAsString(dialect) shouldBe "\"${dialect.wire}\""
        mapper.readValue("\"${dialect.wire}\"", Dialect::class.java) shouldBe dialect
    }

    @Test
    fun `an unknown dialect wire value is rejected`() {
        shouldThrow<IllegalArgumentException> { Dialect.fromWire("SNOWFLAKE") }
        shouldThrow<IllegalArgumentException> { Dialect.fromWire("postgres") }
    }
}
