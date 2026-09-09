package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineFolder
import co.datapipelines.pipeline.PipelineFolderLevel
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.pipelineServiceOver
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * [PipelineUiController] — the page's first render, which since 067 goes through the same
 * [PipelineBrowseModel] the htmx partial does, so the screen and the fragment that replaces
 * its list cannot disagree about which presentation is showing.
 *
 * Browsing (no `q`) fills the tree's ROOT level; a non-empty `q` fills the flat search list
 * and its truthful total. The dispatcher view is the same either way, which is what makes
 * "clear the search box and you are back in the tree" true by construction.
 */
class PipelineUiControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val browse = co.datapipelines.web.pipelineBrowseModelOver(repository)
    private val controller = PipelineUiController(browse, themeResolver)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val pageSize = PipelineBrowseModel.PAGE_SIZE

    private fun pipeline(name: String = "my_pipeline") =
        PipelineRecord(
            id = UUID.randomUUID(),
            name = name,
            displayName = "My Pipeline",
            description = "A test pipeline",
            ownerId = userId,
            currentVersion = 1,
            createdAt = java.time.Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-08-10T00:00:00Z"),
        )

    private fun level(
        folders: List<PipelineFolder> = emptyList(),
        pipelines: List<PipelineRecord> = emptyList(),
        total: Int = pipelines.size,
        hasMore: Boolean = false,
    ) = PipelineFolderLevel(folders, foldersTruncated = false, pipelines = pipelines, total = total, hasMore = hasMore)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                setOf(Scope.AUTHOR),
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `the page renders the tree's ROOT level, and since 077 that is folders only`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        // The repository is told to answer WITH a leaf — a pre-077 flat pipeline, which can
        // still exist because §14.2 gives pipelines no migration gate. The ROOT level must
        // drop it: §4.1 makes the root a directory of folders.
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns
            level(folders = listOf(PipelineFolder("nyc", "nyc", 6)), pipelines = listOf(pipeline("legacy_flat")))
        every { repository.findDrafts(any(), any()) } returns emptyMap()

        val model = ExtendedModelMap()
        val viewName = controller.list(model, mockk(), null, null)

        viewName shouldBe "pipelines/list"
        model["activeTheme"] shouldBe "saas"
        model["searching"] shouldBe false
        model["levelId"] shouldBe PipelineBrowseModel.ROOT_LEVEL_ID
        @Suppress("UNCHECKED_CAST")
        (model["folders"] as List<PipelineFolderView>).map { it.path } shouldBe listOf("nyc")
        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<PipelineRecord>) shouldHaveSize 0
        model["total"] shouldBe 0
        model["hasMore"] shouldBe false
        model["offset"] shouldBe 0
    }

    @Test
    fun `a non-empty q renders the FLAT search list, not the tree`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.findAll(workspaceId) } returns
            listOf(pipeline("nyc/mobility/alpha"), pipeline("nyc/mobility/beta"), pipeline("trade/gamma"))
        every { repository.findDrafts(any(), any()) } returns emptyMap()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), "beta", null)

        model["searching"] shouldBe true
        @Suppress("UNCHECKED_CAST")
        val result = model["pipelines"] as List<PipelineRecord>
        result shouldHaveSize 1
        result[0].name shouldBe "nyc/mobility/beta"
        model["total"] shouldBe 1
        // Browsing is not consulted for a search: two presentations, one at a time.
        verify(exactly = 0) { repository.listFolder(any(), any(), any(), any()) }
    }

    @Test
    fun `search still matches display name and description, over full paths`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        val now = java.time.Instant.now()
        val byDisplayName = PipelineRecord(UUID.randomUUID(), "nyc/p1", "Alpha Bravo", "desc one", userId, 1, now, now)
        val byDescription = PipelineRecord(UUID.randomUUID(), "nyc/p2", "Charlie Delta", "contains BRAVO", userId, 1, now, now)
        val neither = PipelineRecord(UUID.randomUUID(), "nyc/p3", "Echo", "nothing", userId, 1, now, now)
        every { repository.findAll(workspaceId) } returns listOf(byDisplayName, byDescription, neither)
        every { repository.findDrafts(any(), any()) } returns emptyMap()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), "bravo", null)

        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<PipelineRecord>) shouldHaveSize 2
    }

    @Test
    fun `a blank search is not a search - it renders the tree`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns level()

        controller.list(ExtendedModelMap(), mockk(), "   ", null)

        verify(exactly = 0) { repository.findAll(workspaceId) }
        verify(exactly = 1) { repository.listFolder(workspaceId, null, 0, pageSize) }
    }

    @Test
    fun `a negative offset is clamped to zero`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns level()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, -5)

        model["offset"] shouldBe 0
    }

    @Test
    fun `scopes are populated from authenticated principal`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns level()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null)

        @Suppress("UNCHECKED_CAST")
        val scopes = model["scopes"] as Set<String>
        scopes shouldBe setOf("AUTHOR")
    }

    @Test
    fun `an empty workspace renders an empty level, and asks for no drafts`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.listFolder(workspaceId, null, 0, pageSize) } returns level()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null)

        model["total"] shouldBe 0
        model["hasMore"] shouldBe false
        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<*>) shouldHaveSize 0
        verify(exactly = 0) { repository.findDrafts(any(), any()) }
    }
}
