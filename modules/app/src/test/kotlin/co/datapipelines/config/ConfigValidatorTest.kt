package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The §7 rules as data. No Spring context: [ConfigValidator.validate] is pure, so every
 * rule — including the closing rule that the **documented dev setup passes the
 * production checks** — is exercised by feeding a [ConfigSnapshot].
 */
class ConfigValidatorTest {
    /** The shared §7 baseline and secret generator ([ConfigSnapshots]) — one copy, two suites. */
    private fun secret(bytes: Int): String = ConfigSnapshots.secret(bytes)

    private fun validSnapshot() = ConfigSnapshots.valid()

    /** The §3.23 baseline under the OTHER posture — see [ConfigSnapshots.hardened]. */
    private fun hardened() = ConfigSnapshots.hardened()

    // §7 closing rule — the documented laptop setup must pass the PRODUCTION rules, so a
    // broken local value is fixed at the data, never by weakening the check.
    @Test
    fun `the documented laptop setup passes every production rule`() {
        // configuration.md §6 verbatim in shape: the `local` env under the `development`
        // posture, localhost metadata DB and Redis, passwordless loopback Redis, open theme
        // default, secrets from deploy/secrets.env (openssl rand -base64 32 → 32 bytes each).
        val laptop =
            validSnapshot().copy(
                datasourceUrl = "jdbc:postgresql://localhost:5434/datapipelines",
                redisHost = "localhost",
                redisPassword = "",
                env = "local",
                posture = "development",
                activeProfiles = setOf("development"),
            )

        val report = ConfigValidator.validate(laptop)

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

    // ---- §3.23 (075): the posture matrix ------------------------------------------------
    //
    // One test per ROW of the posture table in docs/environments.md: the `hardened` refusal
    // fires AND the `development` allowance holds. A refusal test alone would pass against a
    // validator that refused everything, which is why every row asserts both halves.

    @Test
    fun `a named environment with no posture refuses to start, naming both variables`() {
        val report = ConfigValidator.validate(validSnapshot().copy(env = "qa", posture = null))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.env")
        report.violations.single().shouldContain("datapipelines.posture")
    }

    @Test
    fun `only the environment named local gets a posture for free`() {
        val local = ConfigValidator.validate(validSnapshot().copy(env = "local", posture = null))

        local.violations.shouldBeEmpty()
    }

    @Test
    fun `a posture that is not one of the two values is refused, listing them`() {
        val report = ConfigValidator.validate(validSnapshot().copy(env = "qa", posture = "prod"))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.posture")
        report.violations.single().shouldContain("development")
        report.violations.single().shouldContain("hardened")
    }

    @Test
    fun `an env name outside the grammar is refused`() {
        val report = ConfigValidator.validate(validSnapshot().copy(env = "QA Prod!", posture = "hardened"))

        report.violations.shouldContain(
            report.violations.single { it.contains("is not a legal environment name") },
        )
    }

    @Test
    fun `an env name inside the grammar is accepted`() {
        ConfigValidator.validate(hardened().copy(env = "sandbox-eu")).violations.shouldBeEmpty()
        ConfigValidator.validate(hardened().copy(env = "perf_2")).violations.shouldBeEmpty()
    }

    @Test
    fun `demo is allowed under development and refused under hardened`() {
        ConfigValidator.validate(validSnapshot().copy(demo = "nyc,trade")).violations.shouldBeEmpty()

        val report = ConfigValidator.validate(hardened().copy(demo = "nyc"))
        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.demo")
        report.violations.single().shouldContain("hardened")
    }

    @Test
    fun `a bootstrap password is allowed under development and refused under hardened`() {
        val seeded =
            validSnapshot().copy(
                localEnabled = true,
                localBootstrapPasswordSet = true,
                bootstrapAdminEmail = "admin@example.com",
            )
        ConfigValidator.validate(seeded).violations.shouldBeEmpty()

        val report = ConfigValidator.validate(seeded.copy(env = "prod", posture = "hardened"))
        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("bootstrap-password")
        report.violations.single().shouldContain("hardened")
    }

    @Test
    fun `the hash form of the seed is refused under hardened too`() {
        val report =
            ConfigValidator.validate(
                hardened().copy(
                    localEnabled = true,
                    localBootstrapPasswordHashSet = true,
                    bootstrapAdminEmail = "admin@example.com",
                ),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("bootstrap-password-hash")
    }

    @Test
    fun `loopback infrastructure is allowed under development and refused under hardened`() {
        val loopback =
            validSnapshot().copy(
                datasourceUrl = "jdbc:postgresql://localhost:5432/datapipelines",
                redisHost = "127.0.0.1",
                redisPassword = "",
            )
        ConfigValidator.validate(loopback).violations.shouldBeEmpty()

        val report = ConfigValidator.validate(loopback.copy(env = "prod", posture = "hardened"))
        report.violations.shouldHaveSize(2)
        report.violations.first().shouldContain("spring.datasource.url")
        report.violations.last().shouldContain("datapipelines.redis.host")
    }

    @Test
    fun `hardened without OIDC is refused unless local-only is acknowledged`() {
        val localOnly = hardened().copy(oidcProviders = emptyList(), localEnabled = true)

        val refused = ConfigValidator.validate(localOnly)
        refused.violations.shouldHaveSize(1)
        refused.violations.single().shouldContain("DATAPIPELINES_AUTH_ALLOW_LOCAL_ONLY")

        val acknowledged = ConfigValidator.validate(localOnly.copy(authAllowLocalOnly = true))
        acknowledged.violations.shouldBeEmpty()
        acknowledged.warnings.single().shouldContain("event=config.auth_local_only")
    }

    @Test
    fun `development without OIDC is not a posture violation - local accounts are enough`() {
        val report = ConfigValidator.validate(validSnapshot().copy(oidcProviders = emptyList(), localEnabled = true))

        report.violations.shouldBeEmpty()
    }

    @Test
    fun `a spring profile that disagrees with the posture refuses to start`() {
        val report = ConfigValidator.validate(hardened().copy(activeProfiles = setOf("development")))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("SPRING_PROFILES_ACTIVE")
        report.violations.single().shouldContain("hardened")
    }

    @Test
    fun `the profile the posture derives is not a mismatch`() {
        ConfigValidator.validate(hardened().copy(activeProfiles = setOf("hardened"))).violations.shouldBeEmpty()
    }

    @Test
    fun `the renamed dev profile is refused rather than silently ignored`() {
        val report = ConfigValidator.validate(validSnapshot().copy(activeProfiles = setOf("dev")))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("development")
    }

    @Test
    fun `the deprecated deployment-name alias names the deployment and WARNs`() {
        val report = ConfigValidator.validate(validSnapshot().copy(env = null, deploymentName = "qa", posture = "development"))

        report.violations.shouldBeEmpty()
        report.warnings.single().shouldContain("event=config.deployment_name_deprecated")
        report.warnings.single().shouldContain("qa")
    }

    @Test
    fun `the alias set alone still selects the posture rule - a named env needs a posture`() {
        val report = ConfigValidator.validate(validSnapshot().copy(env = null, deploymentName = "qa", posture = null))

        report.violations.single().shouldContain("datapipelines.posture")
    }

    @Test
    fun `the alias and the new key disagreeing refuses to start, naming both`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(env = "qa", posture = "development", deploymentName = "uat"),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.env")
        report.violations.single().shouldContain("datapipelines.deployment.name")
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
