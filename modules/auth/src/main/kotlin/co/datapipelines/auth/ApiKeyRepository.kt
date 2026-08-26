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
     * Pins the new key to [workspaceId] — the workspace the creator resolved as active
     * (their membership in it is the caller's check, auth.md §7.4). No default: a key
     * without an explicit workspace decision must not compile.
     */
    fun insert(
        id: String,
        userId: UUID,
        name: String,
        keyHash: String,
        scopes: Set<Scope>,
        expiresAt: Instant?,
        workspaceId: UUID,
    ): ApiKey {
        jdbc.update(
            """
            INSERT INTO api_keys (id, user_id, name, key_hash, scopes, expires_at, workspace_id)
            VALUES (:id, :user_id, :name, :key_hash, :scopes, :expires_at, :workspace_id)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("user_id", userId)
                .addValue("name", name)
                .addValue("key_hash", keyHash)
                .addValue("scopes", scopes.map { it.wire }.toTypedArray())
                .addValue("expires_at", expiresAt?.let { java.sql.Timestamp.from(it) })
                .addValue("workspace_id", workspaceId),
        )
        return checkNotNull(findById(id)) { "api_keys row '$id' vanished immediately after insert" }
    }

    /** Soft revoke. Returns true if a live key was flipped. Owner check enforced by caller. */
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
        /** Every read joins `workspaces` so the pinned workspace's name reaches the principal (D3). */
        val SELECT_COLUMNS =
            """
            SELECT k.*, w.name AS workspace_name
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
        )
    }
}
