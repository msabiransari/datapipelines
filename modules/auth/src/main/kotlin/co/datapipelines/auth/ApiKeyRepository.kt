package co.datapipelines.auth

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * `api_keys` persistence (metadata-db §4.2) via `NamedParameterJdbcTemplate`. Revocation is a
 * soft flag (never a DELETE) so `audit_log.key_id` keeps resolving (metadata-db §4.2 note); the
 * retention sweep's purge (keys v2 A17) is the ONE delete, and only of rows nothing references.
 *
 * Since V34 (#215) `user_id` is who the key ACTS AS — its own identity, for every kind since
 * keys v2 (A13) — and `created_by` is who created it. The reads that mean "the caller's keys"
 * ([findByUser], [revoke]) are keyed on `created_by`, so they keep returning exactly the keys a
 * person created; [revokeLiveByCreator] is the member-removal revocation (A17, B6).
 *
 * Workspace pinning (D3, slice 2): every key is pinned to exactly one workspace at
 * issuance — [insert] takes the id explicitly (no default anywhere; the creator's
 * reach is checked by [ApiKeyService.issue]) — and the pin IS the key's
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

    /**
     * Every key the user CREATED, revoked included — the `GET /api/v1/auth/api-keys` listing
     * (rest-api §16.1), whose `is_revoked` field is only meaningful when both values can appear
     * (gate C, F12c). Creator-scoped in SQL: every key they minted, of every kind.
     */
    fun findByUser(userId: UUID): List<ApiKey> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE k.created_by = :uid ORDER BY k.created_at DESC",
            MapSqlParameterSource("uid", userId),
            ::map,
        )

    /**
     * Live keys with this NAME in this workspace (074, promotion §19.5).
     *
     * A list, not a single row, for the readers that predate keys v2; since V37's unique index
     * on live `(workspace_id, name)` (A18) the list has at most one row — promotion still
     * refuses to guess if it ever sees more.
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
     * (their reach is the caller's check, auth.md §7.4). No default: a key
     * without an explicit workspace decision must not compile. [userId] is who the key acts as
     * (its identity, every kind since keys v2), [createdBy] who created it, and [role] the role
     * the creation chose (A13/A14) — the `chk_api_keys_role` CHECK states the kind/role families
     * in the database.
     */
    @Suppress("LongParameterList") // one row, spelled out; the alternative is a builder for one call site
    fun insert(
        id: String,
        userId: UUID,
        createdBy: UUID,
        name: String,
        keyHash: String,
        role: KeyRole?,
        expiresAt: Instant?,
        workspaceId: UUID,
        kind: ApiKeyKind,
    ): ApiKey {
        jdbc.update(
            """
            INSERT INTO api_keys (id, user_id, created_by, name, key_hash, role, expires_at, workspace_id, kind)
            VALUES (:id, :user_id, :created_by, :name, :key_hash, :role, :expires_at, :workspace_id, :kind)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("user_id", userId)
                .addValue("created_by", createdBy)
                .addValue("name", name)
                .addValue("key_hash", keyHash)
                .addValue("role", role?.wire)
                .addValue("expires_at", expiresAt?.let { java.sql.Timestamp.from(it) })
                .addValue("workspace_id", workspaceId)
                .addValue("kind", kind.wire),
        )
        return checkNotNull(findById(id)) { "api_keys row '$id' vanished immediately after insert" }
    }

    /**
     * Opens AND clears the sealed plaintext of [keyId] in one statement (show-once, #213 —
     * D16 amended 2026-09-23). The `FOR UPDATE` pre-image CTE is the read and the destruction
     * at once: no read-then-clear window in which two tabs both copy, and a concurrent second
     * open blocks on the lock and then sees `secret_sealed IS NOT NULL` fail — it gets null.
     * (Postgres 16 has no `RETURNING OLD.col`, so `UPDATE … RETURNING` alone cannot hand back
     * the value it just nulled.) Creator-scoped on [createdBy] (keys v2: the sealed copies in
     * flight are the migrated login keys of their creators; new keys carry none); null when the
     * row is not the caller's or when the copy was already read. Read ONLY by the one
     * show-once surface, never loaded onto the [ApiKey] model: a secret that rides every row
     * read is a secret one careless log serializes.
     */
    fun openAndClearSealedSecret(
        keyId: String,
        createdBy: UUID,
    ): ByteArray? =
        jdbc
            .query(
                """
                WITH old AS (
                    SELECT id, secret_sealed FROM api_keys
                    WHERE id = :id AND created_by = :uid AND secret_sealed IS NOT NULL
                    FOR UPDATE
                ),
                upd AS (
                    UPDATE api_keys SET secret_sealed = NULL WHERE id IN (SELECT id FROM old)
                )
                SELECT secret_sealed FROM old
                """.trimIndent(),
                MapSqlParameterSource("id", keyId).addValue("uid", createdBy),
            ) { rs, _ -> rs.getBytes("secret_sealed") }
            .firstOrNull()

    /**
     * Every key of [kind] pinned to [workspaceId], revoked included, newest first — the
     * `/api-keys` admin page's listing (D17). Unlike [findByUser] this is NOT owner-scoped:
     * the page administers the WORKSPACE's keys of the kinds it is asked for, whoever created
     * them.
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
     * Every key pinned to [workspaceId], revoked included, newest first (keys v2: the Keys page
     * lists the workspace's keys of EVERY kind it may show — kind filtering happens in the
     * caller, which knows what the caller may see).
     */
    fun findByWorkspace(workspaceId: UUID): List<ApiKey> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE k.workspace_id = :wid ORDER BY k.created_at DESC",
            MapSqlParameterSource("wid", workspaceId),
            ::map,
        )

    /**
     * Soft revoke of a key of [kind] in [workspaceId], NOT owner-scoped — the `/api-keys`
     * page's delete (D17: a workspace admin retires the workspace's API keys, not only their
     * own). The kind and workspace predicates are the safety rails: this can never touch a
     * key pinned elsewhere.
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
     * Soft revoke of EVERY LIVE key [userId] CREATED in [workspaceId] (keys v2 A17/B6 — the
     * member-removal safety, restated for created-by: removing a member ends the keys they
     * created here, of every kind). Returns the revoked key ids (the caller deactivates their
     * identities, evicts the [AuthCache] entries and audits each), empty when there were none —
     * "already gone" is the idempotent success the callers report as nothing-to-revoke, never
     * an error. The predicates ARE the safety rail: `created_by` AND `workspace_id` AND live,
     * in the one statement — no id-alone form, no read-then-revoke to drift into.
     */
    fun revokeLiveByCreator(
        userId: UUID,
        workspaceId: UUID,
    ): List<String> =
        jdbc
            .query(
                "UPDATE api_keys SET is_revoked = TRUE " +
                    "WHERE created_by = :uid AND workspace_id = :wid AND is_revoked = FALSE " +
                    "RETURNING id",
                MapSqlParameterSource("uid", userId).addValue("wid", workspaceId),
            ) { rs, _ -> rs.getString("id") }

    /**
     * Which people hold a LIVE key they CREATED in [workspaceId] (keys v2: the members row's
     * "has a key" state, restated from the login mint to created-by). Creator IDS only,
     * deliberately: a key id or prefix is the key listing's business (`/api-keys`), never the
     * members row's, and no plaintext exists to show (§7.4).
     */
    fun liveCreatorIds(workspaceId: UUID): Set<UUID> =
        jdbc
            .query(
                "SELECT created_by FROM api_keys WHERE workspace_id = :wid AND is_revoked = FALSE",
                MapSqlParameterSource("wid", workspaceId),
            ) { rs, _ -> rs.getObject("created_by", UUID::class.java) }
            .toSet()

    /**
     * True when a LIVE key named [name] already exists in [workspaceId] (keys v2 A18 — the
     * partial unique index makes it a database fact; this read is the create path's clean
     * refusal ahead of it, so a duplicate name answers a catalogued conflict instead of the
     * constraint's raw failure).
     */
    fun liveNameExists(
        workspaceId: UUID,
        name: String,
    ): Boolean =
        jdbc
            .query(
                "SELECT 1 FROM api_keys WHERE workspace_id = :wid AND name = :name AND is_revoked = FALSE LIMIT 1",
                MapSqlParameterSource("wid", workspaceId).addValue("name", name),
            ) { _, _ -> true }
            .firstOrNull() ?: false

    /**
     * Soft revoke of a key [userId] CREATED (the "your own keys" delete, `mcp_key.revoke_own`).
     * Returns the revoked key, or null when no live key of theirs had that id — the caller
     * deactivates the identity in the same transaction.
     */
    fun revoke(
        id: String,
        userId: UUID,
    ): ApiKey? {
        val flipped =
            jdbc.update(
                "UPDATE api_keys SET is_revoked = TRUE WHERE id = :id AND created_by = :uid AND is_revoked = FALSE",
                MapSqlParameterSource().addValue("id", id).addValue("uid", userId),
            ) > 0
        return if (flipped) findById(id) else null
    }

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
         * while `(k.secret_sealed IS NOT NULL)` is the fact the Keys page needs. `minted_at_login`
         * left with the login mint (keys v2 V37, A15).
         */
        val SELECT_COLUMNS =
            """
            SELECT k.id, k.user_id, k.created_by, k.name, k.key_hash, k.role, k.is_revoked, k.created_at,
                   k.last_used_at, k.last_used_ip, k.last_used_user_agent, k.expires_at,
                   k.workspace_id, k.kind,
                   (k.secret_sealed IS NOT NULL) AS has_sealed_secret,
                   w.name AS workspace_name
              FROM api_keys k
              JOIN workspaces w ON w.id = k.workspace_id
            """.trimIndent()
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNum: Int,
    ): ApiKey =
        ApiKey(
            id = rs.getString("id"),
            userId = rs.getObject("user_id", UUID::class.java),
            name = rs.getString("name"),
            keyHash = rs.getString("key_hash"),
            isRevoked = rs.getBoolean("is_revoked"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
            expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            workspaceName = rs.getString("workspace_name"),
            // V11, `mcp` since keys v2 V37 (A19). A row with an unexpected kind fails loudly
            // rather than authenticate — a key that cannot be classified authenticates for nobody.
            kind = ApiKeyKind.fromWire(rs.getString("kind")),
            hasSealedSecret = rs.getBoolean("has_sealed_secret"),
            // V34, widened V37. Null only on the revoked pre-v2 rows the migration left
            // untouched (a revoked viewer key carries no role); a LIVE row's CHECK forbids it.
            role = KeyRole.fromWireOrNull(rs.getString("role")),
            createdBy = rs.getObject("created_by", UUID::class.java),
        )
}
