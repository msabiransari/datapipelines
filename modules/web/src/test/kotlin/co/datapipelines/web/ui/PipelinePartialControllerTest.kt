package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * [PipelinePartialController] — the two listing paths and the truthful-total rule (034 E3),
 * not the template (ListPartialsRenderTest renders the fragment). Unfiltered pages ask the
 * repository for size+1 and countAll for the total; a `q` search filters in memory over the
 * FULL list — which is only correct because the filter must match columns the repository
 * query does not know (description). The drafts attribute feeds the "pending release" badge
 * (versioning §7) — its absence would silently hide agent work.
 */
class PipelinePartialControllerTest {
    private val pipelines = mockk<PipelineRepository>()

    // 056 made the controller a thin caller of PipelineService; the mock repository still drives
    // it, wrapped in the real service the way every other post-056 web test does (TestSupport).
    private val controller = PipelinePartialController(co.datapipelines.web.pipelineServiceOver(pipelines))

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val model = ExtendedModelMap()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    init {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    setOf(Scope.AUTHOR),
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    private fun record(
        name: String,
        displayName: String = name,
        description: String = "",
    ) = PipelineRecord(
        id = UUID.randomUUID(),
        name = name,
        displayName = displayName,
        description = description,
        ownerId = userId,
        currentVersion = 1,
        isDeleted = false,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `unfiltered - fetches size plus one, total from countAll, hasMore from the overflow`() {
        every { pipelines.findAll(workspaceId, null, any(), any()) } returns List(26) { record("p$it") }
        every { pipelines.countAll(workspaceId) } returns 100
        every { pipelines.findDrafts(workspaceId, any()) } returns emptyMap()

        controller.list(model, q = null, offset = 0)

        verify { pipelines.findAll(workspaceId, null, 26, 0) }
        (model["pipelines"] as List<*>).size shouldBe 25
        model["total"] shouldBe 100
        model["hasMore"] shouldBe true
    }

    @Test
    fun `unfiltered - a negative offset is clamped to zero`() {
        every { pipelines.findAll(workspaceId, null, any(), any()) } returns emptyList()
        every { pipelines.countAll(workspaceId) } returns 0
        every { pipelines.findDrafts(workspaceId, any()) } returns emptyMap()

        controller.list(model, q = null, offset = -5)

        verify { pipelines.findAll(workspaceId, null, 26, 0) }
        model["offset"] shouldBe 0
    }

    @Test
    fun `a search filters in memory across name, display name and description`() {
        every { pipelines.findAll(workspaceId) } returns
            listOf(
                record("revenue_monthly", "Monthly Revenue"),
                record("churn", "Churn Model", description = "revenue impact cohorts"),
                record("etl_nightly", "Nightly ETL"),
            )
        every { pipelines.findDrafts(workspaceId, any()) } returns emptyMap()

        controller.list(model, q = "revenue", offset = 0)

        val shown = model["pipelines"] as List<*>
        shown.size shouldBe 2
        model["total"] shouldBe 2
        model["hasMore"] shouldBe false
    }

    @Test
    fun `a blank search is not a search`() {
        every { pipelines.findAll(workspaceId, null, any(), any()) } returns emptyList()
        every { pipelines.countAll(workspaceId) } returns 0
        every { pipelines.findDrafts(workspaceId, any()) } returns emptyMap()

        controller.list(model, q = "   ", offset = 0)

        verify(exactly = 0) { pipelines.findAll(workspaceId) }
    }

    @Test
    fun `search paging drops and takes over the filtered list`() {
        every { pipelines.findAll(workspaceId) } returns List(30) { record("p$it") }
        every { pipelines.findDrafts(workspaceId, any()) } returns emptyMap()

        controller.list(model, q = "p", offset = 25)

        (model["pipelines"] as List<*>).size shouldBe 5
        model["hasMore"] shouldBe false
    }

    @Test
    fun `the drafts map for the page's ids feeds the pending-release badge`() {
        val pageIds = List(3) { UUID.randomUUID() }
        every { pipelines.findAll(workspaceId, null, any(), any()) } returns
            pageIds.map { record("p").let { r -> r.copy(id = it) } }
        every { pipelines.countAll(workspaceId) } returns 3
        every { pipelines.findDrafts(workspaceId, pageIds) } returns emptyMap()

        controller.list(model, q = null, offset = 0)

        verify { pipelines.findDrafts(workspaceId, pageIds) }
        model["drafts"] shouldBe emptyMap<Any, Any>()
        model["q"] shouldBe ""
    }

    @Test
    fun `the partial view name is returned`() {
        every { pipelines.findAll(workspaceId, null, any(), any()) } returns emptyList()
        every { pipelines.countAll(workspaceId) } returns 0
        every { pipelines.findDrafts(workspaceId, any()) } returns emptyMap()

        controller.list(model, q = null, offset = 0) shouldBe "partials/pipelines"
    }
}
