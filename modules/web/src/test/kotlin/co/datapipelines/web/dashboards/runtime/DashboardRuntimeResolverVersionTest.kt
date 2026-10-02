package co.datapipelines.web.dashboards.runtime

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardObjectType
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationOccurrence
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.visualizations.ArtifactFamily
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * The resolver's `version` parameter (#369 R2): absent is [DashboardService.findServed] unchanged — the current
 * RELEASED version; a value is [DashboardService.findServedVersion] — a DRAFT or RELEASED version by number — and
 * an absent, discarded or lens-hidden one is the family's 404 naming the version the caller named. R1 is inherited,
 * not re-decided here: the pin rule below stays RELEASED-only, so the draft-resolution case runs over a body whose
 * pins are released and the draft-pin case proves the refusal the preview's board shows in place.
 */
class DashboardRuntimeResolverVersionTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()
    private val name = "finance/dashboards/board"

    private val dashboards = mockk<DashboardService>()
    private val visualizations = mockk<VisualizationService>()
    private val resolver =
        DashboardRuntimeResolver(
            dashboards = dashboards,
            visualizations = visualizations,
            sets = mockk(),
            releaseFacts = mockk(),
            pipelineRepository = mockk(),
            pipelines = mockk(),
            templates = mockk(),
        )

    private fun served(
        version: Int,
        status: PipelineVersionStatus,
        body: DashboardBody,
    ): ArtifactVersion<DashboardBody> =
        ArtifactVersion(
            ArtifactRecord(
                id = dashboardId,
                workspaceId = workspaceId,
                name = name,
                displayName = "board",
                description = "",
                currentVersion = null,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                createdBy = UUID.randomUUID(),
            ),
            ArtifactVersionDetail(
                artifactId = dashboardId,
                version = version,
                status = status,
                bodyHash = "hash-$version-${body.hashCode()}",
                createdAt = Instant.EPOCH,
                createdBy = UUID.randomUUID(),
            ),
            body,
        )

    private fun bodyWith(vizPin: Boolean): DashboardBody =
        DashboardBody(
            displayName = "board",
            visualizations =
                if (vizPin) {
                    listOf(
                        VisualizationOccurrence(
                            name = "v1",
                            type = DashboardObjectType.VISUALIZATION,
                            visualization = ArtifactRef("finance/charts/x", 1),
                            inputs = emptyMap(),
                        ),
                    )
                } else {
                    emptyList()
                },
            layout = co.datapipelines.visualization.DashboardLayout(),
        )

    @Test
    fun `a named DRAFT version resolves - the body the preview page will mount`() {
        val draft = served(2, PipelineVersionStatus.DRAFT, bodyWith(vizPin = false))
        every { dashboards.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 2) } returns draft
        val resolved = resolver.resolve(workspaceId, ReadLens.Everything, dashboardId, 2)
        resolved.served.detail.version shouldBe 2
        resolved.served.detail.status shouldBe PipelineVersionStatus.DRAFT
        resolved.configurationId.length shouldBe 64
    }

    @Test
    fun `an absent named version is the family 404 for the version they named`() {
        every { dashboards.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 9) } returns null
        val thrown =
            assertThrows<ApiException> { resolver.resolve(workspaceId, ReadLens.Everything, dashboardId, 9) }
        thrown.code shouldBe ArtifactFamily.DASHBOARD.notFoundCode
        thrown.details["id"] shouldBe dashboardId.toString()
        thrown.details["version"] shouldBe 9
    }

    @Test
    fun `absent means today's read - the current RELEASED version through findServed, and null is the plain 404`() {
        every { dashboards.findServed(workspaceId, ReadLens.Everything, dashboardId) } returns null
        val thrown = assertThrows<ApiException> { resolver.resolve(workspaceId, ReadLens.Everything, dashboardId) }
        thrown.code shouldBe DashboardErrorCodes.NOT_FOUND
        thrown.details.keys shouldBe setOf("id")
        verify(exactly = 0) { dashboards.findServedVersion(any(), any(), any(), any()) }
    }

    @Test
    fun `a draft pinning a DRAFT visualization is dependency_missing naming the pin, with the release hint`() {
        val draft = served(2, PipelineVersionStatus.DRAFT, bodyWith(vizPin = true))
        every { dashboards.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 2) } returns draft
        val pinned = mockk<ArtifactVersion<VisualizationBody>>()
        every { pinned.detail } returns
            ArtifactVersionDetail(
                artifactId = UUID.randomUUID(),
                version = 1,
                status = PipelineVersionStatus.DRAFT,
                bodyHash = "pin-hash",
                createdAt = Instant.EPOCH,
                createdBy = UUID.randomUUID(),
            )
        every { pinned.body } returns mockk<VisualizationBody>()
        every { visualizations.findVersionByName(workspaceId, ReadLens.Everything, "finance/charts/x", 1) } returns pinned

        val thrown = assertThrows<ApiException> { resolver.resolve(workspaceId, ReadLens.Everything, dashboardId, 2) }
        thrown.code shouldBe DashboardErrorCodes.RUNTIME_DEPENDENCY_MISSING
        thrown.details["dependency"] shouldBe "visualization"
        thrown.details["name"] shouldBe "v1"
        thrown.details["reason"] shouldBe "not_released"
        thrown.message shouldContain "release it first"
    }
}
