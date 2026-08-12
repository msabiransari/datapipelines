package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * The §7 rules as data. No Spring context: [ConfigValidator.validate] is pure, so every
 * rule — including the closing rule that the **documented dev setup passes the
 * production checks** — is exercised by feeding a [ConfigSnapshot].
 */
class ConfigValidatorTest {
    private val random = SecureRandom()

    /** A fresh base64 secret of [bytes] decoded bytes — never a literal (see ApplicationSmokeTest). */
    private fun secret(bytes: Int): String = Base64.getEncoder().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    /** The valid production-grade baseline; individual tests break one rule at a time. */
    private fun validSnapshot() =
        ConfigSnapshot(
            datasourceUrl = "jdbc:postgresql://db.internal:5432/datapipelines",
            datasourceUsername = "datapipelines",
            datasourcePassword = "s3cret",
            redisHost = "redis.internal",
            redisPassword = "redis-secret",
            jwtSecret = secret(32),
            dbEncryptionKey = secret(32),
            uiTheme = "saas",
            oidcProviders =
                listOf(
                    OidcProviderSnapshot(
                        name = "google",
                        clientId = "id",
                        clientSecret = "secret",
                        issuerUri = "https://accounts.google.com",
                    ),
                ),
            resultTtlMinSeconds = 60,
            resultTtlDefaultSeconds = 300,
            resultTtlMaxSeconds = 3600,
            activeProfiles = emptySet(),
            vendoredThemes = setOf("saas", "high-contrast"),
        )

    // §7 closing rule — the documented dev setup must pass the PRODUCTION rules, so a
    // broken dev value is fixed at the data, never by weakening the check.
    @Test
    fun `the documented dev setup passes every production rule`() {
        // configuration.md §6 verbatim in shape: localhost metadata DB and Redis,
        // passwordless loopback Redis, open theme default, secrets from .env.local
        // (openssl rand -base64 32 → 32 decoded bytes each).
        val dev =
            validSnapshot().copy(
                datasourceUrl = "jdbc:postgresql://localhost:5432/datapipelines",
                redisHost = "localhost",
                redisPassword = "",
                activeProfiles = setOf("dev"),
            )

        val report = ConfigValidator.validate(dev)

        report.violations.shouldBeEmpty()
        report.warnings.shouldBeEmpty()
    }

    @Test
    fun `every missing required key is named in one pass`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    datasourceUrl = null,
                    datasourceUsername = null,
                    datasourcePassword = " ",
                    redisHost = null,
                    jwtSecret = "",
                    dbEncryptionKey = null,
                ),
            )

        // Redis is also passwordless-and-not-loopback here? No: host is null → loopback-ish, no warning.
        report.violations.shouldHaveSize(6)
        report.violations.forEach { it.shouldContain("required") }
    }

    @Test
    fun `jwt secret shorter than 32 decoded bytes is refused`() {
        val report = ConfigValidator.validate(validSnapshot().copy(jwtSecret = secret(31)))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.jwt.secret")
        report.violations.single().shouldContain("31")
    }

    @Test
    fun `jwt secret that is not base64 is refused`() {
        val report = ConfigValidator.validate(validSnapshot().copy(jwtSecret = "not!base64!"))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("not valid base64")
    }

    @Test
    fun `db encryption key must decode to exactly 32 bytes`() {
        // The historical dev literal decoded to 28 bytes (configuration.md §6 note) — exactly
        // the value this rule exists to catch.
        val short = ConfigValidator.validate(validSnapshot().copy(dbEncryptionKey = secret(28)))
        short.violations.shouldHaveSize(1)
        short.violations.single().shouldContain("28")

        val long = ConfigValidator.validate(validSnapshot().copy(dbEncryptionKey = secret(33)))
        long.violations.shouldHaveSize(1)

        val malformed = ConfigValidator.validate(validSnapshot().copy(dbEncryptionKey = "%%%"))
        malformed.violations.shouldHaveSize(1)
        malformed.violations.single().shouldContain("not valid base64")
    }

    @Test
    fun `ui theme must match a vendored theme directory when assets exist`() {
        val bad = ConfigValidator.validate(validSnapshot().copy(uiTheme = "dracula"))
        bad.violations.shouldHaveSize(1)
        bad.violations.single().shouldContain("dracula")
        bad.violations.single().shouldContain("saas")

        val empty = ConfigValidator.validate(validSnapshot().copy(uiTheme = " "))
        empty.violations.shouldHaveSize(1)
    }

    @Test
    fun `ui theme check defers with a warning while no theme assets are vendored (pre-P8)`() {
        val report = ConfigValidator.validate(validSnapshot().copy(vendoredThemes = null))

        report.violations.shouldBeEmpty()
        report.warnings.shouldHaveSize(1)
        report.warnings.single().shouldContain("config.ui_theme_unverifiable")
    }

    @Test
    fun `at least one fully-configured OIDC provider is required`() {
        val none = ConfigValidator.validate(validSnapshot().copy(oidcProviders = emptyList()))
        none.violations.shouldHaveSize(1)
        none.violations.single().shouldContain("oidc.providers")

        val incomplete =
            ConfigValidator.validate(
                validSnapshot().copy(
                    oidcProviders = listOf(OidcProviderSnapshot(name = "okta", clientId = "id", clientSecret = "", issuerUri = "")),
                ),
            )
        incomplete.violations.shouldHaveSize(2)
        incomplete.violations.forEach { it.shouldContain("okta") }
    }

    @Test
    fun `result ttl ordering must be min lte default lte max`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(resultTtlMinSeconds = 60, resultTtlDefaultSeconds = 30, resultTtlMaxSeconds = 3600),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("ttl")
    }

    @Test
    fun `dev profile against non-localhost infrastructure refuses to start`() {
        val remoteDb =
            ConfigValidator.validate(
                validSnapshot().copy(activeProfiles = setOf("dev")),
            )
        remoteDb.violations.shouldHaveSize(1)
        remoteDb.violations.single().shouldContain("dev")
        remoteDb.violations.single().shouldContain("db.internal")

        val remoteRedis =
            ConfigValidator.validate(
                validSnapshot().copy(
                    datasourceUrl = "jdbc:postgresql://localhost:5432/datapipelines",
                    activeProfiles = setOf("dev"),
                ),
            )
        remoteRedis.violations.shouldHaveSize(1)
        remoteRedis.violations.single().shouldContain("redis.internal")
    }

    @Test
    fun `dev and prod profiles together refuse to start even on localhost`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    datasourceUrl = "jdbc:postgresql://localhost:5432/datapipelines",
                    redisHost = "localhost",
                    activeProfiles = setOf("dev", "production"),
                ),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("production")
    }

    @Test
    fun `ui theme check defers with a warning while vendored themes set is empty`() {
        val report = ConfigValidator.validate(validSnapshot().copy(vendoredThemes = emptySet()))

        report.violations.shouldBeEmpty()
        report.warnings.shouldHaveSize(1)
        report.warnings.single().shouldContain("config.ui_theme_unverifiable")
    }

    @Test
    fun `passwordless redis off loopback warns but does not refuse`() {
        val report = ConfigValidator.validate(validSnapshot().copy(redisPassword = ""))

        report.violations.shouldBeEmpty()
        report.warnings.shouldHaveSize(1)
        report.warnings.single().shouldContain("config.redis_no_password")
        report.warnings.single().shouldContain("redis.internal")
    }

    @Test
    fun `ConfigSnapshot toString redacts secret fields`() {
        val snapshot =
            ConfigSnapshot(
                datasourceUrl = "jdbc:postgresql://db.internal:5432/dp",
                datasourceUsername = "app",
                datasourcePassword = "super-secret-password",
                redisHost = "redis.internal",
                redisPassword = "redis-secret-123",
                jwtSecret = "jwt-secret-abc",
                dbEncryptionKey = "enc-key-xyz",
                uiTheme = "saas",
                oidcProviders =
                    listOf(
                        OidcProviderSnapshot(
                            name = "google",
                            clientId = "client-123",
                            clientSecret = "oidc-secret-456",
                            issuerUri = "https://accounts.google.com",
                        ),
                    ),
                resultTtlMinSeconds = 60,
                resultTtlDefaultSeconds = 300,
                resultTtlMaxSeconds = 3600,
                activeProfiles = emptySet(),
                vendoredThemes = setOf("saas"),
            )

        val str = snapshot.toString()

        str shouldContain "datasourcePassword=<redacted>"
        str shouldContain "redisPassword=<redacted>"
        str shouldContain "jwtSecret=<redacted>"
        str shouldContain "dbEncryptionKey=<redacted>"
        str shouldContain "datasourceUrl=jdbc:postgresql://db.internal:5432/dp"
    }

    @Test
    fun `OidcProviderSnapshot toString redacts clientSecret`() {
        val provider = OidcProviderSnapshot(name = "okta", clientId = "id", clientSecret = "secret", issuerUri = "https://okta.example.com")

        val str = provider.toString()

        str shouldContain "clientSecret=<redacted>"
        str shouldContain "clientId=id"
        str shouldContain "name=okta"
    }

    @Test
    fun `jdbc host parsing covers ports, failover lists and IPv6`() {
        ConfigValidator.jdbcHost("jdbc:postgresql://db.internal:5432/dp") shouldBe "db.internal"
        ConfigValidator.jdbcHost("jdbc:postgresql://db1:5432,db2:5432/dp?ssl=true") shouldBe "db1"
        ConfigValidator.jdbcHost("jdbc:postgresql://[::1]:5432/dp") shouldBe "::1"
        ConfigValidator.jdbcHost("jdbc:postgresql:///dp") shouldBe null
        ConfigValidator.jdbcHost(null) shouldBe null

        ConfigValidator.isLoopback("localhost") shouldBe true
        ConfigValidator.isLoopback("127.0.0.2") shouldBe true
        ConfigValidator.isLoopback("::1") shouldBe true
        ConfigValidator.isLoopback("10.0.0.8") shouldBe false
        ConfigValidator.isLoopback(null) shouldBe true
    }
}
