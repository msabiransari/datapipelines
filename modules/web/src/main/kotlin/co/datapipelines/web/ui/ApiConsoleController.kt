package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.auth.ApiKeyCredential
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.mcp.McpToolCatalog
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.config.EndpointsProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * 079 §C — the API section (T139, and the owner's "I don't see any API menu", which was
 * literally true: published endpoints, the keys that may call them and the MCP connection
 * had no screen anywhere in the app).
 *
 * ## The route
 *
 * `/api-console`, NOT anything under `/api`. That prefix is the programmatic surface and is
 * split in two — the `/api/v1` REST envelope and the published endpoints (every other category
 * of `/api`, R-EP5) this page lists — and `ui-screens.md §2.1`'s three-URL-space rule says a
 * page never lives in the JSON space. (A glob in a KDoc opens a nested block comment: Kotlin
 * nests them, so the prefixes are written without their wildcards here.) `/api-console` cannot
 * collide with either: Spring matches whole path segments, and `api-console` is a different
 * first segment from `api`. It needs no
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
 * ## Read-only about endpoints AND keys (179, D17)
 *
 * The keys card lived here until 179; it moved to `/api-keys`, the workspace admin's
 * `MANAGE_API_KEYS` page, and the per-endpoint table now shows each path's ASSOCIATED key
 * names with a manage link for the roles that hold that row. What stays is the endpoints
 * inventory and the MCP connection card — and the top bar's chip, not this page, is where a
 * user copies their own MCP key.
 */
@Controller
class ApiConsoleController(
    private val publishing: EndpointPublishService,
    private val bindings: EndpointKeyBindingRepository,
    private val apiKeys: ApiKeyRepository,
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

        // "Associated keys" is the EXACT-NODE set, which is what the REST surface reports and
        // what a reader can act on ("this path has bindings"). It is deliberately NOT the
        // EFFECTIVE authorization — that walks ancestors and takes the nearest node with any
        // binding (EndpointAuthorizer), and zero bindings does not mean nobody can call the
        // endpoint: a `user` key pinned to this workspace with `execute` still may.
        val allBindings = bindings.findByWorkspace(workspaceId)
        val boundByPath = allBindings.groupBy { it.pathPrefix }
        // 179: the column names the keys, so the key ids resolve to names in one batch —
        // the workspace's API keys, not the caller's.
        val keyNames =
            apiKeys
                .findByWorkspaceAndKind(workspaceId, ApiKeyKind.ENDPOINT)
                .associate { it.id to it.name }

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
                    boundKeyNames = boundByPath[endpoint.pathPattern].orEmpty().mapNotNull { keyNames[it.apiKeyId] }.sorted(),
                    enabled = endpoint.isEnabled,
                )
            },
        )
        model.addAttribute("defaultTimeoutSeconds", endpointsProperties.timeoutDefaultSeconds)

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
        RoleModel.stamp(model, principal)
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
     *
     * [boundKeyNames] are the API keys associated at the EXACT node (179: names, not a
     * count — the reader's question is "which", not "how many").
     */
    data class EndpointRow(
        val url: String,
        val segments: List<Boolean>,
        val pipelineDisplayName: String,
        val pipelinePath: String?,
        val servedVersion: Int?,
        val servedDraft: Boolean,
        val timeoutSeconds: Int,
        val boundKeyNames: List<String>,
        val enabled: Boolean,
    )

    private companion object {
        /** `PublishedEndpointController.ROOT`, restated as the display prefix. */
        const val PUBLISHED_PREFIX = "/api"

        /** What `site/add-mcp-server.html` already shows when the origin is not configured. */
        const val HOST_PLACEHOLDER = "{host}"

        /** A pipeline deleted out from under a published endpoint — shown, never crashed on. */
        const val DELETED_PIPELINE = "(deleted pipeline)"
    }
}
