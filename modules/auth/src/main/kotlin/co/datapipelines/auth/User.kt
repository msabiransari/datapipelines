package co.datapipelines.auth

import java.time.Instant
import java.util.UUID

/**
 * A row of `users` (metadata-db §4.1). Authenticated via OIDC, or via an optional
 * local password (auth.md §5A) — the Argon2id hash itself is deliberately NOT on
 * this type: it is a secret, loaded only by [UserRepository.findLocalCredential]
 * on the login path, so it can never leak into a log line or a JSON payload that
 * serializes a principal.
 *
 * [provider] is free text — the OIDC registration name the deployment configured,
 * or a placeholder with meaning ([UserService.BOOTSTRAP_PROVIDER], [UserService.LOCAL_PROVIDER]).
 *
 * [email] is always the lowercase-normalized address (auth.md §4.2): provider case
 * differences must not fork one human into two rows.
 */
data class User(
    val id: UUID,
    val email: String,
    val displayName: String,
    val profilePictureUrl: String? = null,
    val provider: String,
    val providerSubject: String,
    val isActive: Boolean,
    val isAdmin: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastLoginAt: Instant? = null,
    /**
     * `users.theme_preference` (metadata-db §4.1). **`null` is meaningful:** it means
     * "no explicit choice — follow the deployment default `datapipelines.ui.theme`",
     * which is not the same as materializing today's default into the row.
     */
    val themePreference: String? = null,
    /**
     * `users.must_change_password` (metadata-db §4.1, auth.md §5A.4): while TRUE the
     * forced-change gate redirects every authenticated route to the change-password
     * screen. Safe on the principal-facing type — a boolean, not a secret.
     */
    val mustChangePassword: Boolean = false,
    /**
     * Whether `password_hash IS NOT NULL` (metadata-db §4.1) — the flag, never the
     * hash. Drives the user-administration table's local-access indicator (§5A.1).
     */
    val hasLocalPassword: Boolean = false,
    /** `users.locked_until` — the lockout horizon (§5A.3), for the admin table. */
    val lockedUntil: Instant? = null,
)
