package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
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

class TemplateUiControllerTest {
    private val repository = mockk<TemplateRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = TemplateUiController(repository, themeResolver)

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

    @Test
    fun `list page returns templates view with theme and templates`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.list(any(), null, null, 0, 26) } returns
            listOf(
                template(),
                template("orders_v2.sql"),
            )

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.list(model, mockk(), null, null, null)

        viewName shouldBe "templates/list"
        model["activeTheme"] shouldBe "saas"
        @Suppress("UNCHECKED_CAST")
        val result = model["templates"] as List<Template>
        result shouldHaveSize 2
    }

    @Test
    fun `list page passes filters to repository`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.list(any(), Dialect.POSTGRES, "orders", 0, 26) } returns listOf(template())

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.list(model, mockk(), "orders", "POSTGRES", null)

        @Suppress("UNCHECKED_CAST")
        val result = model["templates"] as List<Template>
        result shouldHaveSize 1
        model["selectedDialect"] shouldBe "POSTGRES"
        model["q"] shouldBe "orders"
    }

    @Test
    fun `partial returns fragment view`() {
        authenticate()
        every { repository.list(any(), null, null, 0, 26) } returns listOf(template())

        val partialController = TemplatePartialController(repository)
        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = partialController.list(model, null, null, null)

        viewName shouldBe "partials/templates"
        @Suppress("UNCHECKED_CAST")
        (model["templates"] as List<*>) shouldHaveSize 1
    }

    @Test
    fun `partial handles pagination hasMore detection`() {
        authenticate()
        val many = (1..26).map { template("t$it.sql") }
        every { repository.list(any(), null, null, 0, 26) } returns many

        val partialController = TemplatePartialController(repository)
        val model: ExtendedModelMap = ExtendedModelMap()
        partialController.list(model, null, null, 0)

        @Suppress("UNCHECKED_CAST")
        val result = model["templates"] as List<Template>
        result shouldHaveSize 25
        model["hasMore"] shouldBe true
    }

    @Test
    fun `scopes are populated from authenticated principal`() {
        authenticate()

        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.list(any(), null, null, 0, 26) } returns emptyList()

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null)

        @Suppress("UNCHECKED_CAST")
        val scopes = model["scopes"] as Set<String>
        scopes shouldBe setOf("READ")
    }

    @Test
    fun `empty list renders correctly`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.list(any(), null, null, 0, 26) } returns emptyList()

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.list(model, mockk(), null, null, null)

        @Suppress("UNCHECKED_CAST")
        (model["templates"] as List<*>) shouldHaveSize 0
        model["hasMore"] shouldBe false
    }
}
