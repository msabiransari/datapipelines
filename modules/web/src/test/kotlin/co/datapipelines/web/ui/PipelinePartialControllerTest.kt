package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineFolder
import co.datapipelines.pipeline.PipelineFolderLevel
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * [PipelinePartialController] — the two listing presentations, the one-level-per-request rule,
 * and the detail pane.
 *
 * The 067 shape: `prefix` absent is the WRAPPER (search when `q` is non-empty, the tree's root
 * otherwise); `prefix` present — empty string included — is exactly ONE tree level. Since #350
 * that wrapper is the SIDEBAR's (`scope=nav`, stamped with [PipelineBrowseModel.NAV_STAMP_HEADER]);
 * any other scope is the `/pipelines` catalog, always the flat list (the cases at the end). The
 * truthful-total rule (034 E3) and the drafts attribute that feeds the "pending release" badge
 * (versioning §7) survive both presentations; the badge's absence would silently hide agent
 * work, which is why it is asserted rather than assumed.
 */
class PipelinePartialControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val service = co.datapipelines.web.pipelineServiceOver(repository)
    private val controller =
        PipelinePartialController(co.datapipelines.web.pipelineBrowseModelOver(repository, service), co.datapipelines.web.EVERYTHING_LENS)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val model = ExtendedModelMap()
    private val response = MockHttpServletResponse()
    private val pageSize = PipelineBrowseModel.PAGE_SIZE

    /**
     * The route as the SIDEBAR calls it (`scope=nav`) unless [scope] says otherwise — the shape
     * every pre-#350 case here described (no prefix + no q = the tree's root).
     */
    private fun list(
        q: String?,
        prefix: String?,
        offset: Int?,
        scope: String? = PipelineListScope.NAV.wire,
    ): String = controller.list(model, response, q, prefix, offset, scope)

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

    private fun level(
        folders: List<PipelineFolder> = emptyList(),
        pipelines: List<PipelineRecord> = emptyList(),
        total: Int = pipelines.size,
        hasMore: Boolean = false,
    ) = PipelineFolderLevel(folders, foldersTruncated = false, pipelines = pipelines, total = total, hasMore = hasMore)

    @Test
    fun `no prefix and no q - the wrapper renders the tree's ROOT level`() {
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns
            level(folders = listOf(PipelineFolder("nyc", "nyc", 6), PipelineFolder("trade", "trade", 3)), total = 0)

        list(q = null, prefix = null, offset = 0) shouldBe "partials/pipelines"

        model["searching"] shouldBe false
        model["levelId"] shouldBe PipelineBrowseModel.ROOT_LEVEL_ID
        @Suppress("UNCHECKED_CAST")
        (model["folders"] as List<PipelineFolderView>).map { it.path to it.pipelineCount } shouldBe
            listOf("nyc" to 6, "trade" to 3)
    }

    @Test
    fun `077 - the ROOT level carries no leaves, even when the repository still returns one`() {
        // The repository is deliberately told to answer WITH a leaf — a pre-077 flat pipeline,
        // which can still exist because a pipeline name is validated at save only (§14.2 gives
        // it no migration gate). The model must drop it from the ROOT level: §4.1 makes the
        // root a directory of folders, and the fragment has no "leaf at the root" branch left.
        //
        // Asserting through the repository rather than around it is the point: a test that
        // stubbed an empty leaf list would pass with the production filter deleted.
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns
            level(
                folders = listOf(PipelineFolder("nyc", "nyc", 6)),
                pipelines = listOf(record("legacy_flat")),
                total = 1,
                hasMore = true,
            )

        list(q = null, prefix = "", offset = 0) shouldBe "partials/pipeline-tree-level"

        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<PipelineRecord>).shouldBeEmpty()
        // total and hasMore travel with the leaves, so the level and its pager cannot disagree.
        model["total"] shouldBe 0
        model["hasMore"] shouldBe false
        @Suppress("UNCHECKED_CAST")
        (model["folders"] as List<PipelineFolderView>).map { it.path } shouldBe listOf("nyc")
    }

    @Test
    fun `077 - a NESTED level still carries its leaves`() {
        // The other half: dropping leaves at the ROOT must not drop them anywhere else.
        every { repository.listFolder(workspaceId, "nyc/mobility", 0, pageSize) } returns
            level(pipelines = listOf(record("nyc/mobility/revenue_by_borough")), total = 1)
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = null, prefix = "nyc/mobility", offset = 0)

        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<PipelineRecord>).map { it.name } shouldBe listOf("nyc/mobility/revenue_by_borough")
        model["total"] shouldBe 1
    }

    @Test
    fun `a prefix renders exactly ONE level - the level view, not the wrapper`() {
        every { repository.listFolder(workspaceId, "nyc/mobility", 0, pageSize) } returns
            level(pipelines = listOf(record("nyc/mobility/revenue_by_borough")))
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = null, prefix = "nyc/mobility", offset = 0) shouldBe "partials/pipeline-tree-level"

        model["prefix"] shouldBe "nyc/mobility"
        // Its own id, derived once, so the folder's placeholder and this fragment agree.
        model["levelId"] shouldBe PipelineBrowseModel.levelId("nyc/mobility")
        verify(exactly = 1) { repository.listFolder(workspaceId, "nyc/mobility", 0, pageSize) }
    }

    @Test
    fun `an EMPTY prefix is present, and means the root level - not an absent prefix`() {
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns level()

        list(q = null, prefix = "", offset = 0) shouldBe "partials/pipeline-tree-level"

        model["prefix"] shouldBe ""
        model["levelId"] shouldBe PipelineBrowseModel.ROOT_LEVEL_ID
    }

    @Test
    fun `q is ignored while prefix is present - browse and search are different presentations`() {
        every { repository.listFolder(workspaceId, "nyc", 0, pageSize) } returns level()

        list(q = "revenue", prefix = "nyc", offset = 0)

        verify(exactly = 0) { repository.findAll(workspaceId) }
    }

    @Test
    fun `a prefix that is not a legal pipeline name renders an EMPTY level and never queries`() {
        // Not a 400: a level that cannot exist is an ordinary empty level, the same answer the
        // templates browser gives. What it must NOT be is an arbitrary-length LIKE pattern.
        list(q = null, prefix = "nyc/../etc", offset = 0) shouldBe "partials/pipeline-tree-level"

        model["prefix"] shouldBe "nyc/../etc"
        (model["folders"] as List<*>).size shouldBe 0
        (model["pipelines"] as List<*>).size shouldBe 0
        model["total"] shouldBe 0
        verify(exactly = 0) { repository.listFolder(any(), any(), any(), any()) }
    }

    @Test
    fun `a search filters in memory across name, display name and description`() {
        every { repository.findAll(workspaceId) } returns
            listOf(
                record("nyc/mobility/revenue_monthly", "Monthly Revenue"),
                record("nyc/churn", "Churn Model", description = "revenue impact cohorts"),
                record("etl_nightly", "Nightly ETL"),
            )
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = "revenue", prefix = null, offset = 0)

        model["searching"] shouldBe true
        (model["pipelines"] as List<*>).size shouldBe 2
        model["total"] shouldBe 2
        model["hasMore"] shouldBe false
    }

    @Test
    fun `search paging drops and takes over the filtered list`() {
        every { repository.findAll(workspaceId) } returns List(30) { record("nyc/p$it") }
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = "nyc", prefix = null, offset = 25)

        (model["pipelines"] as List<*>).size shouldBe 5
        model["hasMore"] shouldBe false
    }

    @Test
    fun `the drafts map for the level's ids feeds the pending-release badge`() {
        // A NESTED level: since 077 the root has no leaves, so the badge query it feeds is a
        // nested-level question (§4.1).
        val ids = List(3) { UUID.randomUUID() }
        every { repository.listFolder(workspaceId, "nyc/mobility", 0, pageSize) } returns
            level(pipelines = ids.map { record("nyc/mobility/p", id = it) }, total = 3)
        every { repository.findDrafts(workspaceId, ids) } returns emptyMap()

        list(q = null, prefix = "nyc/mobility", offset = 0)

        verify { repository.findDrafts(workspaceId, ids) }
        model["drafts"] shouldBe emptyMap<Any, Any>()
        model["q"] shouldBe ""
    }

    @Test
    fun `a negative offset is clamped to zero`() {
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns level()

        list(q = null, prefix = null, offset = -5)

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
    // #350 — the two instances: the sidebar (`scope=nav`, stamped) and the /pipelines catalog
    // ---------------------------------------------------------------------------------------

    @Test
    fun `#350 - the CATALOG with no q is the flat list of every pipeline, under its own root - never the tree`() {
        every { repository.findAll(workspaceId, null, pageSize + 1, 0) } returns
            listOf(record("nyc/mobility/a"), record("trade/b"))
        every { repository.countAll(workspaceId) } returns 2
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = null, prefix = null, offset = 0, scope = null) shouldBe "partials/pipelines"

        model["searching"] shouldBe true
        model["scope"] shouldBe PipelineListScope.CATALOG.wire
        model["rootId"] shouldBe PipelineBrowseModel.CATALOG_ROOT_ID
        (model["pipelines"] as List<*>).size shouldBe 2
        model["total"] shouldBe 2
        // The catalog is not the sidebar: no tree level was asked for and no stamp travels.
        verify(exactly = 0) { repository.listFolder(any(), any(), any(), any()) }
        response.getHeader(PipelineBrowseModel.NAV_STAMP_HEADER) shouldBe null
    }

    @Test
    fun `#350 - an unknown scope is the catalog, not an error and not the sidebar`() {
        every { repository.findAll(workspaceId, null, pageSize + 1, 0) } returns emptyList()
        every { repository.countAll(workspaceId) } returns 0
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = null, prefix = null, offset = 0, scope = "<script>") shouldBe "partials/pipelines"

        model["scope"] shouldBe PipelineListScope.CATALOG.wire
        response.getHeader(PipelineBrowseModel.NAV_STAMP_HEADER) shouldBe null
    }

    @Test
    fun `#350 - the CATALOG search names its own root and scope, so its pager and clear stay on the page`() {
        every { repository.findAll(workspaceId) } returns listOf(record("nyc/mobility/revenue_monthly"))
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        list(q = "revenue", prefix = null, offset = 0, scope = "page")

        model["rootId"] shouldBe PipelineBrowseModel.CATALOG_ROOT_ID
        model["scope"] shouldBe "page"
        model["q"] shouldBe "revenue"
    }

    @Test
    fun `#350 - every SIDEBAR response is stamped with the workspace and the lens it was rendered under`() {
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns level()
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()
        list(q = null, prefix = null, offset = 0)
        response.getHeader(PipelineBrowseModel.NAV_STAMP_HEADER) shouldBe "acme|all"

        // A folder level is the sidebar's whatever the scope parameter says.
        val second = MockHttpServletResponse()
        every { repository.listFolder(workspaceId, "nyc", 0, pageSize) } returns level()
        controller.list(model, second, q = null, prefix = "nyc", offset = 0, scope = null)
        second.getHeader(PipelineBrowseModel.NAV_STAMP_HEADER) shouldBe "acme|all"

        // The sidebar's search too: its results land in the same tree.
        val third = MockHttpServletResponse()
        every { repository.findAll(workspaceId) } returns emptyList()
        controller.list(model, third, q = "x", prefix = null, offset = 0, scope = "nav")
        third.getHeader(PipelineBrowseModel.NAV_STAMP_HEADER) shouldBe "acme|all"
        model["rootId"] shouldBe PipelineBrowseModel.ROOT_LEVEL_ID
    }

    @Test
    fun `#350 - a LENSED caller's sidebar is stamped lens and carries only the admitted rows`() {
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

        lensed.list(model, response, q = null, prefix = "nyc/mobility", offset = 0, scope = "nav")

        response.getHeader(PipelineBrowseModel.NAV_STAMP_HEADER) shouldBe "acme|lens"
        // The rows are the lens's answer — the hidden ones are absent from the model, not
        // rendered and left to CSS.
        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<PipelineRecord>).map { it.name } shouldBe listOf("nyc/mobility/admitted")
        model["total"] shouldBe 1
    }
}
