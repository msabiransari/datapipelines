package co.datapipelines.visualization

import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [ArtifactJson]'s mapper is the backstop for every path that binds WITHOUT a reader's pre-scan (a transfer
 * payload after its lifecycle keys are stripped): a wrong JSON type is refused, never coerced.
 */
class ArtifactJsonTest {
    private val base = DocumentFixtures.visualization().also { it.remove("name") }

    @Test
    fun `a number or a boolean where a string is declared is refused, not bound as text`() {
        shouldThrow<MismatchedInputException> { bind(base.deepCopy().put("display_name", 5)) }
        shouldThrow<MismatchedInputException> { bind(base.deepCopy().put("display_name", 1.5)) }
        shouldThrow<MismatchedInputException> { bind(base.deepCopy().put("description", true)) }
    }

    @Test
    fun `a string or a number where an integer or a boolean is declared is refused`() {
        val versionAsText =
            base.deepCopy().also {
                (it.get("transform").get("template") as com.fasterxml.jackson.databind.node.ObjectNode).put("version", "2")
            }
        shouldThrow<MismatchedInputException> { bind(versionAsText) }
        val versionAsFloat =
            base.deepCopy().also {
                (it.get("transform").get("template") as com.fasterxml.jackson.databind.node.ObjectNode).put("version", 2.0)
            }
        shouldThrow<InvalidFormatException> { bind(versionAsFloat) }
        val nullableAsNumber =
            base.deepCopy().also {
                (
                    (
                        it
                            .get(
                                "inputs",
                            ).get("revenue")
                            .get("columns")
                            .get(0)
                    ) as com.fasterxml.jackson.databind.node.ObjectNode
                ).put("nullable", 1)
            }
        shouldThrow<MismatchedInputException> { bind(nullableAsNumber) }
    }

    @Test
    fun `the stored form round-trips - what the writer emits, the reader binds to an equal body`() {
        val body = bind(base)
        ArtifactJson.readVisualization(ArtifactJson.writeBody(body)) shouldBe body
    }

    private fun bind(tree: com.fasterxml.jackson.databind.JsonNode): VisualizationBody =
        ArtifactJson.mapper.treeToValue(tree, VisualizationBody::class.java)
}
