package co.datapipelines.visualization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** [VisualizationConfig] enforces every key's bounds at construction, naming the key; the properties build it. */
class VisualizationConfigTest {
    @Test
    fun `the defaults are the enum's and in bounds - the seven proposed numbers`() {
        VisualizationConfig().valuesByKey().mapKeys { it.key.key } shouldBe
            mapOf(
                "max-visualizations-per-dashboard" to 50L,
                "max-cases-per-visualization" to 20L,
                "max-fixture-rows-per-case" to 1_000L,
                "max-config-bytes" to 262_144L,
                "max-bindings-per-visualization" to 64L,
                "max-inputs-per-visualization" to 8L,
                "max-columns-per-input" to 256L,
            )
        VisualizationProperties().toConfig() shouldBe VisualizationConfig()
    }

    @Test
    fun `a value one past either bound is refused naming the key`() {
        VisualizationKey.entries.forEach { key ->
            listOf(key.min - 1, key.max + 1).forEach { value ->
                shouldThrow<IllegalArgumentException> { key.check(value) }.message!! shouldContain key.path
            }
            key.check(key.min)
            key.check(key.max)
        }
        shouldThrow<IllegalArgumentException> { VisualizationProperties(maxCasesPerVisualization = 0).toConfig() }
            .message!! shouldContain VisualizationKey.MAX_CASES_PER_VISUALIZATION.path
    }
}
