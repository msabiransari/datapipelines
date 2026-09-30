package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
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

/** A TemplateBundle that records and answers nothing; these cases carry no templates. */
private object SilentBundle : TemplateBundle {
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
 * O2 of the orchestrator's L1c pass (#10 L1c-b): an import that lands RELEASED judges its pins by the
 * RELEASE rules, not the save rules. The save rules accept a DRAFT pin (a draft may be released WITH its
 * pin); an import has no such consent to give — a hand-edited envelope that drops its bundle lands a
 * RELEASED artifact pinning a local, author-mutable DRAFT, breaking "a RELEASED dashboard pins only
 * RELEASED" (`dashboard.release.dependency_not_released`). The refusal is the family's RELEASE code.
 *
 * Both cases plant the pin's status at DRAFT through the SAME port the production judge reads
 * ([LifecycleHarness.templateStatuses] / the validator fakes' set facts) while the SAVE-side fake keeps
 * the pin's contract valid — so a green run here can only be the release judge, never a save refusal.
 */
class ArtifactTransferImportReleaseRulesTest {
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
                SilentBundle,
                VisualizationReader(),
                DashboardReader(),
                transactions = h.transactions,
                releaseRules = h.importReleaseRules,
            )
    }

    @Test
    fun `a visualization import whose transform pin is a local DRAFT refuses with the release code`() {
        val created = h.createVisualization()
        h.visualizations.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR)
        val envelope = transfer.exportVisualization(WORKSPACE, created.record.id)
        // The importing workspace holds the pinned template version, but only as a DRAFT.
        h.templateStatuses[ValidatorFakes.TRANSFORM_REF] = PipelineVersionStatus.DRAFT
        (envelope.get("visualization") as ObjectNode).put("id", UUID.randomUUID().toString())

        val refusal = shouldThrow<DatapipelinesException> { transfer.importVisualization(OTHER_WORKSPACE, envelope, AUTHOR) }

        withClue("the refusal must be the family's RELEASE code: ${refusal.details}") {
            refusal.code shouldBe VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED
        }
        withClue("nothing may land") { countVisualizations(OTHER_WORKSPACE) shouldBe 0 }
    }

    @Test
    fun `a dashboard import whose pinned parameter set is a local DRAFT refuses with the release code`() {
        val envelope = exportedDashboardEnvelope()
        // The set's parameters keep their shape so the SAVE rules would pass — only the status flips,
        // so the refusal below can only be the release judge's.
        h.fakes.sets[ValidatorFakes.SET_REF] =
            ParameterSetFact(
                PipelineVersionStatus.DRAFT,
                listOf(SetParameterFact("year", emptySet()), SetParameterFact("currency", emptySet())),
            )
        (envelope.get("dashboard") as ObjectNode).put("id", UUID.randomUUID().toString())
        val bundledEnvelope = (envelope.get("visualizations") as com.fasterxml.jackson.databind.node.ArrayNode).get(0) as ObjectNode
        (bundledEnvelope.get("visualization") as ObjectNode).put("id", UUID.randomUUID().toString())

        val refusal = shouldThrow<DatapipelinesException> { transfer.importDashboard(OTHER_WORKSPACE, envelope, AUTHOR) }

        withClue("the refusal must be the family's RELEASE code: ${refusal.details}") {
            refusal.code shouldBe DashboardErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED
        }
        withClue("nothing may land") { countVisualizations(OTHER_WORKSPACE) shouldBe 0 }
    }

    /** A released visualization and a released dashboard pinning it (the atomicity test's fixture shape). */
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
