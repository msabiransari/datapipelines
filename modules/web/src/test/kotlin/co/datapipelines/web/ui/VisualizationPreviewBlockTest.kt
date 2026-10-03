package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.FixtureEvaluation
import co.datapipelines.visualization.PreviewCase
import co.datapipelines.visualization.TestPreview
import co.datapipelines.web.visualizations.PreviewViews
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #399 — the workspace's Preview tab embeds the SAME fixture-mode block the capability page does (#353): one
 * builder, two keys. The capability block keeps its pre-#399 identity (`configuration_id` = `preview:<run>:<i>`,
 * `expires_at` present, the key order visualization → expires_at → cases); the workspace block is that block field
 * for field — inputs, assertions, the §8.1 config, the results — except the configuration key
 * (`workspace:<id>:v<n>:<i>`) and the absent expiry. A workspace builder that drops or alters any field is red here.
 */
class VisualizationPreviewBlockTest {
    private val workspaceId = UUID.randomUUID()
    private val vizId = UUID.randomUUID()
    private val runId = UUID.randomUUID()
    private val expiresAt = Instant.parse("2026-10-02T22:00:00Z")

    private val version =
        VisualizationUiFixtures
            .version(vizId, workspaceId, 2, PipelineVersionStatus.DRAFT, config = """{"data":[{"type":"bar","x":[]}]}""")
            .let { it.copy(body = it.body.copy(bindings = mapOf("data[0].x" to "month"))) }
    private val fixtures =
        mapOf("revenue" to listOf(ArtifactJson.mapper.createObjectNode().put("month", "Jan")))
    private val cases =
        listOf(
            PreviewCase("one month", fixtures, listOf(mapOf("month" to "Jan")), null, emptyList()),
            PreviewCase("closes </script>", emptyMap(), emptyList(), null, emptyList()),
            PreviewCase("refused", fixtures, null, FixtureEvaluation.Refused("template.evaluate.input_invalid", "no fit"), emptyList()),
        )

    private val tabs =
        VisualizationTabModel(
            visualizations = mockk(),
            workspace = mockk(),
            previewCases = mockk(),
            sessions = mockk(),
            dashboards = mockk(),
            templateStatuses = TemplateVersionStatuses { _, _, _ -> null },
        )

    @Test
    fun `the capability block keeps its identity - the run key on every case, the expiry, the key order`() {
        val block = PreviewViews.page(TestPreview(runId, UUID.randomUUID(), expiresAt, version, cases))

        block.fieldNames().asSequence().toList() shouldBe listOf("visualization", "expires_at", "cases")
        block.path("expires_at").asText() shouldBe expiresAt.toString()
        block.path("cases").map { it.path("config").path("configuration_id").asText() } shouldBe
            listOf("preview:$runId:0", "preview:$runId:1", "preview:$runId:2")
    }

    @Test
    fun `the workspace block is the capability block field for field, keyed by the version and never expiring`() {
        val capability = PreviewViews.page(TestPreview(runId, UUID.randomUUID(), expiresAt, version, cases))
        val json = tabs.previewJson(version, cases)
        json shouldNotContain "</script"
        val workspace = ArtifactJson.mapper.readTree(json) as ObjectNode

        workspace.has("expires_at") shouldBe false
        workspace.path("cases").map { it.path("config").path("configuration_id").asText() } shouldBe
            listOf("workspace:$vizId:v2:0", "workspace:$vizId:v2:1", "workspace:$vizId:v2:2")

        // Strip the two intended differences; what remains is identical, case by case and field by field.
        fun normalised(node: ObjectNode): ObjectNode =
            node.deepCopy().also { copy ->
                copy.remove("expires_at")
                copy.path("cases").forEach { (it.path("config") as ObjectNode).remove("configuration_id") }
            }
        normalised(workspace) shouldBe normalised(capability)
    }
}
