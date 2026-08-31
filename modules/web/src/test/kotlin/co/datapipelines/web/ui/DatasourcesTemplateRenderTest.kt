package co.datapipelines.web.ui

import co.datapipelines.datasources.Datasource
import co.datapipelines.typesystem.Dialect
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
 * Render-level guard for the 028 datasources SPA table + toast notifications.
 * Unit tests pin the controller's model; THIS class pins the swap contracts the
 * browser depends on — the two defects that made the screen break its layout
 * were both contract failures invisible to controller tests:
 *
 *  1. The list fragment's ROOT must carry `id="datasource-list-wrapper"`. The
 *     page's placeholder div is replaced by the fragment on first render; when
 *     the id lived on that placeholder, every later pager/filter swap targeted
 *     an element that no longer existed and htmx failed silently.
 *  2. The Test button must NOT swap rows. It appends a server-rendered toast to
 *     the layout's `#toast` stack (`hx-swap="beforeend"`) — the old contract
 *     swapped one row for a two-row fragment and then fetched the WHOLE list
 *     into a `<tr>` ("Back to list"), which the browser's table parser tore
 *     apart (the 028 screenshot's nested headers and duplicated pagers).
 *
 * Engine infra mirrors TemplateHtmxRenderAuditTest (same WebContext shape).
 */
class DatasourcesTemplateRenderTest {
    @Test
    fun `list fragment renders the stable swap root and the toast-delivered test button`() {
        val html = engine().process("partials/datasources", context().apply { fillListModel() })

        html shouldContain "id=\"datasource-list-wrapper\""
        html shouldContain "hx-post=\"/partials/datasources/pg-prod/test\""
        html shouldContain "hx-target=\"#toast\""
        html shouldContain "hx-swap=\"beforeend\""
        html shouldContain "hx-disabled-elt=\"this\""
        // The dead View button (hx-get of the REST JSON endpoint, swapped raw into
        // the button) is gone — §4.5's row action is Test.
        html shouldNotContain "/api/v1/datasources"
        // The pager keeps the active filter in its URLs.
        html shouldContain "q=trip"
        html shouldContain "dialect=POSTGRES"
    }

    @Test
    fun `toast fragment renders the design-system toast with the model's variant`() {
        val html =
            engine().process(
                "partials/toast",
                context().apply {
                    setVariable("variant", "success")
                    setVariable("title", "Connection succeeded")
                    setVariable("message", "pg-prod — Server version: 15.4")
                },
            )

        html shouldContain "ds-toast ds-toast-success"
        html shouldContain "ds-toast-close"
        html shouldContain "Connection succeeded"
        html shouldContain "pg-prod — Server version: 15.4"
    }

    @Test
    fun `datasources page wires the spa filter controls and the toast stack`() {
        val html = engine().process("datasources/list", context().apply { fillPageModel() })

        // Filter controls re-fetch ONLY the fragment, addressed by id (the register
        // modal carries its own name="dialect" — name-based includes would cross-wire).
        html shouldContain "id=\"ds-filter-q\""
        html shouldContain "hx-get=\"/partials/datasources\""
        html shouldContain "hx-trigger=\"input changed delay:300ms, search\""
        html shouldContain "hx-include=\"#ds-filter-dialect\""
        html shouldContain "hx-include=\"#ds-filter-q\""
        html shouldContain "id=\"ds-filter-spinner\""
        // The list still renders inside the page, and the layout serves the stack
        // plus the toast lifecycle script.
        html shouldContain "id=\"datasource-list-wrapper\""
        html shouldContain "id=\"toast\" class=\"ds-toast-stack\""
        html shouldContain "/js/toast.js"
    }

    private fun WebContext.fillListModel() {
        setVariable("datasources", listOf(datasource("pg-prod"), datasource("sample-trips")))
        setVariable("q", "trip")
        setVariable("selectedDialect", "POSTGRES")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 42)
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun WebContext.fillPageModel() {
        // Layout chrome (the UiWorkspaceAdvice set, per UiLayoutChromeAdviceTest).
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/datasources")
        // DatasourceUiController's model.
        setVariable("dialects", listOf("POSTGRES", "MYSQL"))
        setVariable("selectedDialect", "")
        setVariable("scopes", setOf("ADMIN"))
        setVariable("isAdmin", true)
        setVariable("memberDatasourcesEnabled", false)
        setVariable("canRegister", true)
        setVariable("bindingHint", "Bound to your active workspace: acme")
        setVariable("datasources", listOf(datasource("pg-prod")))
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 1)
    }

    private fun datasource(name: String) =
        Datasource(
            name = name,
            displayName = name,
            description = null,
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db:5432/app",
            username = "readonly",
        )

    private fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

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
}
