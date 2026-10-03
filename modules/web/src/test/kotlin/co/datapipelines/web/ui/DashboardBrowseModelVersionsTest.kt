package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * #422 — the dashboards Versions tab's rows carry the keys page's time shape: a relative age for the
 * cell and the absolute UTC stamp for its `title`, both computed in the fill against ITS `now`
 * (never the template's, never the JVM zone's), so a render test can pin "3 days ago" without a clock.
 */
class DashboardBrowseModelVersionsTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()
    private val dashboards = mockk<DashboardService>()
    private val model =
        DashboardBrowseModel(dashboards, mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

    private val created = Instant.parse("2026-10-01T09:00:00Z")

    private fun detail(
        version: Int,
        status: PipelineVersionStatus,
        releasedAt: Instant? = null,
    ) = ArtifactVersionDetail(dashboardId, version, status, "hash-$version", created, UUID.randomUUID(), releasedAt = releasedAt)

    @Test
    fun `a row carries the relative age and the absolute UTC stamp for created and released`() {
        every { dashboards.listVersions(workspaceId, any(), dashboardId) } returns
            listOf(
                detail(2, PipelineVersionStatus.DRAFT),
                detail(1, PipelineVersionStatus.RELEASED, releasedAt = created.plusSeconds(3_600)),
            )
        val ui = ExtendedModelMap()

        model.fillVersions(ui, workspaceId, LensedView.EVERYTHING, dashboardId, 1, now = created.plusSeconds(3 * 86_400 + 7_200))

        @Suppress("UNCHECKED_CAST")
        val (draft, released) = ui["versions"] as List<DashboardBrowseModel.VersionDetailView>
        draft.createdAgo shouldBe "3 days ago"
        draft.createdAbsolute shouldBe "2026-10-01 09:00 UTC"
        draft.releasedAgo shouldBe null
        draft.releasedAbsolute shouldBe null
        released.releasedAgo shouldBe "3 days ago"
        released.releasedAbsolute shouldBe "2026-10-01 10:00 UTC"
        released.isServed shouldBe true
    }

    @Test
    fun `an instant at or after now reads just now rather than a negative age`() {
        every { dashboards.listVersions(workspaceId, any(), dashboardId) } returns listOf(detail(1, PipelineVersionStatus.DRAFT))
        val ui = ExtendedModelMap()

        model.fillVersions(ui, workspaceId, LensedView.EVERYTHING, dashboardId, null, now = created.minusSeconds(30))

        @Suppress("UNCHECKED_CAST")
        (ui["versions"] as List<DashboardBrowseModel.VersionDetailView>).single().createdAgo shouldBe "just now"
    }
}
