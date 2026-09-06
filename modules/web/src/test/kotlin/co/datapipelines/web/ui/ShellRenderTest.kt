package co.datapipelines.web.ui

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * 076 §B — the boosted-navigation shell, pinned at the RENDER.
 *
 * `layouts/default.html` carries the whole contract: `hx-boost="true"` on the
 * nav and on `<main id="app-main">` (shell.js retargets boosted swaps at the
 * main region — hx-target/hx-select are deliberately NOT on those elements,
 * because htmx would inherit them into every partial swap), the five
 * full-navigation routes marked `hx-boost="false"` (/login, /logout, the OIDC
 * redirects, the forced-change gate, file downloads), the #app-progress bar,
 * and the hidden #toast-flash bin INSIDE the swapped region so server-rendered
 * redirect flashes survive the swap.
 *
 * Every assertion renders the real templates through the real layout — a
 * dropped attribute is invisible to controller tests and to the JS tests.
 */
class ShellRenderTest {
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
    fun `every app page renders the boost shell on nav and main`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        html shouldContain "<nav class=\"app-nav\" hx-boost=\"true\">"
        html shouldContain "<main id=\"app-main\" class=\"app-container app-main\" hx-boost=\"true\">"
        html shouldContain "id=\"app-progress\""
        html shouldContain "id=\"toast-flash\" hidden"
        html shouldContain "data-nav-section=\"/pipelines\""
        html shouldContain "/js/shell.js"
    }

    @Test
    fun `logout is a full navigation`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        html shouldContain "class=\"app-logout-form\" hx-boost=\"false\""
    }

    @Test
    fun `login and the OIDC redirect are full navigations`() {
        val html = engine.process("login", webContext().apply { fillLogin() })

        html shouldContain "action=\"/login\" style=\"text-align: left;\" hx-boost=\"false\""
        html shouldContain "href=\"/oauth2/authorization/sso\""
        html shouldContain "hx-boost=\"false\""
    }

    @Test
    fun `the forced-change gate is a full navigation`() {
        val html = engine.process("settings/password", webContext().apply { fillPassword() })

        html shouldContain "id=\"password-change-form\""
        html shouldContain "hx-boost=\"false\""
    }

    @Test
    fun `the editor's result downloads are full navigations`() {
        val html = engine.process("pipelines/editor", webContext().apply { fillEditor() })

        html shouldContain "hx-boost=\"false\" x-bind:href=\"resultPanel.downloadUrl('json')\""
        html shouldContain "hx-boost=\"false\" x-bind:href=\"resultPanel.downloadUrl('csv')\""
        html shouldContain "hx-boost=\"false\" x-bind:href=\"resultPanel.downloadUrl('arrow')\""
    }

    private fun WebContext.fillList() {
        fillLayoutChrome()
        setVariable("scopes", setOf("READ"))
        setVariable("dialects", emptyList<String>())
        setVariable("pipelines", emptyList<Any>())
        setVariable("drafts", emptyMap<Any, Any>())
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 0)
    }

    private fun WebContext.fillLogin() {
        fillLayoutChrome()
        setVariable("localEnabled", true)
        setVariable(
            "providers",
            listOf(mapOf("registrationId" to "sso", "displayName" to "Corporate SSO")),
        )
        setVariable("error", null)
    }

    private fun WebContext.fillPassword() {
        fillLayoutChrome()
        setVariable("mustChange", true)
        setVariable("hasLocalPassword", true)
    }

    private fun WebContext.fillEditor() {
        fillLayoutChrome()
        setVariable("pipelineJson", "{\"id\":\"p1\",\"name\":\"demo\",\"nodes\":[]}")
        setVariable("lifecycleJson", "{\"hasDraft\":false}")
        setVariable("pipelineId", "11111111-1111-1111-1111-111111111111")
        setVariable("hasDraft", false)
        setVariable("draftVersion", null)
        setVariable("draftHash", null)
        setVariable("releasedVersion", 1)
    }

    private fun WebContext.fillLayoutChrome() {
        setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/pipelines")
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
