package co.datapipelines.visualization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [DashboardRuntimeConfig] enforces every key's bounds AND the three relations, each refusal naming the key(s) — the
 * boot rule (`DashboardRuntimeRules`) is its twin, held equal by `DashboardRuntimeConfigKeysSpecDriftTest`.
 */
class DashboardRuntimeConfigTest {
    @Test
    fun `the shipped defaults are the owner-confirmed numbers and satisfy every bound`() {
        val config = DashboardRuntimeConfig()

        config.maxConcurrentRefreshesPerWorkspace shouldBe 4
        config.maxExecutionsPerRefresh shouldBe 16
        config.maxConcurrentDashboardExecutionsPerInstance shouldBe 40
        config.maxWaitSeconds shouldBe 10
        config.maxBytesPerSource shouldBe 4L * 1024 * 1024
        config.maxBytesPerRefresh shouldBe 32L * 1024 * 1024
        config.defaultRefreshSeconds shouldBe 600
        config.maxRefreshSeconds shouldBe 900
        config.parameterLockSeconds shouldBe 30
        config.renderSeconds shouldBe 20
    }

    @Test
    fun `each key one past a bound refuses naming the key`() {
        DashboardRuntimeKey.entries.forEach { key ->
            listOf(key.min - 1, key.max + 1).forEach { outside ->
                val refusal = shouldThrow<IllegalArgumentException> { DashboardRuntimeKey.valueOf(key.name).check(outside) }
                refusal.message!! shouldContain key.path
            }
        }
        shouldThrow<IllegalArgumentException> { DashboardRuntimeConfig(maxConcurrentRefreshesPerWorkspace = 0) }.message!! shouldContain
            "max-concurrent-refreshes-per-workspace"
        shouldThrow<IllegalArgumentException> { DashboardRuntimeConfig(maxBytesPerSource = 1_000) }.message!! shouldContain
            "max-bytes-per-source"
    }

    @Test
    fun `the three relations refuse naming both keys`() {
        shouldThrow<IllegalArgumentException> { DashboardRuntimeConfig(defaultRefreshSeconds = 901) }.message!!.let {
            it shouldContain "default-refresh-seconds"
            it shouldContain "max-refresh-seconds"
        }
        shouldThrow<IllegalArgumentException> { DashboardRuntimeConfig(maxBytesPerSource = 33_554_433) }.message!!.let {
            it shouldContain "max-bytes-per-source"
            it shouldContain "max-bytes-per-refresh"
        }
        shouldThrow<IllegalArgumentException> { DashboardRuntimeConfig(maxExecutionsPerRefresh = 41) }.message!!.let {
            it shouldContain "max-executions-per-refresh"
            it shouldContain "max-concurrent-dashboard-executions-per-instance"
        }
        DashboardRuntimeConfig(defaultRefreshSeconds = 900, maxExecutionsPerRefresh = 40) // the boundary itself is legal
    }

    @Test
    fun `the binding twin flattens each group into the config, and a bad value refuses at toConfig`() {
        val bound =
            DashboardRuntimeProperties(
                admission = DashboardRuntimeProperties.Admission(maxWaitSeconds = 3),
                results = DashboardRuntimeProperties.Results(maxBytesPerSource = 2_048, maxBytesPerRefresh = 4_096),
                timeouts = DashboardRuntimeProperties.Timeouts(defaultRefreshSeconds = 60, maxRefreshSeconds = 120),
            ).toConfig()

        bound.maxWaitSeconds shouldBe 3
        bound.maxBytesPerSource shouldBe 2_048
        bound.maxRefreshSeconds shouldBe 120
        shouldThrow<IllegalArgumentException> {
            DashboardRuntimeProperties(timeouts = DashboardRuntimeProperties.Timeouts(renderSeconds = 0)).toConfig()
        }.message!! shouldContain "render-seconds"
    }
}
