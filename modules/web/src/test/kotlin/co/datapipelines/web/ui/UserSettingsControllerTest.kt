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
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
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
