package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.LocalPasswordService
import co.datapipelines.auth.Scope
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID

class AdminUsersControllerTest {
    private val themeResolver = mockk<ThemeResolver>()
    private val authProperties = AuthProperties()
    private val controller = AdminUsersController(themeResolver, authProperties)

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

    @Test
    fun `admin users page renders the design-system table`() {
        val html =
            engine().process(
                "admin/users",
                WebContext(
                    JakartaServletWebApplication
                        .buildApplication(MockServletContext())
                        .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
                ).apply {
                    setVariable("_csrf", mapOf("token" to "t"))
                    setVariable("workspaceHeaderFragment", "")
                    setVariable("workspaceOptions", emptyList<Any>())
                    setVariable("activeWorkspace", "acme")
                    setVariable("activeTheme", "saas")
                    setVariable("authenticated", true)
                    setVariable("currentPath", "/admin/users")
                    setVariable("localEnabled", true)
                },
            )

        html shouldContain "<table class=\"ds-table\">"
        html shouldNotContain "border-bottom:1px solid var(--border-default)"
    }

    private fun engine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }
}

class AdminUsersPartialControllerTest {
    private val userService = mockk<UserService>()
    private val localPasswordService = mockk<LocalPasswordService>()
    private val partialController = AdminUsersPartialController(userService, localPasswordService)

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
    fun `search rows render status and role as design-system badges, not styled spans`() {
        authenticate()
        every { userService.search("test", 0, 20) } returns listOf(sampleUser())

        val response = partialController.search("test", 0, 20)

        // sampleUser is active and a non-admin (029: chips are ds-badge variants now).
        response.body shouldContain "ds-badge ds-badge-success"
        response.body shouldContain "ds-badge ds-badge-default"
        response.body shouldNotContain "padding:var(--gap-xs)"
        response.body shouldNotContain "background:var(--surface-tertiary)"
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

    @Test
    fun `create local user returns the new row plus the one-time password notice`() {
        authenticate()
        every { localPasswordService.createLocalUser("new@example.com", "New", adminPrincipal.userId) } returns
            LocalPasswordService.CreateResult.Success(sampleUser(), "ABCD-EFGH-JKLM")

        val response = partialController.createLocalUser("new@example.com", "New")

        response.statusCode shouldBe HttpStatus.OK
        response.body shouldContain "ABCD-EFGH-JKLM"
        response.body shouldContain "admin-notice"
        response.body shouldContain "Test User"
    }

    @Test
    fun `create local user with a taken email is a 409`() {
        authenticate()
        every { localPasswordService.createLocalUser("taken@example.com", "", adminPrincipal.userId) } returns
            LocalPasswordService.CreateResult.EmailTaken

        val response = partialController.createLocalUser("taken@example.com", "")

        response.statusCode shouldBe HttpStatus.CONFLICT
    }

    @Test
    fun `reset password returns the row plus the one-time notice`() {
        authenticate()
        every { localPasswordService.resetPassword(userId, adminPrincipal.userId) } returns "WXYZ-2345-ABCD"
        every { userService.snapshot(userId) } returns sampleUser()

        val response = partialController.toggle(userId, "reset-password")

        response.statusCode shouldBe HttpStatus.OK
        response.body shouldContain "WXYZ-2345-ABCD"
        response.body shouldContain "admin-notice"
    }

    @Test
    fun `disable local and unlock swap the row`() {
        authenticate()
        every { localPasswordService.disableLocalAccess(userId, adminPrincipal.userId) } returns true
        every { localPasswordService.unlock(userId, adminPrincipal.userId) } returns true
        every { userService.snapshot(userId) } returns sampleUser()

        partialController.toggle(userId, "disable-local").statusCode shouldBe HttpStatus.OK
        partialController.toggle(userId, "unlock").statusCode shouldBe HttpStatus.OK
    }

    @Test
    fun `create refusals now reach the user as a toast`() {
        authenticate()

        val response = partialController.createLocalUser("not-an-email", "")

        response.statusCode shouldBe HttpStatus.BAD_REQUEST // the status is unchanged
        response.headers.getFirst("HX-Retarget") shouldBe "#toast" // …and now deliverable
        response.headers.getFirst("HX-Reswap") shouldBe "beforeend"
        response.body shouldContain "ds-toast-danger"
        response.body shouldContain "valid email address"
    }

    @Test
    fun `a taken email refusal is a deliverable 409 toast`() {
        authenticate()
        every { localPasswordService.createLocalUser("taken@example.com", "", adminPrincipal.userId) } returns
            LocalPasswordService.CreateResult.EmailTaken

        val response = partialController.createLocalUser("taken@example.com", "")

        response.statusCode shouldBe HttpStatus.CONFLICT
        response.headers.getFirst("HX-Retarget") shouldBe "#toast"
        response.headers.getFirst("HX-Reswap") shouldBe "beforeend"
        response.body shouldContain "ds-toast-danger"
        response.body shouldContain "already exists"
    }

    @Test
    fun `create success keeps the one-time password inline and only points at it`() {
        authenticate()
        every { localPasswordService.createLocalUser("new@example.com", "New", adminPrincipal.userId) } returns
            LocalPasswordService.CreateResult.Success(sampleUser(), "ABCD-EFGH-JKLM")

        val response = partialController.createLocalUser("new@example.com", "New")

        response.body shouldContain "id=\"admin-notice\" hx-swap-oob=\"true\"" // unchanged (inline form)
        response.body shouldContain "ABCD-EFGH-JKLM" // still inline, persistent
        response.body shouldContain "hx-swap-oob=\"beforeend:#toast\""
        val toastBody = response.body!!.substringAfter("beforeend:#toast")
        toastBody shouldNotContain "ABCD-EFGH-JKLM" // never in the toast
    }

    @Test
    fun `a row action keeps the row swap and gains a toast naming the action and the email`() {
        authenticate()
        every { userService.deactivate(userId, adminPrincipal.userId) } returns true
        every { userService.snapshot(userId) } returns sampleUser()

        val response = partialController.toggle(userId, "deactivate")

        response.statusCode shouldBe HttpStatus.OK
        response.body shouldContain "id=\"user-row-$userId\""
        response.body shouldContain "hx-swap-oob=\"beforeend:#toast\""
        response.body shouldContain "User deactivated"
        response.body shouldContain "user@example.com"
    }

    @Test
    fun `reset password keeps the notice and only points a toast at it`() {
        authenticate()
        every { localPasswordService.resetPassword(userId, adminPrincipal.userId) } returns "WXYZ-2345-ABCD"
        every { userService.snapshot(userId) } returns sampleUser()

        val response = partialController.toggle(userId, "reset-password")

        response.body shouldContain "hx-swap-oob=\"beforeend:#toast\""
        val toastBody = response.body!!.substringAfter("beforeend:#toast")
        toastBody shouldNotContain "WXYZ-2345-ABCD" // the secret stays in the inline notice only
    }
}
