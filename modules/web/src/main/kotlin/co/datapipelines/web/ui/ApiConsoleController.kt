package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.auth.ApiKeyCredential
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.mcp.McpToolCatalog
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.config.EndpointsProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Instant

/**
 * 079 §C — the API section (T139, and the owner's "I don't see any API menu", which was
 * literally true: published endpoints, the keys that may call them and the MCP connection
 * had no screen anywhere in the app).
 *
 * ## The route
 *
 * `/api-console`, NOT anything under `/api`. That prefix is the programmatic surface and is
 * split in two — the `/api/v1` REST envelope and the `/api/x` published endpoints this page
 * lists — and `ui-screens.md §2.1`'s three-URL-space rule says a page never lives in the JSON
 * space. (A glob in a KDoc opens a nested block comment: Kotlin nests them, so the prefixes
 * are written without their wildcards here.) `/api-console` cannot collide with either: Spring matches whole path
 * segments, and `api-console` is a different first segment from `api`. It needs no
 * `SecurityConfig` entry — that chain's `permitAll` list is explicit and everything else is
 * `.anyRequest().authenticated()`.
 *
 * ## The scope
 *
 * `READ_RESOURCES` (`read`), the same floor `EndpointsController.list` uses. `@RequiredScope`
 * is enforced on ANY path once declared — the `/api` and `/partials` prefixes only govern
 * where an UNannotated handler is default-denied — so this is a real gate, not decoration,
 * and it is also what refuses an `endpoint`-kind key here (that key kind carries no scopes
 * and reaches only the published-endpoint surface, auth §7.7).
 *
 * ## Read-only about ENDPOINTS, the management screen for KEYS (091)
 *
 * Publishing and binding an existing endpoint stay on REST/MCP (074 step 6's intent) — the
 * endpoints card is a truthful inventory plus links. Keys are different: 091 moves their
 * issuance and revocation HERE, because "the keys that may call this" and "the endpoints they
 * may call" are one question, and `/settings/api-keys` kept them a page apart. Settings now
 * holds nothing but a link to this screen.
 *
 * The key surface is `MANAGE_OWN_API_KEYS` — any authenticated principal, own keys only — and
 * it is the partial controller's annotation that enforces it, not this page's `read` floor. The
 * UI is a convenience, never the enforcement point (ui-screens.md §4 preamble).
 */
@Controller
class ApiConsoleController(
    private val publishing: EndpointPublishService,
    private val bindings: EndpointKeyBindingRepository,
    private val apiKeys: ApiKeyRepository,
    private val keyRows: ApiKeyRows,
    private val pipelineNames: PipelineNames,
    private val endpointsProperties: EndpointsProperties,
    private val authProperties: AuthProperties,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/api-console")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun console(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id

        val endpoints = publishing.list(principal).sortedBy { it.pathPattern }
        // ONE batch lookup for the whole page, never one per row — the same rule
        // [PipelineNames] was written for (T114). `EndpointsController` still does the
        // per-row read; this page has no excuse to copy it.
        val names = pipelineNames.lookup(workspaceId, endpoints.map { it.pipelineId }.toSet())
        val served = pipelineNames.servedVersions(workspaceId, endpoints.map { it.pipelineId }.toSet())

        // "Bound keys" is the EXACT-NODE count, which is what the REST surface reports and
        // what a reader can act on ("this path has bindings"). It is deliberately NOT the
        // EFFECTIVE authorization — that walks ancestors and takes the nearest node with any
        // binding (EndpointAuthorizer), and zero bindings does not mean nobody can call the
        // endpoint: a `user` key pinned to this workspace with `execute` still may.
        val allBindings = bindings.findAll().filter { it.workspaceId == workspaceId }
        val boundByPath = allBindings.groupBy { it.pathPrefix }

        model.addAttribute(
            "endpoints",
            endpoints.map { endpoint ->
                val name = names[endpoint.pipelineId]
                EndpointRow(
                    url = PUBLISHED_PREFIX + endpoint.pathPattern,
                    segments = endpoint.parsed.segments.map { segment -> segment is EndpointPath.Segment.Variable },
                    pipelineDisplayName = name?.displayName ?: name?.name ?: DELETED_PIPELINE,
                    pipelinePath = name?.name,
                    servedVersion = served[endpoint.pipelineId]?.version,
                    servedDraft = served[endpoint.pipelineId]?.draft ?: false,
                    timeoutSeconds = endpoint.timeoutSeconds,
                    boundKeys = boundByPath[endpoint.pathPattern]?.size ?: 0,
                    enabled = endpoint.isEnabled,
                )
            },
        )
        model.addAttribute("defaultTimeoutSeconds", endpointsProperties.timeoutDefaultSeconds)

        // The key list is the caller's OWN keys, revoked ones included: `is_revoked` is a fact
        // an operator checks, and a list that hides them cannot answer "did I revoke that?".
        // Endpoint keys show the paths they are bound to instead of scopes, because an endpoint
        // key HAS no scopes (§7.7): an empty scope cell would read as "this key can do nothing".
        // Named `keys`, not `apiKeys`: the table is a Thymeleaf FRAGMENT with a `keys`
        // parameter, and a page that renders it inline resolves that name from the model.
        // A different attribute name renders the empty state on a page full of keys —
        // silently, because a fragment parameter that is not supplied is simply null.
        model.addAttribute("keys", keyRows.of(apiKeys.findByUser(principal.userId), Instant.now()))

        // 091 — the form's options. Rendered from the SAME source the partial validates against
        // (`ApiKeyForm`), so a select can never offer a value the server refuses. Order is the
        // owner's ruling: Kind → Scope → Name → Expiry → Bindings, scope and bindings conditional.
        model.addAttribute("kindChoices", ApiKeyForm.kindChoices(principal.isAdmin))
        model.addAttribute("scopeChoices", ApiKeyForm.scopeChoices(principal.scopes))
        model.addAttribute("expiryChoices", ApiKeyForm.EXPIRY_CHOICES)
        model.addAttribute("expiryCustomWire", ApiKeyForm.CUSTOM)
        // The picker offers the LITERAL prefixes of this workspace's published paths, never a
        // node with a `{variable}` segment: the authorizer walks the concrete request path's
        // ancestors, so a binding on a variable node would authorise nothing (see ApiKeyForm).
        model.addAttribute("bindingNodes", ApiKeyForm.bindingNodes(endpoints.map { it.pathPattern }))

        model.addAttribute("mcpUrl", mcpUrl())
        model.addAttribute("mcpHeader", ApiKeyCredential.HEADER)
        model.addAttribute("keyPrefix", ApiKeyCredential.KEY_PREFIX)
        // Built HERE, not in the template: a multi-line block assembled out of Thymeleaf
        // string literals would depend on how that dialect treats a backslash escape, and
        // the answer would be discovered by a reader seeing a literal "\n" on the screen.
        // Every value in it is a constant of the system (the header name, the key prefix,
        // the MCP path) or configuration — none of it is user input, and `th:text` escapes
        // the whole thing on the way out regardless.
        model.addAttribute("mcpConfigJson", mcpConfigJson())
        // NEVER a literal: the count is what `tools/list` will actually return, and the
        // marketing site renders the same expression (SiteController) for the same reason.
        model.addAttribute("mcpToolCount", McpToolCatalog.NAMES.size)
        model.addAttribute("canAuthor", Scope.satisfies(principal.scopes, Scope.AUTHOR))
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "api/console"
    }

    /**
     * The MCP URL the connection JSON shows.
     *
     * `datapipelines.auth.base-url` is the deployment's declared external origin and is the
     * ONLY safe source: `OidcConfig` documents why a request-derived origin is refused — a
     * hostile `Host`/`X-Forwarded-Host` would otherwise pick the URL, and here that URL is
     * one a reader is invited to paste into an agent's config alongside a live API key.
     * Unset, the card renders the same `{host}/mcp` placeholder the marketing site uses
     * rather than guessing.
     */
    private fun mcpConfigJson(): String =
        """
        {
          "datapipelines": {
            "url": "${mcpUrl()}",
            "headers": { "${ApiKeyCredential.HEADER}": "${ApiKeyCredential.KEY_PREFIX}…" }
          }
        }
        """.trimIndent()

    private fun mcpUrl(): String {
        val base =
            authProperties.baseUrl
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
        return (base ?: HOST_PLACEHOLDER) + ApiKeyCredential.MCP_PATH
    }

    /**
     * One published endpoint, as the table renders it. [segments] marks which path segments
     * are `{variables}` so the template can chip them without re-parsing a string — the
     * parsed form is already on the row.
     */
    data class EndpointRow(
        val url: String,
        val segments: List<Boolean>,
        val pipelineDisplayName: String,
        val pipelinePath: String?,
        val servedVersion: Int?,
        val servedDraft: Boolean,
        val timeoutSeconds: Int,
        val boundKeys: Int,
        val enabled: Boolean,
    )

    private companion object {
        /** `PublishedEndpointController.ROOT`, restated as the display prefix. */
        const val PUBLISHED_PREFIX = "/api/x"

        /** What `site/add-mcp-server.html` already shows when the origin is not configured. */
        const val HOST_PLACEHOLDER = "{host}"

        /** A pipeline deleted out from under a published endpoint — shown, never crashed on. */
        const val DELETED_PIPELINE = "(deleted pipeline)"
    }
}
