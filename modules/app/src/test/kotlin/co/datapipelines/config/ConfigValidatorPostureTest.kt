package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The §3.23 posture rules as data (075) — [PostureRules], driven through
 * [ConfigValidator.validate] exactly as the boot does. Its own suite for the reason
 * `ConfigValidatorEndpointsTest`, `ConfigValidatorOrgTest` and `ConfigValidatorKeyProviderTest`
 * have theirs: one §7 area, one file.
 *
 * **One test per ROW of the posture table** in docs/environments.md, and each asserts BOTH
 * halves — the `hardened` refusal fires AND the `development` allowance holds. A refusal
 * test alone would pass against a validator that refused everything, which is exactly the
 * shape of guard that cannot go red for the reason it was written.
 */
class ConfigValidatorPostureTest {
    private fun validSnapshot() = ConfigSnapshots.valid()

    /** The §3.23 baseline under the OTHER posture — see [ConfigSnapshots.hardened]. */
    private fun hardened() = ConfigSnapshots.hardened()

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

        val report = ConfigValidator.validate(seeded.copy(env = "prod", posture = "hardened", activeProfiles = setOf("hardened")))
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

        val report =
            ConfigValidator.validate(loopback.copy(env = "prod", posture = "hardened", activeProfiles = setOf("hardened")))
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

    /**
     * The silent case, and the reason the alignment rule is not only about a MISMATCHED
     * profile. `application.yml` derives the profile from the posture, so this state arises
     * when the posture is supplied a way the derivation misses; §7 refuses it because the
     * hardened RULES would be enforced while every hardened DEFAULT — authoring off, Secure
     * cookies — quietly stayed at the development column's. Found by
     * `ApplicationHardenedSmokeTest`, which booted with authoring still on.
     */
    @Test
    fun `a hardened posture whose profile did not load refuses to start`() {
        val report = ConfigValidator.validate(hardened().copy(activeProfiles = emptySet()))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("did not load")
        report.violations.single().shouldContain("DATAPIPELINES_POSTURE")
    }

    @Test
    fun `a development posture needs no profile - the base defaults ARE its column`() {
        ConfigValidator.validate(validSnapshot().copy(env = "qa", posture = "development")).violations.shouldBeEmpty()
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
}
