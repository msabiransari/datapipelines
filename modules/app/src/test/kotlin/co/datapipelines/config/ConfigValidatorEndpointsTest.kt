package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The §7 rule for `datapipelines.endpoints.*` (074, configuration.md §3.22).
 *
 * Its own class rather than three more methods on `ConfigValidatorTest`, which is already at
 * detekt's `LargeClass` cap — the same split `ConfigValidatorKeyProviderTest` made for §3.20.
 */
class ConfigValidatorEndpointsTest {
    private fun validSnapshot() = ConfigSnapshots.valid()

    @Test
    fun `endpoint timeout ordering must be min lte default lte max`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    endpointsTimeoutMinSeconds = 60,
                    endpointsTimeoutDefaultSeconds = 30,
                    endpointsTimeoutMaxSeconds = 300,
                ),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("timeout-default-seconds")
    }

    @Test
    fun `an endpoint timeout minimum below one second refuses to start`() {
        // A floor of 0 would let a publish store a timeout that can never complete an execution,
        // turning every serve of that endpoint into the §5.4 `202` path — a working endpoint that
        // never returns data inline, which reads as a product bug rather than a config one.
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    endpointsTimeoutMinSeconds = 0,
                    endpointsTimeoutDefaultSeconds = 30,
                    endpointsTimeoutMaxSeconds = 300,
                ),
            )

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("timeout-min-seconds")
    }

    @Test
    fun `absent endpoint timeout keys are not a violation — the binder default applies`() {
        // Unlike the result TTLs, which the executor reads directly, an unset endpoints key binds
        // to EndpointsProperties' documented default. Refusing startup for an absent key would
        // make every pre-074 deployment fail to boot on upgrade.
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    endpointsTimeoutMinSeconds = null,
                    endpointsTimeoutDefaultSeconds = null,
                    endpointsTimeoutMaxSeconds = null,
                ),
            )

        report.violations.shouldBeEmpty()
    }

    @Test
    fun `the capture durations - defaults and positive values pass, an absent key takes the binder default`() {
        ConfigValidator.validate(validSnapshot()).violations.shouldBeEmpty()
        ConfigValidator
            .validate(validSnapshot().copy(endpointsCaptureWindowSeconds = "900", endpointsCaptureDetailRetentionHours = "24"))
            .violations
            .shouldBeEmpty()
        ConfigValidator
            .validate(validSnapshot().copy(endpointsCaptureWindowSeconds = " 60 ", endpointsCaptureDetailRetentionHours = "1"))
            .violations
            .shouldBeEmpty()
    }

    @Test
    fun `zero, negative, non-numeric, overflowing and unrepresentable capture durations each refuse startup naming the key`() {
        val window = "datapipelines.endpoints.capture-window-seconds"
        val retention = "datapipelines.endpoints.capture-detail-retention-hours"
        listOf("0", "-5", "abc", "99999999999", "").forEach { bad ->
            ConfigValidator.validate(validSnapshot().copy(endpointsCaptureWindowSeconds = bad)).violations.single() shouldContain window
            ConfigValidator.validate(validSnapshot().copy(endpointsCaptureDetailRetentionHours = bad)).violations.single() shouldContain
                retention
        }
        // Int.MAX_VALUE hours from now is past 9999-12-31: no admission could store that expiry.
        ConfigValidator
            .validate(validSnapshot().copy(endpointsCaptureDetailRetentionHours = Int.MAX_VALUE.toString()))
            .violations
            .single() shouldContain "not representable"
        // Both bad at once: two violations, one per key.
        ConfigValidator
            .validate(validSnapshot().copy(endpointsCaptureWindowSeconds = "0", endpointsCaptureDetailRetentionHours = "0"))
            .violations
            .size shouldBe 2
    }
}
