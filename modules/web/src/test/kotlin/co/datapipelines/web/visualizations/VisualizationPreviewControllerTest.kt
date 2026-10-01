package co.datapipelines.web.visualizations

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.FixtureEvaluation
import co.datapipelines.visualization.PreviewCase
import co.datapipelines.visualization.TestPreview
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationTestCapabilities
import co.datapipelines.web.ui.UiProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * The preview page's controller over a mocked capability operation: every refusal is the ONE 404 state (and a
 * malformed id never reaches the capability check), a served preview carries per case a §8.1 configuration with one
 * occurrence and the results a refresh would deliver — bindings projected by column, the empty state, the evaluator's
 * refusal — through the script-safe JSON path, and the capability itself never appears in what the page renders.
 */
class VisualizationPreviewControllerTest {
    private val capabilities = mockk<VisualizationTestCapabilities>()
    private val controller = VisualizationPreviewController(capabilities, UiProperties(theme = "dark"))
    private val id = UUID.randomUUID()

    @Test
    fun `every refusal renders the one unavailable state with a 404 - no data, no id, no reason`() {
        every { capabilities.preview(id, any()) } throws
            DatapipelinesException(
                VisualizationErrorCodes.TEST_SESSION_NOT_FOUND,
                "No such test session.",
                mapOf("reason" to "session_unknown"),
            )
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()

        val view = controller.preview(id.toString(), "WRONG", null, model, response)

        view shouldBe "visualizations/preview"
        response.status shouldBe 404
        model["available"] shouldBe false
        model.containsAttribute("previewJson") shouldBe false
    }

    @Test
    fun `a malformed id is the same 404 and never reaches the capability check`() {
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()
        controller.preview("not-a-uuid", "TOKEN", null, model, response)
        response.status shouldBe 404
        model["available"] shouldBe false
        verify(exactly = 0) { capabilities.preview(any(), any()) }
    }

    @Test
    fun `an error that is not the session refusal is not swallowed into the 404`() {
        every { capabilities.preview(id, any()) } throws DatapipelinesException("visualization.not_found", "x", emptyMap())
        shouldThrow<DatapipelinesException> { controller.preview(id.toString(), "T", null, ExtendedModelMap(), MockHttpServletResponse()) }
    }

    @Test
    fun `a served preview carries each case's configuration and results through the script-safe JSON`() {
        every { capabilities.preview(id, "SECRET-TOKEN") } returns preview()
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()

        controller.preview(id.toString(), "SECRET-TOKEN", "light", model, response)

        response.status shouldBe 200
        model["available"] shouldBe true
        model["activeTheme"] shouldBe "light"
        model["bundle"] shouldBe "2d"
        val json = model["previewJson"] as String
        json shouldNotContain "SECRET-TOKEN" // the page never echoes its capability
        json shouldNotContain "</script" // a case name cannot close the block
        val page = ArtifactJson.mapper.readTree(json)
        val cases = page.path("cases")
        cases.size() shouldBe 3

        val data = cases[0]
        data
            .path("config")
            .path("visualizations")[0]
            .path("renderer")
            .path("kind")
            .asText() shouldBe "plotly"
        data
            .path("config")
            .path("actions")[0]
            .path("initial")
            .asBoolean() shouldBe true
        data
            .path("results")
            .path(PreviewViews.OCCURRENCE)
            .path("rows")
            .asInt() shouldBe 2
        data
            .path("results")
            .path(PreviewViews.OCCURRENCE)
            .path("bindings")
            .path("data[0].x")
            .map { it.asText() } shouldBe listOf("Jan", "Feb")
        data.path("inputs").path("revenue").size() shouldBe 2

        cases[1]
            .path("results")
            .path(PreviewViews.OCCURRENCE)
            .path("rows")
            .asInt() shouldBe 0
        cases[2]
            .path("results")
            .path(PreviewViews.OCCURRENCE)
            .path("error")
            .path("code")
            .asText() shouldBe
            "template.evaluate.input_invalid"
    }

    @Test
    fun `the theme is a closed set - anything else is the deployment default`() {
        every { capabilities.preview(id, any()) } returns preview()
        val model = ExtendedModelMap()
        controller.preview(id.toString(), "T", "../../etc", model, MockHttpServletResponse())
        model["activeTheme"] shouldBe "dark"
    }

    private fun preview(): TestPreview {
        val body =
            VisualizationReader()
                .readOrThrow(
                    ArtifactJson.mapper.readTree(
                        """
                        {"name": "finance/visualizations/monthly_revenue", "display_name": "Monthly revenue", "description": "",
                         "renderer": {"kind": "plotly", "version": "4"},
                         "inputs": {"revenue": {"columns": [{"name": "month", "type": "STRING", "nullable": false}]}},
                         "config": {"data": [{"type": "bar", "x": []}]}, "bindings": {"data[0].x": "month"},
                         "tests": {"cases": [{"name": "two months", "fixtures": {"revenue": [{"month": "Jan"}]}, "assertions": [{"kind": "rendered"}]}]}}
                        """.trimIndent(),
                    ),
                ).body
        val at = Instant.parse("2026-10-01T22:00:00Z")
        val record =
            ArtifactRecord(
                id,
                UUID.randomUUID(),
                "finance/visualizations/monthly_revenue",
                "Monthly revenue",
                "",
                null,
                at,
                at,
                UUID.randomUUID(),
            )
        val version =
            ArtifactVersion(record, ArtifactVersionDetail(id, 1, PipelineVersionStatus.DRAFT, "hash-v1", at, UUID.randomUUID()), body)
        val fixtures =
            mapOf(
                "revenue" to
                    listOf(
                        ArtifactJson.mapper.createObjectNode().put("month", "Jan"),
                        ArtifactJson.mapper.createObjectNode().put("month", "Feb"),
                    ),
            )
        return TestPreview(
            UUID.randomUUID(),
            UUID.randomUUID(),
            at,
            version,
            listOf(
                PreviewCase("two months", fixtures, listOf(mapOf("month" to "Jan"), mapOf("month" to "Feb")), null, emptyList()),
                PreviewCase("no data </script>", emptyMap(), emptyList(), null, emptyList()),
                PreviewCase(
                    "refused",
                    fixtures,
                    null,
                    FixtureEvaluation.Refused("template.evaluate.input_invalid", "the fixture does not fit"),
                    emptyList(),
                ),
            ),
        )
    }
}
