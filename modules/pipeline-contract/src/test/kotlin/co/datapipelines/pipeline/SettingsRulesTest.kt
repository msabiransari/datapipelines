package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** pipeline-contract §12.8 and §5.1 — `settings.tempdb`. */
class SettingsRulesTest {
    private val validator = Fixtures.validator()
    private val workspaceId = UUID.randomUUID()

    @Test
    fun `the documented H2 config is accepted and exposed typed`() {
        val settings = tempdb("""{"max_memory_mb": 1024}""")

        validator.validate(Fixtures.pipeline(settings = settings), workspaceId).failures.shouldBeEmpty()
        settings.tempdb.maxMemoryMb shouldBe 1024
    }

    @Test
    fun `a config key H2 does not define is rejected`() {
        val codes = validator.validate(Fixtures.pipeline(settings = tempdb("""{"page_size": 8}""")), workspaceId).codes

        codes shouldContainExactly listOf(Validation.TEMPDB_CONFIG_INVALID)
    }

    @Test
    fun `max_memory_mb must be a positive integer`() {
        listOf("""{"max_memory_mb": 0}""", """{"max_memory_mb": -1}""", """{"max_memory_mb": "1024"}""").forEach {
            validator.validate(Fixtures.pipeline(settings = tempdb(it)), workspaceId).codes shouldContainExactly
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

    // ---------------------------------------------------------- §5.3 pipeline query_timeout_seconds

    @Test
    fun `a positive pipeline query_timeout_seconds within the ceiling is accepted`() {
        val pipeline = Fixtures.pipeline(settings = PipelineSettings(queryTimeoutSeconds = 300))

        validator.validate(pipeline, workspaceId).failures.shouldBeEmpty()
    }

    @Test
    fun `pipeline query_timeout_seconds must be positive and within the operator ceiling`() {
        listOf(0, -1, 901).forEach { bad ->
            val pipeline = Fixtures.pipeline(settings = PipelineSettings(queryTimeoutSeconds = bad))

            validator.validate(pipeline, workspaceId).codes shouldContainExactly listOf(Validation.PIPELINE_QUERY_TIMEOUT_INVALID)
        }
    }

    @Test
    fun `the ceiling is the validator's configured value, not a hard-coded 900`() {
        // Proves PIPELINE_QUERY_TIMEOUT_INVALID's range check reads the CONSTRUCTOR parameter
        // rather than a literal: a validator built with a lower ceiling refuses a value the
        // default-ceiling validator (900) accepts.
        val stricter = Fixtures.validator(nodeQueryTimeoutMaxSeconds = 100)

        stricter.validate(Fixtures.pipeline(settings = PipelineSettings(queryTimeoutSeconds = 200)), workspaceId).codes shouldContainExactly
            listOf(Validation.PIPELINE_QUERY_TIMEOUT_INVALID)
        stricter.validate(Fixtures.pipeline(settings = PipelineSettings(queryTimeoutSeconds = 100)), workspaceId).failures.shouldBeEmpty()
    }

    // -------------------------------------------------------------- §4.11 node query_timeout_seconds

    @Test
    fun `a node's query_timeout_seconds within its own deadline and the ceiling is accepted`() {
        val node = Fixtures.node(settings = NodeSettings(timeoutSeconds = 300, queryTimeoutSeconds = 120))

        validator.validate(Fixtures.pipeline(nodes = listOf(node)), workspaceId).failures.shouldBeEmpty()
    }

    @Test
    fun `a node's query_timeout_seconds is checked against the operator default deadline when the node declares none`() {
        val underDefault = Fixtures.node(settings = NodeSettings(queryTimeoutSeconds = 120))
        validator.validate(Fixtures.pipeline(nodes = listOf(underDefault)), workspaceId).failures.shouldBeEmpty()

        // The operator default node deadline is 300 (PipelineValidator.DEFAULT_NODE_TIMEOUT_SECONDS).
        val overDefault = Fixtures.node(settings = NodeSettings(queryTimeoutSeconds = 301))
        validator.validate(Fixtures.pipeline(nodes = listOf(overDefault)), workspaceId).codes shouldContainExactly
            listOf(Validation.NODE_QUERY_TIMEOUT_INVALID)
    }

    @Test
    fun `node query_timeout_seconds must be positive and within the operator ceiling`() {
        listOf(0, -1, 901).forEach { bad ->
            val node = Fixtures.node(settings = NodeSettings(timeoutSeconds = 900, queryTimeoutSeconds = bad))

            validator.validate(Fixtures.pipeline(nodes = listOf(node)), workspaceId).codes shouldContainExactly
                listOf(Validation.NODE_QUERY_TIMEOUT_INVALID)
        }
    }

    @Test
    fun `node query_timeout_seconds may not exceed that same node's own effective wall-clock deadline`() {
        val node = Fixtures.node(settings = NodeSettings(timeoutSeconds = 60, queryTimeoutSeconds = 61))

        validator.validate(Fixtures.pipeline(nodes = listOf(node)), workspaceId).codes shouldContainExactly
            listOf(Validation.NODE_QUERY_TIMEOUT_INVALID)
    }

    @Test
    fun `node query_timeout_seconds is refused on a CALCULATOR node, which runs no statement`() {
        val calculatorNode = Fixtures.calculatorNode(settings = NodeSettings(queryTimeoutSeconds = 60))

        validator.validate(Fixtures.pipeline(nodes = listOf(calculatorNode)), workspaceId).codes shouldContainExactly
            listOf(Validation.NODE_QUERY_TIMEOUT_INVALID)
    }

    @Test
    fun `pipeline-level query_timeout_seconds does not affect the node-level invariant check`() {
        // The pipeline setting is a DEFAULT for nodes that declare none of their own; it must not
        // be read by the node-level invariant, which compares only the node's OWN two settings.
        val node = Fixtures.node(settings = NodeSettings(timeoutSeconds = 900))
        val pipeline = Fixtures.pipeline(nodes = listOf(node), settings = PipelineSettings(queryTimeoutSeconds = 5))

        validator.validate(pipeline, workspaceId).failures.shouldBeEmpty()
    }

    private fun tempdb(configJson: String): PipelineSettings {
        val config = Fixtures.json(configJson).properties().associate { it.key to it.value }
        return PipelineSettings(TempdbSettings(StagingEngine.H2, config))
    }
}
