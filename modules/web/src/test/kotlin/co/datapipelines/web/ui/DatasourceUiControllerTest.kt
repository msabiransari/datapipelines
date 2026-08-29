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

    private fun datasource(name: String = "pg-prod") =
        Datasource(
            name = name,
            displayName = "Production Postgres",
            description = "Main production database",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db:5432/app",
            username = "readonly",
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
    fun `test connection returns row fragment`() {
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

        viewName shouldBe "partials/datasource-row"
        model["testName"] shouldBe "pg-prod"
        model["testResult"] shouldBe result
    }

    @Test
    fun `test connection failure returns row fragment with error`() {
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

        viewName shouldBe "partials/datasource-row"
        @Suppress("UNCHECKED_CAST")
        val tr = model["testResult"] as TestResult
        tr.connected shouldBe false
        tr.error shouldBe "Connection refused"
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
