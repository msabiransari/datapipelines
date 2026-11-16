package co.datapipelines.visualization

import co.datapipelines.pipeline.ValidationFailure
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * A chart document's save layers (design record 2026-11-06 §4.4) through [VisualizationValidator]: the authored
 * `bindings` tri-state (absent → derived, null → the schema, a non-derived object → `chart.bindings.mismatch`). The
 * D-A library layer's methods left with the layer (js-rendered record §5.4, O-13).
 */
class ChartDocumentLayersTest {
    @Test
    fun `absent bindings are derived, null bindings fail the schema, and a non-derived object is a mismatch`() {
        val absent = DocumentFixtures.visualization().also { it.remove("bindings") }
        valid(absent).document.body.bindings shouldBe mapOf("amounts" to "amounts", "month_labels" to "month_labels")

        val nulled = DocumentFixtures.visualization().also { it.putNull("bindings") }
        invalid(nulled).map { Triple(it.path, it.details["keyword"], it.details["pointer"]) } shouldBe
            listOf(Triple("bindings", "type", "/bindings"))

        val empty = DocumentFixtures.visualization().also { it.putObject("bindings") }
        invalid(empty).map { it.path to it.details["reason"] } shouldBe listOf("bindings" to "chart.bindings.mismatch")
    }

    private fun outcome(tree: ObjectNode): ArtifactValidation<VisualizationDocument> =
        VisualizationValidator(ValidatorFakes.Fakes().templateFacts)
            .validate(ValidatorFakes.WORKSPACE, ValidatorFakes.visualizationDocument(tree))

    private fun valid(tree: ObjectNode): ArtifactValidation.Valid<VisualizationDocument> =
        outcome(tree).shouldBeInstanceOf<ArtifactValidation.Valid<VisualizationDocument>>()

    private fun invalid(tree: ObjectNode): List<ValidationFailure> =
        outcome(tree).shouldBeInstanceOf<ArtifactValidation.Invalid>().result.failures
}
