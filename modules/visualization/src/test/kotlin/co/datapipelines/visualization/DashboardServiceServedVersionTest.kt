package co.datapipelines.visualization

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `findServedVersion` (#369, the draft preview's R2) beside `findServed`: a NAMED version is served DRAFT or
 * RELEASED under the whole view, RELEASED only under a narrowing lens (the promoter never learns of a draft —
 * the same null a discarded or absent version answers, the family's 404 at the surface), and the repository's
 * answer is filtered by name through the lens in every case. `findServed` itself is unchanged (the released
 * view's golden lives in the web module's tests); the unit here pins the LENS truth the runtime routes inherit.
 */
class DashboardServiceServedVersionTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()
    private val name = "finance/dashboards/board"

    private val repository = mockk<DashboardRepository> { every { kind } returns ArtifactKind.DASHBOARD }
    private val service =
        DashboardService(
            repository = repository,
            validator = mockk(),
            visualizations = mockk(),
            sets = mockk(),
            authoring = AuthoringGuard(true),
        )

    private fun version(
        version: Int,
        status: PipelineVersionStatus,
    ): ArtifactVersion<DashboardBody> {
        val record =
            ArtifactRecord(
                id = dashboardId,
                workspaceId = workspaceId,
                name = name,
                displayName = "board",
                description = "",
                currentVersion = if (status == PipelineVersionStatus.RELEASED) version else null,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                createdBy = UUID.randomUUID(),
            )
        val detail =
            ArtifactVersionDetail(
                artifactId = dashboardId,
                version = version,
                status = status,
                bodyHash = "hash-$version",
                createdAt = Instant.EPOCH,
                createdBy = UUID.randomUUID(),
            )
        return ArtifactVersion(
            record,
            detail,
            DashboardBody(displayName = "board", visualizations = emptyList(), layout = co.datapipelines.visualization.DashboardLayout()),
        )
    }

    private fun stubbed(status: PipelineVersionStatus) = version(2, status)

    @Test
    fun `the whole view is served a DRAFT version by number`() {
        every { repository.findVersion(workspaceId, dashboardId, 2) } returns stubbed(PipelineVersionStatus.DRAFT)
        service.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 2)?.detail?.status shouldBe PipelineVersionStatus.DRAFT
    }

    @Test
    fun `the whole view is served a RELEASED version by number`() {
        every { repository.findVersion(workspaceId, dashboardId, 2) } returns stubbed(PipelineVersionStatus.RELEASED)
        service.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 2)?.detail?.status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `a DISCARDED version is served to no one under any lens`() {
        every { repository.findVersion(workspaceId, dashboardId, 2) } returns stubbed(PipelineVersionStatus.DISCARDED)
        service.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 2) shouldBe null
        service.findServedVersion(workspaceId, ReadLens.Only(setOf(name)), dashboardId, 2) shouldBe null
    }

    @Test
    fun `a narrowing lens never learns of a draft - the same null an absent version answers`() {
        every { repository.findVersion(workspaceId, dashboardId, 2) } returns stubbed(PipelineVersionStatus.DRAFT)
        service.findServedVersion(workspaceId, ReadLens.Only(setOf(name)), dashboardId, 2) shouldBe null
    }

    @Test
    fun `a narrowing lens is served the RELEASED version it admits and nothing it does not`() {
        every { repository.findVersion(workspaceId, dashboardId, 2) } returns stubbed(PipelineVersionStatus.RELEASED)
        service.findServedVersion(workspaceId, ReadLens.Only(setOf(name)), dashboardId, 2)?.detail?.version shouldBe 2
        service.findServedVersion(workspaceId, ReadLens.Only(setOf("other/dashboards/one")), dashboardId, 2) shouldBe null
    }

    @Test
    fun `an absent version answers null - the surface's 404 for the version they named`() {
        every { repository.findVersion(workspaceId, dashboardId, 9) } returns null
        service.findServedVersion(workspaceId, ReadLens.Everything, dashboardId, 9) shouldBe null
    }
}
