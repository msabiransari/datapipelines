package co.datapipelines.web.ui

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.support.RequestContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.context.webmvc.SpringWebMvcThymeleafRequestContext
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.spring6.naming.SpringContextVariableNames
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

    @Test
    fun `the sign-in ceremony is the product's own canvas - a token-styled DAG stage beside the panel, provider first`() {
        // 2026-09-08 redesign: the editor's dot-grid ground and a four-engine DAG as an inline
        // SVG (no canvas, no script, no inline style — every colour is a class the stylesheet
        // resolves through tokens), the panel beside it, the identity provider as the primary
        // action with the local form under an "or with email" divider.
        val html =
            engine.process(
                "login",
                webContext().apply {
                    fillLogin()
                    setVariable("providers", listOf(mapOf("registrationId" to "google", "displayName" to "Google")))
                },
            )

        assertAll(
            { html shouldContain "class=\"app-auth-split\"" },
            { html shouldContain "app-auth-stage" },
            { html shouldContain "app-auth-story" },
            { html shouldContain "class=\"app-auth-dag\"" },
            { html shouldContain "Parquet on S3" },
            // The story ends where the data goes: an API a customer calls and a dashboard a team reads.
            { html shouldContain "/api/x/finance/revenue" },
            { html shouldContain "app-auth-screen-api" },
            { html shouldContain "Revenue by customer" },
            { html shouldContain "app-auth-screen-dash" },
            // The first act: the person who asks and the agent that builds, outside the box.
            { html shouldContain "app-auth-person" },
            { html shouldContain "app-auth-agent" },
            { html shouldContain "datapipelines · workspace" },
            { html shouldContain "Continue with Google" },
            { html shouldContain "or with email" },
            { html shouldNotContain "style=\"" },
            { html shouldNotContain "<canvas" },
            // The layout loads htmx and toast.js by src; the ceremony adds no inline script.
            { html shouldNotContain "<script>" },
            // Provider first, then the email form — the order the panel reads in.
            { html.indexOf("Continue with Google") shouldBeLessThan html.indexOf("login-email") },
        )
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
        ).withRoles()

    /**
     * 098 §D — the login form renders EXACTLY ONE `_csrf` hidden input.
     *
     * Measured on the demo stack before the fix (2026-09-08):
     * `curl -s http://localhost:18098/login | grep -c 'name="_csrf"'` answered **2**. One was
     * written by hand in `login.html`; the other is Thymeleaf's, because `th:action` on a
     * `method="post"` form asks Spring's `RequestDataValueProcessor` for the extra hidden fields
     * and Spring Security's `CsrfRequestDataValueProcessor` supplies the token. Two inputs with
     * the same value are harmless today; two with DIFFERENT values would not be, and nothing
     * said which of them the browser would send.
     *
     * The processor is registered in this render context on purpose: without it Thymeleaf writes
     * no hidden field at all, so a test on the plain engine would count 0 and pass for the wrong
     * reason. That is also the FALSIFICATION — remove the automatic half by writing a plain
     * `action="/login"` instead of `th:action`, and this test counts 0. The other half of the
     * falsification is at the wire: `AuthHttpBoundaryTest`'s "a cookie-authenticated state change
     * with the matching double-submit token succeeds" is the case that fails 403 when the token
     * the form carries is not the one the filter expects.
     */
    @Test
    fun `the login form renders exactly one csrf hidden input`() {
        val html = engine.process("login", securityWebContext().apply { fillLogin() })

        Regex("name=\"_csrf\"").findAll(html).count() shouldBe 1
        // …and it is the real token, not an empty attribute.
        html shouldContain "value=\"tok-098\""
    }

    /**
     * A render context wired the way the running application is: a `RequestDataValueProcessor`
     * bean reachable from the ServletContext, and a `CsrfToken` on the request. This is what
     * makes `th:action` emit the hidden field, and therefore what makes the count above mean
     * anything.
     */
    private fun securityWebContext(): WebContext {
        val servletContext = MockServletContext()
        val applicationContext = GenericWebApplicationContext(servletContext)
        applicationContext.beanFactory.registerSingleton("requestDataValueProcessor", CsrfRequestDataValueProcessor())
        applicationContext.refresh()
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, applicationContext)

        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val token: CsrfToken = DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok-098")
        request.setAttribute(CsrfToken::class.java.name, token)
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(servletContext)
                    .buildExchange(request, response),
            )
        // The two variables `ThymeleafView` sets in production, and the ONLY route by which
        // `th:action` reaches a RequestDataValueProcessor: `SpringContextUtils.getRequestContext`
        // reads `thymeleafRequestContext`, and `RequestDataValueProcessorUtils.getExtraHiddenFields`
        // returns null without it — a bare `engine.process()` sets neither, and the form then
        // renders with NO hidden field at all, which would make this test pass at 0 for the
        // wrong reason. (Verified by reading the two classes out of thymeleaf-spring6 3.1.5
        // rather than assuming: the lookups are by variable name, not by servlet context.)
        context.setVariable(
            ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
            ThymeleafEvaluationContext(applicationContext, null),
        )
        context.setVariable(
            SpringContextVariableNames.THYMELEAF_REQUEST_CONTEXT,
            SpringWebMvcThymeleafRequestContext(
                RequestContext(request, response, servletContext, mutableMapOf()),
                request,
            ),
        )
        return context
    }
}
