package co.datapipelines.visualization

import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.visualization.ChartLibrarySupport.Companion.CANNOT
import co.datapipelines.visualization.ChartLibrarySupport.Companion.DEGRADED
import co.datapipelines.visualization.ChartLibrarySupport.Companion.DIRECT
import co.datapipelines.visualization.ChartLibrarySupport.Companion.MEANING
import co.datapipelines.visualization.ChartLibrarySupport.Companion.PRESENTATION
import co.datapipelines.visualization.ChartLibrarySupport.Support
import co.datapipelines.visualization.DocumentFixtures.obj
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * A chart document's three save layers (design record 2026-11-06 §4.4) through [VisualizationValidator]: the D-A table
 * as shipped and with PLANTED rows (a `cannot` meaning row refuses at its pointer; any other non-direct row warns, naming
 * the library), the first refusing layer is the only one reported, and the authored `bindings` tri-state (absent →
 * derived, null → the schema, a non-derived object → `chart.bindings.mismatch`).
 */
class ChartDocumentLayersTest {
    @Test
    fun `the shipped D-A table is Plotly's column - 35 rows, all direct - so the worked document saves without a warning`() {
        val table =
            checkNotNull(javaClass.getResourceAsStream("/co/datapipelines/visualization/chart-support/plotly.json")).use {
                ArtifactJson.mapper.readTree(it)
            }
        table.path("library").asText() shouldBe "plotly"
        table.path("fields").size() shouldBe SHIPPED_ROWS
        table.path("fields").map { it.path("class").asText() }.toSet() shouldBe setOf(DIRECT)
        valid(DocumentFixtures.visualization()).warnings.shouldBeEmpty()
    }

    @Test
    fun `a planted cannot row on a meaning field the document sets refuses the save at the field's pointer`() {
        val failures = invalid(DocumentFixtures.visualization(), planted("mark.type" to Support(MEANING, CANNOT)))
        failures.map { Triple(it.code, it.path, it.details["reason"]) } shouldBe
            listOf(Triple(VisualizationErrorCodes.CONFIG_SCHEMA_INVALID, "config.mark.type", "chart.unsupported.mark.type"))
    }

    @Test
    fun `any other non-direct row the document sets is a warning naming the library - a row it does not set is silent`() {
        val warnings =
            valid(
                DocumentFixtures.visualization(),
                planted(
                    "category.title" to Support(PRESENTATION, DEGRADED),
                    "mark.type" to Support(MEANING, DEGRADED),
                    "value.format" to Support(PRESENTATION, CANNOT),
                    "color.field" to Support(MEANING, CANNOT),
                ),
            ).warnings
        warnings shouldBe
            listOf(
                ArtifactWarning("chart.degraded.category.title", "config.encoding.x.title", "plotly"),
                ArtifactWarning("chart.degraded.mark.type", "config.mark.type", "plotly"),
                ArtifactWarning("chart.degraded.value.format", "config.encoding.y.format", "plotly"),
            )
    }

    @Test
    fun `a save-rule refusal stops before the D-A layer - one defect, one layer`() {
        val unknown = DocumentFixtures.visualization().also { it.obj("config.encoding.y").put("field", "profit") }
        invalid(unknown, planted("mark.type" to Support(MEANING, CANNOT))).map { it.details["reason"] }.toSet() shouldBe
            setOf("chart.field.unknown", "chart.bindings.mismatch")
    }

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

    private fun planted(vararg rows: Pair<String, Support>): ChartDocumentRules =
        ChartDocumentRules(support = ChartLibrarySupport("plotly", mapOf(*rows)))

    private fun outcome(
        tree: ObjectNode,
        charts: ChartDocumentRules,
    ): ArtifactValidation<VisualizationDocument> =
        VisualizationValidator(ValidatorFakes.Fakes().templateFacts, charts = charts)
            .validate(ValidatorFakes.WORKSPACE, ValidatorFakes.visualizationDocument(tree))

    private fun valid(
        tree: ObjectNode,
        charts: ChartDocumentRules = ChartDocumentRules.DEFAULT,
    ): ArtifactValidation.Valid<VisualizationDocument> =
        outcome(tree, charts).shouldBeInstanceOf<ArtifactValidation.Valid<VisualizationDocument>>()

    private fun invalid(
        tree: ObjectNode,
        charts: ChartDocumentRules = ChartDocumentRules.DEFAULT,
    ): List<ValidationFailure> = outcome(tree, charts).shouldBeInstanceOf<ArtifactValidation.Invalid>().result.failures

    private companion object {
        const val SHIPPED_ROWS = 35
    }
}
