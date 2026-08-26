package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

class AdminUsersControllerTest {
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = AdminUsersController(themeResolver)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val adminPrincipal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "admin@example.com",
            displayName = "Admin",
            scopes = setOf(Scope.ADMIN),
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `admin users page returns view with theme`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(adminPrincipal, null, emptyList())
        every { themeResolver.resolve(any()) } returns "saas"

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.users(model, mockk(relaxed = true))

        viewName shouldBe "admin/users"
        model["activeTheme"] shouldBe "saas"
    }
}

class AdminUsersPartialControllerTest {
    private val userService = mockk<UserService>()
    private val partialController = AdminUsersPartialController(userService)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val adminPrincipal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "admin@example.com",
            displayName = "Admin",
            scopes = setOf(Scope.ADMIN),
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(adminPrincipal, null, emptyList())
    }

    private fun sampleUser() =
        User(
            id = userId,
            email = "user@example.com",
            displayName = "Test User",
            profilePictureUrl = null,
            provider = "google",
            providerSubject = "sub123",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
            lastLoginAt = null,
            themePreference = null,
        )

    @Test
    fun `search returns table rows`() {
        authenticate()
        every { userService.search("test", 0, 20) } returns listOf(sampleUser())

        val response = partialController.search("test", 0, 20)

        response.statusCode shouldBe HttpStatus.OK
        response.body shouldContain "Test User"
        response.body shouldContain "user@example.com"
    }

    @Test
    fun `search with no results returns empty message`() {
        authenticate()
        every { userService.search("nobody", 0, 20) } returns emptyList()

        val response = partialController.search("nobody", 0, 20)

        response.statusCode shouldBe HttpStatus.OK
        response.body shouldContain "No users found"
    }

    @Test
    fun `toggle unknown action returns bad request`() {
        authenticate()
        val response = partialController.toggle(userId, "unknown_action")
        response.statusCode shouldBe HttpStatus.BAD_REQUEST
    }
}
