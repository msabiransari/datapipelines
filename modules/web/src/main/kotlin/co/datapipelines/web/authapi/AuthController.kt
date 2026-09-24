package co.datapipelines.web.authapi

import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
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

/** §16.1 create body. `scopes` defaults server-side (configuration `auth.api-keys.default-scopes`). */
data class CreateApiKeyRequest(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("scopes") @get:JsonProperty("scopes") @param:JsonProperty("scopes")
    val scopes: List<String>? = null,
    @field:JsonProperty("expires_at") @get:JsonProperty("expires_at") @param:JsonProperty("expires_at")
    val expiresAt: Instant? = null,
    /**
     * §7.7 — `user` (the default, and every key that existed before 074) or `endpoint`.
     * Absent means `user`, so every existing client's request means exactly what it did.
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
 * Scope enforcement is the annotation + `auth`'s ScopeInterceptor ([ScopeMatrix] rows
 * `VIEW_OWN_MCP_KEY`, `MANAGE_API_KEYS`, `CURRENT_PRINCIPAL`, `USER_ADMINISTRATION`); the
 * privilege-escalation guard on key scopes (§7.4) lives in [ApiKeyService.issue]. Audit
 * events are written by the services, not here (auth.md §10.1).
 *
 * Since 179 (D16/D17) issuance and the self surface are DIFFERENT rows: a `user` key is
 * minted by the login hook and nowhere else (`auth.key_kind_not_mintable` answers any
 * attempt), and creating `endpoint` keys is the workspace admin's `MANAGE_API_KEYS`. What
 * every role keeps is the view/delete of its OWN login-minted key — `VIEW_OWN_MCP_KEY`.
 *
 * ## Catalog gaps — reported, not papered over
 * §13 has no `auth.user.not_found` and no "api key not found" code. Unknown users are answered
 * with the correct status (404) and the nearest catalogued not-found code, with
 * `details.reason = "user_not_found"` removing any ambiguity — the stand-in pattern this module
 * documents in `ApiErrors`. Key revocation is deliberately idempotent: `204` whether or not the
 * key existed, so the endpoint discloses nothing about key-id existence.
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
     * §16.1 (179, D16) — the caller's ONE live MCP key in the ACTIVE workspace: what the top
     * bar shows (id, prefix, whether the sealed secret can be copied). 404-shaped emptiness is
     * deliberately NOT used — "no key yet" is a state, not an error, so the answer is
     * `data: null` and the caller renders the sign-in hint.
     */
    @GetMapping("/api-keys/mine")
    @RequiredScope(Permission.MCP_KEY_OWN)
    fun myMcpKey(): ApiResponse<Map<String, Any?>?> {
        val principal = currentPrincipal()
        val key =
            principal.workspace?.let { apiKeyRepository.findLiveUserKey(principal.userId, it.id) }
                ?: return ApiResponse.of(null)
        return ApiResponse.of(
            mapOf(
                "id" to key.id,
                "name" to key.name,
                "prefix" to key.id.take(MCP_PREFIX_CHARS) + "…",
                "copyable" to key.hasSealedSecret,
                "created_at" to key.createdAt.toString(),
            ),
        )
    }

    /**
     * §16.1 — issue (D17: the `MANAGE_API_KEYS` row since 179 — a workspace admin's verb).
     * `kind` absent means `user`, which the service REFUSES (`auth.key_kind_not_mintable`):
     * the refusal, not a silently different credential, is what a pre-179 client should meet.
     * The plaintext `key` is in this response exactly once.
     */
    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(Permission.API_KEY_CREATE)
    fun createKey(
        @RequestBody body: CreateApiKeyRequest,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val scopes =
            body.scopes
                ?.map { parseScope(it) }
                ?.toSet()
                .orEmpty()
        val kind =
            body.kind?.let {
                ApiKeyKind.fromWireOrNull(it)
                    ?: throw ApiException(
                        PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
                        "Unknown key kind '$it'. Supported: ${ApiKeyKind.WIRE_VALUES.joinToString(", ")}.",
                        mapOf("kind" to it, "supported" to ApiKeyKind.WIRE_VALUES),
                    )
            } ?: ApiKeyKind.DEFAULT
        // D3 + §7.4: the key pins the creator's ACTIVE workspace (their membership in it is
        // re-checked inside issue). No request-payload workspace exists in v1 — cross-workspace
        // key issuance has no surface. §7.7's bindings ride the same call, before the mint.
        val issued =
            endpointKeys.issue(
                principal = principal,
                name = body.name,
                scopes = scopes,
                kind = kind,
                bindingPaths = body.bindings.orEmpty(),
                expiresAt = body.expiresAt,
            )
        return ApiResponse.of(
            mapOf(
                "id" to issued.record.id,
                "name" to issued.record.name,
                "scopes" to issued.record.scopes.map { it.wire },
                "kind" to issued.record.kind.wire,
                "bindings" to body.bindings.orEmpty(),
                "key" to issued.plaintext,
                "created_at" to issued.record.createdAt.toString(),
                "expires_at" to issued.record.expiresAt?.toString(),
            ),
        )
    }

    /** §16.1 — revoke. Idempotent `204`: no existence disclosure (see the class KDoc). */
    @DeleteMapping("/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.MCP_KEY_OWN)
    fun revokeKey(
        @PathVariable keyId: String,
    ) {
        apiKeys.revoke(keyId, currentPrincipal().userId)
    }

    /** §16.2 — the current principal, for agents and the UI to discover their scope set. */
    @GetMapping("/me")
    @RequiredScope(Permission.PROFILE_READ)
    fun me(): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        return ApiResponse.of(
            mapOf(
                "user_id" to principal.userId.toString(),
                "email" to principal.email,
                "display_name" to principal.displayName,
                "scopes" to principal.scopes.map { it.wire },
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
        val user = users.snapshot(userId) ?: return userNotFound(userId)
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

    /** Applies an admin mutation and answers with the updated record; 404 for an unknown user. */
    private fun flip(
        userId: UUID,
        mutation: (UUID) -> Boolean,
    ): ResponseEntity<Any> {
        if (users.snapshot(userId) == null) return userNotFound(userId)
        mutation(userId)
        val updated = users.snapshot(userId) ?: return userNotFound(userId)
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

    private fun parseScope(raw: String): Scope =
        runCatching { Scope.fromWire(raw) }.getOrNull()
            ?: throw co.datapipelines.web.api.ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "Unknown scope '$raw'.",
                mapOf("scope" to raw.take(MAX_ECHOED_VALUE_CHARS), "supported" to Scope.entries.map { it.wire }),
            )

    private fun ApiKey.toResponse(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "name" to name,
            "scopes" to scopes.map { it.wire },
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

        /** D16 — the top bar shows this many characters of the key (the `dpk_` id head). */
        const val MCP_PREFIX_CHARS = 12
    }
}
