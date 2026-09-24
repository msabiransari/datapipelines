package co.datapipelines.config

import co.datapipelines.web.bootstrap.BootstrapProperties
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSingleElement
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The §7 rules #224 added: `datapipelines.bootstrap.demo-api-key`'s shape and hardened refusal,
 * and `datapipelines.endpoints.key-request-budget`'s bounds (configuration.md §3.18/§3.22).
 */
class ConfigValidatorDemoApiTest {
    private fun validSnapshot() = ConfigSnapshots.valid()

    @Test
    fun `a blank demo key is the kill switch, never a violation`() {
        val report = ConfigValidator.validate(validSnapshot().copy(bootstrapDemoApiKey = ""))

        report.violations.shouldBeEmpty()
    }

    @Test
    fun `the committed default is a well-formed key and starts cleanly`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    bootstrapDemoApiKey = "dpk_DEMOPUBLIC42.THISKEYISPUBLICBYDESIGNDEMOONLYREADONLY222222222",
                ),
            )

        report.violations.shouldBeEmpty()
    }

    @Test
    fun `a malformed demo key refuses startup naming the key and the shape`() {
        val report = ConfigValidator.validate(validSnapshot().copy(bootstrapDemoApiKey = "not-a-key"))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.bootstrap.demo-api-key")
        report.violations.single().shouldContain("dpk_")
    }

    @Test
    fun `a demo key whose id part is not twelve base32 characters refuses startup`() {
        val report =
            ConfigValidator.validate(validSnapshot().copy(bootstrapDemoApiKey = "dpk_SHORT0.a_very_long_secret_part"))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.bootstrap.demo-api-key")
    }

    @Test
    fun `a demo key with no secret part refuses startup`() {
        val report =
            ConfigValidator.validate(validSnapshot().copy(bootstrapDemoApiKey = "dpk_DEMOPUBLIC42."))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.bootstrap.demo-api-key")
    }

    @Test
    fun `an absent budget key is not a violation - the binder default applies`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    endpointsKeyRequestBudgetWindowSeconds = null,
                    endpointsKeyRequestBudgetMaxRequests = null,
                ),
            )

        report.violations.shouldBeEmpty()
    }

    @Test
    fun `a budget window below one second refuses startup naming the key`() {
        val report =
            ConfigValidator.validate(validSnapshot().copy(endpointsKeyRequestBudgetWindowSeconds = 0))

        report.violations.shouldHaveSingleElement { violation -> violation.contains("key-request-budget.window-seconds") }
    }

    @Test
    fun `a negative budget max-requests refuses startup naming the key, while zero is legal`() {
        val negative =
            ConfigValidator.validate(validSnapshot().copy(endpointsKeyRequestBudgetMaxRequests = -1))

        negative.violations.shouldHaveSize(1)
        negative.violations.single().shouldContain("key-request-budget.max-requests")

        val zero = ConfigValidator.validate(validSnapshot().copy(endpointsKeyRequestBudgetMaxRequests = 0))

        zero.violations.shouldBeEmpty()
    }

    @Test
    fun `the committed default does not refuse a hardened boot - set means the operator chose it`() {
        val report =
            ConfigValidator.validate(
                ConfigSnapshots.hardened().copy(bootstrapDemoApiKey = BootstrapProperties.DEFAULT_DEMO_API_KEY),
            )

        report.violations.shouldBeEmpty()
    }

    @Test
    fun `an operator-set demo key under hardened refuses startup, the way the demo flag does`() {
        val report =
            ConfigValidator.validate(
                ConfigSnapshots.hardened().copy(bootstrapDemoApiKey = "dpk_OPERATCHOSE2.THISKEYISPUBLICBYDESIGNDEMOONLY22222222"),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("hardened")
        report.violations.single().shouldContain("datapipelines.bootstrap.demo-api-key")
    }
}
