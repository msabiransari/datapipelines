package co.datapipelines.auth

import java.time.Instant
import java.util.UUID

/**
 * A row of `users` (metadata-db §4.1). OIDC-authenticated; no password column.
 * [provider] is free text — the OIDC registration name the deployment configured.
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
)
