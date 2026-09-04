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
            workspacesProvisioningMode = "self-serve",
            bootstrapDatasourcesFile = null,
            bootstrapExamplesFile = null,
            bootstrapAdminEmail = null,
            activeProfiles = emptySet(),
            vendoredThemes = setOf("saas", "high-contrast"),
        )
}
