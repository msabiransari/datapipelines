package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
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
 * `/api-keys` — the workspace's API keys (179, D17; ui-screens.md §4.19).
 *
 * What 079's console called "endpoint keys" is what the product now calls API keys: a
 * credential whose whole authority is the published endpoints it is associated with. This
 * page is their one home — the table (name, prefix, created by, created, last used, bound
 * paths), creation (name, expiry, initial associations), deletion, and per-key
 * association with the workspace's published paths.
 *
 * Every route here is `MANAGE_API_KEYS` (§7.6): workspace admins and super admins (owner
 * ruling 8). A `user` key cannot be created anywhere — the login hook mints those (D16) —
 * and the create form offers only what the service would accept.
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
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_API_KEYS)
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
     * Create an API key (the form's order: Kind → Name → Expiry → Associations). Everything
     * the select elements offer is re-resolved server-side against the same [ApiKeyForm]
     * source — a hand-crafted POST gets exactly the answer the form's user would.
     */
    @PostMapping("/partials/api-keys")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_API_KEYS)
    fun create(
        @RequestParam(required = false) kind: String?,
        @RequestParam name: String,
        @RequestParam(required = false) expiry: String?,
        @RequestParam(required = false) expiryDate: String?,
        @RequestParam(required = false) bindings: List<String>?,
        model: Model,
    ): String {
        val principal = requirePrincipal()
        // The form offers only `endpoint` (and `server` to a super admin); anything else —
        // `user` included — is the service's refusal, never a silent reinterpretation.
        val requestedKind =
            kind?.trim()?.takeIf { it.isNotEmpty() }?.let {
                ApiKeyKind.fromWireOrNull(it) ?: throw unknownKind(it)
            } ?: ApiKeyKind.ENDPOINT
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
                scopes = emptySet(),
                kind = requestedKind,
                bindingPaths = bindingPaths,
                expiresAt = expiresAt,
            )

        model.addAttribute("key", issued.plaintext)
        model.addAttribute("keyId", issued.record.id)
        model.addAttribute("keyName", issued.record.name)
        model.addAttribute("keyKind", issued.record.kind.wire)
        model.addAttribute("keyScopes", issued.record.scopes.map { it.wire }.sorted())
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
     * Delete an API key of this workspace, whoever created it (D17). Owner-scoped revocation
     * is the MCP key's rule (the top bar); this page administers the WORKSPACE's keys.
     */
    @DeleteMapping("/partials/api-keys/{keyId}")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_API_KEYS)
    fun revoke(
        @PathVariable keyId: String,
        model: Model,
    ): String {
        val principal = requirePrincipal()
        apiKeyService.revokeWorkspaceEndpointKey(keyId, principal.requireWorkspace().id, principal.userId)
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
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_API_KEYS)
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
        model.addAttribute("kindChoices", ApiKeyForm.kindChoices(principal.isSuperAdmin))
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
     * The page lists the two ADMIN kinds (D17/D18): `endpoint` keys (the page's subject) and
     * `server` keys (the super admin's promotion credential — mintable here, and a table that
     * hid it would be lying about what the form just did). `user` keys are the top bar's, never
     * this table's.
     */
    private fun rows(principal: AuthenticatedPrincipal) =
        keyRows.of(
            ApiKeyKind.entries
                .filter { it != ApiKeyKind.USER }
                .flatMap { apiKeyRepository.findByWorkspaceAndKind(principal.requireWorkspace().id, it) },
            Instant.now(),
            ownerLabels = ownerLabels(principal.requireWorkspace().id),
        )

    /** owner id → display label, one lookup per distinct owner — the "Created by" column. */
    private fun ownerLabels(workspaceId: UUID): Map<UUID, String> =
        ApiKeyKind.entries
            .filter { it != ApiKeyKind.USER }
            .flatMap { apiKeyRepository.findByWorkspaceAndKind(workspaceId, it) }
            .map { it.userId }
            .distinct()
            .associateWith { userId ->
                userRepository.findById(userId)?.let { it.displayName.ifBlank { null } ?: it.email }
                    ?: ApiKeyRows.UNKNOWN_OWNER
            }

    /**
     * The key the association verbs address: an `endpoint` key of the ACTIVE workspace. A
     * key of another workspace or another kind is not-found — the non-disclosure rule the
     * whole key surface follows, and the shape guard that keeps this page's verbs from ever
     * touching a user's MCP key.
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
