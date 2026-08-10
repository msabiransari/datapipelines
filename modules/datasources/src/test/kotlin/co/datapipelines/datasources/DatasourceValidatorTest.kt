package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [DatasourceValidator] — the §9 rule set and the §5.4 test pool build (datasources.md §13.2).
 *
 * The passthrough model is only safe because these run on every save: an unknown pool key, a
 * wrong-typed value, a server-managed key, and an unknown namespace each fail with
 * `properties_invalid` naming the offender; a dead host does **not** fail the build.
 */
class DatasourceValidatorTest {
    private val validator = DatasourceValidator()

    private fun codes(
        datasource: Datasource,
        isCreate: Boolean = true,
    ): List<String> = validator.validate(datasource, isCreate).errors.map { it.code }

    @Test
    fun `a valid datasource passes`() {
        validator.validate(Fixtures.h2(), isCreate = true).valid shouldBe true
    }

    @Test
    fun `an invalid name is rejected`() {
        codes(Fixtures.h2(name = "Bad Name!")) shouldContain DatasourceErrorCodes.NAME_INVALID
    }

    @Test
    fun `a query timeout below 1 is rejected`() {
        codes(Fixtures.h2(queryTimeoutSeconds = 0)) shouldContain DatasourceErrorCodes.QUERY_TIMEOUT_INVALID
    }

    @Test
    fun `a positive query timeout is accepted`() {
        validator.validate(Fixtures.h2(queryTimeoutSeconds = 30), isCreate = true).valid shouldBe true
    }

    @Test
    fun `a password is required on create but not on update`() {
        codes(Fixtures.h2(password = null), isCreate = true) shouldContain DatasourceErrorCodes.PASSWORD_MISSING
        validator
            .validate(Fixtures.h2(password = null), isCreate = false)
            .errors
            .map { it.code } shouldBe emptyList()
    }

    @Test
    fun `an unknown hikari key fails properties_invalid and names the key`() {
        val result =
            validator.validate(
                Fixtures.h2(properties = DatasourceProperties(hikari = mapOf("bogusKey" to 1))),
                isCreate = true,
            )

        result.valid shouldBe false
        val error = result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }
        error.field shouldContain "bogusKey"
    }

    @Test
    fun `a wrong-typed hikari value fails properties_invalid and names the key`() {
        val result =
            validator.validate(
                Fixtures.h2(properties = DatasourceProperties(hikari = mapOf("maximumPoolSize" to "not-a-number"))),
                isCreate = true,
            )

        result.valid shouldBe false
        result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain "maximumPoolSize"
    }

    @Test
    fun `a server-managed key under hikari is rejected`() {
        val result =
            validator.validate(
                Fixtures.h2(properties = DatasourceProperties(hikari = mapOf("jdbcUrl" to "jdbc:h2:mem:evil"))),
                isCreate = true,
            )

        result.errors.map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
        result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain "jdbcUrl"
    }

    @Test
    fun `a server-managed key under jdbc is also rejected`() {
        val result =
            validator.validate(
                Fixtures.h2(properties = DatasourceProperties(jdbc = mapOf("password" to "smuggled"))),
                isCreate = true,
            )

        result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain "jdbc.password"
    }

    @Test
    fun `the server-managed denylist matches case-insensitively`() {
        // Driver and HikariCP property matching is case-insensitive, so an exact-case denylist
        // would let 'JDBCURL' / 'PassWord' through and silently override a server-managed field.
        // Without this case, every denylist assertion above would still pass a case-SENSITIVE
        // implementation — so this is the test that can actually fail for the right reason.
        listOf("JdbcUrl", "JDBCURL", "PassWord", "DriverClassName", "POOLNAME").forEach { key ->
            val result =
                validator.validate(
                    Fixtures.h2(properties = DatasourceProperties(hikari = mapOf(key to "x"))),
                    isCreate = true,
                )

            withClue("hikari key '$key' must be refused as server-managed") {
                result.errors.map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
            }
        }
    }

    @Test
    fun `the injection-surface denylist matches case-insensitively`() {
        listOf("Init", "INIT", "RunScript").forEach { key ->
            val result =
                validator.validate(
                    Fixtures.h2(properties = DatasourceProperties(jdbc = mapOf(key to "RUNSCRIPT FROM 'x'"))),
                    isCreate = true,
                )

            withClue("jdbc key '$key' must be refused as an injection surface") {
                result.errors.map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
            }
        }
    }

    @Test
    fun `an unknown properties namespace is rejected`() {
        val result =
            validator.validate(
                Fixtures.h2(properties = DatasourceProperties(unknownNamespaces = setOf("bogus"))),
                isCreate = true,
            )

        result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain "bogus"
    }

    @Test
    fun `an injection-surface driver key in properties_jdbc is refused`() {
        val result =
            validator.validate(
                Fixtures.h2(properties = DatasourceProperties(jdbc = mapOf("INIT" to "RUNSCRIPT FROM 'x'"))),
                isCreate = true,
            )

        result.errors.map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
    }

    @Test
    fun `saving a datasource pointing at a dead host succeeds - the pool builds without connecting`() {
        // POSTGRES driver is on the test classpath; initializationFailTimeout = -1 means no
        // connection is attempted at build, so an unreachable host is not a save-time failure.
        val deadHost = Fixtures.postgres(jdbcUrl = "jdbc:postgresql://192.0.2.1:5432/nope")

        validator.validate(deadHost, isCreate = true).valid shouldBe true
    }

    @Test
    fun `a dialect whose driver is not on the classpath fails driver_not_loaded`() {
        // Oracle is not bundled (no -Poracle), so its driver class is absent in this build.
        val result = validator.validate(oracleDatasource(), isCreate = true)

        result.errors.map { it.code } shouldContain DatasourceErrorCodes.DRIVER_NOT_LOADED
    }

    @Test
    fun `validation is exhaustive - multiple failures are collected together`() {
        val broken = Fixtures.h2(name = "Bad Name!", queryTimeoutSeconds = 0, password = null)

        codes(broken) shouldContainAll
            listOf(
                DatasourceErrorCodes.NAME_INVALID,
                DatasourceErrorCodes.QUERY_TIMEOUT_INVALID,
                DatasourceErrorCodes.PASSWORD_MISSING,
            )
    }

    @Test
    fun `PUT with a name different from the path is rejected as name_invalid`() {
        val result = validator.validateNameMatchesPath("pg_prod", Fixtures.h2(name = "pg_staging"))

        result.valid shouldBe false
        result.errors.single().code shouldBe DatasourceErrorCodes.NAME_INVALID
    }

    @Test
    fun `PUT with a matching name passes the immutability guard`() {
        validator.validateNameMatchesPath("test_h2", Fixtures.h2(name = "test_h2")).valid shouldBe true
    }

    private fun oracleDatasource(): Datasource =
        Datasource(
            name = "ora_test",
            displayName = "Oracle",
            dialect = Dialect.ORACLE,
            jdbcUrl = "jdbc:oracle:thin:@//db.internal:1521/svc",
            username = "app",
            password = "secret",
        )
}
