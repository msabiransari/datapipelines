package co.datapipelines.config

import java.security.SecureRandom
import java.util.Base64

/**
 * The §7 baseline snapshot both validator suites break one rule at a time against.
 *
 * Extracted from [ConfigValidatorTest] when 068 split the key-provider rules into their own
 * suite: two copies of a "valid production configuration" is exactly the fixture that drifts,
 * and a test whose baseline is quietly invalid stops proving anything.
 *
 * Deliberately named without the `Test` suffix so the module's `verifyTestsExecuted` guard
 * counts only real test classes — the same convention `McpFixtures` follows.
 */
internal object ConfigSnapshots {
    private val random = SecureRandom()

    /** A fresh base64 secret of [bytes] decoded bytes — never a literal (see ApplicationSmokeTest). */
    fun secret(bytes: Int): String = Base64.getEncoder().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    /** The valid production-grade baseline; individual tests break one rule at a time. */
    fun valid() =
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
            // RBAC round 1 removed `provisioning-mode` and `open-join` (D-R11), and the
            // validator refuses either BY NAME — so a snapshot that still sets one is not a
            // valid configuration, and this fixture must not pretend otherwise.
            workspacesProvisioningMode = null,
            bootstrapDatasourcesFile = null,
            bootstrapExamplesFile = null,
            bootstrapAdminEmail = null,
            // §3.21 — the shipped org defaults. The baseline must be VALID, and an unset org
            // block is not: every one of the five keys has a default in application.yml, so a
            // null here would mean a deployment whose yml lost the block entirely.
            orgCurrencyName = "Dollar",
            orgCurrencySymbol = "$",
            orgFiscalStartDate = "01-01",
            orgWeekStart = "monday",
            orgTimezone = "UTC",
            activeProfiles = emptySet(),
            vendoredThemes = setOf("saas", "high-contrast"),
        )

    /**
     * The same baseline under the OTHER posture (§3.23, 075): a named environment that
     * declared `hardened`. Every value in [valid] already satisfies the hardened rules —
     * non-loopback metadata DB and Redis, a fully-configured OIDC provider, no seeded
     * credential, no demo — which is the point: `hardened` is not a different configuration,
     * it is the same one with the shortcuts refused. A posture test that had to change five
     * other fields to go green would be testing the fixture, not the rule.
     *
     * The `hardened` PROFILE is active here because that is what a real hardened boot looks
     * like — `application.yml` derives `spring.profiles.active` from the posture, and §7
     * refuses a hardened posture whose profile did not load (its DEFAULTS would silently be
     * the other column's).
     */
    fun hardened() = valid().copy(env = "prod", posture = "hardened", activeProfiles = setOf("hardened"))
}
