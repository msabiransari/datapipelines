package co.datapipelines.web.authapi

import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.KeyKindNotMintableException
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * §16.1 create body (keys v2, #233). A key carries a KIND (what it is) and a ROLE (what it may
 * do): `role` is REQUIRED for `kind: "mcp"` — one of the member roles the subset rule allows
 * the creator — and optional for `endpoint`/`server`, whose role is fixed by the kind and which
 * refuse a contradicting one rather than quietly correcting it.
 */
data class CreateApiKeyRequest(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("role") @get:JsonProperty("role") @param:JsonProperty("role")
    val role: String? = null,
    /**
     * Retired with the scopes (#215 PK8) and kept ONLY so a request that still sends it is refused
     * by name: a caller who writes `"scopes": ["author"]` believes the key will carry it, and
     * silently ignoring the field would leave that belief standing.
     */
    @field:JsonProperty("scopes") @get:JsonProperty("scopes") @param:JsonProperty("scopes")
    val scopes: List<String>? = null,
    @field:JsonProperty("expires_at") @get:JsonProperty("expires_at") @param:JsonProperty("expires_at")
    val expiresAt: Instant? = null,
    /**
     * §7.7 — `mcp`, `endpoint` or `server` (keys v2 A19). REQUIRED: the login mint that used to
     * make a default kind is retired (A15), and minting a credential the caller did not name
     * would be worse than refusing the request.
     */
    @field:JsonProperty("kind") @get:JsonProperty("kind") @param:JsonProperty("kind")
    val kind: String? = null,
    /**
     * §7.7 — the endpoint-tree nodes this key authorises, for `kind: "endpoint"`. Part of
     * ISSUANCE rather than a second call because the plaintext key is returned exactly once: a
     * failure between mint and bind would leave an operator holding an unusable secret.
     */
    @field:JsonProperty("bindings") @get:JsonProperty("bindings") @param:JsonProperty("bindings")
    val bindings: List<String>? = null,
)

/**
 * The auth surface under `/api/v1/auth` (rest-api.md §16): own API-key management, the current
 * principal, and user administration.
 *
 * Enforcement is the annotation + `auth`'s ScopeInterceptor, which asks [ScopeMatrix] for the
 * declared catalog permission (`mcp_key.own`, `mcp_key.create`, `mcp_key.revoke_own`,
 * `profile.read`, `user.manage`); the per-kind create floors and the subset rule (keys v2 A14)
 * live in [ApiKeyService.issue], the own-or-admin revocation in [ApiKeyService.revokeAs]. Audit
 * events are written by the services, not here (auth.md §10.1).
 *
 * Since keys v2 (#233) issuance is `mcp_key.create` at the route — author, promoter, workspace
 * admin — with `api_key.create`/`server_key.create` asked per kind in the service; revocation
 * of a key YOU created is `mcp_key.revoke_own`, any key in the workspace needing
 * `api_key.revoke` there. What every role keeps is the view of its OWN created keys —
 * `mcp_key.own`.
 *
 * ## Catalog gaps — reported, not papered over
 * §13 has no `auth.user.not_found` and no "api key not found" code. Unknown users are answered
 * with the correct status (404) and the nearest catalogued not-found code, with
 * `details.reason = "user_not_found"` removing any ambiguity — the stand-in pattern this module
 * documents in `ApiErrors`. Key revocation is deliberately idempotent: `204` whether or not the
 * key existed (or was the caller's to revoke), so the endpoint discloses nothing about key-id
 * existence.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val apiKeys: ApiKeyService,
    private val apiKeyRepository: ApiKeyRepository,
    private val users: UserService,
    /**
     * Issuance that also writes endpoint bindings (§7.7). Cross-aggregate — a key is `auth`'s and
     * a binding is the endpoint registry's — so it lives in `modules/application`, like every
     * other use case that needs more than one aggregate.
     */
    private val endpointKeys: EndpointKeyService,
) {
    /** §16.1 — the caller's own keys, revoked included (`is_revoked` must be able to vary); never secrets. */
    @GetMapping("/api-keys")
    @RequiredScope(Permission.MCP_KEY_OWN)
    fun listKeys(): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.of(apiKeyRepository.findByUser(currentPrincipal().userId).map { it.toResponse() })

    /**
     * §16.1 — issue (keys v2 A13–A15: the ONE creation path for every kind, the page's form
     * driving the same service). `kind` is REQUIRED (`auth.key_kind_not_mintable` without one —
     * no default exists any more), and an `mcp` key REQUIRES `role`: the member role the subset
     * rule allows this caller to give. The plaintext `key` is in this response exactly once.
     */
    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(Permission.MCP_KEY_CREATE)
    @Suppress("ThrowsCount") // each refusal is its own catalogued code with its own details — merging hides which one fired
    fun createKey(
        @RequestBody body: CreateApiKeyRequest,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        if (body.scopes != null) {
            throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "API keys carry a role, not scopes. Remove \"scopes\"; the role follows the kind " +
                    "(${KeyRole.WIRE_VALUES.joinToString(", ")}).",
                mapOf("field" to "scopes", "supported_roles" to KeyRole.WIRE_VALUES),
            )
        }
        val role = body.role?.let(::parseRole)
        val kind =
            body.kind?.let {
                ApiKeyKind.fromWireOrNull(it)
                    ?: throw ApiException(
                        PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
                        "Unknown key kind '$it'. Supported: ${ApiKeyKind.WIRE_VALUES.joinToString(", ")}.",
                        mapOf("kind" to it, "supported" to ApiKeyKind.WIRE_VALUES),
                    )
            } ?: throw KeyKindNotMintableException()
        // D3 + §7.4: the key pins the creator's ACTIVE workspace (their membership in it is
        // re-checked inside issue). No request-payload workspace exists in v1 — cross-workspace
        // key issuance has no surface. §7.7's bindings ride the same call, before the mint.
        val issued =
            endpointKeys.issue(
                principal = principal,
                name = body.name,
                role = role,
                kind = kind,
                bindingPaths = body.bindings.orEmpty(),
                expiresAt = body.expiresAt,
            )
        return ApiResponse.of(
            issued.record.toResponse() - listOf("last_used_at", "is_revoked") +
                mapOf(
                    "bindings" to body.bindings.orEmpty(),
                    "key" to issued.plaintext,
                ),
        )
    }

    /**
     * §16.1 — revoke a key YOU created (`mcp_key.revoke_own`, keys v2 A14). A caller holding
     * `api_key.revoke` may revoke any key of the active workspace through the same route (a
     * `server` key additionally `server_key.revoke`, asked in the service); anything the caller
     * may not touch answers the idempotent `204` — no existence disclosure.
     */
    @DeleteMapping("/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.MCP_KEY_REVOKE_OWN)
    fun revokeKey(
        @PathVariable keyId: String,
    ) {
        apiKeys.revokeAs(currentPrincipal(), keyId)
    }

    /** §16.2 — the current principal, for agents and the UI to discover who they act as and with what role. */
    @GetMapping("/me")
    @RequiredScope(Permission.PROFILE_READ)
    fun me(): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        return ApiResponse.of(
            mapOf(
                "user_id" to principal.userId.toString(),
                "email" to principal.email,
                "display_name" to principal.displayName,
                // #215: the role this principal is judged as — a key role, `super_admin`, or the
                // active membership's (an MCP key's capped at author). Informative; scopes are gone.
                "role" to principal.heldRole,
                "auth_method" to principal.authMethod.name,
                "key_id" to principal.keyId,
            ),
        )
    }

    /** §16.3 — user listing (admin). */
    @GetMapping("/users")
    @RequiredScope(Permission.USER_MANAGE)
    fun listUsers(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val raw = users.search(q.orEmpty(), page, size + 1)
        val items = raw.take(size).map { it.toResponse() }
        return ApiResponse.of(PagedData(items, Pagination.unknownTotal(page, size, items.size, raw.size > size)))
    }

    /** §16.3 — user detail (admin). */
    @GetMapping("/users/{userId}")
    @RequiredScope(Permission.USER_MANAGE)
    fun getUser(
        @PathVariable userId: UUID,
    ): ResponseEntity<Any> {
        val user = users.administrableUser(userId) ?: return userNotFound(userId)
        return ResponseEntity.ok(ApiResponse.of(user.toResponse()))
    }

    /** §16.3 — deactivate; effective within one cache TTL, immediately on this instance. */
    @PostMapping("/users/{userId}/deactivate")
    @RequiredScope(Permission.USER_MANAGE)
    fun deactivate(
        @PathVariable userId: UUID,
    ): ResponseEntity<Any> = flip(userId) { users.deactivate(it, currentPrincipal().userId) }

    /** §16.3 — activate. */
    @PostMapping("/users/{userId}/activate")
    @RequiredScope(Permission.USER_MANAGE)
    fun activate(
        @PathVariable userId: UUID,
    ): ResponseEntity<Any> = flip(userId) { users.activate(it, currentPrincipal().userId) }

    /** §16.3 — grant admin. */
    @PostMapping("/users/{userId}/grant-admin")
    @RequiredScope(Permission.USER_MANAGE)
    fun grantAdmin(
        @PathVariable userId: UUID,
    ): ResponseEntity<Any> = flip(userId) { users.grantAdmin(it, currentPrincipal().userId) }

    /** §16.3 — revoke admin. */
    @PostMapping("/users/{userId}/revoke-admin")
    @RequiredScope(Permission.USER_MANAGE)
    fun revokeAdmin(
        @PathVariable userId: UUID,
    ): ResponseEntity<Any> = flip(userId) { users.revokeAdmin(it, currentPrincipal().userId) }

    /**
     * Applies an admin mutation and answers with the updated record; 404 for an unknown user AND for
     * a row that is not a person — the System account or a key's identity (#215 A.6/A3: an identity
     * is managed only through its key) — decided BEFORE the mutation runs.
     */
    private fun flip(
        userId: UUID,
        mutation: (UUID) -> Boolean,
    ): ResponseEntity<Any> {
        if (users.administrableUser(userId) == null) return userNotFound(userId)
        mutation(userId)
        val updated = users.administrableUser(userId) ?: return userNotFound(userId)
        return ResponseEntity.ok(ApiResponse.of(updated.toResponse()))
    }

    /** The §16.3 unknown-user answer — see the class KDoc for the code stand-in. */
    private fun userNotFound(userId: UUID): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            co.datapipelines.web.api.ApiErrorResponse.of(
                code = PipelineErrorCodes.Execution.NOT_FOUND,
                message = "User '$userId' not found.",
                details = mapOf("reason" to "user_not_found", "user_id" to userId.toString()),
                userMessage = "We couldn't find that user.",
            ),
        )

    private fun parseRole(raw: String): KeyRole =
        KeyRole.find(raw)
            ?: throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "Unknown key role '$raw'.",
                mapOf("role" to raw.take(MAX_ECHOED_VALUE_CHARS), "supported" to KeyRole.WIRE_VALUES),
            )

    /**
     * §16.1's key shape (keys v2): `role` — the key's OWN role, of every kind — the `identity`
     * it ACTS AS (its own `service` identity), and `created_by`, the person who created it.
     */
    private fun ApiKey.toResponse(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "name" to name,
            "role" to role?.wire,
            "identity" to mapOf("id" to userId.toString(), "display_name" to users.snapshot(userId)?.displayName),
            "created_by" to createdBy.toString(),
            "kind" to kind.wire,
            "created_at" to createdAt.toString(),
            "expires_at" to expiresAt?.toString(),
            "last_used_at" to lastUsedAt?.toString(),
            "is_revoked" to isRevoked,
        )

    private fun User.toResponse(): Map<String, Any?> =
        mapOf(
            "user_id" to id.toString(),
            "email" to email,
            "display_name" to displayName,
            "is_active" to isActive,
            "is_admin" to isAdmin,
            "created_at" to createdAt.toString(),
            "last_login_at" to lastLoginAt?.toString(),
        )

    private companion object {
        const val MAX_ECHOED_VALUE_CHARS = 32
    }
}
