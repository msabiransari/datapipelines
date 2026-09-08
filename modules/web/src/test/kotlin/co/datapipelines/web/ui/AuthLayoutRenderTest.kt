package co.datapipelines.web.ui

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * 090 §C — `layouts/auth.html`, the ceremony layout, pinned at the RENDER.
 *
 * The defect this replaces: `login.html` decorated with `layouts/default`, and the layout
 * renders the rail, the top bar and the workspace switcher whenever `authenticated` is
 * true. A signed-in visitor who opened `/login` therefore got the sign-in form INSIDE a
 * working app shell (owner's walk, 2026-09-07). `UiController` now bounces that visitor;
 * this test pins the other half — that the ceremony screens cannot render the shell even
 * if someone hands the layout an authenticated model, which is exactly what an
 * `authenticated = true` context below is.
 *
 * The three assertions are the layout's contract, not its appearance:
 *   - no `nav.app-nav` — there is nothing to navigate to before the session exists, and
 *     `NavigationGoldenPathBrowserTest` already reads a zero count of it signed out;
 *   - no `id="app-main"` — that id IS the boosted-swap target (076 §B). A ceremony screen
 *     that carries it can be swapped into an app page and vice versa;
 *   - no `hx-boost` anywhere — the same rule stated at the attribute rather than the id,
 *     so a link added to the layout later cannot re-arm boosting by itself.
 */
class AuthLayoutRenderTest {
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

    @Test
    fun `the login page renders the auth layout, not the app shell`() {
        val html = engine.process("login", webContext().apply { fillLogin() })

        html shouldContain "class=\"app-auth\""
        html shouldContain "app-auth-brand"
        html shouldNotContain "app-nav"
        html shouldNotContain "id=\"app-main\""
        html shouldNotContain "app-shell"
        html shouldNotContain "hx-boost=\"true\""
    }

    /**
     * The signed-in model is the one that used to produce the shell. Rendering it here is
     * the falsification: with `login.html` pointed back at `layouts/default`, this test
     * goes red on the first assertion and the three below it.
     */
    @Test
    fun `the login page renders no shell even for an authenticated model`() {
        val html = engine.process("login", webContext().apply { fillLogin(authenticated = true) })

        html shouldNotContain "app-rail"
        html shouldNotContain "app-topbar"
        html shouldNotContain "app-ws-switch"
        html.indexOf("id=\"login-email\"") shouldBeGreaterThan -1
    }

    /**
     * The forced-change gate is a ceremony; a voluntary password change from Settings is
     * not. TWO page templates over one card partial, chosen by `UserSettingsController` —
     * so both branches are asserted here, and `UserSettingsControllerTest` pins that the
     * controller picks between them on `must_change_password`. (The first attempt was one
     * template with a `__${'$'}{...}__` conditional decorator; Thymeleaf resolves that while
     * PARSING and caches the result, so whichever request arrived first froze the layout
     * for all of them. This test caught it.)
     */
    @Test
    fun `the forced password change renders the auth layout`() {
        val html = engine.process("settings/password-forced", webContext().apply { fillPassword(mustChange = true) })

        html shouldContain "class=\"app-auth\""
        html shouldNotContain "id=\"app-main\""
        html shouldContain "id=\"password-change-form\""
    }

    @Test
    fun `a voluntary password change from settings keeps the app shell`() {
        val html = engine.process("settings/password", webContext().apply { fillPassword(mustChange = false) })

        html shouldContain "id=\"app-main\""
        html shouldContain "app-nav"
        html shouldNotContain "class=\"app-auth\""
    }

    /**
     * The htmx pair the ceremony still needs: `settings/password` posts through `hx-post`,
     * and its success arrives as an out-of-band toast. Without the CSRF header on <body>
     * every such post 403s, and without the stack the toast has nowhere to land — both
     * failures are silent on the screen a locked-out user is stuck on.
     */
    @Test
    fun `the auth layout carries the csrf header pair and the toast stack`() {
        val html = engine.process("settings/password-forced", webContext().apply { fillPassword(mustChange = true) })

        html shouldContain "DP-CSRF-Token"
        html shouldContain "id=\"toast\""
        html shouldContain "htmx.min.js"
    }

    /** The theme has to survive the layout swap: a ceremony screen in the wrong theme is a bug. */
    @Test
    fun `the auth layout resolves the active theme stylesheet and the app stylesheet`() {
        val html = engine.process("login", webContext().apply { fillLogin() })

        html shouldContain "/vendor/design-system/themes/saas.css"
        html shouldContain "/css/app.css"
        html.contains("data-theme=\"saas\"") shouldBe true
    }

    private fun WebContext.fillLogin(authenticated: Boolean = false) {
        fillChrome(authenticated)
        setVariable("providers", emptyList<Any>())
        setVariable("localEnabled", true)
    }

    private fun WebContext.fillPassword(mustChange: Boolean) {
        fillChrome(authenticated = true)
        setVariable("mustChange", mustChange)
        setVariable("hasLocalPassword", true)
    }

    private fun WebContext.fillChrome(authenticated: Boolean) {
        setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", authenticated)
        setVariable("currentPath", "/login")
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
