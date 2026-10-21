package co.datapipelines.web.ui

import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/** The real shell engine and catalog context shared by shell and path-parity render tests. */
internal object ShellRenderFixture {
    fun engine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    fun fillList(context: WebContext) =
        with(context) {
            fillLayoutChrome(context)
            setVariable("scopes", setOf("READ"))
            setVariable("dialects", emptyList<String>())
            // #350: the /pipelines page is the flat catalog under its own root.
            setVariable("searching", true)
            setVariable("rootId", PipelineBrowseModel.CATALOG_ROOT_ID)
            setVariable("pipelines", emptyList<Any>())
            setVariable("drafts", emptyMap<Any, Any>())
            setVariable("q", "")
            setVariable("offset", 0)
            setVariable("hasMore", false)
            setVariable("total", 0)
        }

    fun fillLayoutChrome(context: WebContext) =
        with(context) {
            setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
            setVariable("workspaceHeaderFragment", "")
            setVariable("workspaceOptions", emptyList<Any>())
            setVariable("activeWorkspace", "acme")
            setVariable("activeTheme", "saas")
            setVariable("authenticated", true)
            setVariable("currentPath", "/pipelines")
            // #513 — UiWorkspaceAdvice's attribute at runtime; the render tests set it by hand.
            setVariable("searchMaxLength", SearchNeedle.MAX_LENGTH)
            // 127 — SiteOriginAdvice's attribute at runtime; the render tests set it by hand.
            setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
        }

    fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()
}
