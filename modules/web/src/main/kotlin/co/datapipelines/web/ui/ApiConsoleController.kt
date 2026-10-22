package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
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
 * The keys card lived here until 179; it moved to `/api-keys`, the Keys page — its route is
 * `MCP_KEY_OWN`, every member with a workspace, and keys v2 (A15) made it the ONE creation
 * path for every kind, the `mcp` key included. The per-endpoint table shows each path's
 * ASSOCIATED key names with a manage link for the workspace admin and super admin. What
 * stays is the endpoints inventory and the MCP connection card — and the `mcp` key an
 * agent presents at `/mcp` is created on the Keys page too.
 */
@Controller
class ApiConsoleController(
    private val publishing: EndpointPublishService,
    private val bindings: EndpointKeyBindingRepository,
    private val apiKeys: ApiKeyRepository,
    private val pipelineNames: PipelineNames,
    private val endpointsProperties: EndpointsProperties,
    /** The MCP card's values — shared with the API home (#585) so the two cards cannot drift. */
    private val mcpConnection: McpConnectionCard,
    private val themeResolver: ThemeResolver,
) : ShellPage {
    @GetMapping("/api-console")
    @RequiredScope(Permission.ENDPOINT_READ)
    fun console(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id

        val endpoints = publishing.list(principal).sortedBy { it.pathPattern }
        // #274 — the legacy rows, listed after the live ones and flagged with their reason. A
        // legacy path never serves (the serve registry never sees it), so its section offers
        // exactly one verb: unpublish.
        val legacyEndpoints = publishing.listLegacy(principal).sortedBy { it.pathPattern }
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
        model.addAttribute(
            "legacyEndpoints",
            legacyEndpoints.map(LegacyEndpointRow::of),
        )

        mcpConnection.stamp(model)
        RoleModel.stamp(model, principal)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "api/console"
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

    /**
     * One LEGACY row (#274): a stored path today's grammar refuses, retired rather than
     * fatal. There is no parsed form to chip and no pipeline version to name — the row
     * carries the refusal's reason, and its only offered verb is the template's unpublish
     * button. [path] is the stored pattern (the unpublish call's `path` parameter); [url]
     * is the display form.
     */
    data class LegacyEndpointRow(
        val path: String,
        val url: String,
        val reason: String,
        val enabled: Boolean,
        /**
         * #293: [path] is the stored path itself — the echo was not cut — so an unpublish BY
         * [path] addresses this row. False for a database-written path past the grammar's limit:
         * its cut echo names a path no row is stored at (or, worse, a different row stored at
         * exactly the cut), so the console withholds the verb there.
         */
        val addressable: Boolean = true,
    ) {
        companion object {
            /**
             * The row a stored legacy endpoint renders as — its path echoed bounded (#286,
             * [EndpointPath.echoBounded]), like the REST listing and `endpoints_list`.
             */
            fun of(legacy: co.datapipelines.application.endpoints.EndpointRow.Legacy): LegacyEndpointRow {
                val echoed = EndpointPath.echoBounded(legacy.pathPattern)
                return LegacyEndpointRow(
                    path = echoed,
                    url = PUBLISHED_PREFIX + echoed,
                    reason = legacy.reason,
                    enabled = legacy.enabled,
                    addressable = !EndpointPath.isEchoCut(legacy.pathPattern),
                )
            }
        }
    }

    private companion object {
        /** `PublishedEndpointController.ROOT`, restated as the display prefix. */
        const val PUBLISHED_PREFIX = "/api"

        /** A pipeline deleted out from under a published endpoint — shown, never crashed on. */
        const val DELETED_PIPELINE = "(deleted pipeline)"
    }
}
