package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
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
 * otherwise); `prefix` present — empty string included — is exactly ONE tree level. The
 * truthful-total rule (034 E3) and the drafts attribute that feeds the "pending release" badge
 * (versioning §7) survive both presentations; the badge's absence would silently hide agent
 * work, which is why it is asserted rather than assumed.
 */
class PipelinePartialControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val service = co.datapipelines.web.pipelineServiceOver(repository)
    private val controller = PipelinePartialController(co.datapipelines.web.pipelineBrowseModelOver(repository, service))

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val model = ExtendedModelMap()
    private val pageSize = PipelineBrowseModel.PAGE_SIZE

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

        controller.list(model, q = null, prefix = null, offset = 0) shouldBe "partials/pipelines"

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

        controller.list(model, q = null, prefix = "", offset = 0) shouldBe "partials/pipeline-tree-level"

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

        controller.list(model, q = null, prefix = "nyc/mobility", offset = 0)

        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<PipelineRecord>).map { it.name } shouldBe listOf("nyc/mobility/revenue_by_borough")
        model["total"] shouldBe 1
    }

    @Test
    fun `a prefix renders exactly ONE level - the level view, not the wrapper`() {
        every { repository.listFolder(workspaceId, "nyc/mobility", 0, pageSize) } returns
            level(pipelines = listOf(record("nyc/mobility/revenue_by_borough")))
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        controller.list(model, q = null, prefix = "nyc/mobility", offset = 0) shouldBe "partials/pipeline-tree-level"

        model["prefix"] shouldBe "nyc/mobility"
        // Its own id, derived once, so the folder's placeholder and this fragment agree.
        model["levelId"] shouldBe PipelineBrowseModel.levelId("nyc/mobility")
        verify(exactly = 1) { repository.listFolder(workspaceId, "nyc/mobility", 0, pageSize) }
    }

    @Test
    fun `an EMPTY prefix is present, and means the root level - not an absent prefix`() {
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns level()

        controller.list(model, q = null, prefix = "", offset = 0) shouldBe "partials/pipeline-tree-level"

        model["prefix"] shouldBe ""
        model["levelId"] shouldBe PipelineBrowseModel.ROOT_LEVEL_ID
    }

    @Test
    fun `q is ignored while prefix is present - browse and search are different presentations`() {
        every { repository.listFolder(workspaceId, "nyc", 0, pageSize) } returns level()

        controller.list(model, q = "revenue", prefix = "nyc", offset = 0)

        verify(exactly = 0) { repository.findAll(workspaceId) }
    }

    @Test
    fun `a prefix that is not a legal pipeline name renders an EMPTY level and never queries`() {
        // Not a 400: a level that cannot exist is an ordinary empty level, the same answer the
        // templates browser gives. What it must NOT be is an arbitrary-length LIKE pattern.
        controller.list(model, q = null, prefix = "nyc/../etc", offset = 0) shouldBe "partials/pipeline-tree-level"

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

        controller.list(model, q = "revenue", prefix = null, offset = 0)

        model["searching"] shouldBe true
        (model["pipelines"] as List<*>).size shouldBe 2
        model["total"] shouldBe 2
        model["hasMore"] shouldBe false
    }

    @Test
    fun `search paging drops and takes over the filtered list`() {
        every { repository.findAll(workspaceId) } returns List(30) { record("nyc/p$it") }
        every { repository.findDrafts(workspaceId, any()) } returns emptyMap()

        controller.list(model, q = "nyc", prefix = null, offset = 25)

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

        controller.list(model, q = null, prefix = "nyc/mobility", offset = 0)

        verify { repository.findDrafts(workspaceId, ids) }
        model["drafts"] shouldBe emptyMap<Any, Any>()
        model["q"] shouldBe ""
    }

    @Test
    fun `a negative offset is clamped to zero`() {
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns level()

        controller.list(model, q = null, prefix = null, offset = -5)

        model["offset"] shouldBe 0
    }

    @Test
    fun `106 - the detail fill supplies all three regions in ONE call`() {
        val id = UUID.randomUUID()
        val body =
            """
            {"schema_version":1,"name":"nyc/mobility/revenue_by_borough","display_name":"Revenue","description":"d",
             "settings":{"tempdb":{"engine":"H2"}},
             "parameters":{"start_date":{"type":"DATE","required":false,"default":"2024-01-01"}},
             "nodes":[{"id":"a","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":1},"output":{"target":"caller"}}]}
            """.trimIndent()
        every { repository.findById(workspaceId, id) } returns record("nyc/mobility/revenue_by_borough", id = id)
        every { repository.findDraftDetail(workspaceId, id) } returns null
        every { repository.findCurrentVersionDetail(workspaceId, id) } returns
            co.datapipelines.pipeline.PipelineVersionDetail(id, 1, PipelineVersionStatus.RELEASED, "h", Instant.EPOCH, userId)
        every { repository.findVersionBody(workspaceId, id, 1) } returns body
        every { repository.listVersions(workspaceId, id) } returns
            listOf(PipelineVersionRecord(id, 1, PipelineVersionStatus.RELEASED, "h", Instant.EPOCH, userId))
        every { repository.findLiveParentsPinningVersion(workspaceId, "nyc/mobility/revenue_by_borough", 1) } returns emptyList()

        val executions = mockk<co.datapipelines.executor.ExecutionRepository>()
        every { executions.findAll(workspaceId, id, null, null, null, 1, 0) } returns emptyList()
        val endpoints = mockk<co.datapipelines.application.endpoints.PublishedEndpointRepository>()
        every { endpoints.findByPipeline(id) } returns emptyList()
        val runStats = mockk<PipelineRunStats>()
        every { runStats.runsByVersion(id) } returns mapOf(1 to 7)
        every { runStats.totalRuns(id) } returns 7
        // The REGISTRY decides what is a datasource; `pg` resolves, so it is rendered with its
        // dialect. A name it cannot resolve is left out rather than linked to nothing.
        val registry =
            co.datapipelines.pipeline.DatasourceRegistry { name ->
                if (name == "pg") co.datapipelines.pipeline.DatasourceFacts(co.datapipelines.typesystem.Dialect.POSTGRES) else null
            }
        val detailController =
            PipelinePartialController(
                co.datapipelines.web.pipelineBrowseModelOver(
                    repository,
                    service,
                    executions = executions,
                    endpoints = endpoints,
                    datasources = registry,
                    runStats = runStats,
                ),
            )

        detailController.detail(model, id) shouldBe "partials/pipeline-detail"

        assertDetailRegions()
    }

    /** The three regions, read off the model the one fill left behind. */
    private fun assertDetailRegions() {
        // ---- header
        (model["pipeline"] as PipelineRecord).name shouldBe "nyc/mobility/revenue_by_borough"
        model["folderPath"] shouldBe "nyc/mobility/"
        model["leafName"] shouldBe "revenue_by_borough"
        // No draft: nothing to release, and Delete is refused because a release exists.
        model["releasableVersion"] shouldBe null
        model["canDelete"] shouldBe false
        model["canDiscardCurrent"] shouldBe true

        // ---- reading column
        model["workingVersion"] shouldBe 1
        model["draftVersion"] shouldBe null
        model["nodeCount"] shouldBe 1
        model["stagingEngine"] shouldBe co.datapipelines.pipeline.StagingEngine.H2
        (model["parameters"] as Map<*, *>).keys shouldBe setOf("start_date")
        (model["datasourceRows"] as List<*>) shouldBe
            listOf(DatasourceRowView("pg", co.datapipelines.typesystem.Dialect.POSTGRES))
        (model["templatePins"] as List<*>) shouldBe listOf(TemplatePinView("test/t.sql", 1))
        model["lastRun"] shouldBe null

        // ---- acting column
        val versions = model["versions"] as List<*>
        versions.size shouldBe 1
        val row = versions.single() as VersionRowView
        row.usageLabel shouldBe "7 runs"
        row.isCurrent shouldBe true
        row.canDiscard shouldBe true
        row.canRelease shouldBe false
        model["runCount"] shouldBe 7
        model["usageCount"] shouldBe 0
    }

    @Test
    fun `106 - a runs fragment over a NON-admin principal asks only for that user's runs`() {
        // The execution-history screen's rule, restated at the second surface over the same
        // rows: the principal in this spec holds AUTHOR, so `findByUser` is the read, and a
        // strict mock means calling `findAll` instead would fail rather than pass quietly.
        val id = UUID.randomUUID()
        val executions = mockk<co.datapipelines.executor.ExecutionRepository>()
        every { executions.findByUser(workspaceId, userId, id, null, null, null, 20, 0) } returns emptyList()
        val runsController =
            PipelinePartialController(co.datapipelines.web.pipelineBrowseModelOver(repository, service, executions = executions))

        runsController.runs(model, id) shouldBe "partials/pipeline-runs"

        verify(exactly = 1) { executions.findByUser(workspaceId, userId, id, null, null, null, 20, 0) }
    }

    @Test
    fun `an id that no longer names a live pipeline renders the quiet not-found pane`() {
        val id = UUID.randomUUID()
        every { repository.findById(workspaceId, id) } returns null

        controller.detail(model, id) shouldBe "partials/pipeline-detail"

        model["pipeline"] shouldBe null
        verify(exactly = 0) { repository.listVersions(any(), any()) }
    }
}
