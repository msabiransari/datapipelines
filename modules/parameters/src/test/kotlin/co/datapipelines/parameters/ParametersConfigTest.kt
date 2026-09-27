package co.datapipelines.parameters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** Record §11's keys: the documented defaults, every bound enforced naming its key, and the two twins in step. */
class ParametersConfigTest {
    @Test
    fun `the defaults are the record's - fifteen keys`() {
        val config = ParametersConfig()
        config.valuesByKey().mapKeys { it.key.key } shouldBe
            linkedMapOf(
                "max-parameters-per-set" to 64L,
                "max-options-per-selector" to 200L,
                "max-multi-bind-values" to 1_000L,
                "max-binds-per-statement" to 2_000L,
                "max-option-value-chars" to 1_024L,
                "max-option-label-chars" to 256L,
                "max-input-length" to 4_096L,
                "max-expression-depth" to 16L,
                "max-expression-nodes" to 128L,
                "max-regex-steps" to 100_000L,
                "evaluate-timeout-seconds" to 30L,
                "selector-query-timeout-seconds" to 10L,
                "max-concurrent-selector-queries" to 4L,
                "max-waiting-selector-queries" to 64L,
                "max-evaluate-response-bytes" to 4_194_304L,
            )
        config.valueLimits.maxRegexReads shouldBe 100_000L
        config.valueLimits.defaultMaxLength shouldBe 4_096
    }

    @Test
    fun `each bound refuses one past it, naming the key - and accepts the bound itself`() {
        shouldThrow<IllegalArgumentException> { ParametersConfig(maxParametersPerSet = 257) }.message shouldContain
            "datapipelines.parameters.max-parameters-per-set"
        shouldThrow<IllegalArgumentException> { ParametersConfig(maxParametersPerSet = 0) }
        ParametersConfig(maxParametersPerSet = 256).maxParametersPerSet shouldBe 256
        shouldThrow<IllegalArgumentException> { ParametersConfig(maxMultiBindValues = 1_001) }
        shouldThrow<IllegalArgumentException> { ParametersConfig(maxBindsPerStatement = 2_001) }
        shouldThrow<IllegalArgumentException> { ParametersConfig(maxRegexSteps = 999) }
        shouldThrow<IllegalArgumentException> { ParametersConfig(maxEvaluateResponseBytes = 65_535) }
        ParametersConfig(maxWaitingSelectorQueries = 0).maxWaitingSelectorQueries shouldBe 0
    }

    @Test
    fun `a selector statement's timeout must fit inside its evaluate`() {
        shouldThrow<IllegalArgumentException> {
            ParametersConfig(evaluateTimeoutSeconds = 5, selectorQueryTimeoutSeconds = 6)
        }.message shouldContain
            "selector-query-timeout-seconds"
        ParametersConfig(evaluateTimeoutSeconds = 5, selectorQueryTimeoutSeconds = 5).selectorQueryTimeoutSeconds shouldBe 5
    }

    @Test
    fun `ParametersProperties is ParametersConfig's twin - the same fields, the same defaults, and toConfig checks the bounds`() {
        val properties = ParametersProperties()
        val config = ParametersConfig()
        val propertyNames = ParametersProperties::class.primaryConstructor!!.parameters.map { it.name }
        val configNames = ParametersConfig::class.primaryConstructor!!.parameters.map { it.name }
        propertyNames shouldBe configNames
        propertyNames.forEach { name ->
            ParametersProperties::class.memberProperties.single { it.name == name }.get(properties) shouldBe
                ParametersConfig::class.memberProperties.single { it.name == name }.get(config)
        }
        properties.toConfig() shouldBe config
        shouldThrow<IllegalArgumentException> { ParametersProperties(maxExpressionDepth = 65).toConfig() }
    }
}
