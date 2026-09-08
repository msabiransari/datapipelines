package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import io.kotest.matchers.collections.shouldContain
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

class UiControllerTest {
    private val themeResolver = mockk<ThemeResolver>()
    private val oidcRegistrations = mockk<OidcRegistrations>()
    private val authProperties = AuthProperties()
    private val controller = UiController(themeResolver, oidcRegistrations, authProperties)

    private fun mockRequest() = mockk<HttpServletRequest>()

    @Test
    fun `login page returns login view and iterates over mock providers`() {
        val providers =
            listOf(
                Provider("google", "Sign in with Google"),
                Provider("github", "Sign in with GitHub"),
            )
        every { oidcRegistrations.providers() } returns providers
        every { themeResolver.resolve(any()) } returns "saas"
        val request = mockRequest()
        every { request.getParameter("error") } returns null

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.login(model, request)

        viewName shouldBe "login"
        @Suppress("UNCHECKED_CAST")
        val modelProviders = model["providers"] as List<Provider>
        modelProviders shouldHaveSize 2
        modelProviders.map { it.registrationId } shouldContain "google"
        modelProviders.map { it.registrationId } shouldContain "github"
        model["activeTheme"] shouldBe "saas"
    }

    @Test
    fun `login page passes error param to model`() {
        every { oidcRegistrations.providers() } returns emptyList()
        every { themeResolver.resolve(any()) } returns "saas"
        val request = mockRequest()
        every { request.getParameter("error") } returns "oidc_error"

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.login(model, request)

        model["error"] shouldBe "oidc_error"
    }

    @Test
    fun `login page exposes whether local password login is enabled`() {
        every { oidcRegistrations.providers() } returns emptyList()
        every { themeResolver.resolve(any()) } returns "saas"
        val request = mockRequest()
        every { request.getParameter("error") } returns null

        val disabledModel = ExtendedModelMap()
        UiController(themeResolver, oidcRegistrations, AuthProperties()).login(disabledModel, request)
        disabledModel["localEnabled"] shouldBe false

        val enabledModel = ExtendedModelMap()
        val localOn = AuthProperties(local = AuthProperties.Local(enabled = true))
        UiController(themeResolver, oidcRegistrations, localOn).login(enabledModel, request)
        enabledModel["localEnabled"] shouldBe true
    }

    @Test
    fun `dashboard returns dashboard view with theme`() {
        every { themeResolver.resolve(any()) } returns "dark"
        val request = mockRequest()

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.dashboard(model, request)

        viewName shouldBe "dashboard"
        model["activeTheme"] shouldBe "dark"
    }

    /**
     * 090 §C — a live session never sees the sign-in form.
     *
     * `/login` is `permitAll`, so the JWT filter authenticates a valid `dp_session` before
     * the request reaches this controller and `UiWorkspaceAdvice` reports `authenticated`.
     * Opening it in a second tab therefore rendered the form inside the working app shell
     * (owner's walk, 2026-09-07). The bounce reads the SecurityContext — the principal, not
     * the cookie — so it fires exactly when the session is real.
     *
     * The collaborators are left UNSTUBBED on purpose: a redirect that still asked
     * `oidcRegistrations.providers()` would blow up on the strict mock, which is what makes
     * this test evidence that the bounce happens BEFORE the model is built rather than
     * after it.
     */
    @Test
    fun `a signed-in visitor is redirected away from the login page`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(signedInPrincipal(), null, emptyList())

        val model = ExtendedModelMap()
        controller.login(model, mockRequest()) shouldBe "redirect:/dashboard"
        model.isEmpty() shouldBe true
    }

    /**
     * The other half, and the reason the check reads the principal and not the cookie: a
     * request whose credential did NOT authenticate (expired, tampered, deactivated owner)
     * arrives here with an empty context, and that visitor genuinely needs the form —
     * `/login?error=expired` exists for exactly them.
     */
    @Test
    fun `a request with no authenticated principal still gets the login form`() {
        SecurityContextHolder.getContext().authentication = null
        every { oidcRegistrations.providers() } returns emptyList()
        every { themeResolver.resolve(any()) } returns "saas"
        val request = mockRequest()
        every { request.getParameter("error") } returns "expired"

        controller.login(ExtendedModelMap(), request) shouldBe "login"
    }

    /** A non-session credential (an API key principal is not one) must not bounce a browser. */
    @Test
    fun `an authentication holding a non-principal object does not bounce`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("anonymous", null, emptyList())
        every { oidcRegistrations.providers() } returns emptyList()
        every { themeResolver.resolve(any()) } returns "saas"
        val request = mockRequest()
        every { request.getParameter("error") } returns null

        controller.login(ExtendedModelMap(), request) shouldBe "login"
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun signedInPrincipal() =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "signed-in@example.test",
            displayName = "Signed In",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
        )
}
