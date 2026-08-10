package co.datapipelines.auth

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * `api_keys` persistence (metadata-db §4.2) via `NamedParameterJdbcTemplate`.
 * `scopes` is a Postgres `TEXT[]`; revocation is a soft flag (never a DELETE) so
 * `audit_log.key_id` keeps resolving (metadata-db §4.2 note).
 */
@Repository
class ApiKeyRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findById(id: String): ApiKey? =
        jdbc
            .query(
                "SELECT * FROM api_keys WHERE id = :id",
                MapSqlParameterSource("id", id),
                ::map,
            ).firstOrNull()

    fun findActiveByUser(userId: UUID): List<ApiKey> =
        jdbc.query(
            "SELECT * FROM api_keys WHERE user_id = :uid AND is_revoked = FALSE ORDER BY created_at DESC",
            MapSqlParameterSource("uid", userId),
            ::map,
        )

    fun insert(
        id: String,
        userId: UUID,
        name: String,
        keyHash: String,
        scopes: Set<Scope>,
        expiresAt: Instant?,
    ): ApiKey {
        val params =
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("user_id", userId)
                .addValue("name", name)
                .addValue("key_hash", keyHash)
                .addValue("scopes", scopes.map { it.wire }.toTypedArray())
                .addValue("expires_at", expiresAt?.let { java.sql.Timestamp.from(it) })
        return jdbc
            .query(
                """
                INSERT INTO api_keys (id, user_id, name, key_hash, scopes, expires_at)
                VALUES (:id, :user_id, :name, :key_hash, :scopes, :expires_at)
                RETURNING *
                """.trimIndent(),
                params,
                ::map,
            ).first()
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
        )
    }
}
