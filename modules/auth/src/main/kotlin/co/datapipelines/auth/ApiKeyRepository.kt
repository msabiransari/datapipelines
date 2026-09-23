package co.datapipelines.auth

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * `api_keys` persistence (metadata-db §4.2) via `NamedParameterJdbcTemplate`.
 * `scopes` is a Postgres `TEXT[]`; revocation is a soft flag (never a DELETE) so
 * `audit_log.key_id` keeps resolving (metadata-db §4.2 note).
 *
 * Workspace pinning (D3, slice 2): every key is pinned to exactly one workspace at
 * issuance — [insert] takes the id explicitly (no default anywhere; the creator's
 * membership in it is checked by [ApiKeyService.issue]) — and the pin IS the key's
 * request context, so reads join `workspaces` to carry the name into the principal.
 */
class ApiKeyRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findById(id: String): ApiKey? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE k.id = :id",
                MapSqlParameterSource("id", id),
                ::map,
            ).firstOrNull()

    fun findActiveByUser(userId: UUID): List<ApiKey> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE k.user_id = :uid AND k.is_revoked = FALSE ORDER BY k.created_at DESC",
            MapSqlParameterSource("uid", userId),
            ::map,
        )

    /**
     * Every key the user owns, revoked included — the `GET /api/v1/auth/api-keys` listing
     * (rest-api §16.1), whose `is_revoked` field is only meaningful when both values can appear
     * (gate C, F12c). Owner-scoped in SQL, like everything else here.
     */

    fun findByUser(userId: UUID): List<ApiKey> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE k.user_id = :uid ORDER BY k.created_at DESC",
            MapSqlParameterSource("uid", userId),
            ::map,
        )

    /**
     * Live keys with this NAME in this workspace (074, promotion §19.5).
     *
     * A list, not a single row: `api_keys.name` carries no uniqueness constraint (metadata-db
     * §4.2), so a name can legitimately belong to several people in one workspace. The caller
     * decides what an ambiguous name means; promotion refuses to guess.
     */
    fun findByWorkspaceAndName(
        workspaceId: UUID,
        name: String,
    ): List<ApiKey> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE k.workspace_id = :workspaceId AND k.name = :name AND k.is_revoked = FALSE",
            mapOf("workspaceId" to workspaceId, "name" to name),
            ::map,
        )

    /**
     * Pins the new key to [workspaceId] — the workspace the creator resolved as active
     * (their membership in it is the caller's check, auth.md §7.4). No default: a key
     * without an explicit workspace decision must not compile.
     */
    @Suppress("LongParameterList") // one row, spelled out; the alternative is a builder for one call site
    fun insert(
        id: String,
        userId: UUID,
        name: String,
        keyHash: String,
        scopes: Set<Scope>,
        expiresAt: Instant?,
        workspaceId: UUID,
        kind: ApiKeyKind = ApiKeyKind.DEFAULT,
        secretSealed: ByteArray? = null,
        mintedAtLogin: Boolean = false,
    ): ApiKey {
        jdbc.update(
            """
            INSERT INTO api_keys (id, user_id, name, key_hash, scopes, expires_at, workspace_id, kind, secret_sealed, minted_at_login)
            VALUES (:id, :user_id, :name, :key_hash, :scopes, :expires_at, :workspace_id, :kind, :secret_sealed, :minted_at_login)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("user_id", userId)
                .addValue("name", name)
                .addValue("key_hash", keyHash)
                .addValue("scopes", scopes.map { it.wire }.toTypedArray())
                .addValue("expires_at", expiresAt?.let { java.sql.Timestamp.from(it) })
                .addValue("workspace_id", workspaceId)
                .addValue("kind", kind.wire)
                .addValue("secret_sealed", secretSealed)
                .addValue("minted_at_login", mintedAtLogin),
        )
        return checkNotNull(findById(id)) { "api_keys row '$id' vanished immediately after insert" }
    }

    /**
     * The caller's ONE live `user` key in [workspaceId] — the login-minted MCP key (D16,
     * V31's partial unique index makes "one" a database fact, not a hope). Null when the
     * login hook has not minted yet (or the user rotated and has not signed in since).
     */
    fun findLiveUserKey(
        userId: UUID,
        workspaceId: UUID,
    ): ApiKey? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE k.user_id = :uid AND k.workspace_id = :wid " +
                    "AND k.kind = 'user' AND k.is_revoked = FALSE ORDER BY k.created_at DESC",
                MapSqlParameterSource("uid", userId).addValue("wid", workspaceId),
                ::map,
            ).firstOrNull()

    /**
     * Opens AND clears the sealed plaintext of [keyId] in one statement (show-once, #213 —
     * D16 amended 2026-09-23). The `FOR UPDATE` pre-image CTE is the read and the destruction
     * at once: no read-then-clear window in which two tabs both copy, and a concurrent second
     * open blocks on the lock and then sees `secret_sealed IS NOT NULL` fail — it gets null.
     * (Postgres 16 has no `RETURNING OLD.col`, so `UPDATE … RETURNING` alone cannot hand back
     * the value it just nulled.) Owner-scoped on [userId]; null when the row is not the
     * caller's, when the copy was already read, or for rows minted before R3 and non-user
     * kinds. Read ONLY by the copy surface, never loaded onto the [ApiKey] model: a secret
     * that rides every row read is a secret that one careless log serializes.
     */
    fun openAndClearSealedSecret(
        keyId: String,
        userId: UUID,
    ): ByteArray? =
        jdbc
            .query(
                """
                WITH old AS (
                    SELECT id, secret_sealed FROM api_keys
                    WHERE id = :id AND user_id = :uid AND secret_sealed IS NOT NULL
                    FOR UPDATE
                ),
                upd AS (
                    UPDATE api_keys SET secret_sealed = NULL WHERE id IN (SELECT id FROM old)
                )
                SELECT secret_sealed FROM old
                """.trimIndent(),
                MapSqlParameterSource("id", keyId).addValue("uid", userId),
            ) { rs, _ -> rs.getBytes("secret_sealed") }
            .firstOrNull()

    /**
     * Every key of [kind] pinned to [workspaceId], revoked included, newest first — the
     * `/api-keys` admin page's listing (D17). Unlike [findByUser] this is NOT owner-scoped:
     * the page administers the WORKSPACE's API keys, whoever created them.
     */
    fun findByWorkspaceAndKind(
        workspaceId: UUID,
        kind: ApiKeyKind,
    ): List<ApiKey> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE k.workspace_id = :wid AND k.kind = :kind ORDER BY k.created_at DESC",
            MapSqlParameterSource("wid", workspaceId).addValue("kind", kind.wire),
            ::map,
        )

    /**
     * Soft revoke of a key of [kind] in [workspaceId], NOT owner-scoped — the `/api-keys`
     * page's delete (D17: a workspace admin retires the workspace's API keys, not only their
     * own). The kind and workspace predicates are the safety rails: this can never touch a
     * user's MCP key or a key pinned elsewhere.
     */
    fun revokeInWorkspace(
        id: String,
        workspaceId: UUID,
        kind: ApiKeyKind,
    ): Boolean =
        jdbc.update(
            "UPDATE api_keys SET is_revoked = TRUE WHERE id = :id AND workspace_id = :wid AND kind = :kind AND is_revoked = FALSE",
            MapSqlParameterSource().addValue("id", id).addValue("wid", workspaceId).addValue("kind", kind.wire),
        ) > 0

    /**
     * The ONE live `user` key [userId] holds in [workspaceId] — revoked (roles record §3.7,
     * ruling 1/3, #200): a key is tied to user + workspace, so ending the membership — or an
     * admin's explicit revoke — ends the key.
     *
     * The predicates ARE the safety rail, all four in the one statement: `user_id` AND
     * `workspace_id` AND `kind = 'user'` AND live. The statement can never touch an
     * endpoint/server key or another workspace's key, and there is no id-alone form and no
     * read-then-revoke to drift into. Returns the revoked key's id (the caller evicts the
     * [AuthCache] entry and audits), or null when no live key existed — "already gone" is the
     * idempotent success the callers report as nothing-to-revoke, never an error.
     */
    fun revokeUserKeyForWorkspace(
        userId: UUID,
        workspaceId: UUID,
    ): String? =
        jdbc
            .query(
                "UPDATE api_keys SET is_revoked = TRUE " +
                    "WHERE user_id = :uid AND workspace_id = :wid AND kind = 'user' AND is_revoked = FALSE " +
                    "RETURNING id",
                MapSqlParameterSource("uid", userId).addValue("wid", workspaceId),
            ) { rs, _ -> rs.getString("id") }
            .firstOrNull()

    /**
     * Which users of [workspaceId] hold a live `user` key (#200) — the members row's
     * "has a key" state on the admin's own members table. Owner IDS only, deliberately: a
     * key id or prefix is the key listing's business (`/api-keys`), never the members row's,
     * and no plaintext exists to show (§7.4 — sealed, opened only by the owner's top bar).
     */
    fun liveUserKeyOwnerIds(workspaceId: UUID): Set<UUID> =
        jdbc
            .query(
                "SELECT user_id FROM api_keys WHERE workspace_id = :wid AND kind = 'user' AND is_revoked = FALSE",
                MapSqlParameterSource("wid", workspaceId),
            ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }
            .toSet()

    /**
     * Soft revoke. Returns true if a live key was flipped. Owner check enforced by caller.
     */
    fun revoke(
        id: String,
        userId: UUID,
    ): Boolean =
        jdbc.update(
            "UPDATE api_keys SET is_revoked = TRUE WHERE id = :id AND user_id = :uid AND is_revoked = FALSE",
            MapSqlParameterSource().addValue("id", id).addValue("uid", userId),
        ) > 0

    /** Best-effort usage stamp (auth.md §7.3 step 9) — fire-and-forget by the caller. */
    fun touchUsage(
        id: String,
        sourceIp: String?,
        userAgent: String?,
    ) {
        jdbc.update(
            """
            UPDATE api_keys
               SET last_used_at = NOW(),
                   last_used_ip = CAST(:ip AS INET),
                   last_used_user_agent = :ua
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ip", sourceIp)
                .addValue("ua", userAgent),
        )
    }

    private companion object {
        /**
         * Every read joins `workspaces` so the pinned workspace's name reaches the principal
         * (D3). The columns are EXPLICIT since V31 rather than `k.*`: `secret_sealed` is the
         * one column this projection must never return — a sealed secret riding the
         * hot validation path's row object is a secret one careless log away from a leak —
         * while `(k.secret_sealed IS NOT NULL)` is the fact the top bar needs.
         */
        val SELECT_COLUMNS =
            """
            SELECT k.id, k.user_id, k.name, k.key_hash, k.scopes, k.is_revoked, k.created_at,
                   k.last_used_at, k.last_used_ip, k.last_used_user_agent, k.expires_at,
                   k.workspace_id, k.kind, k.minted_at_login,
                   (k.secret_sealed IS NOT NULL) AS has_sealed_secret,
                   w.name AS workspace_name
              FROM api_keys k
              JOIN workspaces w ON w.id = k.workspace_id
            """.trimIndent()
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNum: Int,
    ): ApiKey {
        @Suppress("UNCHECKED_CAST")
        val rawScopes = (rs.getArray("scopes").array as Array<Any?>).mapNotNull { it as String? }
        return ApiKey(
            id = rs.getString("id"),
            userId = rs.getObject("user_id", UUID::class.java),
            name = rs.getString("name"),
            keyHash = rs.getString("key_hash"),
            scopes = rawScopes.map { Scope.fromWire(it) }.toSet(),
            isRevoked = rs.getBoolean("is_revoked"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
            expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            workspaceName = rs.getString("workspace_name"),
            // V11. A row written before the column existed reads as the column's DEFAULT, so
            // there is no null to tolerate — but fromWire would throw on an unexpected value,
            // and a key that cannot be classified must fail loudly rather than authenticate.
            kind = ApiKeyKind.fromWire(rs.getString("kind")),
            hasSealedSecret = rs.getBoolean("has_sealed_secret"),
            mintedAtLogin = rs.getBoolean("minted_at_login"),
        )
    }
}
