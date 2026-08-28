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
    fun `introspection include-schemas entries are plain names - patterns and blanks are rejected`() {
        // §3.3/§7A: the allowlist is exact names, no patterns — an `apex_*` entry here would
        // LOOK like it exempts a family while exemptting nothing (the prefix language belongs
        // to the exclusion floors, not the allowlist), and a blank entry exempts nobody.
        val pattern = validator.validate(Fixtures.h2(introspectionIncludeSchemas = listOf("apex_*")), isCreate = true)
        val blank = validator.validate(Fixtures.h2(introspectionIncludeSchemas = listOf("   ")), isCreate = true)
        val clean = validator.validate(Fixtures.h2(introspectionIncludeSchemas = listOf("apex_reporting")), isCreate = true)

        pattern.valid shouldBe false
        pattern.errors.map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
        (pattern.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field ?: "") shouldContain
            "introspection_include_schemas"
        blank.valid shouldBe false
        clean.valid shouldBe true
    }

    @Test
    fun `include-schemas entries carrying the SQL-LIKE wildcard percent are rejected per character`() {
        // R4 F2: `*` is not the only pattern vocabulary — a DB operator naturally writes the
        // SQL-LIKE `%` (`apex_%`, `apex%`). The allowlist matches EXACT stored names, so such
        // an entry validates, stores, and never exempts anything — the exact "looks like it
        // exempts a family while exempting nothing" failure the save-time rejection exists to
        // prevent. One red case per wildcard character.
        listOf("apex_*", "apex%", "apex_%").forEach { entry ->
            val result = validator.validate(Fixtures.h2(introspectionIncludeSchemas = listOf(entry)), isCreate = true)

            withClue("entry '$entry' must be rejected as a wildcard pattern") {
                result.valid shouldBe false
                result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain
                    "introspection_include_schemas"
            }
        }
    }

    @Test
    fun `an underscore in an include-schemas entry is a literal name character - not SQL-LIKE's single-char wildcard`() {
        // Deliberate contract pin (R4 F2 deviation): `_` is an ordinary character in real
        // schema names on every supported dialect — the feature's own documented use case is
        // exempting Oracle's `APEX_REPORTING` via `apex_reporting` (§3.3, §7A, rest-api §9.1
        // all use that example) — and the allowlist matches exactly, so an underscore entry
        // exempts the exactly-named schema. Rejecting `_` would break every snake_case schema
        // name while catching only a rare LIKE-vocabulary misunderstanding; the wildcards that
        // can NEVER match a real name (`*`, `%`) are the ones rejected.
        val result = validator.validate(Fixtures.h2(introspectionIncludeSchemas = listOf("apex_reporting", "my_app")), isCreate = true)

        result.valid shouldBe true
    }

    @Test
    fun `include-schemas entries over the legal-identifier alphabet are accepted`() {
        // R5 F7: the alphabet is the union of legal UNQUOTED identifier characters across
        // the supported dialects — letters, digits, `_` (ratified 008 deviation), `$`
        // (Postgres, MySQL, H2, DuckDB), `#` (Oracle, SQL Server). Entries are lowercase
        // post-normalization, so uppercase is outside the stored alphabet by construction.
        listOf("apex_reporting", "my_app", "team\$data", "group#1", "s2024", "2024_reports", "apex_").forEach { entry ->
            val result = validator.validate(Fixtures.h2(introspectionIncludeSchemas = listOf(entry)), isCreate = true)

            withClue("entry '$entry' is a legal schema name and must be accepted") { result.valid shouldBe true }
        }
    }

    @Test
    fun `include-schemas entries outside the legal-identifier alphabet are rejected and the entry is named`() {
        // R5 F7: the wildcard DENYLIST grew one character per review round (`*` R3, `%` R4)
        // while `?`, glob ranges `[a-z]`, pasted quoted identifiers `"apex_reporting"`, and
        // qualified `db.schema` entries still stored and silently exempted nothing. The
        // complement — "can match a real schema name on a supported dialect" — closes the
        // whole class at once. One red case per family. Note `apex_` (trailing underscore,
        // no wildcard) is ACCEPTED, not rejected: it is a legal real schema name and the
        // allowlist matches exactly — same reasoning as the ratified 008 `_` deviation.
        listOf(
            "apex_*", // glob prefix (the R3 family)
            "apex%", // SQL-LIKE (the R4 family)
            "ap?x", // glob single-char
            "ap_[a-z]", // glob range
            "\"apex_reporting\"", // pasted quoted identifier
            "`apex`", // pasted backtick-quoted identifier
            "db.apex", // qualified db.schema
            "my app", // interior whitespace
            "schéma", // non-ASCII
        ).forEach { entry ->
            val result = validator.validate(Fixtures.h2(introspectionIncludeSchemas = listOf(entry)), isCreate = true)

            withClue("entry '$entry' can never match a real schema name and must be rejected") {
                result.valid shouldBe false
                val error = result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }
                error.field shouldContain "introspection_include_schemas"
                error.message shouldContain entry.take(16)
            }
        }
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

    // --------------------------- readOnly refusal (workspaces design §6 layer 2b, §5.6)

    @Test
    fun `properties-dot-hikari readOnly is refused BOTH ways - hardening a writable datasource`() {
        // Direction 1: an operator trying to READONLY-harden a writable datasource from
        // properties — refused, because the flag is the entity's (the API/admin surface), and a
        // properties-derived one would be a second source of truth the executor never sees.
        val result =
            validator.validate(
                Fixtures.h2(properties = DatasourceProperties(hikari = mapOf("readOnly" to true))),
                isCreate = true,
            )

        result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain "readOnly"
    }

    @Test
    fun `properties-dot-hikari readOnly is refused BOTH ways - un-hardening a readonly datasource`() {
        // Direction 2: the dangerous one — an operator flipping readOnly FALSE on a datasource
        // the server would otherwise lease read-only connections for.
        val result =
            validator.validate(
                Fixtures.h2(
                    isReadonly = true,
                    properties = DatasourceProperties(hikari = mapOf("readOnly" to false)),
                ),
                isCreate = true,
            )

        result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain "readOnly"
    }

    @Test
    fun `readOnly is refused under the jdbc namespace too - both carriers, identically`() {
        // §5.6: the URL and the property map are validated against the same union — the
        // server-managed set covers both carriers, and the case-insensitive variant is caught
        // with it.
        val result =
            validator.validate(
                Fixtures.h2(properties = DatasourceProperties(jdbc = mapOf("readonly" to "true"))),
                isCreate = true,
            )

        result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain "readonly"
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
