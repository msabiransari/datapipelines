package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.LocalPasswordService
import co.datapipelines.auth.Scope
import co.datapipelines.auth.User
import co.datapipelines.auth.UserRepository
import co.datapipelines.auth.WorkspaceContext
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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

class UserSettingsControllerTest {
    private val userRepository = mockk<UserRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val localPasswordService = mockk<LocalPasswordService>()
    private val controller =
        UserSettingsController(userRepository, themeResolver, UiProperties(theme = "forest"), localPasswordService)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "test@example.com",
            displayName = "Test User",
            scopes = setOf(Scope.READ, Scope.EXECUTE),
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private val sampleUser =
        User(
            id = userId,
            email = "test@example.com",
            displayName = "Test User",
            profilePictureUrl = "https://example.com/avatar.jpg",
            provider = "google",
            providerSubject = "sub123",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
            lastLoginAt = Instant.parse("2026-08-02T00:00:00Z"),
            themePreference = "dark",
        )

    @Test
    fun `settings page returns view with user and themes`() {
        authenticate()
        every { userRepository.findById(any()) } returns sampleUser
        every { themeResolver.resolve(any()) } returns "dark"

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.settings(model, mockk(relaxed = true))

        viewName shouldBe "settings/index"
        @Suppress("UNCHECKED_CAST")
        (model["themes"] as List<*>).size shouldBe 9
        model["user"] shouldBe sampleUser
        model["activeTheme"] shouldBe "dark"
    }

    @Test
    fun `update theme persists preference`() {
        authenticate()
        every { userRepository.setThemePreference(userId, "ocean") } just runs

        val model: ExtendedModelMap = ExtendedModelMap()
        val result = controller.updateTheme("ocean", model)

        // 025 C1: the response is a RENDERED fragment, never a hand-built string carrying
        // an unprocessed th:href — the raw string's OOB <span> was swapped over the
        // layout's real stylesheet link and dropped the page's theme CSS until reload.
        result shouldBe "partials/theme-swap"
        model["theme"] shouldBe "ocean"
    }

    @Test
    fun `a blank theme clears the preference and swaps to the DEPLOYMENT default - not a hardcoded name`() {
        authenticate()
        every { userRepository.setThemePreference(userId, null) } just runs

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.updateTheme("", model) shouldBe "partials/theme-swap"

        model["theme"] shouldBe "forest"
    }

    @Test
    fun `update theme rejects unknown value`() {
        authenticate()
        @Suppress("UNCHECKED_CAST")
        val response = controller.updateTheme("bogus_theme", ExtendedModelMap()) as org.springframework.http.ResponseEntity<String>

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body shouldContain "Unknown theme"
    }

    @Test
    fun `an unknown theme refusal is a deliverable toast - real 400, retargeted at the stack`() {
        authenticate()
        @Suppress("UNCHECKED_CAST")
        val response = controller.updateTheme("bogus_theme", ExtendedModelMap()) as org.springframework.http.ResponseEntity<String>

        // Shape C (§5.1): the status is unchanged, but bridgeErrors can now admit it.
        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.headers.getFirst("HX-Retarget") shouldBe "#toast"
        response.headers.getFirst("HX-Reswap") shouldBe "beforeend"
        response.body shouldContain "hx-swap-oob=\"beforeend:#toast\""
        response.body shouldContain "ds-toast-danger"
        response.body shouldContain "Unknown theme"
    }

    @Test
    fun `the settings page theme select is toast-only and the status div is gone`() {
        val html =
            engine().process(
                "settings/index",
                webContext().apply {
                    fillLayoutChrome()
                    setVariable("user", sampleUser)
                    setVariable("authMethod", "OIDC")
                    setVariable("themes", listOf("saas", "ocean"))
                    setVariable("sessionScopes", listOf("read"))
                },
            )

        // Shape B: the select has no content target — the response is link + toast only.
        html shouldContain "hx-swap=\"none\""
        html shouldNotContain "id=\"theme-status\""
    }

    @Test
    fun `the password screen delivers its 400s inline through its own listener`() {
        val template =
            checkNotNull(javaClass.getResource("/templates/settings/password.html")) {
                "settings/password.html not on the test classpath"
            }.readText()

        // The failures are field-level/credential validation — they stay inline (§5.1),
        // but htmx never swaps 4xx, so the screen owns its error path explicitly.
        template shouldContain "htmx:responseError"
        template shouldContain "password-change-result"
    }

    @Test
    fun `a password failure stays inline - 400 span, no retarget, no toast markup`() {
        authenticate()
        every { localPasswordService.changeOwn(userId, "wrong-current-1", "new-password-1") } returns
            LocalPasswordService.ChangeResult.WrongCurrentPassword

        val response = controller.changeOwnPassword("wrong-current-1", "new-password-1", "new-password-1")

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.headers["HX-Retarget"] shouldBe null // field-level validation is never a toast
        response.body shouldContain "current password is incorrect"
        response.body!! shouldNotContain "hx-swap-oob"
    }

    @Test
    fun `a password success is a toast-only response`() {
        authenticate()
        every { localPasswordService.changeOwn(userId, "current-password-1", "new-password-1") } returns
            LocalPasswordService.ChangeResult.Success

        val response = controller.changeOwnPassword("current-password-1", "new-password-1", "new-password-1")

        response.statusCode shouldBe HttpStatus.OK
        response.body shouldContain "hx-swap-oob=\"beforeend:#toast\""
        response.body shouldContain "Password changed"
    }

    private fun WebContext.fillLayoutChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/settings")
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

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

    @Test
    fun `change password with mismatched confirmation never reaches the service`() {
        authenticate()

        val response = controller.changeOwnPassword("current-password-1", "new-password-1", "new-password-2")

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body shouldContain "do not match"
    }

    @Test
    fun `change password maps the service outcomes to fragments`() {
        authenticate()
        every { localPasswordService.changeOwn(userId, "current-password-1", "new-password-1") } returns
            LocalPasswordService.ChangeResult.Success
        controller
            .changeOwnPassword("current-password-1", "new-password-1", "new-password-1")
            .statusCode shouldBe HttpStatus.OK

        every { localPasswordService.changeOwn(userId, "wrong-current-1", "new-password-1") } returns
            LocalPasswordService.ChangeResult.WrongCurrentPassword
        val wrong = controller.changeOwnPassword("wrong-current-1", "new-password-1", "new-password-1")
        wrong.statusCode shouldBe HttpStatus.BAD_REQUEST
        wrong.body shouldContain "current password is incorrect"

        every { localPasswordService.changeOwn(userId, "current-password-1", "short") } returns
            LocalPasswordService.ChangeResult.PolicyViolation("Password must be at least 12 characters")
        val weak = controller.changeOwnPassword("current-password-1", "short", "short")
        weak.statusCode shouldBe HttpStatus.BAD_REQUEST
        weak.body shouldContain "at least 12 characters"
    }
}
