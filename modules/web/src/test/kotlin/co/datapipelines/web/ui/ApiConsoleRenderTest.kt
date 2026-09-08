package co.datapipelines.web.ui

import co.datapipelines.auth.Scope
import co.datapipelines.mcp.McpToolCatalog
import io.kotest.assertions.withClue
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
        context.setVariable("keys", emptyList<Any>())
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
        // 091 — the form's options come from ApiKeyForm on both sides (page and partial),
        // so the fixtures ARE the production tables rather than a second copy of them.
        context.setVariable("kindChoices", ApiKeyForm.kindChoices(isAdmin = false))
        context.setVariable("scopeChoices", ApiKeyForm.scopeChoices(setOf(Scope.AUTHOR)))
        context.setVariable("expiryChoices", ApiKeyForm.EXPIRY_CHOICES)
        context.setVariable("expiryCustomWire", ApiKeyForm.CUSTOM)
        context.setVariable("bindingNodes", ApiKeyForm.bindingNodes(listOf("/nyc/mobility/briefing")))
        context.fill()
        return engine.process("api/console", context)
    }

    @Suppress("LongParameterList") // a row, spelled out
    private fun row(
        name: String = "agent",
        kind: String = "user",
        scopes: List<String> = listOf("read"),
        boundPaths: List<String> = emptyList(),
        createdRelative: String = "3 days ago",
        createdAbsolute: String = "2026-09-05 09:00 UTC",
        lastUsedRelative: String = "never",
        lastUsedAbsolute: String? = null,
        expiresRelative: String = "never",
        expiresAbsolute: String? = null,
        isRevoked: Boolean = false,
        isExpired: Boolean = false,
    ) = ApiKeyRows.Row(
        id = "dpk_A7QxKF2MPLQR",
        name = name,
        kind = kind,
        prefix = "dpk_A7QxKF2MPLQR".take(8) + "…",
        scopes = scopes,
        boundPaths = boundPaths,
        createdRelative = createdRelative,
        createdAbsolute = createdAbsolute,
        lastUsedRelative = lastUsedRelative,
        lastUsedAbsolute = lastUsedAbsolute,
        expiresRelative = expiresRelative,
        expiresAbsolute = expiresAbsolute,
        isRevoked = isRevoked,
        isExpired = isExpired,
    )

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
    fun `a user key shows scopes, an endpoint key its bindings, a server key its route family`() {
        val html =
            render {
                setVariable(
                    "keys",
                    listOf(
                        row(name = "agent", kind = "user", scopes = listOf("execute", "read")),
                        row(name = "serve", kind = "endpoint", boundPaths = listOf("/nyc/mobility")),
                        row(name = "uat receiver", kind = "server"),
                    ),
                )
            }

        html shouldContain "dpk_A7Qx…"
        html shouldContain ">read<"
        html shouldContain ">execute<"
        // An endpoint key carries NO scopes (auth §7.7); its authority is its bindings, and
        // that is what the cell must show — in the `/nyc/**` form 074 defined.
        html shouldContain "/nyc/mobility/**"
        // The ROOT binding reads `/**`, never `//**`: it authorises the whole tree.
        render { setVariable("keys", listOf(row(kind = "endpoint", boundPaths = listOf("/")))) } shouldContain ">/**<"
        // A server key has neither, and an empty cell would read as "this key can do nothing".
        html shouldContain "promotion routes"
    }

    @Test
    fun `every timestamp is relative in the cell and absolute on hover`() {
        // Both questions are real: "is this about to expire?" at a glance, "exactly when?" on
        // hover. A cell with only one of them sends the reader to the database.
        val html =
            render {
                setVariable(
                    "keys",
                    listOf(
                        row(
                            createdRelative = "3 days ago",
                            createdAbsolute = "2026-09-05 09:00 UTC",
                            expiresRelative = "in 84 days",
                            expiresAbsolute = "2026-12-01 00:00 UTC",
                            lastUsedRelative = "2 hours ago",
                            lastUsedAbsolute = "2026-09-08 07:00 UTC",
                        ),
                    ),
                )
            }

        html shouldContain "title=\"2026-09-05 09:00 UTC\""
        html shouldContain ">3 days ago<"
        html shouldContain "title=\"2026-12-01 00:00 UTC\""
        html shouldContain ">in 84 days<"
        html shouldContain "title=\"2026-09-08 07:00 UTC\""
        html shouldContain ">2 hours ago<"
    }

    @Test
    fun `a dead key keeps its row and loses its revoke affordance`() {
        val revoked = render { setVariable("keys", listOf(row(isRevoked = true))) }
        revoked shouldContain ">revoked<"
        revoked shouldNotContain "hx-delete"

        val expired = render { setVariable("keys", listOf(row(isExpired = true))) }
        expired shouldContain ">expired<"
        expired shouldNotContain "hx-delete"

        // …and a live one HAS it, or the two assertions above would pass on a table with no
        // revoke button at all.
        render { setVariable("keys", listOf(row())) } shouldContain "hx-delete"
    }

    @Test
    fun `the form's fields are in the owner's order - Kind, Scope, Name, Expiry, Bindings`() {
        val html = render { }

        val order =
            listOf(
                "name=\"kind\"",
                "id=\"key-scope\"",
                "id=\"key-name\"",
                "id=\"key-expiry\"",
                "id=\"key-bindings-field\"",
            ).map { html.indexOf(it) }

        withClue("one of the five fields is missing: $order") { order.none { it < 0 } shouldBe true }
        withClue("the fields render out of order: $order") { order shouldBe order.sorted() }
    }

    @Test
    fun `scope and bindings are conditional on the kind, and the server kind is admin-only`() {
        val forMember = render { }
        // The two conditional fields exist for the kinds that take them…
        forMember shouldContain "id=\"key-scope-field\""
        forMember shouldContain "id=\"key-bindings-field\""
        // …and each kind card declares which, so the script has no table of its own to drift.
        forMember shouldContain "data-scope=\"true\""
        forMember shouldContain "data-bindings=\"true\""
        // A non-admin is not offered a kind the server would refuse (§7.7: minting a server
        // key is admin-only). The UI filter is convenience; ApiKeyService is the guard.
        forMember shouldNotContain "Server key"

        val forAdmin = render { setVariable("kindChoices", ApiKeyForm.kindChoices(isAdmin = true)) }
        forAdmin shouldContain "Server key"
    }

    @Test
    fun `the scope select states capabilities, not HTTP verbs`() {
        val html = render { }

        html shouldContain "list and inspect"
        html shouldContain "run released pipelines"
        // The owner asked for verb scopes and the answer is no: `execute` is a POST that
        // writes nothing, and an MCP tool call has no verb to split on.
        html shouldNotContain ">GET — "
        html shouldNotContain "POST scope"
    }

    @Test
    fun `the binding picker offers the literal published prefixes and the whole tree`() {
        val html =
            render {
                setVariable("bindingNodes", ApiKeyForm.bindingNodes(listOf("/nyc/revenue/{borough}")))
            }

        html shouldContain "/** (everything)"
        html shouldContain "/nyc/**"
        html shouldContain "/nyc/revenue/**"
        // A node with a {variable} segment is NOT offered: the authorizer walks the concrete
        // request path's ancestors, so binding one would authorise nothing at all.
        html shouldNotContain "{borough}"
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
