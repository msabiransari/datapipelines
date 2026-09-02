package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * The templates screen's two controllers over the one [TemplateBrowseModel] they share.
 *
 * The browse model is REAL here, not a double: the thing worth pinning is that the screen
 * dispatches to the tree or to search by the same rule on both surfaces (a page load and an
 * htmx refresh), and a mocked browse model would assert nothing about that rule. The
 * repository underneath is the double, so each test names the query it is about.
 */
class TemplateUiControllerTest {
    private val repository = mockk<TemplateRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val browse = TemplateBrowseModel(repository)
    private val controller = TemplateUiController(browse, themeResolver)
    private val partialController =
        TemplatePartialController(
            repository,
            browse,
            mockk<TemplateValidator>(),
            mockk<AuthoringGuard>(),
            TemplateUsageService(repository, mockk<co.datapipelines.pipeline.PipelineRepository>()),
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
                setOf(Scope.READ),
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    /** Browsing: the root LEVEL, never the flat list. */
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
    fun `an empty search browses the TREE — folders, root leaves, and no flat listing`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubRootLevel(leaves = listOf(template(), template("orders_v2.sql")), total = 42)

        val model = ExtendedModelMap()
        val viewName = controller.list(model, mockk(), null, null, null, null)

        viewName shouldBe "templates/list"
        model["activeTheme"] shouldBe "saas"
        model["searching"] shouldBe false
        model["levelId"] shouldBe TemplateBrowseModel.ROOT_LEVEL_ID
        (model["folders"] as List<*>) shouldHaveSize 1
        (model["templates"] as List<*>) shouldHaveSize 2
        // 034 E3: the pager's total is the level's truthful count, not an estimate.
        model["total"] shouldBe 42
    }

    @Test
    fun `a non-empty search is a FLAT list, and clearing it returns to the tree`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.list(any(), Dialect.POSTGRES, TemplateType.SQL, "orders", 0, 26) } returns listOf(template())
        every { repository.count(any(), Dialect.POSTGRES, TemplateType.SQL, "orders") } returns 1
        every { repository.findDrafts(any(), any()) } returns emptyMap()

        val searching = ExtendedModelMap()
        controller.list(searching, mockk(), "orders", "POSTGRES", "sql", null)

        searching["searching"] shouldBe true
        (searching["templates"] as List<*>) shouldHaveSize 1
        searching["selectedDialect"] shouldBe "POSTGRES"
        searching["selectedType"] shouldBe "sql"
        searching["q"] shouldBe "orders"

        stubRootLevel()
        val cleared = ExtendedModelMap()
        controller.list(cleared, mockk(), "", "POSTGRES", "sql", null)
        cleared["searching"] shouldBe false
    }

    @Test
    fun `the create form carries the SERVER's grammar, not a copy of it`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubRootLevel()

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null, null)

        model["namePattern"] shouldBe co.datapipelines.templates.TemplateNameGrammar.pattern
        model["nameMaxLength"] shouldBe co.datapipelines.templates.TemplateNameGrammar.maxLength
        model["types"] shouldBe TemplateType.WIRE_VALUES
    }

    @Test
    fun `the partial with no prefix answers the WRAPPER`() {
        authenticate()
        stubRootLevel(total = 100)

        val model = ExtendedModelMap()
        val viewName = partialController.list(model, null, null, null, null, null)

        viewName shouldBe TemplateBrowseModel.WRAPPER_VIEW
        (model["templates"] as List<*>) shouldHaveSize 1
    }

    @Test
    fun `the partial with a prefix answers ONE level — that folder's direct children only`() {
        authenticate()
        every { repository.listChildFolders(any(), eq("acme/finance"), null, null, any()) } returns emptyList()
        every { repository.listChildTemplates(any(), eq("acme/finance"), null, null, 0, 26) } returns
            listOf(template("acme/finance/monthly_revenue"))
        every { repository.countChildTemplates(any(), eq("acme/finance"), null, null) } returns 1
        every { repository.findDrafts(any(), any()) } returns emptyMap()

        val model = ExtendedModelMap()
        val viewName = partialController.list(model, null, null, null, "acme/finance", null)

        viewName shouldBe TemplateBrowseModel.LEVEL_VIEW
        model["levelId"] shouldBe TemplateBrowseModel.levelId("acme/finance")
        model["prefix"] shouldBe "acme/finance"
        (model["templates"] as List<*>) shouldHaveSize 1
    }

    @Test
    fun `an empty prefix is the ROOT level, so the root's own pager stays inside the tree`() {
        authenticate()
        stubRootLevel()

        val model = ExtendedModelMap()
        val viewName = partialController.list(model, null, null, null, "", null)

        viewName shouldBe TemplateBrowseModel.LEVEL_VIEW
        model["levelId"] shouldBe TemplateBrowseModel.ROOT_LEVEL_ID
    }

    @Test
    fun `a level pages its leaves — 26 probed, 25 shown, hasMore true`() {
        authenticate()
        val many = (1..26).map { template("t$it.sql") }
        stubRootLevel(folders = emptyList(), leaves = many, total = 100)

        val model = ExtendedModelMap()
        partialController.list(model, null, null, null, null, 0)

        (model["templates"] as List<*>) shouldHaveSize 25
        model["hasMore"] shouldBe true
        model["total"] shouldBe 100
    }

    @Test
    fun `sub-folders past the cap are reported, never silently dropped`() {
        authenticate()
        val many = (1..(TemplateBrowseModel.FOLDER_LIMIT + 1)).map { TemplateFolder("f$it", "f$it", 1) }
        stubRootLevel(folders = many, leaves = emptyList(), total = 0)

        val model = ExtendedModelMap()
        partialController.list(model, null, null, null, null, null)

        (model["folders"] as List<*>) shouldHaveSize TemplateBrowseModel.FOLDER_LIMIT
        model["foldersTruncated"] shouldBe true
    }

    @Test
    fun `a prefix that is not a legal template path renders an EMPTY level, never a query`() {
        authenticate()

        val model = ExtendedModelMap()
        // Upper case is illegal (§4.1 is lower-case only), so this names no real folder.
        val viewName = partialController.list(model, null, null, null, "ACME/../etc", null)

        viewName shouldBe TemplateBrowseModel.LEVEL_VIEW
        (model["folders"] as List<*>) shouldHaveSize 0
        (model["templates"] as List<*>) shouldHaveSize 0
        model["total"] shouldBe 0
        // The repository is a strict mock with nothing stubbed: reaching it would throw.
        verify(exactly = 0) { repository.listChildFolders(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `scopes are populated from the authenticated principal`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubRootLevel(folders = emptyList(), leaves = emptyList(), total = 0)

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null, null)

        @Suppress("UNCHECKED_CAST")
        val scopes = model["scopes"] as Set<String>
        scopes shouldBe setOf("READ")
    }

    @Test
    fun `an empty workspace renders the level with nothing in it`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        stubRootLevel(folders = emptyList(), leaves = emptyList(), total = 0)

        val model = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null, null)

        (model["templates"] as List<*>) shouldHaveSize 0
        (model["folders"] as List<*>) shouldHaveSize 0
        model["hasMore"] shouldBe false
    }
}
