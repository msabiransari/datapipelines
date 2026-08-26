package co.datapipelines.web.authapi

import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.pipeline.PipelineErrorCodes
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
)

/**
 * The auth surface under `/api/v1/auth` (rest-api.md §16): own API-key management, the current
 * principal, and user administration.
 *
 * Scope enforcement is the annotation + `auth`'s ScopeInterceptor ([ScopeMatrix] rows
 * `MANAGE_OWN_API_KEYS`, `CURRENT_PRINCIPAL`, `USER_ADMINISTRATION`); the privilege-escalation
 * guard on key scopes (§7.4) lives in [ApiKeyService.issue]. Audit events are written by the
 * services, not here (auth.md §10.1).
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
) {
    /** §16.1 — the caller's own keys, revoked included (`is_revoked` must be able to vary); never secrets. */
    @GetMapping("/api-keys")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS)
    fun listKeys(): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.of(apiKeyRepository.findByUser(currentPrincipal().userId).map { it.toResponse() })

    /** §16.1 — issue. The plaintext `key` is in this response exactly once. */
    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS)
    fun createKey(
        @RequestBody body: CreateApiKeyRequest,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val scopes =
            body.scopes
                ?.map { parseScope(it) }
                ?.toSet()
                .orEmpty()
        val issued =
            apiKeys.issue(
                ownerId = principal.userId,
                name = body.name,
                scopes = scopes,
                creatorScopes = principal.scopes,
                // D3 + §7.4: the key pins the creator's ACTIVE workspace (their membership in
                // it is re-checked inside issue). No request-payload workspace exists in v1 —
                // cross-workspace key issuance has no surface.
                workspaceId = principal.requireWorkspace().id,
                expiresAt = body.expiresAt,
            )
        return ApiResponse.of(
            mapOf(
                "id" to issued.record.id,
                "name" to issued.record.name,
                "scopes" to issued.record.scopes.map { it.wire },
                "key" to issued.plaintext,
                "created_at" to issued.record.createdAt.toString(),
                "expires_at" to issued.record.expiresAt?.toString(),
            ),
        )
    }

    /** §16.1 — revoke. Idempotent `204`: no existence disclosure (see the class KDoc). */
    @DeleteMapping("/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS)
    fun revokeKey(
        @PathVariable keyId: String,
    ) {
        apiKeys.revoke(keyId, currentPrincipal().userId)
    }

    /** §16.2 — the current principal, for agents and the UI to discover their scope set. */
    @GetMapping("/me")
    @RequiredScope(ScopeMatrix.RestOperation.CURRENT_PRINCIPAL)
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
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
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
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun getUser(
        @PathVariable userId: UUID,
    ): ResponseEntity<Any> {
        val user = users.snapshot(userId) ?: return userNotFound(userId)
        return ResponseEntity.ok(ApiResponse.of(user.toResponse()))
    }

    /** §16.3 — deactivate; effective within one cache TTL, immediately on this instance. */
    @PostMapping("/users/{userId}/deactivate")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun deactivate(
        @PathVariable userId: UUID,
    ): ResponseEntity<Any> = flip(userId) { users.deactivate(it, currentPrincipal().userId) }

    /** §16.3 — activate. */
    @PostMapping("/users/{userId}/activate")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun activate(
        @PathVariable userId: UUID,
    ): ResponseEntity<Any> = flip(userId) { users.activate(it, currentPrincipal().userId) }

    /** §16.3 — grant admin. */
    @PostMapping("/users/{userId}/grant-admin")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun grantAdmin(
        @PathVariable userId: UUID,
    ): ResponseEntity<Any> = flip(userId) { users.grantAdmin(it, currentPrincipal().userId) }

    /** §16.3 — revoke admin. */
    @PostMapping("/users/{userId}/revoke-admin")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
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
