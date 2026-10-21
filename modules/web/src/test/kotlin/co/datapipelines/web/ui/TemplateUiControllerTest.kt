package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The templates catalog page's controller and the partial controller's list (#398), over the
 * one [TemplateBrowseModel] they share.
 *
 * The browse model is REAL here, not a double: the thing worth pinning is that the catalog
 * always answers the flat list (an empty `q` is every template — the pipelines ruling, read
 * onto templates) on both surfaces (a page load and an htmx refresh), and a mocked browse model
 * would assert nothing about that rule. Since #466 the partial has no sidebar instance (the
 * tree is `/api/v1/templates/tree`). The repository underneath is the double, so each test
 * names the query it is about.
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

    @Test
    fun `the catalog with no query lists EVERY template - the flat list, never the tree`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubFlatList(leaves = listOf(template(), template("test/orders_v2.sql")), total = 42)

        val model = ExtendedModelMap()
        val viewName = controller.list(model, mockk(), null, null, null, null, null)

        viewName shouldBe "templates/list"
        model["activeTheme"] shouldBe "saas"
        // #398: the page has no tree — an empty query is the workspace's whole list.
        model["searching"] shouldBe true
        model["rootId"] shouldBe TemplateBrowseModel.CATALOG_ROOT_ID
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
        controller.list(searching, mockk(), "orders", "POSTGRES", "sql", null, null)

        searching["searching"] shouldBe true
        (searching["templates"] as List<*>) shouldHaveSize 1
        searching["selectedDialect"] shouldBe "POSTGRES"
        searching["selectedType"] shouldBe "sql"
        searching["q"] shouldBe "orders"
    }

    @Test
    fun `the lifecycle filter reaches the repository as its bucket and stamps the select (#579)`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every {
            repository.list(any(), null, null, null, 0, 26, null, LifecycleBucket.DRAFT_ONLY, false)
        } returns listOf(template())
        every { repository.count(any(), null, null, null, null, LifecycleBucket.DRAFT_ONLY, false) } returns 1
        every { repository.findDrafts(any(), any()) } returns emptyMap()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null, null, "draft")

        (model["templates"] as List<*>) shouldHaveSize 1
        model["lifecycle"] shouldBe "draft"
        model["lifecycleLabel"] shouldBe "Draft only"
        model["filtered"] shouldBe true
    }

    @Test
    fun `a lifecycle the templates catalog does not offer is the house 400 (#579)`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"

        // `discarded` is not offered: a discarded-only template has no page to open.
        val refused =
            shouldThrow<ResponseStatusException> {
                controller.list(ExtendedModelMap(), mockk(), null, null, null, null, "discarded")
            }
        refused.statusCode shouldBe HttpStatus.BAD_REQUEST
        refused.reason shouldBe CatalogLifecycle.BAD_LIFECYCLE
    }

    @Test
    fun `the create form carries the SERVER's grammar, not a copy of it`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubFlatList()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null, null, null)

        model["namePattern"] shouldBe co.datapipelines.templates.TemplateNameGrammar.pattern
        model["nameMaxLength"] shouldBe co.datapipelines.templates.TemplateNameGrammar.maxLength
        model["types"] shouldBe TemplateType.WIRE_VALUES
    }

    @Test
    fun `the htmx refresh answers the same flat list under the catalog's root`() {
        authenticate()
        stubFlatList()

        val model = ExtendedModelMap()
        val viewName = partialController.list(model, MockHttpServletResponse(), q = null, dialect = null, type = null, offset = 0, lifecycle = null)

        viewName shouldBe TemplateBrowseModel.SEARCH_VIEW
        model["rootId"] shouldBe TemplateBrowseModel.CATALOG_ROOT_ID
    }

    @Test
    fun `the page carries no scopes attribute - the role is the whole answer (#215)`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubFlatList(leaves = emptyList(), total = 0)

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null, null, null)

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
        controller.list(model, mockk(), null, null, null, null, null)

        (model["templates"] as List<*>) shouldHaveSize 0
        model["hasMore"] shouldBe false
        model["searching"] shouldBe true
    }
}
