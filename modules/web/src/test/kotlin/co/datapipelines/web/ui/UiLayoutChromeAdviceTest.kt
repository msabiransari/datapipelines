package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.util.UUID

/**
 * Pins the layout-chrome attributes [UiWorkspaceAdvice] adds globally (027 UI pass):
 * `activeTheme` must resolve on EVERY screen — a controller forgetting it renders a
 * `themes/null.css` URL that 404s and leaves every design token unresolved (no borders,
 * no surfaces) — `authenticated` gates the nav links, and `currentPath` drives the
 * active-link state.
 */
class UiLayoutChromeAdviceTest {
    private val themeResolver = mockk<ThemeResolver>()
    private val advice = UiWorkspaceAdvice(mockk<WorkspaceService>(), themeResolver)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.c",
                "A",
                setOf(Scope.AUTHOR),
                AuthMethod.OIDC,
                workspace = null,
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `activeTheme resolves through the theme resolver for any request`() {
        every { themeResolver.resolve(any()) } returns "ocean"

        advice.activeTheme(MockHttpServletRequest()) shouldBe "ocean"
    }

    @Test
    fun `authenticated reflects the presence of a principal`() {
        advice.authenticated() shouldBe false

        authenticate()

        advice.authenticated() shouldBe true
    }

    @Test
    fun `currentPath is the request URI used for nav active state`() {
        val request = MockHttpServletRequest().apply { requestURI = "/executions" }

        advice.currentPath(request) shouldBe "/executions"
    }

    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    /** Renders `layouts/default` (via test-stub) with the given chrome variables. */
    private fun renderLayout(authenticated: Boolean, currentPath: String): String {
        val application = JakartaServletWebApplication.buildApplication(MockServletContext())
        val exchange = application.buildExchange(MockHttpServletRequest(), MockHttpServletResponse())
        val context = WebContext(exchange)
        context.setVariable("_csrf", mapOf("token" to "csrf-token-123"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", null)
        context.setVariable("activeTheme", "light")
        context.setVariable("authenticated", authenticated)
        context.setVariable("currentPath", currentPath)
        return engine.process("test-stub", context)
    }

    @Test
    fun `anonymous requests render no nav links and no logout`() {
        val html = renderLayout(authenticated = false, currentPath = "/login")

        html shouldNotContain "app-nav-link"
        html shouldNotContain "Logout"
        html shouldContain "app-brand"
    }

    @Test
    fun `authenticated requests render nav links with the current section active`() {
        val html = renderLayout(authenticated = true, currentPath = "/executions")

        html shouldContain "app-nav-link"
        html shouldContain "Logout"
        Regex("""class="app-nav-link active"[^>]*>Executions""").containsMatchIn(html) shouldBe true
    }
}
