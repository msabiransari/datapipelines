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
    private fun renderLayout(
        authenticated: Boolean,
        currentPath: String,
    ): String {
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
    fun `anonymous requests render no rail, no nav links and no logout`() {
        val html = renderLayout(authenticated = false, currentPath = "/login")

        // 079 §A: the whole rail is gated on `authenticated`, which is also what keeps
        // `nav.app-nav` at zero occurrences for an anonymous request
        // (NavigationGoldenPathBrowserTest). The BRAND survives, in the slim anonymous
        // top bar — the login page has to say whose login it is.
        html shouldNotContain "app-nav-link"
        html shouldNotContain "app-rail\""
        html shouldNotContain "Log out"
        html shouldContain "app-brand"
        html shouldContain "app-topbar-anon"
    }

    @Test
    fun `authenticated requests render the rail with the current section active`() {
        val html = renderLayout(authenticated = true, currentPath = "/executions")

        html shouldContain "app-nav-link"
        html shouldContain "Log out"
        // The highlight is a class AND aria-current, both server-computed for the first
        // paint and both mirrored by shell.js after a boosted swap (079 §A). Asserted on
        // the WHOLE opening tag rather than on an attribute order Thymeleaf is free to
        // change: the two states must land on the SAME anchor, and only that one.
        val active = Regex("""<a[^>]*\bclass="app-nav-link active"[^>]*>""").findAll(html).map { it.value }.toList()
        active.size shouldBe 1
        active.single() shouldContain "data-nav-label=\"Executions\""
        active.single() shouldContain "aria-current=\"page\""
    }

    @Test
    fun `the top bar renders the breadcrumb the nav table derived`() {
        val html = renderLayout(authenticated = true, currentPath = "/executions")

        html shouldContain "app-crumbs"
        // crumbGroup/crumbPage are AppShellAdvice's, so this stub render leaves them null
        // and the crumb falls back to the brand word — the point pinned here is that the
        // top bar's three crumb elements exist for shell.js to write into after a swap.
        html shouldContain "app-crumb-page"
    }
}
