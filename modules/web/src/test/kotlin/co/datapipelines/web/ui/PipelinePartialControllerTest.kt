package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionRecord
import co.datapipelines.pipeline.PipelineVersionStatus
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
 * [PipelinePartialController] — the `/pipelines` catalog's list and the Runs fragment.
 *
 * Since #466 the list route has ONE presentation, the catalog's flat list (#350): the sidebar's
 * server-rendered tree levels and nav search are gone (the sidebar reads the REST tree), so a
 * stale `scope` or `prefix` is an unbound parameter — pinned over real HTTP in
 * `PromoterLensSweepTest`, where the request reaches the dispatcher. The truthful-total rule
 * (034 E3) and the drafts attribute that feeds the "pending release" badge (versioning §7) are
 * asserted rather than assumed: the badge's absence would silently hide agent work.
 */
class PipelinePartialControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val service = co.datapipelines.web.pipelineServiceOver(repository)
    private val controller =
        PipelinePartialController(co.datapipelines.web.pipelineBrowseModelOver(repository, service), co.datapipelines.web.EVERYTHING_LENS)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val model = ExtendedModelMap()
    private val pageSize = PipelineBrowseModel.PAGE_SIZE

    private fun list(
        q: String?,
        offset: Int?,
    ): String = controller.list(model, q, offset, null)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    init {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
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
        id: UUID = UUID.randomUUID(),
    ) = PipelineRecord(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        ownerId = userId,
        currentVersion = 1,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `a search filters in memory across name, display name and description`() {
        every { repository.findAll(workspaceId) } returns
            listOf(
                record("nyc/mobility/revenue_monthly", "Monthly Revenue"),
                record("nyc/churn", "Churn Model", description = "revenue impact cohorts"),
                record("etl_nightly", "Nightly ETL"),
            )
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = "revenue", offset = 0)

        model["searching"] shouldBe true
        (model["pipelines"] as List<*>).size shouldBe 2
        model["total"] shouldBe 2
        model["hasMore"] shouldBe false
    }

    @Test
    fun `search paging drops and takes over the filtered list`() {
        every { repository.findAll(workspaceId) } returns List(30) { record("nyc/p$it") }
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = "nyc", offset = 25)

        (model["pipelines"] as List<*>).size shouldBe 5
        model["hasMore"] shouldBe false
    }

    @Test
    fun `the drafts map for the catalog page's ids feeds the pending-release badge`() {
        val ids = List(3) { UUID.randomUUID() }
        every { repository.findAll(workspaceId, null, pageSize + 1, 0) } returns ids.map { record("nyc/mobility/p", id = it) }
        every { repository.countAll(workspaceId) } returns 3
        every { repository.findDrafts(workspaceId, ids) } returns emptyMap()

        list(q = null, offset = 0)

        verify { repository.findDrafts(workspaceId, ids) }
        model["drafts"] shouldBe emptyMap<Any, Any>()
        model["q"] shouldBe ""
    }

    @Test
    fun `a negative offset is clamped to zero`() {
        every { repository.findAll(workspaceId, null, pageSize + 1, 0) } returns emptyList()
        every { repository.countAll(workspaceId) } returns 0
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = null, offset = -5)

        model["offset"] shouldBe 0
    }

    @Test
    fun `106 + #275 - a runs fragment over a NON-admin member reads her own runs plus the workspace's scheduled runs`() {
        // The execution-history screen's rule, restated at the second surface over the same
        // rows: the principal in this spec holds AUTHOR, so `findVisible` (own runs OR
        // `triggered_via = SCHEDULE`, R3) is the read, and a strict mock means calling `findAll`
        // — or the pre-#275 own-only `findByUser` — would fail rather than pass quietly.
        val id = UUID.randomUUID()
        val executions = mockk<co.datapipelines.executor.ExecutionRepository>()
        // 178: the runs pane resolves the pipeline through the caller's view before reading runs.
        every { repository.findById(workspaceId, id) } returns record("acme/runs", id = id)
        every { executions.findVisible(workspaceId, userId, id, null, null, null, 20, 0) } returns emptyList()

        runsControllerOver(executions).runs(model, id) shouldBe "partials/pipeline-runs"

        verify(exactly = 1) { executions.findVisible(workspaceId, userId, id, null, null, null, 20, 0) }
    }

    @Test
    fun `#275 - a runs fragment over a promoter reads her own runs only - R3's scheduled arm is execution_read's`() {
        // The pane is a `pipeline.read` route, so the promoter reaches it; the promoter holds no
        // `execution.read`, so the scheduled runs R3 lifts are not hers — as in `visibleTo`.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme", co.datapipelines.auth.WorkspaceRole.PROMOTER),
                ),
                null,
                emptyList(),
            )
        val id = UUID.randomUUID()
        val executions = mockk<co.datapipelines.executor.ExecutionRepository>()
        every { repository.findById(workspaceId, id) } returns record("acme/runs", id = id)
        every { executions.findByUser(workspaceId, userId, id, null, null, null, 20, 0) } returns emptyList()

        runsControllerOver(executions).runs(model, id) shouldBe "partials/pipeline-runs"

        verify(exactly = 1) { executions.findByUser(workspaceId, userId, id, null, null, null, 20, 0) }
    }

    private fun runsControllerOver(executions: co.datapipelines.executor.ExecutionRepository) =
        PipelinePartialController(
            co.datapipelines.web.pipelineBrowseModelOver(repository, service, executions = executions),
            co.datapipelines.web.EVERYTHING_LENS,
        )

    // ---------------------------------------------------------------------------------------
    // #350 — the /pipelines catalog, the route's one presentation since #466
    // ---------------------------------------------------------------------------------------

    @Test
    fun `#350 - the CATALOG with no q is the flat list of every pipeline, under its own root - never the tree`() {
        every { repository.findAll(workspaceId, null, pageSize + 1, 0) } returns
            listOf(record("nyc/mobility/a"), record("trade/b"))
        every { repository.countAll(workspaceId) } returns 2
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = null, offset = 0) shouldBe "partials/pipelines"

        model["searching"] shouldBe true
        model["rootId"] shouldBe PipelineBrowseModel.CATALOG_ROOT_ID
        (model["pipelines"] as List<*>).size shouldBe 2
        model["total"] shouldBe 2
        // No tree level is asked for: the folder read is the REST tree's alone (#466).
        verify(exactly = 0) { repository.listFolder(any(), any(), any(), any()) }
    }

    @Test
    fun `#350 - the CATALOG search names its own root, so its pager and clear stay on the page`() {
        every { repository.findAll(workspaceId) } returns listOf(record("nyc/mobility/revenue_monthly"))
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = "revenue", offset = 0)

        model["rootId"] shouldBe PipelineBrowseModel.CATALOG_ROOT_ID
        model["q"] shouldBe "revenue"
    }

    @Test
    fun `#350 - a LENSED caller's catalog search carries only the admitted rows`() {
        val lensed =
            PipelinePartialController(
                co.datapipelines.web.pipelineBrowseModelOver(repository, service),
                co.datapipelines.application.lens.PromoterLens {
                    co.datapipelines.application.lens.LensedView(
                        co.datapipelines.pipeline.ReadLens
                            .Only(setOf("nyc/mobility/admitted")),
                        co.datapipelines.pipeline.ReadLens.Everything,
                    )
                },
            )
        every { repository.findAll(workspaceId) } returns
            listOf(record("nyc/mobility/admitted"), record("nyc/mobility/hidden"), record("trade/hidden"))
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        lensed.list(model, q = "nyc/mobility", offset = 0, lifecycle = null)

        // The rows are the lens's answer — the hidden ones are absent from the model, not
        // rendered and left to CSS.
        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<PipelineRecord>).map { it.name } shouldBe listOf("nyc/mobility/admitted")
        model["total"] shouldBe 1
    }
}
