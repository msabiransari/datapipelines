package co.datapipelines.auth

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * The `users` fields the local login path needs (auth.md §5A, metadata-db §4.1),
 * including the Argon2id [passwordHash] — deliberately NOT part of [User], so the
 * secret exists only between this query and the `SecretHasher.verify` call.
 */
data class LocalCredential(
    val userId: UUID,
    val passwordHash: String,
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
)

/** Post-update state of one failed local-login attempt (auth.md §5A.3). */
data class LocalLoginFailure(
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
)

/**
 * `users` persistence (metadata-db §4.1) via `NamedParameterJdbcTemplate` — no JPA
 * (auth.md §12.3). Every UPDATE sets `updated_at = NOW()` in its own SET clause
 * (metadata-db §2: "an UPDATE that forgets updated_at is a bug in the repository").
 *
 * Email is stored and matched **lowercase** (auth.md §4.2). Normalization is the
 * caller's contract ([UserService]); this repository additionally lowercases on the
 * lookup path so a stray mixed-case argument cannot miss an existing row.
 */
class UserRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findByEmail(email: String): User? =
        jdbc
            .query(
                "SELECT * FROM users WHERE email = :email",
                MapSqlParameterSource("email", email.trim().lowercase()),
                ::map,
            ).firstOrNull()

    fun findById(id: UUID): User? =
        jdbc
            .query(
                "SELECT * FROM users WHERE id = :id",
                MapSqlParameterSource("id", id),
                ::map,
            ).firstOrNull()

    /** Just the liveness flag — the hot per-request read (metadata-db §4.1 note). */
    fun isActive(id: UUID): Boolean? =
        jdbc
            .query(
                "SELECT is_active FROM users WHERE id = :id",
                MapSqlParameterSource("id", id),
            ) { rs, _ -> rs.getBoolean("is_active") }
            .firstOrNull()

    fun insert(
        email: String,
        displayName: String,
        profilePictureUrl: String?,
        provider: String,
        providerSubject: String,
        isAdmin: Boolean,
    ): User {
        val params =
            MapSqlParameterSource()
                .addValue("email", email.trim().lowercase())
                .addValue("display_name", displayName)
                .addValue("profile_picture_url", profilePictureUrl)
                .addValue("provider", provider)
                .addValue("provider_subject", providerSubject)
                .addValue("is_admin", isAdmin)
        return jdbc
            .query(
                """
                INSERT INTO users (email, display_name, profile_picture_url, provider, provider_subject, is_admin)
                VALUES (:email, :display_name, :profile_picture_url, :provider, :provider_subject, :is_admin)
                RETURNING *
                """.trimIndent(),
                params,
                ::map,
            ).first()
    }

    /**
     * Links an existing account to a (possibly new) OIDC identity on re-login (§4.2).
     *
     * [displayName] refreshes on every login from the ID token's `name` claim — the
     * long-standing behavior, owner-ratified 2026-08-28 (021 Deviation 3): no profile-edit
     * feature exists, so there is no user-chosen name to protect; the §6.1 bootstrap
     * placeholder is replaced by this same refresh at the first real sign-in.
     */
    fun updateIdentity(
        id: UUID,
        displayName: String?,
        profilePictureUrl: String?,
        provider: String,
        providerSubject: String,
    ) {
        jdbc.update(
            """
            UPDATE users
               SET display_name = :display_name,
                   profile_picture_url = :profile_picture_url,
                   provider = :provider,
                   provider_subject = :provider_subject,
                   updated_at = NOW()
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("display_name", displayName)
                .addValue("profile_picture_url", profilePictureUrl)
                .addValue("provider", provider)
                .addValue("provider_subject", providerSubject),
        )
    }

    /** Idempotently grants admin (§4.4 / §10.1). Returns true if it flipped. */
    fun grantAdmin(id: UUID): Boolean =
        jdbc.update(
            "UPDATE users SET is_admin = TRUE, updated_at = NOW() WHERE id = :id AND is_admin = FALSE",
            MapSqlParameterSource("id", id),
        ) > 0

    /**
     * Idempotently revokes admin (§10.1 `auth.user.admin_revoked`). Returns true if it
     * flipped. Once revoked, the §4.4 bootstrap path never re-grants — that path fires
     * only at row creation.
     */
    fun revokeAdmin(id: UUID): Boolean =
        jdbc.update(
            "UPDATE users SET is_admin = FALSE, updated_at = NOW() WHERE id = :id AND is_admin = TRUE",
            MapSqlParameterSource("id", id),
        ) > 0

    /**
     * Activates / deactivates a user (§4.2, §10.1). Returns true if the flag changed,
     * so the caller only audits and invalidates on a real transition.
     */
    fun setActive(
        id: UUID,
        active: Boolean,
    ): Boolean =
        jdbc.update(
            "UPDATE users SET is_active = :active, updated_at = NOW() WHERE id = :id AND is_active <> :active",
            MapSqlParameterSource().addValue("id", id).addValue("active", active),
        ) > 0

    /**
     * User-administration search (§7.6 `USER_ADMINISTRATION`). Case-insensitive
     * substring match over email and display name; a blank [query] lists everyone.
     * Paged by [offset]/[limit] with a stable ordering so pages do not overlap.
     */
    fun search(
        query: String,
        offset: Int,
        limit: Int,
    ): List<User> {
        val term = query.trim().lowercase()
        return jdbc.query(
            """
            SELECT * FROM users
             WHERE :term = ''
                OR LOWER(email) LIKE '%' || :term || '%'
                OR LOWER(display_name) LIKE '%' || :term || '%'
             ORDER BY email
             OFFSET :offset LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("term", term)
                .addValue("offset", offset)
                .addValue("limit", limit),
            ::map,
        )
    }

    /**
     * The local-login credential fields (auth.md §5A, metadata-db §4.1) — the ONLY
     * read path that surfaces `password_hash`, kept off [User] so the secret cannot
     * leak through a serialized principal. Returns null when no such row exists OR
     * the account is OIDC-only (`password_hash IS NULL`): the login service collapses
     * both to the same "bad credentials" outcome, so the two are indistinguishable
     * to a caller (and to an attacker probing for valid emails).
     */
    fun findLocalCredential(email: String): LocalCredential? =
        jdbc
            .query(
                """
                SELECT id, password_hash, failed_login_count, locked_until
                  FROM users
                 WHERE email = :email AND password_hash IS NOT NULL
                """.trimIndent(),
                MapSqlParameterSource("email", email.trim().lowercase()),
            ) { rs, _ ->
                LocalCredential(
                    userId = rs.getObject("id", UUID::class.java),
                    passwordHash = rs.getString("password_hash"),
                    failedLoginCount = rs.getInt("failed_login_count"),
                    lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
                )
            }.firstOrNull()

    /**
     * Sets (or replaces) the local password: stores the Argon2id [hash], stamps
     * `password_changed_at`, sets `must_change_password` per [mustChange] (TRUE for
     * every seed and admin reset — the credential was chosen by someone other than
     * its user), and clears any lockout so the new credential is immediately usable.
     */
    fun setPassword(
        id: UUID,
        hash: String,
        mustChange: Boolean,
    ) {
        jdbc.update(
            """
            UPDATE users
               SET password_hash = :hash,
                   password_changed_at = NOW(),
                   must_change_password = :must_change,
                   failed_login_count = 0,
                   locked_until = NULL,
                   updated_at = NOW()
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("hash", hash)
                .addValue("must_change", mustChange),
        )
    }

    /**
     * Removes local access (auth.md §5A): the account becomes OIDC-only again.
     * Returns true only on a real transition (a hash was present), so the caller
     * audits what changed, not every click (§10.1).
     */
    fun clearPassword(id: UUID): Boolean =
        jdbc.update(
            """
            UPDATE users
               SET password_hash = NULL,
                   password_changed_at = NULL,
                   must_change_password = FALSE,
                   failed_login_count = 0,
                   locked_until = NULL,
                   updated_at = NOW()
             WHERE id = :id AND password_hash IS NOT NULL
            """.trimIndent(),
            MapSqlParameterSource("id", id),
        ) > 0

    /** Successful local login: clears the lockout counters and stamps last_login_at. */
    fun recordLocalLoginSuccess(id: UUID) {
        jdbc.update(
            """
            UPDATE users
               SET failed_login_count = 0,
                   locked_until = NULL,
                   last_login_at = NOW(),
                   updated_at = NOW()
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource("id", id),
        )
    }

    /**
     * One failed local-login attempt (auth.md §5A.3), atomically: increments
     * `failed_login_count` and, when the count reaches [maxFailures], sets
     * `locked_until` [lockMinutes] into the future. Atomic in a single
     * UPDATE...RETURNING so concurrent sprays cannot lose increments and slide
     * under the lockout threshold.
     */
    fun recordLocalLoginFailure(
        id: UUID,
        maxFailures: Int,
        lockMinutes: Long,
    ): LocalLoginFailure =
        jdbc
            .query(
                """
                UPDATE users
                   SET failed_login_count = failed_login_count + 1,
                       locked_until = CASE
                           WHEN failed_login_count + 1 >= :max_failures
                           THEN NOW() + (:lock_minutes * INTERVAL '1 minute')
                           ELSE locked_until END,
                       updated_at = NOW()
                 WHERE id = :id
                RETURNING failed_login_count, locked_until
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("max_failures", maxFailures)
                    .addValue("lock_minutes", lockMinutes),
            ) { rs, _ ->
                LocalLoginFailure(
                    failedLoginCount = rs.getInt("failed_login_count"),
                    lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
                )
            }.first()

    /** Admin unlock (auth.md §5A.3). Returns true only when something was locked/failed. */
    fun clearLockout(id: UUID): Boolean =
        jdbc.update(
            """
            UPDATE users
               SET failed_login_count = 0, locked_until = NULL, updated_at = NOW()
             WHERE id = :id AND (failed_login_count > 0 OR locked_until IS NOT NULL)
            """.trimIndent(),
            MapSqlParameterSource("id", id),
        ) > 0

    fun updateLastLogin(id: UUID) {
        jdbc.update(
            "UPDATE users SET last_login_at = NOW(), updated_at = NOW() WHERE id = :id",
            MapSqlParameterSource("id", id),
        )
    }

    /**
     * Sets the stored theme preference (metadata-db §4.1, ui-screens §4.11).
     * `null` clears it back to "follow the deployment default".
     */
    fun setThemePreference(
        id: UUID,
        theme: String?,
    ) {
        jdbc.update(
            "UPDATE users SET theme_preference = :theme, updated_at = NOW() WHERE id = :id",
            MapSqlParameterSource().addValue("id", id).addValue("theme", theme),
        )
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNum: Int,
    ): User =
        User(
            id = rs.getObject("id", UUID::class.java),
            email = rs.getString("email"),
            displayName = rs.getString("display_name"),
            profilePictureUrl = rs.getString("profile_picture_url"),
            provider = rs.getString("provider"),
            providerSubject = rs.getString("provider_subject"),
            isActive = rs.getBoolean("is_active"),
            isAdmin = rs.getBoolean("is_admin"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            lastLoginAt = rs.getTimestamp("last_login_at")?.toInstant(),
            themePreference = rs.getString("theme_preference"),
            mustChangePassword = rs.getBoolean("must_change_password"),
        )
}
