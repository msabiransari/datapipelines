package co.datapipelines.web.ui

import co.datapipelines.mcp.McpToolCatalog
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
 * 079 §C — the API section, pinned at the RENDER. Since 179 the console is READ-ONLY: the
 * keys card and its minting modal moved to `/api-keys` (D17, pinned by
 * [ApiKeysPageRenderTest]), and this suite protects what the console still claims:
 *
 *  - **The tool count is derived, never written down.** A literal here would drift from
 *    `tools/list` and would be believed by whoever pasted the config.
 *  - **The endpoint row names its ASSOCIATED API keys.** "How many" was the pre-179 answer;
 *    the operator's question is "which".
 *  - **There is no "Calls 24 h" column.** Nothing records endpoint serves, so the mock's
 *    column has no truthful source; the page says so rather than inventing one.
 *  - **The version shown is the RELEASED one.** An endpoint pins a pipeline, not a version.
 *  - **The manage link renders for the roles `MANAGE_API_KEYS` admits, and no other.**
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

    /**
     * 179 (D17) — the keys card is GONE from the console: no table, no modal, no create verb.
     * What remains is the read-only inventory and, for the roles that hold MANAGE_API_KEYS,
     * the link to where the verbs live.
     */
    @Test
    fun `the console carries no key management - and the manage link follows MANAGE_API_KEYS`() {
        val admin = render { }
        admin shouldNotContain "id=\"keys-table\""
        admin shouldNotContain "id=\"key-modal\""
        admin shouldNotContain "data-verb=\"key-create\""
        admin shouldContain "Manage API keys"
        admin shouldContain "href=\"/api-keys\""

        val viewer =
            render {
                withRoles(canAuthor = false, canPromote = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "viewer")
            }
        viewer shouldNotContain "href=\"/api-keys\""
        viewer shouldNotContain ">Manage<"
    }

    private fun render(fill: WebContext.() -> Unit): String {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles()
        context.setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", "acme")
        context.setVariable("activeTheme", "saas")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", "/api-console")
        context.setVariable("endpoints", emptyList<Any>())
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
        context.fill()
        return engine.process("api/console", context)
    }

    private fun endpoint(
        url: String = "/api/nyc/v1/mobility/briefing",
        displayName: String = "Mobility briefing",
        path: String? = "nyc/mobility/briefing",
        served: Int? = 3,
        draft: Boolean = false,
        timeout: Int = 30,
        boundKeyNames: List<String> = listOf("ci", "nightly"),
        enabled: Boolean = true,
    ) = ApiConsoleController.EndpointRow(url, listOf(false), displayName, path, served, draft, timeout, boundKeyNames, enabled)

    @Test
    fun `the two cards render, and the tool count is the catalog's size`() {
        val html = render { }

        html shouldContain "Published endpoints"
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
    fun `an endpoint renders GET, its path, the RELEASED version and its associated keys by name`() {
        val html = render { setVariable("endpoints", listOf(endpoint())) }

        html shouldContain ">GET<"
        html shouldContain "/api/nyc/v1/mobility/briefing"
        html shouldContain "Mobility briefing"
        html shouldContain "v3"
        // 179: names, not a count — "which keys" is the question.
        html shouldContain ">ci<"
        html shouldContain ">nightly<"
        html shouldContain "30 s"
    }

    @Test
    fun `an endpoint whose pointer names a draft says draft, not v-number alone`() {
        // D63: in development the endpoint serves the draft the pointer names; the console
        // must not read as if a release were live.
        val html = render { setVariable("endpoints", listOf(endpoint(served = 4, draft = true))) }

        html shouldContain "v4 · draft"
    }

    @Test
    fun `an endpoint whose pipeline has no release says so, and one with no associated keys is flagged`() {
        val html =
            render {
                setVariable("endpoints", listOf(endpoint(served = null, boundKeyNames = emptyList(), enabled = false)))
            }

        // Three separate truths, none of them papered over: an endpoint can outlive the
        // release it was published against, "no associations" is not "nobody can call it" (a
        // workspace-pinned user key with `execute` still may), and a disabled endpoint is
        // still listed.
        html shouldContain "nothing to serve"
        html shouldContain ">none<"
        html shouldContain "Disabled"
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
