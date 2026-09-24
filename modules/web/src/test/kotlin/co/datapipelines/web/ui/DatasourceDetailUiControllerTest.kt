package co.datapipelines.web.ui

import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.application.semantics.SemanticsService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * 162 (#156) — [DatasourceDetailUiController]'s own wiring: which tree a dialect gets, and that
 * an invisible name redirects on every one of the three routes, exactly like the LAKE-only
 * behaviour this round replaces. The trees' own CONTENT is [DatasourceSchemaTreeBrowseModelTest]
 * (the model) and [DatasourceSchemaTreeRenderTest] (the fragments) — this file is the
 * controller's dispatch, which neither of those can see.
 */
class DatasourceDetailUiControllerTest {
    private val registry = mockk<DatasourceRegistry>()
    private val lakeTables = mockk<LakeTableRegistryService>()
    private val lakeBrowse = mockk<LakeTableBrowseModel>(relaxed = true)
    private val schemaTree = mockk<DatasourceSchemaTreeBrowseModel>(relaxed = true)
    private val themeResolver = mockk<ThemeResolver>()
    private val semantics = mockk<SemanticsService>()
    private val controller = DatasourceDetailUiController(registry, lakeTables, lakeBrowse, schemaTree, themeResolver, semantics)

    private val workspaceId = UUID.randomUUID()
    private val request = mockk<HttpServletRequest>()

    private fun postgres(name: String = "pg-demo") =
        Datasource(name = name, displayName = name, description = null, dialect = Dialect.POSTGRES, jdbcUrl = "jdbc:postgresql://db/app")

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    @Test
    fun `detail for a non-LAKE dialect fills the schema tree, not the LAKE registry`() {
        authenticate()
        val ds = postgres()
        every { themeResolver.resolve(any()) } returns "saas"
        every { registry.getVisible("pg-demo", workspaceId) } returns ds
        every { semantics.list(any(), any(), any()) } returns emptyList()

        val model = ExtendedModelMap()
        val view = controller.detail(model, request, "pg-demo")

        view shouldBe "datasources/detail"
        model["datasource"] shouldBe ds
        verify { schemaTree.fillRoot(model, ds) }
        verify(exactly = 0) { lakeBrowse.fillLevel(any(), any(), any(), any()) }
    }

    @Test
    fun `detail for LAKE fills the registry tree, not the schema tree`() {
        authenticate()
        val ds =
            Datasource(name = "lake-demo", displayName = "lake-demo", description = null, dialect = Dialect.LAKE, jdbcUrl = "jdbc:duckdb:")
        every { themeResolver.resolve(any()) } returns "saas"
        every { registry.getVisible("lake-demo", workspaceId) } returns ds
        every { lakeTables.list(ds) } returns emptyList()
        every { semantics.list(any(), any(), any()) } returns emptyList()

        val model = ExtendedModelMap()
        controller.detail(model, request, "lake-demo")

        verify { lakeBrowse.fillLevel(model, emptyList(), prefix = null, offset = 0) }
        verify(exactly = 0) { schemaTree.fillRoot(any(), any()) }
    }

    @Test
    fun `detail on an invisible datasource redirects to the listing and never introspects`() {
        authenticate()
        every { registry.getVisible("ghost", workspaceId) } returns null

        val view = controller.detail(ExtendedModelMap(), request, "ghost")

        view shouldBe "redirect:/datasources"
        verify(exactly = 0) { schemaTree.fillRoot(any(), any()) }
    }

    @Test
    fun `the tables partial passes the schema and offset straight through and returns the model's view`() {
        authenticate()
        val ds = postgres()
        every { registry.getVisible("pg-demo", workspaceId) } returns ds
        every { schemaTree.fillTables(any(), ds, "app.sales", 25) } returns DatasourceSchemaTreeBrowseModel.TABLES_VIEW

        val model = ExtendedModelMap()
        val view = controller.tables(model, "pg-demo", "app.sales", 25)

        view shouldBe DatasourceSchemaTreeBrowseModel.TABLES_VIEW
        verify { schemaTree.fillTables(model, ds, "app.sales", 25) }
    }

    @Test
    fun `the tables partial on an invisible datasource redirects and never introspects`() {
        authenticate()
        every { registry.getVisible("ghost", workspaceId) } returns null

        val view = controller.tables(ExtendedModelMap(), "ghost", null, null)

        view shouldBe "redirect:/datasources"
        verify(exactly = 0) { schemaTree.fillTables(any(), any(), any(), any()) }
    }

    @Test
    fun `the columns partial passes the table and schema straight through`() {
        authenticate()
        val ds = postgres()
        every { registry.getVisible("pg-demo", workspaceId) } returns ds
        every { schemaTree.fillColumns(any(), ds, "public", "orders") } returns DatasourceSchemaTreeBrowseModel.COLUMNS_VIEW

        val model = ExtendedModelMap()
        val view = controller.columns(model, "pg-demo", "orders", "public")

        view shouldBe DatasourceSchemaTreeBrowseModel.COLUMNS_VIEW
        verify { schemaTree.fillColumns(model, ds, "public", "orders") }
    }

    @Test
    fun `the columns partial on an invisible datasource redirects and never introspects`() {
        authenticate()
        every { registry.getVisible("ghost", workspaceId) } returns null

        val view = controller.columns(ExtendedModelMap(), "ghost", "orders", null)

        view shouldBe "redirect:/datasources"
        verify(exactly = 0) { schemaTree.fillColumns(any(), any(), any(), any()) }
    }
}
