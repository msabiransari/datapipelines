package co.datapipelines.web.ui

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
 * 179 (D17) — the `/api-keys` page, pinned at the render. The workspace's `endpoint` keys,
 * admin-managed: what this test protects is the claims the page makes about the system:
 *
 *  - **The table names the creator.** The page lists the whole workspace's keys, so "created
 *    by" is a column, not an assumption.
 *  - **A key's authority is its ASSOCIATIONS.** The bound paths render in the wildcard form
 *    074 defined (`/nyc` covering everything beneath it); the ROOT reads as the whole-tree
 *    wildcard, never a double-slash.
 *  - **There is no `user` kind anywhere** — not a column, not a form choice (D16).
 *  - **The verbs are role-guarded** even though the route already refuses: the fragment must
 *    be safe rendered off its route (the sweep's rule, pinned here by name).
 *  - **Every timestamp is relative in the cell and absolute on hover.**
 */
class ApiKeysPageRenderTest {
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
            ).withRoles()
        context.setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", "acme")
        context.setVariable("activeTheme", "saas")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", "/api-keys")
        context.setVariable("keys", emptyList<Any>())
        context.setVariable("kindChoices", ApiKeyForm.kindChoices(isAdmin = true))
        context.setVariable("expiryChoices", ApiKeyForm.EXPIRY_CHOICES)
        context.setVariable("expiryCustomWire", ApiKeyForm.CUSTOM)
        context.setVariable("bindingNodes", ApiKeyForm.bindingNodes(listOf("/nyc/mobility/briefing")))
        context.fill()
        return engine.process("api/keys", context)
    }

    private fun row(
        name: String = "ci",
        kind: String = "endpoint",
        createdBy: String = "Alice",
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
        prefix = "dpk_A7QxKF2MPLQR".take(ApiKeyRows.PREFIX_CHARS) + "…",
        createdBy = createdBy,
        actsAs = "ci (API key)",
        role = "api caller",
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

    @Test
    fun `a key row renders its prefix, creator, associations and timestamps`() {
        val html =
            render {
                setVariable(
                    "keys",
                    listOf(
                        row(
                            boundPaths = listOf("/nyc/mobility"),
                            expiresRelative = "in 84 days",
                            expiresAbsolute = "2026-12-01 00:00 UTC",
                            lastUsedRelative = "2 hours ago",
                            lastUsedAbsolute = "2026-09-08 07:00 UTC",
                        ),
                    ),
                )
            }

        // D16's 12-character prefix — dpk_ plus eight — is the table's too.
        html shouldContain "dpk_A7QxKF2M…"
        html shouldContain ">Alice<"
        html shouldContain "/nyc/mobility/**"
        html shouldContain "title=\"2026-09-05 09:00 UTC\""
        html shouldContain ">3 days ago<"
        html shouldContain "title=\"2026-12-01 00:00 UTC\""
        html shouldContain ">in 84 days<"
        html shouldContain "title=\"2026-09-08 07:00 UTC\""
        html shouldContain ">2 hours ago<"
        // The verbs: association editor and delete, both present for a live key.
        html shouldContain "Edit associations"
        html shouldContain "data-verb=\"key-revoke\""
        html shouldContain "hx-post=\"/partials/api-keys/dpk_A7QxKF2MPLQR/bindings\""
    }

    @Test
    fun `the root association reads star-star, never double-slash-star-star`() {
        val html = render { setVariable("keys", listOf(row(boundPaths = listOf("/")))) }

        html shouldContain ">/**<"
        html shouldNotContain "//**"
    }

    @Test
    fun `a key with no associations says so - authorises nothing, not nothing at all`() {
        val html = render { setVariable("keys", listOf(row())) }

        html shouldContain "none — authorises nothing"
    }

    @Test
    fun `a deleted key keeps its row and loses its verbs`() {
        val html = render { setVariable("keys", listOf(row(isRevoked = true))) }

        html shouldContain ">deleted<"
        html shouldNotContain "hx-delete"
        html shouldNotContain "Edit associations"
    }

    /**
     * #215 (owner ruling 2026-09-24, `server_key.revoke`): a server key's Delete is a super
     * admin's. A workspace admin still sees the row — it is the workspace's key — but not a
     * verb the service would refuse (114: render only what the server accepts), while their
     * endpoint keys keep theirs.
     */
    @Test
    fun `a server key's Delete is drawn for a super admin only - an endpoint key's for the workspace admin too`() {
        fun deletes(superAdmin: Boolean): Map<String, Boolean> =
            listOf("endpoint", "server").associateWith { kind ->
                val html =
                    render {
                        withRoles(canAdminWorkspace = true, isSuperAdmin = superAdmin, roleLabel = "workspace admin")
                        setVariable("keys", listOf(row(kind = kind)))
                    }
                html.contains("data-verb=\"key-revoke\"")
            }

        deletes(superAdmin = false) shouldBe mapOf("endpoint" to true, "server" to false)
        deletes(superAdmin = true) shouldBe mapOf("endpoint" to true, "server" to true)
    }

    @Test
    fun `no user kind is offered or rendered - the MCP key is minted at login, never here`() {
        val html = render { setVariable("keys", listOf(row())) }

        // D16: the form offers endpoint (API key) and, to a super admin, server. `user` is
        // refused by the service for every role; a form that offered it would be teaching a
        // model the system no longer has.
        html shouldNotContain "value=\"user\""
        html shouldContain "value=\"endpoint\""
        html shouldContain "value=\"server\""
        html shouldNotContain "name=\"scope\""
    }

    @Test
    fun `the create form's fields are in the page's order - Kind, Name, Expiry, Associations`() {
        val html = render { }

        val order =
            listOf(
                "name=\"kind\"",
                "id=\"key-name\"",
                "id=\"key-expiry\"",
                "id=\"key-bindings-field\"",
            ).map { html.indexOf(it) }

        withClue("one of the four fields is missing: $order") { order.none { it < 0 } shouldBe true }
        withClue("the fields render out of order: $order") { order shouldBe order.sorted() }
    }

    @Test
    fun `the page carries no inline style attribute`() {
        val source =
            javaClass.classLoader
                .getResourceAsStream("templates/api/keys.html")!!
                .readBytes()
                .decodeToString()
        source shouldNotContain "style=\""
    }
}
