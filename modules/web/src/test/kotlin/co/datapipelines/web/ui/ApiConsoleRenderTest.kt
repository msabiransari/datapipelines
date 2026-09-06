package co.datapipelines.web.ui

import co.datapipelines.mcp.McpToolCatalog
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
import java.time.Instant

/**
 * 079 §C — the API section, pinned at the RENDER.
 *
 * The screen closes T139 and the owner's "I don't see any API menu": published endpoints,
 * the keys that may call them and the MCP connection had no page anywhere in the app. What
 * this test protects is not the layout but the four claims the page makes about the system,
 * each of which is the kind that goes quietly wrong:
 *
 *  - **The tool count is derived, never written down.** A literal here would drift from
 *    `tools/list` and would be believed by whoever pasted the config.
 *  - **The `endpoint` key kind shows its BINDINGS, not its scopes.** It has none by design
 *    (auth §7.7), and an empty scope cell reads as "this key can do nothing".
 *  - **There is no "Calls 24 h" column.** Nothing records endpoint serves, so the mock's
 *    column has no truthful source; the page says so rather than inventing one.
 *  - **The version shown is the RELEASED one.** An endpoint pins a pipeline, not a version.
 */
class ApiConsoleRenderTest {
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

    private fun render(fill: WebContext.() -> Unit): String {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        context.setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", "acme")
        context.setVariable("activeTheme", "saas")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", "/api-console")
        context.setVariable("endpoints", emptyList<Any>())
        context.setVariable("apiKeys", emptyList<Any>())
        context.setVariable("mcpUrl", "https://app.example/mcp")
        context.setVariable("mcpHeader", "DP-API-Key")
        context.setVariable("keyPrefix", "dpk_")
        context.setVariable(
            "mcpConfigJson",
            "{\n  \"datapipelines\": {\n    \"url\": \"https://app.example/mcp\",\n" +
                "    \"headers\": { \"DP-API-Key\": \"dpk_…\" }\n  }\n}",
        )
        context.setVariable("mcpToolCount", McpToolCatalog.NAMES.size)
        context.setVariable("defaultTimeoutSeconds", 30)
        context.setVariable("canAuthor", true)
        context.fill()
        return engine.process("api/console", context)
    }

    private fun endpoint(
        url: String = "/api/x/nyc/mobility/briefing",
        displayName: String = "Mobility briefing",
        path: String? = "nyc/mobility/briefing",
        released: Int? = 3,
        timeout: Int = 30,
        bound: Int = 2,
        enabled: Boolean = true,
    ) = ApiConsoleController.EndpointRow(url, listOf(false), displayName, path, released, timeout, bound, enabled)

    @Test
    fun `the three cards render, and the tool count is the catalog's size`() {
        val html = render { }

        html shouldContain "Published endpoints"
        html shouldContain "API keys"
        html shouldContain "MCP server"
        // Derived at request time from McpToolCatalog.NAMES — never a literal. If someone
        // adds a tool, this number moves with it, which is the whole contract.
        html shouldContain "${McpToolCatalog.NAMES.size} tools"
        html shouldContain "DP-API-Key"
    }

    @Test
    fun `the endpoints table has no calls column - nothing records endpoint serves`() {
        val html = render { setVariable("endpoints", listOf(endpoint())) }

        // The mock's "Calls 24h" column has no truthful source: no counter column exists on
        // published_endpoints, no Micrometer counter is registered for a serve, and the only
        // trace is an append-only `endpoint.served` audit row whose single reader is a
        // per-execution boolean. The page states the absence instead of filling the column.
        html shouldNotContain "Calls 24h"
        html shouldNotContain "Calls 24 h"
        html shouldContain "Call counts are not recorded"
    }

    @Test
    fun `an endpoint renders GET, its path, the RELEASED version and its bound-key chip`() {
        val html = render { setVariable("endpoints", listOf(endpoint())) }

        html shouldContain ">GET<"
        html shouldContain "/api/x/nyc/mobility/briefing"
        html shouldContain "Mobility briefing"
        html shouldContain "v3"
        html shouldContain "2 bound"
        html shouldContain "30 s"
    }

    @Test
    fun `an endpoint whose pipeline has no release says so, and an unbound one is flagged`() {
        val html =
            render {
                setVariable("endpoints", listOf(endpoint(released = null, bound = 0, enabled = false)))
            }

        // Three separate truths, none of them papered over: an endpoint can outlive the
        // release it was published against, "no bindings" is not "nobody can call it" (a
        // workspace-pinned user key with `execute` still may), and a disabled endpoint is
        // still listed.
        html shouldContain "no released version"
        html shouldContain ">none<"
        html shouldContain "Disabled"
    }

    @Test
    fun `a user key shows scopes and an endpoint key shows the paths it is bound to`() {
        val html =
            render {
                setVariable(
                    "apiKeys",
                    listOf(
                        ApiConsoleController.ApiKeyRow("agent", "user", "dpk_A7Qx", listOf("execute", "read"), emptyList(), null),
                        // MIDDAY UTC on purpose: `#temporals.format` renders an Instant in the SERVER's
                        // default zone (pre-existing app-wide behaviour — /settings has always shown
                        // last-sign-in that way), so a midnight fixture renders as the previous day
                        // wherever the build runs west of Greenwich.
                        ApiConsoleController.ApiKeyRow(
                            "serve",
                            "endpoint",
                            "dpk_Kf3m",
                            emptyList(),
                            listOf("/nyc/mobility"),
                            Instant.parse("2027-06-15T12:00:00Z"),
                        ),
                    ),
                )
            }

        html shouldContain "dpk_A7Qx…"
        html shouldContain ">read<"
        html shouldContain ">execute<"
        // An endpoint key carries NO scopes (auth §7.7); its authority is its bindings, and
        // that is what the cell must show.
        html shouldContain "/nyc/mobility"
        html shouldContain "2027-06-15"
        html shouldContain ">never<"
    }

    @Test
    fun `the empty states name the way in rather than saying nothing`() {
        val html = render { }

        html shouldContain "No published endpoints"
        html shouldContain "endpoints_create"
        html shouldContain "No API keys"
    }

    @Test
    fun `management links are hidden from a principal without author scope`() {
        val withAuthor = render { }
        withAuthor shouldContain "Manage API keys"

        val readOnly = render { setVariable("canAuthor", false) }
        // Actions a principal lacks scope for are NOT RENDERED, not merely disabled
        // (ui-screens.md §4 preamble). The server re-checks regardless.
        readOnly shouldNotContain "Manage API keys"
    }

    @Test
    fun `the page carries no inline style attribute`() {
        // §D widened the inline-style ban to every app template with an EMPTY allowlist;
        // a new screen is exactly where the habit comes back.
        render { setVariable("endpoints", listOf(endpoint())) }
        val source =
            javaClass.classLoader
                .getResourceAsStream("templates/api/console.html")!!
                .readBytes()
                .decodeToString()
        source shouldNotContain "style=\""
    }
}
