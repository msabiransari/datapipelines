package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.TestResult
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

class DatasourceUiControllerTest {
    private val registry = mockk<DatasourceRegistry>()
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = DatasourceUiController(registry, co.datapipelines.auth.WorkspacesProperties(), themeResolver)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun datasource(
        name: String = "pg-prod",
        dialect: Dialect = Dialect.POSTGRES,
        jdbcUrl: String = "jdbc:postgresql://db:5432/app",
        username: String = "readonly",
        displayName: String = "Production Postgres",
        description: String? = "Main production database",
        workspaceName: String? = null,
    ) = Datasource(
        name = name,
        displayName = displayName,
        description = description,
        dialect = dialect,
        jdbcUrl = jdbcUrl,
        username = username,
        workspaceName = workspaceName,
    )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    setOf(Scope.ADMIN),
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    private fun partialController() =
        DatasourcePartialController(
            registry,
            co.datapipelines.web.datasources.DatasourceWorkspaceRules(
                mockk(relaxed = true),
                co.datapipelines.auth.WorkspacesProperties(),
            ),
        )

    @Test
    fun `list page returns datasources view with theme and datasources`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { registry.listVisible(null, workspaceId) } returns listOf(datasource(), datasource("pg-staging"))

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.list(model, mockk(), null, null, null)

        viewName shouldBe "datasources/list"
        model["activeTheme"] shouldBe "saas"
        @Suppress("UNCHECKED_CAST")
        val result = model["datasources"] as List<Datasource>
        result shouldHaveSize 2
        model["total"] shouldBe 2
    }

    @Test
    fun `list page filters by dialect`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        val dsList = listOf(datasource("pg1"), datasource("pg2"))
        every { registry.listVisible(Dialect.POSTGRES, workspaceId) } returns dsList

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.list(model, mockk(), null, "POSTGRES", null)

        model["selectedDialect"] shouldBe "POSTGRES"
        @Suppress("UNCHECKED_CAST")
        val result = model["datasources"] as List<Datasource>
        result shouldHaveSize 2
    }

    @Test
    fun `partial returns fragment view`() {
        authenticate()
        every { registry.listVisible(null, workspaceId) } returns listOf(datasource())

        val partialController = partialController()
        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = partialController.list(model, null, null, null)

        viewName shouldBe "partials/datasources"
        @Suppress("UNCHECKED_CAST")
        (model["datasources"] as List<*>) shouldHaveSize 1
    }

    @Test
    fun `test connection success returns a success toast`() {
        val result =
            TestResult(
                connected = true,
                testedAt = java.time.Instant.now(),
                serverVersion = "15.4",
                error = null,
            )
        authenticate()
        every { registry.getVisible("pg-prod", workspaceId) } returns datasource()
        every { registry.testConnection("pg-prod") } returns result

        val partialController = partialController()
        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = partialController.test(model, "pg-prod")

        viewName shouldBe "partials/toast"
        model["variant"] shouldBe "success"
        model["title"] shouldBe "Connection succeeded"
        model["message"] shouldBe "pg-prod — Server version: 15.4"
    }

    @Test
    fun `test connection failure returns a danger toast with the error`() {
        val result =
            TestResult(
                connected = false,
                testedAt = java.time.Instant.now(),
                serverVersion = null,
                error = "Connection refused",
            )
        authenticate()
        every { registry.getVisible("bad-ds", workspaceId) } returns datasource("bad-ds")
        every { registry.testConnection("bad-ds") } returns result

        val partialController = partialController()
        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = partialController.test(model, "bad-ds")

        viewName shouldBe "partials/toast"
        model["variant"] shouldBe "danger"
        model["title"] shouldBe "Connection failed"
        model["message"] shouldBe "bad-ds — Connection refused"
    }

    @Test
    fun `test connection on an invisible datasource returns a not-found toast and never probes`() {
        authenticate()
        every { registry.getVisible("ghost", workspaceId) } returns null

        val partialController = partialController()
        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = partialController.test(model, "ghost")

        viewName shouldBe "partials/toast"
        model["variant"] shouldBe "danger"
        model["title"] shouldBe "Datasource not found"
        io.mockk.verify(exactly = 0) { registry.testConnection(any<String>()) }
    }

    @Test
    fun `partial search matches every rendered column`() {
        authenticate()
        val partialController = partialController()
        val rows =
            listOf(
                datasource(name = "alpha", jdbcUrl = "jdbc:postgresql://reports.internal:5432/db", username = "svc_reports"),
                datasource(
                    name = "beta",
                    dialect = Dialect.SQLITE,
                    jdbcUrl = "jdbc:sqlite:/tmp/other.db",
                    username = "svc_other",
                    displayName = "Scratch database",
                    description = "Local scratch file",
                ),
            )
        every { registry.listVisible(null, workspaceId) } returns rows

        // jdbcUrl substring, username, and the dialect's wire value each select alpha only.
        listOf("reports.internal", "svc_reports", "postgres").forEach { q ->
            val model: ExtendedModelMap = ExtendedModelMap()
            partialController.list(model, q, null, null)
            @Suppress("UNCHECKED_CAST")
            val shown = model.getAttribute("datasources") as List<Datasource>
            shown.map { it.name } shouldBe listOf("alpha")
        }
    }

    @Test
    fun `partial search matches the rendered workspace column, including the global literal`() {
        authenticate()
        val partialController = partialController()
        every { registry.listVisible(null, workspaceId) } returns
            listOf(
                datasource(name = "bound", workspaceName = "analytics"),
                datasource(name = "shared"),
            )

        val boundModel: ExtendedModelMap = ExtendedModelMap()
        partialController.list(boundModel, "analytics", null, null)
        @Suppress("UNCHECKED_CAST")
        (boundModel.getAttribute("datasources") as List<Datasource>).map { it.name } shouldBe listOf("bound")

        // An unbound row renders the literal "global" in the workspace column.
        val globalModel: ExtendedModelMap = ExtendedModelMap()
        partialController.list(globalModel, "global", null, null)
        @Suppress("UNCHECKED_CAST")
        (globalModel.getAttribute("datasources") as List<Datasource>).map { it.name } shouldBe listOf("shared")
    }

    @Test
    fun `partial search still excludes non-matches`() {
        authenticate()
        every { registry.listVisible(null, workspaceId) } returns listOf(datasource(name = "alpha"))
        val model: ExtendedModelMap = ExtendedModelMap()
        partialController().list(model, "nothing-matches-this", null, null)
        @Suppress("UNCHECKED_CAST")
        (model.getAttribute("datasources") as List<*>) shouldBe emptyList<Datasource>()
    }

    @Test
    fun `scopes are populated from principal`() {
        authenticate()

        every { themeResolver.resolve(any()) } returns "saas"
        every { registry.listVisible(null, workspaceId) } returns emptyList()

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null)

        @Suppress("UNCHECKED_CAST")
        val scopes = model["scopes"] as Set<String>
        scopes shouldBe setOf("ADMIN")
    }
}
