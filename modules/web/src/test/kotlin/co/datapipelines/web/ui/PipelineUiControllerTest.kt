package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
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
 * Since #350 the page is the CATALOG: no `q` fills the flat list of every pipeline the caller
 * may read (newest first, the service's own page), a non-empty `q` the matches and their
 * truthful total. The folder tree lives in the sidebar (`/partials/pipelines?scope=nav`), so the
 * page never asks for a tree level.
 */
class PipelineUiControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val browse = co.datapipelines.web.pipelineBrowseModelOver(repository)
    private val controller = PipelineUiController(browse, themeResolver, co.datapipelines.web.EVERYTHING_LENS)

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

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    /** The catalog's no-query page: the service's paged read over every live row. */
    private fun stubCatalog(vararg rows: PipelineRecord) {
        every { repository.findAll(workspaceId, null, pageSize + 1, 0) } returns rows.toList()
        every { repository.countAll(workspaceId) } returns rows.size
        every { repository.findDrafts(any(), any()) } returns emptyMap()
    }

    @Test
    fun `#350 - the page renders the flat CATALOG of every pipeline, never a tree level`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        // A pre-077 flat name is a row of the catalog like any other — the TREE's root hides
        // it (folders only), so the catalog is where it stays reachable besides search.
        stubCatalog(pipeline("legacy_flat"), pipeline("nyc/mobility/alpha"))

        val model = ExtendedModelMap()
        val viewName = controller.list(model, mockk(), null, null)

        viewName shouldBe "pipelines/list"
        model["activeTheme"] shouldBe "saas"
        model["searching"] shouldBe true
        model["scope"] shouldBe PipelineListScope.CATALOG.wire
        model["rootId"] shouldBe PipelineBrowseModel.CATALOG_ROOT_ID
        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<PipelineRecord>).map { it.name } shouldBe listOf("legacy_flat", "nyc/mobility/alpha")
        model["total"] shouldBe 2
        model["hasMore"] shouldBe false
        model["offset"] shouldBe 0
        // The tree moved into the sidebar: the page asks for no tree level at all.
        verify(exactly = 0) { repository.listFolder(any(), any(), any(), any()) }
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
    fun `a blank search is not a search - it renders the whole catalog`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubCatalog()

        controller.list(ExtendedModelMap(), mockk(), "   ", null)

        verify(exactly = 0) { repository.findAll(workspaceId) }
        verify(exactly = 1) { repository.findAll(workspaceId, null, pageSize + 1, 0) }
    }

    @Test
    fun `a negative offset is clamped to zero`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubCatalog()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, -5)

        model["offset"] shouldBe 0
    }

    @Test
    fun `the page carries no scopes attribute - the role is the whole answer (#215)`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubCatalog()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null)

        // The dead `scopes` attribute went with the scopes (#215 PK8); `RoleModel`'s booleans
        // are what the templates read.
        model.containsAttribute("scopes") shouldBe false
    }

    @Test
    fun `an empty workspace renders an empty catalog with an honest zero`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubCatalog()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null)

        model["total"] shouldBe 0
        model["hasMore"] shouldBe false
        model["q"] shouldBe ""
        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<*>) shouldHaveSize 0
    }
}
