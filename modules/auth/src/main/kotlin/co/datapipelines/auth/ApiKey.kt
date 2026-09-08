package co.datapipelines.auth

import java.time.Instant
import java.util.UUID

/**
 * A row of `api_keys` (metadata-db §4.2). [id] is the public `dpk_<key_id>`
 * handle (not a UUID); [keyHash] is the Argon2id hash of the *full* key.
 *
 * [workspaceId]/[workspaceName] are the key's pinned workspace (D3): since slice 2 the
 * pin IS the key's request context — no `DP-Workspace` override exists for keys.
 */
data class ApiKey(
    val id: String,
    val userId: UUID,
    val name: String,
    val keyHash: String,
    val scopes: Set<Scope>,
    val isRevoked: Boolean,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
    val workspaceId: UUID,
    val workspaceName: String,
    /**
     * What this key IS (V11, §7.7). Defaults to [ApiKeyKind.DEFAULT] so every construction site
     * that predates kinds keeps meaning what it meant — the same choice the column's
     * `DEFAULT 'user'` makes for every stored row.
     */
    val kind: ApiKeyKind = ApiKeyKind.DEFAULT,
) {
    /** True when this key's authority is its endpoint bindings rather than its scopes (§7.7). */
    val isEndpointKey: Boolean get() = kind == ApiKeyKind.ENDPOINT

    /** True when this key's authority is the promotion route family rather than scopes (§7.7). */
    val isServerKey: Boolean get() = kind == ApiKeyKind.SERVER
}

/**
 * The plaintext half of a freshly issued key, returned to the caller exactly once
 * (auth.md §7.4). Only [record] is persisted; [plaintext] is never stored.
 */
data class IssuedApiKey(
    val record: ApiKey,
    val plaintext: String,
)
