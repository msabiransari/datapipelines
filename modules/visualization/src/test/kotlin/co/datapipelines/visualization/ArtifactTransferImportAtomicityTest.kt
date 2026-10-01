package co.datapipelines.visualization

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Records nothing useful here; the dashboard's bundle carries no templates in this fixture. */
private object NoTemplates : TemplateBundle {
    override fun export(
        workspaceId: UUID,
        pins: List<TemplateRef>,
    ): List<JsonNode> = emptyList()

    override fun import(
        workspaceId: UUID,
        templates: List<JsonNode>,
        actor: UUID,
    ) = Unit
}

/**
 * F1 of the orchestrator's L1c security pass (#10 L1c-b): the REST dashboard import is ONE transaction —
 * templates → bundled visualizations → the dashboard — as the promotion receive already is. At the L1c tip
 * each bundled visualization landed RELEASED in its own transaction BEFORE the dashboard was validated, so a
 * refused dashboard left N evidence-less visualizations its audit never named (the record-honesty finding).
 * The refusal here is the dashboard's OWN validation (`dashboard.import.missing_dependency`: its source pins
 * a pipeline the importing workspace does not hold) — the scenario the pass wrote down.
 */
class ArtifactTransferImportAtomicityTest {
    private lateinit var h: LifecycleHarness
    private lateinit var transfer: ArtifactTransferService

    @BeforeEach
    fun reset() {
        VisualizationTestDb.reset()
        h = LifecycleHarness()
        transfer =
            ArtifactTransferService(
                h.visualizations,
                h.dashboards,
                NoTemplates,
                VisualizationReader(),
                DashboardReader(),
                transactions = h.transactions,
                releaseRules = h.importReleaseRules,
            )
    }

    @Test
    fun `a refused dashboard import leaves NOTHING landed - the bundled visualization rolls back with it`() {
        val envelope = exportedDashboardEnvelope()

        // Both payloads get FRESH ids: the exported ids are global primary keys already held by the
        // source rows (C29), and an id_taken at the VISUALIZATION's landing would refuse before the
        // dashboard's own step — the scenario under test is the refusal AFTER the bundle landed.
        // The bundled body is unchanged, so its body_hash still verifies.
        (envelope.get("dashboard") as ObjectNode).put("id", UUID.randomUUID().toString())
        val bundledEnvelope = (envelope.get("visualizations") as com.fasterxml.jackson.databind.node.ArrayNode).get(0) as ObjectNode
        (bundledEnvelope.get("visualization") as ObjectNode).put("id", UUID.randomUUID().toString())

        // The dashboard's own step refuses: its source pipeline is absent on the importing workspace
        // (the save rules' dependency_not_found, the import lens' missing_dependency spelling).
        val sourcePipeline = (envelope.get("dashboard") as ObjectNode).at("/sources/0/pipeline") as ObjectNode
        sourcePipeline.put("name", ValidatorFakes.PIPELINE_REF.name + "_absent")

        val refusal =
            shouldThrow<DatapipelinesException> { transfer.importDashboard(OTHER_WORKSPACE, envelope, AUTHOR) }

        withClue("the refusal must be the dashboard's own import code: ${refusal.details}") {
            refusal.code shouldBe DashboardErrorCodes.IMPORT_MISSING_DEPENDENCY
        }
        withClue("a refused dashboard leaves zero bundled visualizations - the whole import rolled back") {
            countVisualizations(OTHER_WORKSPACE) shouldBe 0
        }
    }

    /** A released visualization, a released dashboard pinning it, and the §12 dashboard envelope over them. */
    private fun exportedDashboardEnvelope(): ObjectNode {
        val visualization = h.createVisualization()
        val releasedViz = h.visualizations.release(WORKSPACE, visualization.record.id, visualization.detail.bodyHash, AUTHOR)
        val document = h.dashboardDocument(visualizationVersion = releasedViz.version.detail.version)
        val dashboard = h.dashboards.create(WORKSPACE, document, AUTHOR, co.datapipelines.pipeline.WriteSurface.MCP)
        h.dashboards.release(WORKSPACE, dashboard.record.id, dashboard.detail.bodyHash, AUTHOR)
        return transfer.exportDashboard(WORKSPACE, dashboard.record.id)
    }

    private fun countVisualizations(workspace: UUID): Int =
        h.jdbc
            .queryForObject(
                "SELECT count(*) FROM visualizations WHERE workspace_id = :ws",
                mapOf("ws" to workspace),
                Int::class.java,
            ) ?: 0
}
