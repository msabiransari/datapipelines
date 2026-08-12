package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.User
import co.datapipelines.auth.UserRepository
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
    private val controller = UserSettingsController(userRepository, themeResolver)

    private val userId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "test@example.com",
            displayName = "Test User",
            scopes = setOf(Scope.READ, Scope.EXECUTE),
            authMethod = AuthMethod.OIDC,
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

        val response = controller.updateTheme("ocean")

        response.statusCode shouldBe HttpStatus.OK
        response.body shouldContain "Theme updated"
    }

    @Test
    fun `update theme rejects unknown value`() {
        authenticate()
        val response = controller.updateTheme("bogus_theme")

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body shouldContain "Unknown theme"
    }
}
