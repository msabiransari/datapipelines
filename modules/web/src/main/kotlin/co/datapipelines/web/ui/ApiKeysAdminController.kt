package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyKindNotMintableException
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.RolePermissions
import co.datapipelines.auth.UserRepository
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.util.UUID

/**
 * `/api-keys` — the workspace's KEYS page (179, D17; keys v2 A13–A15, ui-screens.md §4.19).
 *
 * Keys v2 makes this the ONE creation path for every kind: an `mcp` key (a robot member with
 * the role its creator picks, from the roles the subset rule allows them), an `endpoint` API
 * key, a `server` key. The login mint and the top-bar chip are retired, so the page opens to
 * `mcp_key.own` — every signed-in person with a workspace — and its CONTENT follows the
 * caller's permissions: the whole workspace's table needs `api_key.read`; anyone else sees
 * the `mcp` keys they created themselves.
 *
 * Every route declares the LOWEST permission that may drive it (`mcp_key.create`,
 * `mcp_key.revoke_own`); the per-kind floors (`api_key.create`, `server_key.create`,
 * `api_key.revoke`, `server_key.revoke`) and the subset rule are the service's, asked inside
 * [ApiKeyService.issue] and [ApiKeyService.revokeAs] — so a hand-crafted POST gets exactly the
 * answer the form's user would, and a caller the route admits can never exceed their kind.
 */
@Controller
class ApiKeysAdminController(
    private val apiKeyService: ApiKeyService,
    private val apiKeyRepository: ApiKeyRepository,
    /** §7.7 — issuance that also writes bindings; the same service the REST surface calls. */
    private val endpointKeys: EndpointKeyService,
    private val publishing: EndpointPublishService,
    private val keyRows: ApiKeyRows,
    private val bindings: EndpointKeyBindingRepository,
    private val userRepository: UserRepository,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/api-keys")
    @RequiredScope(Permission.MCP_KEY_OWN)
    fun page(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = requirePrincipal()
        fillPage(model, principal)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "api/keys"
    }

    /**
     * Create a key (the form's order: Kind → Role → Name → Expiry → Associations). Everything
     * the select elements offer is re-resolved server-side against the same [ApiKeyForm]
     * source — a hand-crafted POST gets exactly the answer the form's user would. The subset
     * rule (A14) is enforced in the service: a role outside the creator's offerable set is
     * refused with `auth.role_required`, whatever the form offered.
     */
    @PostMapping("/partials/api-keys")
    @RequiredScope(Permission.MCP_KEY_CREATE)
    fun create(
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) role: String?,
        @RequestParam name: String,
        @RequestParam(required = false) expiry: String?,
        @RequestParam(required = false) expiryDate: String?,
        @RequestParam(required = false) bindings: List<String>?,
        model: Model,
    ): String {
        val principal = requirePrincipal()
        // A key is WHAT THE CALLER CHOSE (keys v2 A15): no kind, no mint — the form always
        // sends one, and a hand-crafted POST without it gets the catalogued refusal.
        val requestedKind =
            kind?.trim()?.takeIf { it.isNotEmpty() }?.let {
                ApiKeyKind.fromWireOrNull(it) ?: throw unknownKind(it)
            } ?: throw KeyKindNotMintableException()
        val requestedRole = role?.trim()?.takeIf { it.isNotEmpty() }?.let { parseRole(it) }
        val expiresAt = ApiKeyForm.resolveExpiry(expiry, expiryDate, Instant.now())
        val bindingPaths =
            bindings
                .orEmpty()
                .flatMap { it.split(',') }
                .mapNotNull { it.trim().takeIf { path -> path.isNotEmpty() } }

        val issued =
            endpointKeys.issue(
                principal = principal,
                name = name.trim(),
                role = requestedRole,
                kind = requestedKind,
                bindingPaths = bindingPaths,
                expiresAt = expiresAt,
            )

        model.addAttribute("key", issued.plaintext)
        model.addAttribute("keyId", issued.record.id)
        model.addAttribute("keyName", issued.record.name)
        model.addAttribute("keyKind", issued.record.kind.wire)
        model.addAttribute("keyRole", issued.record.role?.label)
        model.addAttribute("keyBindings", bindingPaths)
        model.addAttribute("keyExpires", expiresAt?.let { RelativeTime.absolute(it) })
        model.addAttribute("keys", rows(principal))
        RoleModel.stamp(model, principal)
        // The create response refreshes the whole table out-of-band: this flag is what puts
        // hx-swap-oob on the keysTable fragment root for THIS render only.
        model.addAttribute("oob", true)
        return "partials/api-key-created"
    }

    /**
     * Delete a key of this workspace (keys v2 A14): a creator may revoke a key THEY created
     * (`mcp_key.revoke_own`, the route's floor), a workspace admin any key in the workspace
     * (`api_key.revoke`, asked in the service), a server key's delete additionally needing
     * `server_key.revoke` — a super admin's. Foreign keys and permissions the caller lacks
     * answer silently (the redrawn table): a key id must not be probeable through the delete.
     */
    @DeleteMapping("/partials/api-keys/{keyId}")
    @RequiredScope(Permission.MCP_KEY_REVOKE_OWN)
    fun revoke(
        @PathVariable keyId: String,
        model: Model,
    ): String {
        val principal = requirePrincipal()
        apiKeyService.revokeAs(principal, keyId)
        model.addAttribute("keys", rows(principal))
        RoleModel.stamp(model, principal)
        return "partials/api-keys-rows"
    }

    /**
     * Save one key's association SET (D17): the multi-select posts the full selection, and
     * the service writes the delta — a checkbox never maps to "add" or "remove" by itself,
     * because the form's answer is the whole set. The selection is validated against the
     * workspace's published paths' literal prefixes, the same list [ApiKeyForm.bindingNodes]
     * rendered: a hand-crafted path fails normalization there, not here.
     */
    @PostMapping("/partials/api-keys/{keyId}/bindings")
    @RequiredScope(Permission.API_KEY_BIND)
    fun associate(
        @PathVariable keyId: String,
        @RequestParam("bindings", required = false) selected: List<String>?,
        model: Model,
    ): String {
        val principal = requirePrincipal()
        val key = requireWorkspaceEndpointKey(principal, keyId)
        val wanted =
            selected
                .orEmpty()
                .flatMap { it.split(',') }
                .mapNotNull { it.trim().takeIf { path -> path.isNotEmpty() } }
                .toSet()
        val current = bindings.findByKey(key.id).map { it.pathPrefix }.toSet()
        wanted.filter { it !in current }.forEach { endpointKeys.bind(principal, key.id, it) }
        current.filter { it !in wanted }.forEach { endpointKeys.unbind(principal, key.id, it) }
        model.addAttribute("keys", rows(principal))
        RoleModel.stamp(model, principal)
        return "partials/api-keys-rows"
    }

    /** The page's model — shared by the page render and every partial that redraws the table. */
    private fun fillPage(
        model: Model,
        principal: AuthenticatedPrincipal,
    ) {
        model.addAttribute("keys", rows(principal))
        model.addAttribute("kindChoices", kindChoices(principal))
        model.addAttribute("expiryChoices", ApiKeyForm.EXPIRY_CHOICES)
        model.addAttribute("expiryCustomWire", ApiKeyForm.CUSTOM)
        // The association picker offers the LITERAL prefixes of this workspace's published
        // paths — see ApiKeyForm for why a {variable} node is never one of them.
        model.addAttribute(
            "bindingNodes",
            ApiKeyForm.bindingNodes(publishing.list(principal).map { it.pathPattern }),
        )
        RoleModel.stamp(model, principal)
    }

    /**
     * The kind cards this caller is OFFERED (keys v2 A13–A15): an `mcp` card when they hold
     * `mcp_key.create`, its roles exactly [RolePermissions.offerable]'s answer for their
     * permissions here; an `endpoint` card under `api_key.create`; a `server` card under
     * `server_key.create`. The service re-checks everything — the form is convenience, the
     * service is the guard.
     */
    private fun kindChoices(principal: AuthenticatedPrincipal) =
        ApiKeyForm.kindChoices(
            mcpRoles =
                if (principal.holds(Permission.MCP_KEY_CREATE)) {
                    RolePermissions
                        .offerable(creatorPermissions(principal))
                        .let { offerable -> RolePermissions.KEY_OFFERABLE.filter { it in offerable } }
                } else {
                    emptyList()
                },
            mayCreateApiKeys = principal.holds(Permission.API_KEY_CREATE),
            isSuperAdmin = principal.isSuperAdmin,
        )

    /** The creator's permission set here — [ApiKeyService]'s same reading of the subset rule. */
    private fun creatorPermissions(principal: AuthenticatedPrincipal): Set<Permission> =
        when {
            principal.isSuperAdmin -> RolePermissions.SUPER_ADMIN
            else -> principal.workspaceRole?.let { RolePermissions.of(it) } ?: emptySet()
        }

    /**
     * The keys this caller SEES (keys v2 A15): with `api_key.read`, the whole workspace's keys
     * of every kind; without it, the `mcp` keys they created themselves — the robot members
     * they are responsible for. Revoked keys stay VISIBLE (their `is_revoked` is a fact an
     * operator checks).
     */
    private fun rows(principal: AuthenticatedPrincipal): List<ApiKeyRows.Row> {
        val workspaceId = principal.requireWorkspace().id
        val all = apiKeyRepository.findByWorkspace(workspaceId)
        val visible = if (principal.holds(Permission.API_KEY_READ)) all else all.filter { it.kind == ApiKeyKind.MCP && it.createdBy == principal.userId }
        return keyRows.of(visible, Instant.now(), userLabels(workspaceId))
    }

    /**
     * user id → display label, one lookup per distinct user — the "Created by" column (the key's
     * creator, a person) and the "Acts as" column (its own identity, "<key name> (API key)",
     * #215 record §3.3 — the same words its history shows).
     */
    private fun userLabels(workspaceId: UUID): Map<UUID, String> =
        apiKeyRepository
            .findByWorkspace(workspaceId)
            .flatMap { listOf(it.createdBy, it.userId) }
            .distinct()
            .associateWith { userId ->
                userRepository.findById(userId)?.let { user ->
                    ActorNames.displayed(user.displayName.ifBlank { null } ?: user.email, user.kind.wire)
                } ?: ApiKeyRows.UNKNOWN_OWNER
            }

    /**
     * The key the association verbs address: an `endpoint` key of the ACTIVE workspace. A
     * key of another workspace or another kind is not-found — the non-disclosure rule the
     * whole key surface follows, and the shape guard that keeps this page's verbs from ever
     * touching a non-endpoint key's bindings.
     */
    private fun requireWorkspaceEndpointKey(
        principal: AuthenticatedPrincipal,
        keyId: String,
    ): ApiKey {
        val key = apiKeyRepository.findById(keyId)
        if (key == null || key.kind != ApiKeyKind.ENDPOINT || key.workspaceId != principal.requireWorkspace().id) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Endpoint.NOT_FOUND,
                message = "No API key '$keyId' in this workspace.",
                details = mapOf("key_id" to keyId),
            )
        }
        return key
    }

    private fun parseRole(raw: String): KeyRole =
        KeyRole.find(raw)
            ?: throw DatapipelinesException(
                code = PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
                message = "Unknown key role '$raw'. Supported: ${KeyRole.WIRE_VALUES.joinToString(", ")}.",
                details = mapOf("reason" to "role_unknown", "role" to raw.take(32)),
            )

    private fun unknownKind(raw: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
            message = "Unknown key kind '$raw'. Supported: ${ApiKeyKind.WIRE_VALUES.joinToString(", ")}.",
            details = mapOf("reason" to "kind_unknown"),
        )

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")
}
