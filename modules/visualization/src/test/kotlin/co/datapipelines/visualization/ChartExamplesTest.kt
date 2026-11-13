package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * The 25 chart examples (`chart-examples/`, the 854 set re-cut under design record R11) through the PRODUCTION save
 * path — the reader, then [VisualizationValidator] with its three layers — each against `expectations.json`: `valid`
 * passes; `schema` fails ONLY at the schema with an error of the named keyword at the named pointer; `save` refuses
 * with EXACTLY the named `chart.*` code; `product` is refused by the named product rule. The same files and
 * expectations drive the JS reference (`validation-parity.mjs`, the lane's evidence), so the two agree file for file.
 */
class ChartExamplesTest {
    @Test
    fun `every example file has exactly one expectation - a file without one is red`() {
        val files =
            ROOT
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".json") && it.name != "expectations.json" }
                .map { it.relativeTo(ROOT).invariantSeparatorsPath }
                .toSet()
        files shouldBe EXPECTATIONS.keys
        files.size shouldBe EXAMPLE_COUNT
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    fun `the example meets its expectation through the production save path`(file: String) {
        val expected = EXPECTATIONS.getValue(file)
        val tree = ArtifactJson.mapper.readTree(File(ROOT, file)) as ObjectNode
        tree.put("name", "examples/visualizations/example")
        val outcome =
            ValidatorFakes.Fakes().visualizationValidator().validate(
                ValidatorFakes.WORKSPACE,
                ValidatorFakes.visualizationDocument(tree),
            )
        if (expected.path("valid").asBoolean()) {
            withClue(
                (outcome as? ArtifactValidation.Invalid)?.result?.failures,
            ) { outcome.shouldBeInstanceOf<ArtifactValidation.Valid<*>>() }
            return
        }
        val failures = outcome.shouldBeInstanceOf<ArtifactValidation.Invalid>().result.failures
        when (val layer = expected.path("layer").asText()) {
            "schema" -> {
                failures.map { it.code to it.details["reason"] }.toSet() shouldBe
                    setOf(VisualizationErrorCodes.CONFIG_SCHEMA_INVALID to "schema")
                failures.map { it.details["keyword"] to it.details["pointer"] } shouldContain
                    (expected.path("keyword").asText() to expected.path("path").asText())
            }

            "save" -> {
                failures.map { it.code to it.details["reason"] }.toSet() shouldBe
                    setOf(VisualizationErrorCodes.CONFIG_SCHEMA_INVALID to expected.path("code").asText())
            }

            "product" -> {
                failures.map { Triple(it.code, it.details["reason"], it.path) } shouldContain
                    Triple(expected.path("code").asText(), expected.path("reason").asText(), expected.path("path").asText())
            }

            else -> {
                error("unknown layer $layer in expectations.json")
            }
        }
    }

    companion object {
        /** The examples on the test classpath (a directory: test resources are never packaged). */
        private val ROOT: File by lazy {
            File(checkNotNull(ChartExamplesTest::class.java.getResource("/chart-examples/expectations.json")).toURI()).parentFile
        }
        private const val EXAMPLE_COUNT = 25

        private val EXPECTATIONS: Map<String, JsonNode> by lazy {
            ArtifactJson.mapper
                .readTree(File(ROOT, "expectations.json"))
                .path("examples")
                .properties()
                .associate { (file, expectation) -> file to expectation }
        }

        @JvmStatic
        fun examples(): List<String> = EXPECTATIONS.keys.sorted()
    }
}
