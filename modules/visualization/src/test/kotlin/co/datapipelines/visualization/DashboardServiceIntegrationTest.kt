package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [DashboardService]'s own rules against the real schema: the dashboard validates against the pinned visualization
 * THIS database holds; D61's release — the set released, every pinned visualization released or cascaded under
 * consent through the visualization's own release (gate and all) — inside ONE transaction whose stale hash rolls the
 * cascade back; `validate` writes nothing; the import lens.
 */
class DashboardServiceIntegrationTest {
    private lateinit var h: LifecycleHarness

    @BeforeEach
    fun reset() {
        VisualizationTestDb.reset()
        h = LifecycleHarness()
    }

    private fun refusal(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException>(block)

    private fun vizStatus(harness: LifecycleHarness = h): PipelineVersionStatus =
        checkNotNull(harness.visualizationRepository.findRecordByName(WORKSPACE, DocumentFixtures.VISUALIZATION_NAME)).let {
            checkNotNull(harness.visualizationRepository.findVersionDetail(WORKSPACE, it.id, 1)).status
        }

    @Test
    fun `a dashboard validates against the visualization this database holds - an absent pin is dependency_not_found`() {
        shouldThrow<ArtifactValidationException> {
            h.dashboards.create(
                WORKSPACE,
                h.dashboardDocument(1),
                AUTHOR,
                WriteSurface.MCP,
            )
        }.code shouldBe
            DashboardErrorCodes.DEPENDENCY_NOT_FOUND
        h.createVisualization()
        val created = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        created.detail.status shouldBe PipelineVersionStatus.DRAFT
        h.dashboards.validate(WORKSPACE, h.dashboardDocument(1)).shouldBeInstanceOf<ArtifactValidation.Valid<DashboardDocument>>()
    }

    @Test
    fun `release refuses a DRAFT set and a DRAFT visualization without consent - every dependency named`() {
        h.createVisualization()
        val dashboard = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        h.fakes.sets[ValidatorFakes.SET_REF] =
            h.fakes.sets
                .getValue(ValidatorFakes.SET_REF)
                .copy(status = PipelineVersionStatus.DRAFT)
        val refused = refusal { h.dashboards.release(WORKSPACE, dashboard.record.id, dashboard.detail.bodyHash, AUTHOR) }
        refused.code shouldBe DashboardErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED
        (refused.details["dependencies_not_released"] as List<*>).map { (it as Map<*, *>)["kind"] to it["status"] } shouldBe
            listOf("parameter_set" to "DRAFT", "visualization" to "DRAFT")
        vizStatus() shouldBe PipelineVersionStatus.DRAFT
    }

    @Test
    fun `with consent the DRAFT visualization is released in the dashboard's transaction, the dashboard's flip last`() {
        h.createVisualization()
        val dashboard = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        val released =
            h.dashboards.release(
                WORKSPACE,
                dashboard.record.id,
                dashboard.detail.bodyHash,
                AUTHOR,
                releasePinnedVisualizations = true,
            )
        released.visualizationsReleased shouldBe listOf(ArtifactRef(DocumentFixtures.VISUALIZATION_NAME, 1))
        released.version.detail.status shouldBe PipelineVersionStatus.RELEASED
        vizStatus() shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `the cascade is atomic - a stale dashboard hash rolls the visualization's release back`() {
        h.createVisualization()
        val dashboard = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        refusal { h.dashboards.release(WORKSPACE, dashboard.record.id, "stale", AUTHOR, releasePinnedVisualizations = true) }.code shouldBe
            DashboardErrorCodes.VERSION_CONFLICT
        vizStatus() shouldBe PipelineVersionStatus.DRAFT
        checkNotNull(h.dashboardRepository.findDraft(WORKSPACE, dashboard.record.id)).status shouldBe PipelineVersionStatus.DRAFT
    }

    @Test
    fun `until the evidence gate is installed a consented cascade is refused by it - the dashboard and the visualization stay DRAFT`() {
        val gated = LifecycleHarness(evidence = ReleaseEvidence.NOT_INSTALLED)
        gated.createVisualization()
        val dashboard = gated.dashboards.create(WORKSPACE, gated.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        val refused =
            refusal {
                gated.dashboards.release(
                    WORKSPACE,
                    dashboard.record.id,
                    dashboard.detail.bodyHash,
                    AUTHOR,
                    releasePinnedVisualizations = true,
                )
            }
        refused.code shouldBe VisualizationErrorCodes.RELEASE_TESTS_MISSING
        vizStatus(gated) shouldBe PipelineVersionStatus.DRAFT
        checkNotNull(gated.dashboardRepository.findDraft(WORKSPACE, dashboard.record.id)).status shouldBe PipelineVersionStatus.DRAFT
    }

    @Test
    fun `import validates against THIS deployment - a pinned visualization it lacks is missing_dependency`() {
        val export =
            ArtifactExport(java.util.UUID.randomUUID(), DocumentFixtures.DASHBOARD_NAME, null, null, null, h.dashboardDocument(1).body)
        shouldThrow<ArtifactValidationException> { h.dashboards.import(OTHER_WORKSPACE, export, AUTHOR) }.code shouldBe
            DashboardErrorCodes.IMPORT_MISSING_DEPENDENCY
        h.dashboardRepository.findRecord(OTHER_WORKSPACE, export.id) shouldBe null
    }
}
