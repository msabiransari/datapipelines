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
 * [userId] is WHO THE KEY ACTS AS (#215 PK5, keys v2 A13): the key's own `service` identity for
 * EVERY kind — an `mcp` key's identity holds the key's member role in this workspace exactly as a
 * member does. [createdBy] is who created it — the Keys page's "Created by" — and the subject a
 * member removal revokes keys by (A17).
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
     * What this key IS (V11, §7.7; `mcp` since keys v2 V37 — A19). No default on purpose: a kind
     * is a creation decision (A15 — the Keys page is the one creation path), and a caller that
     * forgets it must fail to compile rather than mint a surprise.
     */
    val kind: ApiKeyKind,
    /**
     * Whether `api_keys.secret_sealed` holds the openable plaintext (V31; keys v2: the sealed
     * copies that remain are the login-minted keys' unread ones, V37-migrated, until their first
     * read — no new key is ever minted with one). The flag travels on the model — the Keys page
     * renders Copy from it — while the sealed BLOB never does:
     * [ApiKeyRepository.openAndClearSealedSecret] is the only read that touches the column.
     */
    val hasSealedSecret: Boolean = false,
    /**
     * `api_keys.role` (V34; every live kind since keys v2 V37 — A13): the key role whose
     * [RolePermissions] column is the key's whole authority. Null ONLY on a revoked pre-v2
     * row the migration left untouched (the CHECK forbids it on a live one) — a live key
     * without a role never authenticates.
     */
    val role: KeyRole? = null,
    /** `api_keys.created_by` (V34, B4): the person who created the key. */
    val createdBy: UUID = userId,
) {
    /** True when this key's authority is [KeyRole.API_CALLER] on its bound paths (§7.7). */
    val isEndpointKey: Boolean get() = kind == ApiKeyKind.ENDPOINT

    /** True when this key's authority is [KeyRole.PROMOTION_RECEIVER] on the promotion route family (§7.7). */
    val isServerKey: Boolean get() = kind == ApiKeyKind.SERVER

    /** True when this key is an `mcp` key — a member-role credential over `/mcp` only (A13). */
    val isMcpKey: Boolean get() = kind == ApiKeyKind.MCP
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
