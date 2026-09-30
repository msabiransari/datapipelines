package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.DashboardPin
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionConsumers
import co.datapipelines.pipeline.PipelineVersionRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.web.pipelineBrowseModelOver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * #320 — the pipeline Usage tab lists the DASHBOARDS whose sources pin a version, from the SAME port
 * `PipelineService.refuseIfPinned` asks and once per version (as the parent half is), through the caller's dashboard
 * lens: a hidden dashboard must not leak through the reverse arrow. A dashboard is refusal evidence, so it counts in
 * [UsageView.total] — the badge is the number of things a discard would be refused over.
 */
class PipelineBrowseModelUsageTest {
    private val repository = mockk<PipelineRepository>()
    private val service = mockk<PipelineService>()
    private val asked = mutableListOf<String>()

    private val dashboards =
        object : PipelineVersionConsumers {
            override fun liveVersionPins(
                workspaceId: UUID,
                pipelineName: String,
                version: Int,
            ): List<DashboardPin> {
                asked += "$pipelineName@$version"
                return when (version) {
                    1 -> listOf(DashboardPin("acme/boards/shown", 3, PipelineVersionStatus.RELEASED))
                    2 -> listOf(DashboardPin("acme/boards/secret", 1, PipelineVersionStatus.DRAFT))
                    else -> emptyList()
                }
            }

            override fun anyVersionPins(
                workspaceId: UUID,
                pipelineName: String,
            ): List<DashboardPin> = error("the Usage tab asks the live exact-pin question per version, never the entity one")
        }

    private val model =
        pipelineBrowseModelOver(
            repository,
            service = service,
            endpoints = mockk { every { findByPipeline(PIPELINE) } returns emptyList() },
            schedules = mockk { every { listByTarget(any(), any(), any()) } returns emptyList() },
            dashboards = dashboards,
        )

    private val record = PipelineRecord(PIPELINE, "acme/p", "P", "", OWNER, 2, T0, T0)

    init {
        every { service.findRecord(WS, any(), PIPELINE) } returns record
        every { repository.listVersions(WS, PIPELINE) } returns
            listOf(
                PipelineVersionRecord(PIPELINE, 1, PipelineVersionStatus.RELEASED, "h1", T0, OWNER),
                PipelineVersionRecord(PIPELINE, 2, PipelineVersionStatus.RELEASED, "h2", T0, OWNER),
            )
        every { repository.findLiveParentsPinningVersion(any(), any(), any()) } returns emptyList()
    }

    private fun usage(view: LensedView): UsageView {
        val page = ExtendedModelMap()
        model.fillUsage(page, WS, view, PIPELINE, mockk<AuthenticatedPrincipal>(relaxed = true))
        return page["usage"] as UsageView
    }

    @Test
    fun `the tab lists every dashboard version pinning a version of the pipeline, asked once per version`() {
        val usage = usage(LensedView.EVERYTHING)

        usage.dashboards shouldBe
            listOf(
                UsageView.DashboardUse("acme/boards/shown", 3, "RELEASED", pinnedVersion = 1),
                UsageView.DashboardUse("acme/boards/secret", 1, "DRAFT", pinnedVersion = 2),
            )
        usage.total shouldBe 2
        asked shouldBe listOf("acme/p@1", "acme/p@2")
    }

    @Test
    fun `a dashboard the caller's lens hides is never named on the tab - and does not count`() {
        val narrowed = LensedView(ReadLens.Everything, ReadLens.Everything, dashboards = ReadLens.Only(setOf("acme/boards/shown")))

        val usage = usage(narrowed)

        usage.dashboards.map { it.name } shouldBe listOf("acme/boards/shown")
        usage.total shouldBe 1
    }

    private companion object {
        val WS: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val PIPELINE: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val T0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    }
}
