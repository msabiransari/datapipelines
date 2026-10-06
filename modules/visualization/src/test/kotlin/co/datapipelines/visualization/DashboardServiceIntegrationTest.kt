package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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

    @Test
    fun `the verbs answer the pointer pair and the purge scope from their own results (#372)`() {
        val viz = h.createVisualization()
        h.visualizations.release(WORKSPACE, viz.record.id, viz.detail.bodyHash, AUTHOR)
        val v1 = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        val released1 = h.dashboards.release(WORKSPACE, v1.record.id, v1.detail.bodyHash, AUTHOR).version
        val v2draft =
            h.dashboards.write(
                WORKSPACE,
                v1.record.id,
                h.dashboardDocument(1) { it.put("display_name", "Second") },
                released1.detail.bodyHash,
                AUTHOR,
                WriteSurface.MCP,
            )
        val v2 = h.dashboards.release(WORKSPACE, v1.record.id, v2draft.detail.bodyHash, AUTHOR).version
        v2.detail.version shouldBe 2
        // Discard the CURRENT version: the pair from the result — before = 2, after = 1.
        h.dashboards.discardVersion(WORKSPACE, v1.record.id, 2, AUTHOR).pointer shouldBe PointerMove(before = 2, after = 1)
        // Restore moves the pointer only upward (D60): before = 1, after = 2.
        h.dashboards.restoreVersion(WORKSPACE, v1.record.id, 2).pointer shouldBe PointerMove(before = 1, after = 2)
        // The switch answers from/to, and the name the service already read for its 404.
        h.dashboards.switchCurrent(WORKSPACE, v1.record.id, 1) shouldBe
            Switched(v1.record.name, PointerMove(before = 2, after = 1))
        // A sole draft takes the dashboard with it: scope = entity, from the result.
        val sole = h.dashboards.create(WORKSPACE, h.dashboardDocument(1, name = "acme/dashboards/sole"), AUTHOR, WriteSurface.MCP)
        h.dashboards.purgeDraft(WORKSPACE, sole.record.id, sole.detail.bodyHash).scope shouldBe "entity"
        // A draft beside a release purges alone: scope = version.
        val beside =
            h.dashboards.write(
                WORKSPACE,
                v1.record.id,
                h.dashboardDocument(1) { it.put("display_name", "Beside") },
                v1.detail.bodyHash,
                AUTHOR,
                WriteSurface.MCP,
            )
        h.dashboards.purgeDraft(WORKSPACE, v1.record.id, beside.detail.bodyHash).scope shouldBe "version"
        // The entity purge is entity by definition — the only version is a DRAFT.
        val onlyDraft =
            h.dashboards.create(
                WORKSPACE,
                h.dashboardDocument(1, name = "acme/dashboards/only_draft"),
                AUTHOR,
                WriteSurface.MCP,
            )
        h.dashboards.purgeEntity(WORKSPACE, onlyDraft.record.id) shouldBe Purged.Entity
    }

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

    // ---- L1b: the two lensed reads the MCP tools use ------------------------------------------------------

    @Test
    fun `pinnedBy - the whole view sees every live pin, a narrowing lens only the admitted RELEASED dashboards`() {
        h.createVisualization()
        val dashboard = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        val pin = "${DocumentFixtures.DASHBOARD_NAME}@1"
        val admitting = ReadLens.Only(setOf(DocumentFixtures.DASHBOARD_NAME))

        h.dashboards.pinnedBy(WORKSPACE, ReadLens.Everything, DocumentFixtures.VISUALIZATION_NAME) shouldBe listOf(pin)
        withClue("a DRAFT dashboard is never in a narrowing lens's answer, admitted name or not") {
            h.dashboards.pinnedBy(WORKSPACE, admitting, DocumentFixtures.VISUALIZATION_NAME) shouldBe emptyList()
        }

        h.dashboards.release(WORKSPACE, dashboard.record.id, dashboard.detail.bodyHash, AUTHOR, releasePinnedVisualizations = true)

        h.dashboards.pinnedBy(WORKSPACE, admitting, DocumentFixtures.VISUALIZATION_NAME) shouldBe listOf(pin)
        h.dashboards.pinnedBy(WORKSPACE, ReadLens.NOTHING, DocumentFixtures.VISUALIZATION_NAME) shouldBe emptyList()
        h.dashboards.pinnedBy(WORKSPACE, admitting, "finance/visualizations/unpinned") shouldBe emptyList()
        h.dashboards.pinnedBy(OTHER_WORKSPACE, ReadLens.Everything, DocumentFixtures.VISUALIZATION_NAME) shouldBe emptyList()
    }

    @Test
    fun `findVersionByName - a pin's address, through the lens - DRAFT and hidden answer as absent under a narrowing one`() {
        h.createVisualization()
        val name = DocumentFixtures.VISUALIZATION_NAME
        val admitting = ReadLens.Only(setOf(name))

        checkNotNull(h.visualizations.findVersionByName(WORKSPACE, ReadLens.Everything, name, 1)).detail.status shouldBe
            PipelineVersionStatus.DRAFT
        h.visualizations.findVersionByName(WORKSPACE, admitting, name, 1) shouldBe null
        h.visualizations.findVersionByName(WORKSPACE, ReadLens.Everything, name, 2) shouldBe null
        h.visualizations.findVersionByName(WORKSPACE, ReadLens.Everything, "finance/visualizations/absent", 1) shouldBe null
        h.visualizations.findVersionByName(OTHER_WORKSPACE, ReadLens.Everything, name, 1) shouldBe null

        val dashboard = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        h.dashboards.release(WORKSPACE, dashboard.record.id, dashboard.detail.bodyHash, AUTHOR, releasePinnedVisualizations = true)

        checkNotNull(h.visualizations.findVersionByName(WORKSPACE, admitting, name, 1)).detail.status shouldBe
            PipelineVersionStatus.RELEASED
        h.visualizations.findVersionByName(WORKSPACE, ReadLens.NOTHING, name, 1) shouldBe null
        val board = h.dashboards.findVersionByName(WORKSPACE, ReadLens.Everything, DocumentFixtures.DASHBOARD_NAME, 1)
        checkNotNull(board).detail.status shouldBe PipelineVersionStatus.RELEASED
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
    fun `a DRAFT source pipeline is admitted while authoring and refused by release and import - source_not_released (#459)`() {
        h.createVisualization()
        h.fakes.pipelines[ValidatorFakes.PIPELINE_REF] =
            h.fakes.pipelines
                .getValue(ValidatorFakes.PIPELINE_REF)
                .copy(status = PipelineVersionStatus.DRAFT)
        val dashboard = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        h.dashboards.validate(WORKSPACE, h.dashboardDocument(1)).shouldBeInstanceOf<ArtifactValidation.Valid<DashboardDocument>>()

        val released =
            refusal {
                h.dashboards.release(WORKSPACE, dashboard.record.id, dashboard.detail.bodyHash, AUTHOR, releasePinnedVisualizations = true)
            }
        released.code shouldBe DashboardErrorCodes.SOURCE_NOT_RELEASED
        checkNotNull(h.dashboardRepository.findDraft(WORKSPACE, dashboard.record.id)).status shouldBe PipelineVersionStatus.DRAFT
        vizStatus() shouldBe PipelineVersionStatus.DRAFT

        val export =
            ArtifactExport(java.util.UUID.randomUUID(), "acme/dashboards/imported", null, null, null, h.dashboardDocument(1).body)
        shouldThrow<ArtifactValidationException> { h.dashboards.import(WORKSPACE, export, AUTHOR) }.code shouldBe
            DashboardErrorCodes.SOURCE_NOT_RELEASED
        h.dashboardRepository.findRecord(WORKSPACE, export.id) shouldBe null
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
