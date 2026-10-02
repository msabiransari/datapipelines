package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #320, D7 — a DISCARDED version protects nothing: the pin guards count LIVE versions, so the pipeline release, the set
 * version, the visualization version or the template version a DISCARDED dependent pins can be discarded from under it.
 * Restoring it blind would bring back a RELEASED version the validator refuses on its own and the runtime refuses to
 * serve — so `restoreVersion` judges the DEPENDENCIES against today's state first and refuses the family's own
 * `dependency_not_found` (dashboards) or `transform_binding_invalid` (visualizations), naming the dead pin. A rule that
 * merely tightened since is not a dangling pin and does not block the restore; a wrong-state version keeps the
 * lifecycle's own refusal.
 *
 * Each case first shows the SAME restore succeeds while the dependency is alive (the control), so a refusal is the
 * dependency's doing and nothing else's.
 */
class RestoreDependenciesIntegrationTest {
    private lateinit var h: LifecycleHarness

    @BeforeEach
    fun reset() {
        VisualizationTestDb.reset()
        h = LifecycleHarness()
    }

    private fun refusal(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException>(block)

    /** A RELEASED dashboard version 1, then DISCARDED: the state whose pins are unprotected. */
    private fun discardedDashboard(): UUID {
        h.createVisualization()
        val created = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, co.datapipelines.pipeline.WriteSurface.MCP)
        h.dashboards.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR, releasePinnedVisualizations = true)
        h.dashboards.discardVersion(WORKSPACE, created.record.id, 1, AUTHOR)
        return created.record.id
    }

    private fun status(id: UUID): PipelineVersionStatus = checkNotNull(h.dashboardRepository.findVersionDetail(WORKSPACE, id, 1)).status

    private fun danglingKinds(error: DatapipelinesException): List<Any?> =
        (error.details["failures"] as List<*>).map { ((it as Map<*, *>)["details"] as Map<*, *>)["kind"] }

    @Test
    fun `control - a discarded dashboard whose dependencies are alive restores`() {
        val id = discardedDashboard()

        h.dashboards
            .restoreVersion(WORKSPACE, id, 1)
            .detail.status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `a dashboard whose pipeline release was discarded while it was discarded is refused dependency_not_found`() {
        val id = discardedDashboard()
        h.fakes.pipelines[ValidatorFakes.PIPELINE_REF] =
            h.fakes.pipelines
                .getValue(ValidatorFakes.PIPELINE_REF)
                .copy(status = PipelineVersionStatus.DISCARDED)

        val error = refusal { h.dashboards.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe DashboardErrorCodes.DEPENDENCY_NOT_FOUND
        danglingKinds(error) shouldContainExactly listOf("pipeline")
        status(id) shouldBe PipelineVersionStatus.DISCARDED
    }

    @Test
    fun `a dashboard whose parameter set version was discarded meanwhile is refused dependency_not_found`() {
        val id = discardedDashboard()
        h.fakes.sets[ValidatorFakes.SET_REF] =
            h.fakes.sets
                .getValue(ValidatorFakes.SET_REF)
                .copy(status = PipelineVersionStatus.DISCARDED)

        val error = refusal { h.dashboards.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe DashboardErrorCodes.DEPENDENCY_NOT_FOUND
        danglingKinds(error) shouldContainExactly listOf("parameter_set")
        status(id) shouldBe PipelineVersionStatus.DISCARDED
    }

    @Test
    fun `a dashboard whose visualization was discarded meanwhile - the window itself - is refused dependency_not_found`() {
        val id = discardedDashboard()
        val visualization = checkNotNull(h.visualizationRepository.findRecordByName(WORKSPACE, DocumentFixtures.VISUALIZATION_NAME))
        // The window this guard exists for: nothing live pins the visualization while the dashboard is DISCARDED.
        h.visualizations
            .discardVersion(WORKSPACE, visualization.id, 1, AUTHOR)
            .detail.status shouldBe PipelineVersionStatus.DISCARDED

        val error = refusal { h.dashboards.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe DashboardErrorCodes.DEPENDENCY_NOT_FOUND
        danglingKinds(error) shouldContainExactly listOf("visualization")
        status(id) shouldBe PipelineVersionStatus.DISCARDED
    }

    @Test
    fun `a rule that tightened since is not a dangling pin - only the dependency failures block a restore`() {
        val id = discardedDashboard()
        // The pinned release still EXISTS but is no longer read-only: the validator refuses it (D38), the pin is not dangling.
        h.fakes.pipelines[ValidatorFakes.PIPELINE_REF] =
            h.fakes.pipelines
                .getValue(ValidatorFakes.PIPELINE_REF)
                .copy(readOnly = false)

        h.dashboards
            .restoreVersion(WORKSPACE, id, 1)
            .detail.status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `a version that is not discarded keeps the lifecycle's own refusal - the guard does not answer for it`() {
        h.createVisualization()
        val created = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, co.datapipelines.pipeline.WriteSurface.MCP)

        refusal { h.dashboards.restoreVersion(WORKSPACE, created.record.id, 1) }.code shouldBe DashboardErrorCodes.VERSION_NOT_DISCARDED
        refusal { h.dashboards.restoreVersion(WORKSPACE, UUID.randomUUID(), 1) }.code shouldBe DashboardErrorCodes.NOT_FOUND
    }

    // ---- the visualization's transform template pin

    private fun discardedVisualization(): UUID {
        val created = h.createVisualization()
        h.visualizations.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR)
        h.visualizations.discardVersion(WORKSPACE, created.record.id, 1, AUTHOR)
        return created.record.id
    }

    @Test
    fun `control - a discarded visualization whose template is alive restores`() {
        val id = discardedVisualization()

        h.visualizations
            .restoreVersion(WORKSPACE, id, 1)
            .detail.status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `a visualization whose transform template version was discarded meanwhile is refused transform_binding_invalid`() {
        val id = discardedVisualization()
        h.fakes.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.VersionNotFound

        val error = refusal { h.visualizations.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe VisualizationErrorCodes.TRANSFORM_BINDING_INVALID
        ((error.details["failures"] as List<*>).map { ((it as Map<*, *>)["details"] as Map<*, *>)["reason"] }) shouldContainExactly
            listOf("template_version_not_found")
        checkNotNull(h.visualizationRepository.findVersionDetail(WORKSPACE, id, 1)).status shouldBe PipelineVersionStatus.DISCARDED
    }

    @Test
    fun `a visualization whose template vanished entirely is refused with the other reason`() {
        val id = discardedVisualization()
        h.fakes.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.NotFound

        val error = refusal { h.visualizations.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe VisualizationErrorCodes.TRANSFORM_BINDING_INVALID
        ((error.details["failures"] as List<*>).map { ((it as Map<*, *>)["details"] as Map<*, *>)["reason"] }) shouldContainExactly
            listOf("template_not_found")
    }
}
