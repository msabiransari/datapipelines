package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** pipeline-contract §12.8 and §5.1 — `settings.tempdb`. */
class SettingsRulesTest {
    private val validator = Fixtures.validator()

    @Test
    fun `the documented H2 config is accepted and exposed typed`() {
        val settings = tempdb("""{"max_memory_mb": 1024}""")

        validator.validate(Fixtures.pipeline(settings = settings)).failures.shouldBeEmpty()
        settings.tempdb.maxMemoryMb shouldBe 1024
    }

    @Test
    fun `a config key H2 does not define is rejected`() {
        val codes = validator.validate(Fixtures.pipeline(settings = tempdb("""{"page_size": 8}"""))).codes

        codes shouldContainExactly listOf(Validation.TEMPDB_CONFIG_INVALID)
    }

    @Test
    fun `max_memory_mb must be a positive integer`() {
        listOf("""{"max_memory_mb": 0}""", """{"max_memory_mb": -1}""", """{"max_memory_mb": "1024"}""").forEach {
            validator.validate(Fixtures.pipeline(settings = tempdb(it))).codes shouldContainExactly
                listOf(Validation.TEMPDB_CONFIG_INVALID)
        }
    }

    @Test
    fun `an absent settings block is the same pipeline as an explicit H2 with no config`() {
        // §5.1: "If settings.tempdb is omitted entirely, defaults to H2 with default config."
        // Modelling absence and default-H2 differently would hand the executor two states where
        // the spec defines one.
        PipelineSettings() shouldBe PipelineSettings(TempdbSettings(StagingEngine.H2, emptyMap()))
    }

    private fun tempdb(configJson: String): PipelineSettings {
        val config = Fixtures.json(configJson).properties().associate { it.key to it.value }
        return PipelineSettings(TempdbSettings(StagingEngine.H2, config))
    }
}
