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
            workspacesProvisioningMode = "self-serve",
            bootstrapDatasourcesFile = null,
            bootstrapExamplesFile = null,
            bootstrapAdminEmail = null,
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
                datasourceUrl = "jdbc:postgresql://localhost:5434/datapipelines",
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
    fun `an unknown workspaces provisioning mode is refused`() {
        val report = ConfigValidator.validate(validSnapshot().copy(workspacesProvisioningMode = "free-for-all"))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("provisioning-mode")
        report.violations.single().shouldContain("free-for-all")
    }

    /**
     * §3.17 says `open-join` is a `self-serve` knob; `closed` + `open-join: true` would
     * re-open the membership surface closed mode exists to keep admin-only (the self-join
     * branch gates on `open-join` alone). Refused on exactly that pair; the other three
     * combinations of a set `open-join`/`closed` are clean.
     */
    @Test
    fun `open-join true under closed provisioning is refused, and the other three combinations are clean`() {
        val refused =
            ConfigValidator.validate(validSnapshot().copy(workspacesOpenJoin = true, workspacesProvisioningMode = "closed"))

        refused.violations.shouldHaveSize(1)
        refused.violations.single().shouldContain("datapipelines.workspaces.open-join")
        refused.violations.single().shouldContain("datapipelines.workspaces.provisioning-mode")
        refused.violations.single().shouldContain("closed")

        ConfigValidator
            .validate(validSnapshot().copy(workspacesOpenJoin = true, workspacesProvisioningMode = "self-serve"))
            .violations
            .shouldBeEmpty()
        ConfigValidator
            .validate(validSnapshot().copy(workspacesOpenJoin = true, workspacesProvisioningMode = "auto-per-user"))
            .violations
            .shouldBeEmpty()
        ConfigValidator
            .validate(validSnapshot().copy(workspacesOpenJoin = false, workspacesProvisioningMode = "closed"))
            .violations
            .shouldBeEmpty()
        // An unset mode is the shipped default (self-serve) — open-join stays meaningful.
        ConfigValidator
            .validate(validSnapshot().copy(workspacesOpenJoin = true, workspacesProvisioningMode = null))
            .violations
            .shouldBeEmpty()
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
                workspacesProvisioningMode = "self-serve",
                bootstrapDatasourcesFile = "/etc/datapipelines/bootstrap-datasources.yml",
                bootstrapExamplesFile = "/etc/datapipelines/examples.json",
                bootstrapAdminEmail = "admin@example.com",
                activeProfiles = emptySet(),
                vendoredThemes = setOf("saas"),
            )

        val str = snapshot.toString()

        str shouldContain "datasourcePassword=<redacted>"
        str shouldContain "redisPassword=<redacted>"
        str shouldContain "jwtSecret=<redacted>"
        str shouldContain "dbEncryptionKey=<redacted>"
        str shouldContain "datasourceUrl=jdbc:postgresql://db.internal:5432/dp"
        // Paths and the admin address are not secrets, and a §7 log that hides them cannot
        // answer "is bootstrap on?" — the question this snapshot exists to make answerable.
        str shouldContain "bootstrapDatasourcesFile=/etc/datapipelines/bootstrap-datasources.yml"
        str shouldContain "bootstrapExamplesFile=/etc/datapipelines/examples.json"
        str shouldContain "bootstrapAdminEmail=admin@example.com"
    }

    @Test
    fun `OidcProviderSnapshot toString redacts clientSecret`() {
        val provider = OidcProviderSnapshot(name = "okta", clientId = "id", clientSecret = "secret", issuerUri = "https://okta.example.com")

        val str = provider.toString()

        str shouldContain "clientSecret=<redacted>"
        str shouldContain "clientId=id"
        str shouldContain "name=okta"
    }

    // ------------------------------------------------------------------ §3.18 bootstrap

    @Test
    fun `a bootstrap datasources file without a bootstrap admin email names BOTH keys`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    bootstrapDatasourcesFile = "/etc/datapipelines/bootstrap-datasources.yml",
                    bootstrapAdminEmail = null,
                ),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.bootstrap.datasources-file")
        report.violations.single().shouldContain("datapipelines.auth.bootstrap-admin-email")
    }

    @Test
    fun `an empty bootstrap admin email is treated as unset, not as a configured actor`() {
        // application.yml gives the key an empty env default, so "" is the shape a deployment
        // that never set the variable actually presents.
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(bootstrapDatasourcesFile = "/etc/dp/ds.yml", bootstrapAdminEmail = "  "),
            )

        report.violations.shouldHaveSize(1)
    }

    @Test
    fun `both keys set together is clean, and so is either one left unset`() {
        ConfigValidator
            .validate(validSnapshot().copy(bootstrapDatasourcesFile = "/etc/dp/ds.yml", bootstrapAdminEmail = "admin@example.com"))
            .violations
            .shouldBeEmpty()

        // Feature off: no actor needed.
        ConfigValidator.validate(validSnapshot().copy(bootstrapDatasourcesFile = "  ")).violations.shouldBeEmpty()

        // examples-file seeds at first login, under that user's identity — it needs no admin.
        // It DOES need the mode that seeds (below): this pair was asserted clean at
        // `self-serve` until 048/F5, which is precisely the gap that assertion was hiding.
        ConfigValidator
            .validate(
                validSnapshot().copy(
                    bootstrapExamplesFile = "/etc/dp/examples.json",
                    bootstrapAdminEmail = null,
                    workspacesProvisioningMode = "auto-per-user",
                ),
            ).violations
            .shouldBeEmpty()
    }

    // ------------------------------------------------------------------ §3.18 / §3.17 (048 F5)

    @Test
    fun `an examples file under a mode that never seeds names BOTH keys`() {
        // The silent-config class the sibling cross-key rule exists to prevent: the seeder bean
        // is built, the file is read and structurally checked — and `seed` is never called,
        // because only `auto-per-user` provisions the personal workspace that triggers it.
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(bootstrapExamplesFile = "/etc/dp/examples.json", workspacesProvisioningMode = "self-serve"),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.bootstrap.examples-file")
        report.violations.single().shouldContain("datapipelines.workspaces.provisioning-mode")
        report.violations.single().shouldContain("auto-per-user")
    }

    @Test
    fun `an unset provisioning mode is the shipped default, and is refused the same way`() {
        // application.yml ships `${DATAPIPELINES_WORKSPACES_PROVISIONING_MODE:self-serve}`, so
        // "the operator set no mode" IS self-serve — the seeder is just as unreachable.
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(bootstrapExamplesFile = "/etc/dp/examples.json", workspacesProvisioningMode = null),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.workspaces.provisioning-mode")
    }

    @Test
    fun `closed mode with an examples file is refused too, and auto-per-user is clean`() {
        ConfigValidator
            .validate(validSnapshot().copy(bootstrapExamplesFile = "/etc/dp/examples.json", workspacesProvisioningMode = "CLOSED"))
            .violations
            .shouldHaveSize(1)

        ConfigValidator
            .validate(
                validSnapshot().copy(bootstrapExamplesFile = "/etc/dp/examples.json", workspacesProvisioningMode = " auto-per-user "),
            ).violations
            .shouldBeEmpty()
    }

    @Test
    fun `an empty examples file path is the feature off, not a misconfigured pair`() {
        // Unset = off (§3.18), and application.yml's default binds the empty string.
        ConfigValidator.validate(validSnapshot().copy(bootstrapExamplesFile = "  ")).violations.shouldBeEmpty()
        ConfigValidator.validate(validSnapshot().copy(bootstrapExamplesFile = null)).violations.shouldBeEmpty()
    }

    @Test
    fun `an unknown mode beside an examples file reports the mode itself, not the pair`() {
        // One cause, one violation: a typo'd mode is already named by its own check, and
        // adding a second line about seeding would send the operator down the wrong key.
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(bootstrapExamplesFile = "/etc/dp/examples.json", workspacesProvisioningMode = "atuo-per-user"),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("is not one of")
    }

    // ------------------------------------------------------------------ §7 (055) promotion target

    @Test
    fun `a promotion target without its key refuses startup, naming both keys`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(promotionTargetBaseUrl = "https://uat.example.com", promotionTargetKeySet = false),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.deployment.promotion.target.base-url")
        report.violations.single().shouldContain("datapipelines.deployment.promotion.target.server-key")
    }

    @Test
    fun `a promotion target WITH its key is clean`() {
        ConfigValidator
            .validate(validSnapshot().copy(promotionTargetBaseUrl = "https://uat.example.com", promotionTargetKeySet = true))
            .violations
            .shouldBeEmpty()
    }

    @Test
    fun `a receiver - a key with no target - is not a violation`() {
        // §10.6: a deployment may hold either half, both, or neither. The receiver half alone
        // is the COMMON case, and the receiver-that-also-authors combination is a WARN that
        // belongs to AuthoringStartupCheck (it needs the repositories), not here.
        listOf(null, "  ").forEach { baseUrl ->
            ConfigValidator
                .validate(validSnapshot().copy(promotionTargetBaseUrl = baseUrl, promotionTargetKeySet = false))
                .violations
                .shouldBeEmpty()
        }
    }

    // ------------------------------------------------------------------ §7 (048 F8) reserved names

    @Test
    fun `an OIDC provider named bootstrap is refused - the name is a system placeholder`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    oidcProviders =
                        listOf(
                            OidcProviderSnapshot(
                                name = "bootstrap",
                                clientId = "id",
                                clientSecret = "secret",
                                issuerUri = "https://idp.example.com",
                            ),
                        ),
                ),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("bootstrap")
        report.violations.single().shouldContain("reserved")
    }

    @Test
    fun `local is reserved on the same grounds, and the reservation ignores case and padding`() {
        listOf("local", "  Bootstrap ", "LOCAL").forEach { name ->
            val report =
                ConfigValidator.validate(
                    validSnapshot().copy(
                        oidcProviders =
                            listOf(
                                OidcProviderSnapshot(
                                    name = name,
                                    clientId = "id",
                                    clientSecret = "secret",
                                    issuerUri = "https://idp.example.com",
                                ),
                            ),
                    ),
                )

            report.violations.shouldHaveSize(1)
            report.violations.single().shouldContain("reserved")
        }
    }

    @Test
    fun `an unconfigured entry named bootstrap is refused too - the squat is the name, not the wiring`() {
        // A blank client-id is otherwise IGNORED (it is how the stock `google` entry binds when
        // its env vars are unset). The reservation is not: the name lands in `users.provider`
        // the moment somebody fills the credentials in.
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    localEnabled = true,
                    oidcProviders = listOf(OidcProviderSnapshot(name = "bootstrap", clientId = "", clientSecret = "", issuerUri = "")),
                ),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("reserved")
    }

    @Test
    fun `system is reserved too - the system service account's whole safety argument is that no identity can link to it`() {
        // auth.md §4.5 / R7: `users.provider = 'system'` marks the non-human actor every
        // promoted row and every scheduled write is stamped with. An OIDC provider named
        // `system` would let a real person's identity land on that row through §4.2's linking
        // step — and the account's only defence against login is that nothing can link to it.
        listOf("system", " System ", "SYSTEM").forEach { name ->
            val report =
                ConfigValidator.validate(
                    validSnapshot().copy(
                        oidcProviders =
                            listOf(
                                OidcProviderSnapshot(
                                    name = name,
                                    clientId = "id",
                                    clientSecret = "secret",
                                    issuerUri = "https://idp.example.com",
                                ),
                            ),
                    ),
                )

            report.violations.shouldHaveSize(1)
            report.violations.single().shouldContain("reserved")
        }
    }

    @Test
    fun `the stock provider names are untouched`() {
        listOf("google", "microsoft", "okta", "bootstrap-idp").forEach { name ->
            ConfigValidator
                .validate(
                    validSnapshot().copy(
                        oidcProviders =
                            listOf(
                                OidcProviderSnapshot(
                                    name = name,
                                    clientId = "id",
                                    clientSecret = "secret",
                                    issuerUri = "https://idp.example.com",
                                ),
                            ),
                    ),
                ).violations
                .shouldBeEmpty()
        }
    }

    // ------------------------------------------------------------------ §3.4 local auth

    @Test
    fun `local accounts enabled with zero OIDC providers is a valid configuration`() {
        val report = ConfigValidator.validate(validSnapshot().copy(oidcProviders = emptyList(), localEnabled = true))

        report.violations.shouldBeEmpty()
    }

    @Test
    fun `a provider entry with a blank client-id is ignored, not a violation`() {
        // The stock application.yml google entry binds exactly this shape when
        // GOOGLE_CLIENT_ID is unset (it now defaults to empty) — a local-accounts
        // deployment must not be refused over a provider it never configured.
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    oidcProviders =
                        listOf(
                            OidcProviderSnapshot(name = "google", clientId = "  ", issuerUri = "https://accounts.google.com"),
                        ),
                    localEnabled = true,
                ),
            )

        report.violations.shouldBeEmpty()
    }

    @Test
    fun `both local seed forms set names both keys`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    localEnabled = true,
                    localBootstrapPasswordSet = true,
                    localBootstrapPasswordHashSet = true,
                    bootstrapAdminEmail = "admin@example.com",
                ),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.auth.local.bootstrap-password")
        report.violations.single().shouldContain("datapipelines.auth.local.bootstrap-password-hash")
    }

    // ------------------------------------------------------------------ §3.2 executor concurrency alias (050/R2)

    @Test
    fun `the deprecated executor alias alone is a WARN naming the new key - not a violation`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(executorMaxConcurrentGlobal = "150"),
            )

        report.violations.shouldBeEmpty()
        report.warnings.shouldHaveSize(1)
        report.warnings.single().shouldContain("max-concurrent-executions-per-instance")
        report.warnings.single().shouldContain("150")
    }

    @Test
    fun `both executor keys set and differing is refused - both keys named`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(executorMaxConcurrentGlobal = "150", executorMaxConcurrentPerInstance = "200"),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("max-concurrent-executions-global (150)")
        report.violations.single().shouldContain("max-concurrent-executions-per-instance (200)")
    }

    @Test
    fun `both executor keys set and equal carries the WARN only - no ambiguity, no refusal`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(executorMaxConcurrentGlobal = "120", executorMaxConcurrentPerInstance = "120"),
            )

        report.violations.shouldBeEmpty()
        report.warnings.shouldHaveSize(1)
    }

    @Test
    fun `the alias with the canonical key at its default is the alias-alone corner - WARN only`() {
        // application.yml pins the canonical key to 100 (the documented default) even when the
        // operator set nothing, so canonical == default reads as "unset". The corner — canonical
        // explicitly set to exactly 100 beside alias 150 — resolves to the alias with the WARN
        // stating the value in effect (documented on checkExecutorConcurrencyAlias).
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(executorMaxConcurrentGlobal = "150", executorMaxConcurrentPerInstance = "100"),
            )

        report.violations.shouldBeEmpty()
        report.warnings.single().shouldContain("(150) is in effect")
    }

    @Test
    fun `a non-integer executor alias is left to the binder - the §7 check skips it`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(executorMaxConcurrentGlobal = "lots"),
            )

        report.violations.shouldBeEmpty()
        report.warnings.shouldBeEmpty()
    }

    @Test
    fun `a local seed without local enabled is refused`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(localBootstrapPasswordSet = true, bootstrapAdminEmail = "admin@example.com"),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.auth.local.enabled")
    }

    @Test
    fun `a local seed without a bootstrap admin email names BOTH keys`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(localEnabled = true, localBootstrapPasswordHashSet = true, bootstrapAdminEmail = null),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.auth.local.bootstrap-password-hash")
        report.violations.single().shouldContain("datapipelines.auth.bootstrap-admin-email")
    }

    @Test
    fun `the local lockout bounds must be positive integers`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(localEnabled = true, localLockoutMaxFailures = "0", localLockoutDurationMinutes = "abc"),
            )

        report.violations.shouldHaveSize(2)
        report.violations.forEach { it.shouldContain("lockout") }
    }

    @Test
    fun `a fully configured local seed passes`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    localEnabled = true,
                    localBootstrapPasswordSet = true,
                    bootstrapAdminEmail = "admin@example.com",
                    localLockoutMaxFailures = "5",
                    localLockoutDurationMinutes = "15",
                ),
            )

        report.violations.shouldBeEmpty()
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
