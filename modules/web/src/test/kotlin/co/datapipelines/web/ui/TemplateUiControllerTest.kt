package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateFolder
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldHaveSize
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
import java.util.UUID

/**
 * The templates catalog page's controller and the partial controller's two list instances
 * (#398), over the one [TemplateBrowseModel] they share.
 *
 * The browse model is REAL here, not a double: the thing worth pinning is that the CATALOG
 * always answers the flat list (an empty `q` is every template — the pipelines ruling, read
 * onto templates) while the SIDEBAR dispatches tree-or-search by the same rule on both
 * surfaces (a page load and an htmx refresh), and a mocked browse model would assert nothing
 * about that rule. The repository underneath is the double, so each test names the query it
 * is about.
 */
class TemplateUiControllerTest {
    /** 194d — the COMPOSED reverse arrow the model takes (the record's §8.4). */
    private fun composedUsage(
        templates: co.datapipelines.templates.TemplateRepository,
        pipelines: PipelineRepository,
    ): co.datapipelines.application.templates.TemplateUsage =
        co.datapipelines.application.templates.TemplateUsage(
            TemplateUsageService(templates, pipelines),
            io.mockk.mockk<co.datapipelines.parameters.ParameterSetTemplatePins>(relaxed = true),
            pipelines,
            io.mockk.mockk<co.datapipelines.visualization.ArtifactDependents>(relaxed = true),
        )

    private val repository = mockk<TemplateRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val pipelines = mockk<PipelineRepository>()
    private val browse = co.datapipelines.web.templateBrowseModelOver(repository, composedUsage(repository, pipelines))
    private val controller = TemplateUiController(browse, themeResolver, co.datapipelines.web.EVERYTHING_LENS)
    private val partialController =
        TemplatePartialController(
            repository,
            browse,
            mockk<TemplateValidator>(),
            mockk<AuthoringGuard>(),
            co.datapipelines.web.EVERYTHING_LENS,
        )

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun template(id: String = "fetch_orders.sql") =
        Template(
            id = id,
            version = 1,
            dialect = Dialect.POSTGRES,
            displayName = "Fetch Orders",
            description = "Retrieves order data",
            body = "SELECT 1",
            createdAt = java.time.Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = userId,
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

    /** The flat list: `templates.list` answers [leaves], `count` answers [total]. */
    private fun stubFlatList(
        leaves: List<Template> = listOf(template()),
        total: Int = 1,
    ) {
        every { repository.list(any(), null, null, null, 0, 26, null) } returns leaves
        every { repository.count(any(), null, null, null) } returns total
        every { repository.findDrafts(any(), any()) } returns emptyMap()
    }

    /** Browsing the SIDEBAR's root level: folders only, never a leaf query (077). */
    private fun stubRootLevel(
        folders: List<TemplateFolder> = listOf(TemplateFolder("acme", "acme", 3)),
        leaves: List<Template> = listOf(template()),
        total: Int = 1,
    ) {
        every { repository.listChildFolders(any(), any(), any(), any(), any()) } returns folders
        every { repository.listChildTemplates(any(), any(), any(), any(), any(), any()) } returns leaves
        every { repository.countChildTemplates(any(), any(), any(), any()) } returns total
        every { repository.findDrafts(any(), any()) } returns emptyMap()
    }

    @Test
    fun `the catalog with no query lists EVERY template - the flat list, never the tree`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubFlatList(leaves = listOf(template(), template("test/orders_v2.sql")), total = 42)

        val model = ExtendedModelMap()
        val viewName = controller.list(model, mockk(), null, null, null, null)

        viewName shouldBe "templates/list"
        model["activeTheme"] shouldBe "saas"
        // #398: the page has no tree — an empty query is the workspace's whole list.
        model["searching"] shouldBe true
        model["rootId"] shouldBe TemplateBrowseModel.CATALOG_ROOT_ID
        model["scope"] shouldBe TemplateListScope.CATALOG.wire
        (model["templates"] as List<*>) shouldHaveSize 2
        model["total"] shouldBe 42
    }

    @Test
    fun `the catalog's search is the flat list under the same filters`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.list(any(), Dialect.POSTGRES, TemplateType.SQL, "orders", 0, 26, null) } returns listOf(template())
        every { repository.count(any(), Dialect.POSTGRES, TemplateType.SQL, "orders") } returns 1
        every { repository.findDrafts(any(), any()) } returns emptyMap()

        val searching = ExtendedModelMap()
        controller.list(searching, mockk(), "orders", "POSTGRES", "sql", null)

        searching["searching"] shouldBe true
        (searching["templates"] as List<*>) shouldHaveSize 1
        searching["selectedDialect"] shouldBe "POSTGRES"
        searching["selectedType"] shouldBe "sql"
        searching["q"] shouldBe "orders"
    }

    @Test
    fun `the sidebar's nav scope with no query is the ROOT level — folders only, and no flat listing`() {
        authenticate()
        // 077: the repository is stubbed to answer WITH leaves, and the root level must still
        // show none — §4.1 requires a folder, so nothing sits directly at the root and the
        // model does not even issue the leaf query. Stubbing an empty leaf list instead would
        // pass with the production change deleted.
        stubRootLevel(leaves = listOf(template(), template("test/orders_v2.sql")), total = 42)

        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()
        val viewName =
            partialController.list(model, response, q = null, dialect = null, type = null, prefix = null, offset = 0, scope = TemplateListScope.NAV.wire)

        viewName shouldBe TemplateBrowseModel.LEVEL_VIEW
        model["searching"] shouldBe false
        model["levelId"] shouldBe TemplateBrowseModel.ROOT_LEVEL_ID
        (model["folders"] as List<*>) shouldHaveSize 1
        (model["templates"] as List<*>) shouldHaveSize 0
        // The pager travels with the leaves: an empty root level reports a truthful zero, so
        // the level and its pager cannot disagree (034 E3).
        model["total"] shouldBe 0
    }

    @Test
    fun `077 - the root level never queries the leaves, and a NESTED level still does`() {
        authenticate()
        stubRootLevel()

        val root = ExtendedModelMap()
        partialController.list(root, MockHttpServletResponse(), q = null, dialect = null, type = null, prefix = "", offset = 0)

        (root["templates"] as List<*>) shouldHaveSize 0
        verify(exactly = 0) { repository.listChildTemplates(any(), null, any(), any(), any(), any()) }
        verify(exactly = 0) { repository.listChildTemplates(any(), "", any(), any(), any(), any()) }

        // …and a nested level is untouched by the rule: it queries and it renders.
        val nested = ExtendedModelMap()
        partialController.list(nested, MockHttpServletResponse(), q = null, dialect = null, type = null, prefix = "acme", offset = 0)

        (nested["templates"] as List<*>) shouldHaveSize 1
        verify(exactly = 1) { repository.listChildTemplates(any(), "acme", any(), any(), any(), any()) }
    }

    @Test
    fun `the create form carries the SERVER's grammar, not a copy of it`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubFlatList()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null, null)

        model["namePattern"] shouldBe co.datapipelines.templates.TemplateNameGrammar.pattern
        model["nameMaxLength"] shouldBe co.datapipelines.templates.TemplateNameGrammar.maxLength
        model["types"] shouldBe TemplateType.WIRE_VALUES
    }

    @Test
    fun `the nav scope carries the DP-Nav-Stamp, and the catalog answers without one`() {
        authenticate()
        stubRootLevel()
        stubFlatList()

        val nav = MockHttpServletResponse()
        partialController.list(ExtendedModelMap(), nav, q = null, dialect = null, type = null, prefix = null, offset = 0, scope = TemplateListScope.NAV.wire)
        nav.getHeader(PipelineBrowseModel.NAV_STAMP_HEADER) shouldBe "acme|all"

        val page = MockHttpServletResponse()
        partialController.list(ExtendedModelMap(), page, q = null, dialect = null, type = null, prefix = null, offset = 0, scope = TemplateListScope.CATALOG.wire)
        page.getHeader(PipelineBrowseModel.NAV_STAMP_HEADER) shouldBe null
    }

    @Test
    fun `an unknown scope degrades to the catalog - never an error, never the sidebar`() {
        authenticate()
        stubFlatList()

        val model = ExtendedModelMap()
        val viewName =
            partialController.list(model, MockHttpServletResponse(), q = null, dialect = null, type = null, prefix = null, offset = 0, scope = "bogus")

        viewName shouldBe TemplateBrowseModel.SEARCH_VIEW
        model["rootId"] shouldBe TemplateBrowseModel.CATALOG_ROOT_ID
    }

    @Test
    fun `the page carries no scopes attribute - the role is the whole answer (#215)`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubFlatList(leaves = emptyList(), total = 0)

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null, null)

        // The dead `scopes` attribute went with the scopes (#215 PK8); `RoleModel`'s booleans
        // are what the templates read.
        model.containsAttribute("scopes") shouldBe false
    }

    @Test
    fun `an empty workspace renders the catalog's empty state, not a tree`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubFlatList(leaves = emptyList(), total = 0)

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null, null)

        (model["templates"] as List<*>) shouldHaveSize 0
        model["hasMore"] shouldBe false
        model["searching"] shouldBe true
    }
}
