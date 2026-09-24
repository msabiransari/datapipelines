package co.datapipelines.auth

import java.time.Instant
import java.util.UUID

/**
 * A row of `api_keys` (metadata-db §4.2). [id] is the public `dpk_<key_id>`
 * handle (not a UUID); [keyHash] is the Argon2id hash of the *full* key.
 *
 * [workspaceId]/[workspaceName] are the key's pinned workspace (D3): since slice 2 the
 * pin IS the key's request context — no `DP-Workspace` override exists for keys.
 *
 * [userId] is WHO THE KEY ACTS AS (#215, PK5): the member for the MCP (`user`) key, the key's
 * own `service` identity for an `endpoint` or `server` key. [createdBy] is who created it — the
 * Keys page's "Created by" — and, for the MCP key, the same member (B4).
 */
data class ApiKey(
    val id: String,
    val userId: UUID,
    val name: String,
    val keyHash: String,
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
    /**
     * Whether `api_keys.secret_sealed` holds the openable plaintext (V31, D16; show-once since
     * #213 — the flag reads false from the first Copy on). The flag travels on the model — the
     * top bar renders Copy from it — while the sealed BLOB never does:
     * [ApiKeyRepository.openAndClearSealedSecret] is the only read that touches the column.
     */
    val hasSealedSecret: Boolean = false,
    /** True when the login/switch hook minted this key rather than a person on demand (D16). */
    val mintedAtLogin: Boolean = false,
    /** `api_keys.role` (V34): the key role of an identity-acting kind; null for the MCP key (PK4). */
    val role: KeyRole? = KeyRole.forKind(kind),
    /** `api_keys.created_by` (V34, B4): the person who created the key. */
    val createdBy: UUID = userId,
) {
    /** True when this key's authority is [KeyRole.API_CALLER] on its bound paths (§7.7). */
    val isEndpointKey: Boolean get() = kind == ApiKeyKind.ENDPOINT

    /** True when this key's authority is [KeyRole.PROMOTION_RECEIVER] on the promotion route family (§7.7). */
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

/**
 * A presented server key that passed every check the promotion route asks (§7.7): the [key] and the
 * `service` identity it acts as (#215 record C4) — received versions and the audit row name
 * [identity], not the System actor.
 */
data class ValidatedServerKey(
    val key: ApiKey,
    val identity: User,
)
