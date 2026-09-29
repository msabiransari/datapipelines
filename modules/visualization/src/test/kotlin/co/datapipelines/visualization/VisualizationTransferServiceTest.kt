package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [ArtifactTransferService] (the spec's §12) against the real schema: the mould's four cases — a real envelope
 * re-imports through its lifecycle keys, a typo refuses (the strict mapper survives the strip), the bundle's
 * templates land FIRST from the envelope root, a malformed envelope refuses before anything lands — plus the nine-key
 * strip pinned, the nested-lifecycle-key smuggle refused, and the dashboard envelope's order (templates →
 * visualizations → the dashboard, the pipelines and the set by reference only).
 */
class VisualizationTransferServiceTest {
    private lateinit var h: LifecycleHarness
    private lateinit var bundle: RecordingBundle
    private lateinit var transfer: ArtifactTransferService

    /** Records every import in order; exports one node per pin. */
    private class RecordingBundle : TemplateBundle {
        val imported = mutableListOf<List<JsonNode>>()
        val events = mutableListOf<String>()

        override fun export(
            workspaceId: UUID,
            pins: List<TemplateRef>,
        ): List<JsonNode> =
            pins.map {
                ArtifactJson.mapper
                    .createObjectNode()
                    .put("id", it.id)
                    .put("version", it.version)
            }

        override fun import(
            workspaceId: UUID,
            templates: List<JsonNode>,
            actor: UUID,
        ) {
            imported += templates
            events += "templates:${templates.size}"
        }
    }

    @BeforeEach
    fun reset() {
        VisualizationTestDb.reset()
        h = LifecycleHarness()
        bundle = RecordingBundle()
        transfer = ArtifactTransferService(h.visualizations, h.dashboards, bundle)
    }

    private fun releasedVisualization(): ArtifactVersion<VisualizationBody> {
        val created = h.createVisualization()
        return h.visualizations.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR).version
    }

    @Test
    fun `a real export envelope re-imports elsewhere - the body binds through the nine lifecycle keys, the id is kept`() {
        val released = releasedVisualization()
        val envelope = transfer.exportVisualization(WORKSPACE, released.record.id)
        val payload = envelope.get("visualization") as ObjectNode
        ArtifactTransferService.LIFECYCLE_KEYS.filter { payload.has(it) }.toSet() shouldBe
            setOf("id", "name", "version", "status", "body_hash", "created_at", "updated_at", "current_version", "released_at")
        envelope.get("manifest").get("visualization_body_hash").asText() shouldBe released.detail.bodyHash
        envelope.get("templates").map { it.get("id").asText() } shouldBe listOf("finance/transforms/revenue_bars")
        // Ids are global on one server (C29: another workspace here would be id_taken) — a fresh target is simulated
        // by removing the source rows, then importing the envelope where they were.
        h.visualizationRepository.deleteEntity(WORKSPACE, released.record.id)
        val imported = transfer.importVisualization(WORKSPACE, envelope, AUTHOR)
        imported.created shouldBe true
        imported.detail.artifactId shouldBe released.record.id
        imported.detail.version shouldBe 1
        imported.detail.bodyHash shouldBe released.detail.bodyHash
        bundle.imported.single().size shouldBe 1
        transfer.importVisualization(WORKSPACE, envelope, AUTHOR).unchanged shouldBe true
        shouldThrow<DatapipelinesException> { transfer.importVisualization(OTHER_WORKSPACE, envelope, AUTHOR) }.code shouldBe
            VisualizationErrorCodes.IMPORT_ID_TAKEN
    }

    @Test
    fun `the nine-key strip is exactly the lifecycle keys - top level only`() {
        ArtifactTransferService.LIFECYCLE_KEYS shouldBe
            setOf("id", "name", "version", "created_at", "updated_at", "current_version", "status", "body_hash", "released_at")
        val payload = ArtifactTransferService.payloadOf(releasedVisualization())
        ArtifactTransferService.bodyOf(payload, VisualizationBody::class.java) shouldNotBe null
    }

    @Test
    fun `a typo in the payload refuses, and a lifecycle key smuggled into a nested object never binds`() {
        val envelope = transfer.exportVisualization(WORKSPACE, releasedVisualization().record.id)
        val typo = envelope.deepCopy()
        (typo.get("visualization") as ObjectNode).put("displayname", "x")
        shouldThrow<DatapipelinesException> { transfer.importVisualization(OTHER_WORKSPACE, typo, AUTHOR) }.code shouldBe
            VisualizationErrorCodes.BODY_INVALID
        val smuggled = envelope.deepCopy()
        ((smuggled.get("visualization") as ObjectNode).get("renderer") as ObjectNode).put("id", "x")
        shouldThrow<DatapipelinesException> { transfer.importVisualization(OTHER_WORKSPACE, smuggled, AUTHOR) }.details["reason"] shouldBe
            "malformed_envelope"
        ArtifactTransferService.bodyOf(smuggled.get("visualization") as ObjectNode, VisualizationBody::class.java) shouldBe null
        bundle.imported shouldBe emptyList()
    }

    @Test
    fun `an envelope without its artifact node refuses before any template lands`() {
        val headless = ArtifactJson.mapper.createObjectNode()
        headless.putArray("templates").addObject().put("id", "x")
        shouldThrow<DatapipelinesException> { transfer.importVisualization(OTHER_WORKSPACE, headless, AUTHOR) }.details["reason"] shouldBe
            "malformed_envelope"
        bundle.imported shouldBe emptyList()
    }

    @Test
    fun `a dashboard envelope carries its visualizations' envelopes and its pipelines and set BY REFERENCE - landing in D61's order`() {
        releasedVisualization()
        val dashboard = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        h.dashboards.release(WORKSPACE, dashboard.record.id, dashboard.detail.bodyHash, AUTHOR)
        val envelope = transfer.exportDashboard(WORKSPACE, dashboard.record.id)
        envelope.get("visualizations").size() shouldBe 1
        envelope
            .get("visualizations")
            .get(0)
            .get("visualization")
            .get("name")
            .asText() shouldBe DocumentFixtures.VISUALIZATION_NAME
        envelope
            .get("manifest")
            .get("pipeline_pins")
            .get(0)
            .get("name")
            .asText() shouldBe "finance/pipelines/monthly_revenue"
        envelope
            .get("manifest")
            .get("parameter_set_pin")
            .get("name")
            .asText() shouldBe "finance/parameters/reporting_period"
        envelope.has("pipelines") shouldBe false
        h.dashboardRepository.deleteEntity(WORKSPACE, dashboard.record.id)
        h.visualizationRepository.findRecordByName(WORKSPACE, DocumentFixtures.VISUALIZATION_NAME)?.let {
            h.visualizationRepository.deleteEntity(WORKSPACE, it.id)
        }
        val landed = transfer.importDashboard(WORKSPACE, envelope, AUTHOR)
        bundle.events shouldBe listOf("templates:1")
        landed.visualizations
            .single()
            .detail.status shouldBe PipelineVersionStatus.RELEASED
        landed.dashboard.created shouldBe true
        landed.dashboard.detail.artifactId shouldBe dashboard.record.id
    }

    @Test
    fun `an unreleased artifact does not export - a draft never crosses environments`() {
        val draft = h.createVisualization()
        shouldThrow<DatapipelinesException> { transfer.exportVisualization(WORKSPACE, draft.record.id) }.code shouldBe
            VisualizationErrorCodes.NOT_FOUND
    }
}
