package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.RestoreReach
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
 *
 * #873 §D.1 — the restore then walks the stored body's whole closure through the shared port (record §25.2 row G2): the
 * harness answers the walk's reaches per root family, so these cases pin each family's MAPPING of them — a dangling
 * node anywhere below a pin keeps the native 400 at that pin (plus `path` and `via`), a DRAFT one is the 409
 * `<family>.release.dependency_not_released` in the shared shape; the native direct check answers first. The walk
 * itself is `WalkingRestoreDependencyGuardTest`'s and `RestoreOutboundPinsE2eTest`'s.
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

    // ---- the visualization's script template pin

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
    fun `a visualization whose script template version was discarded meanwhile is refused transform_binding_invalid`() {
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

    // ---- #873 §D.1: the transitive recheck, mapped per family

    private fun reach(
        family: ReleaseFamily,
        name: String,
        status: ReleaseNodeStatus,
        directPath: String,
        path: String,
        vararg via: ReleaseNodeIdentity,
    ) = RestoreReach(ReleaseNodeIdentity(WORKSPACE, family, name, 1), name, status, directPath, path, via.toList())

    private val chart = ReleaseNodeIdentity(WORKSPACE, ReleaseFamily.VISUALIZATION, DocumentFixtures.VISUALIZATION_NAME, 1)
    private val shapePath = "visualizations[0].visualization.transform.template"

    @Suppress("UNCHECKED_CAST")
    private fun blockers(error: DatapipelinesException) = error.details["blockers"] as List<Map<String, Any?>>

    private fun failureDetails(error: DatapipelinesException): List<Map<*, *>> =
        (error.details["failures"] as List<*>).map { (it as Map<*, *>)["details"] as Map<*, *> }

    @Test
    fun `the restore walks the DISCARDED dashboard's own identity once, and a released closure restores`() {
        val id = discardedDashboard()

        h.dashboards
            .restoreVersion(WORKSPACE, id, 1)
            .detail.status shouldBe PipelineVersionStatus.RELEASED
        h.restoreRechecked shouldContainExactly
            listOf(ReleaseNodeIdentity(WORKSPACE, ReleaseFamily.DASHBOARD, DocumentFixtures.DASHBOARD_NAME, 1))
    }

    @Test
    fun `a DRAFT beneath the dashboard's RELEASED visualization refuses 409 dependency_not_released with path and via`() {
        val id = discardedDashboard()
        h.restoreReaches[ReleaseFamily.DASHBOARD] =
            listOf(reach(ReleaseFamily.TEMPLATE, "test/shape.jsonata", ReleaseNodeStatus.DRAFT, VIZ_PIN, shapePath, chart))

        val error = refusal { h.dashboards.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe "dashboard.release.dependency_not_released"
        (error.details["root"] as Map<*, *>)["name"] shouldBe DocumentFixtures.DASHBOARD_NAME
        val blocker = blockers(error).single()
        blocker["family"] shouldBe "template"
        blocker["status"] shouldBe "DRAFT"
        blocker["reason"] shouldBe "not_released"
        blocker["paths"] shouldBe listOf(shapePath)
        blocker["via"] shouldBe listOf(mapOf("family" to "visualization", "name" to DocumentFixtures.VISUALIZATION_NAME, "version" to 1))
        status(id) shouldBe PipelineVersionStatus.DISCARDED
    }

    @Test
    fun `a DISCARDED node beneath a RELEASED pin keeps the dashboard's native 400 at that pin, with path and via`() {
        val id = discardedDashboard()
        h.restoreReaches[ReleaseFamily.DASHBOARD] =
            listOf(
                reach(ReleaseFamily.TEMPLATE, "test/shape.jsonata", ReleaseNodeStatus.DISCARDED, VIZ_PIN, shapePath, chart),
                reach(ReleaseFamily.PIPELINE, "test/draft", ReleaseNodeStatus.DRAFT, "sources[0].pipeline", "sources[0].pipeline"),
            )

        val error = refusal { h.dashboards.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe DashboardErrorCodes.DEPENDENCY_NOT_FOUND
        (error.details["failures"] as List<*>).map { (it as Map<*, *>)["path"] } shouldContainExactly listOf(VIZ_PIN)
        val details = failureDetails(error).single()
        details["kind"] shouldBe "template"
        details["status"] shouldBe "DISCARDED"
        details["path"] shouldBe shapePath
        (details["via"] as List<*>).size shouldBe 1
        status(id) shouldBe PipelineVersionStatus.DISCARDED
    }

    @Test
    fun `the native direct check answers before the walk - a dead pipeline pin is the 400 even over a DRAFT reach`() {
        val id = discardedDashboard()
        h.fakes.pipelines[ValidatorFakes.PIPELINE_REF] =
            h.fakes.pipelines
                .getValue(ValidatorFakes.PIPELINE_REF)
                .copy(status = PipelineVersionStatus.DISCARDED)
        h.restoreReaches[ReleaseFamily.DASHBOARD] =
            listOf(reach(ReleaseFamily.TEMPLATE, "test/shape.jsonata", ReleaseNodeStatus.DRAFT, VIZ_PIN, shapePath, chart))

        val error = refusal { h.dashboards.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe DashboardErrorCodes.DEPENDENCY_NOT_FOUND
        danglingKinds(error) shouldContainExactly listOf("pipeline")
        h.restoreRechecked shouldBe emptyList()
    }

    @Test
    fun `a visualization whose transform template is DRAFT refuses 409 visualization dependency_not_released, no via`() {
        val id = discardedVisualization()
        h.restoreReaches[ReleaseFamily.VISUALIZATION] =
            listOf(reach(ReleaseFamily.TEMPLATE, "test/shape.jsonata", ReleaseNodeStatus.DRAFT, TRANSFORM_PIN, TRANSFORM_PIN))

        val error = refusal { h.visualizations.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe "visualization.release.dependency_not_released"
        val blocker = blockers(error).single()
        blocker["paths"] shouldBe listOf(TRANSFORM_PIN)
        blocker.containsKey("via") shouldBe false
        h.restoreRechecked.single().family shouldBe ReleaseFamily.VISUALIZATION
        checkNotNull(h.visualizationRepository.findVersionDetail(WORKSPACE, id, 1)).status shouldBe PipelineVersionStatus.DISCARDED
    }

    @Test
    fun `a missing node beneath the RELEASED transform template is the visualization's native 400 with path and via`() {
        val id = discardedVisualization()
        val transform = ReleaseNodeIdentity(WORKSPACE, ReleaseFamily.TEMPLATE, "test/shape.jsonata", 1)
        h.restoreReaches[ReleaseFamily.VISUALIZATION] =
            listOf(
                reach(
                    ReleaseFamily.TEMPLATE,
                    "test/lib.sql",
                    ReleaseNodeStatus.MISSING,
                    TRANSFORM_PIN,
                    "$TRANSFORM_PIN.imports[0]",
                    transform,
                ),
            )

        val error = refusal { h.visualizations.restoreVersion(WORKSPACE, id, 1) }

        error.code shouldBe VisualizationErrorCodes.TRANSFORM_BINDING_INVALID
        val details = failureDetails(error).single()
        details["reason"] shouldBe "template_version_not_found"
        details["template"] shouldBe "test/lib.sql"
        details["status"] shouldBe "MISSING"
        details["path"] shouldBe "$TRANSFORM_PIN.imports[0]"
        checkNotNull(h.visualizationRepository.findVersionDetail(WORKSPACE, id, 1)).status shouldBe PipelineVersionStatus.DISCARDED
    }

    private companion object {
        const val VIZ_PIN = "visualizations[0].visualization"
        const val TRANSFORM_PIN = "transform.template"
    }
}
