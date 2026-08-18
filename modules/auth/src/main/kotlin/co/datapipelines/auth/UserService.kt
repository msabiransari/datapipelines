package co.datapipelines.auth

import java.util.UUID

/**
 * User provisioning from an OIDC identity (auth.md §4.2), the per-request liveness
 * check (D13), and the audited administrative operations (§10.1). No JPA —
 * delegates to [UserRepository].
 *
 * ## Email normalization (§4.2)
 * Every lookup and store goes through [normalize]: trimmed and lowercased. A
 * provider that returns `Alice@Company.com` on one login and `alice@company.com` on
 * the next must resolve to one row — otherwise one human forks into two accounts and,
 * worse, the §4.4 bootstrap-admin comparison could mint a second admin.
 */
class UserService(
    private val userRepository: UserRepository,
    private val authCache: AuthCache,
    private val authProperties: AuthProperties,
    private val auditLogger: AuditLogger,
) {
    /**
     * Find-or-create by email (auth.md §4.2). On an existing account the OIDC
     * identity is (re)linked.
     *
     * Bootstrap-admin (§4.4) fires **only when the row is created**: a later login
     * changes nothing, and after an admin deliberately revokes admin
     * (`auth.user.admin_revoked`) this path never re-grants it. Re-instating admin is
     * an explicit administrative operation ([grantAdmin]), not a side effect of
     * logging in.
     */
    fun findOrCreateByEmail(
        email: String,
        displayName: String,
        pictureUrl: String?,
        provider: String,
        providerSubject: String,
    ): User {
        val normalized = normalize(email)
        val existing = userRepository.findByEmail(normalized)
        if (existing != null) {
            userRepository.updateIdentity(
                id = existing.id,
                displayName = displayName,
                profilePictureUrl = pictureUrl,
                provider = provider,
                providerSubject = providerSubject,
            )
            authCache.invalidateUser(existing.id)
            return checkNotNull(userRepository.findById(existing.id)) { "User ${existing.id} vanished mid-provisioning" }
        }

        val created =
            userRepository.insert(
                email = normalized,
                displayName = displayName,
                profilePictureUrl = pictureUrl,
                provider = provider,
                providerSubject = providerSubject,
                isAdmin = isBootstrapAdmin(normalized),
            )
        if (created.isAdmin) {
            auditLogger.log(
                event = "auth.user.admin_granted",
                userId = created.id,
                details = mapOf("actor" to "bootstrap", "email" to normalized),
            )
        }
        return created
    }

    fun updateLastLogin(id: UUID) = userRepository.updateLastLogin(id)

    /** Cached (D13, ~60s) `users.is_active` — read on every authenticated request. */
    fun isActive(id: UUID): Boolean = authCache.isUserActive(id) { userRepository.findById(it) }

    /** Cached (D13, ~60s) `users` snapshot — backs the API-key principal without a per-request query. */
    fun snapshot(id: UUID): User? = authCache.user(id) { userRepository.findById(it) }

    /** User-administration listing (§7.6 `USER_ADMINISTRATION`). */
    fun search(
        query: String,
        offset: Int,
        limit: Int,
    ): List<User> = userRepository.search(query, offset, limit)

    /**
     * Deactivates [targetId] (§4.2, §10.1 `auth.user.deactivated`). The liveness cache
     * is evicted immediately, so on this instance the user's sessions and API keys are
     * dead on the very next request rather than at TTL expiry.
     */
    fun deactivate(
        targetId: UUID,
        actorId: UUID,
    ): Boolean = setActive(targetId, active = false, actorId = actorId, event = "auth.user.deactivated")

    /** Reactivates [targetId] (§10.1 `auth.user.activated`). */
    fun activate(
        targetId: UUID,
        actorId: UUID,
    ): Boolean = setActive(targetId, active = true, actorId = actorId, event = "auth.user.activated")

    /** Grants admin (§10.1 `auth.user.admin_granted`, actor = the acting admin). */
    fun grantAdmin(
        targetId: UUID,
        actorId: UUID,
    ): Boolean = auditedFlip(targetId, actorId, "auth.user.admin_granted") { userRepository.grantAdmin(it) }

    /**
     * Revokes admin (§10.1 `auth.user.admin_revoked`). §4.4 is explicit that the
     * bootstrap path must not undo this on the next login — it fires only at row
     * creation, so a revoked admin stays revoked.
     */
    fun revokeAdmin(
        targetId: UUID,
        actorId: UUID,
    ): Boolean = auditedFlip(targetId, actorId, "auth.user.admin_revoked") { userRepository.revokeAdmin(it) }

    /** Stores the user's theme choice (metadata-db §4.1); `null` = follow the deployment default. */
    fun setThemePreference(
        id: UUID,
        theme: String?,
    ) {
        userRepository.setThemePreference(id, theme)
        authCache.invalidateUser(id)
    }

    private fun setActive(
        targetId: UUID,
        active: Boolean,
        actorId: UUID,
        event: String,
    ): Boolean = auditedFlip(targetId, actorId, event) { userRepository.setActive(it, active) }

    /**
     * Applies [flip], and on a real transition evicts the liveness cache and writes the
     * audit row. No transition → no event: `audit_log` records what changed, not every
     * click (§10.1).
     */
    private fun auditedFlip(
        targetId: UUID,
        actorId: UUID,
        event: String,
        flip: (UUID) -> Boolean,
    ): Boolean {
        val changed = flip(targetId)
        if (changed) {
            authCache.invalidateUser(targetId)
            auditLogger.log(event = event, userId = targetId, details = mapOf("actor" to actorId.toString()))
        }
        return changed
    }

    private fun isBootstrapAdmin(normalizedEmail: String): Boolean {
        val configured = authProperties.bootstrapAdminEmail?.let(::normalize)
        return !configured.isNullOrEmpty() && configured == normalizedEmail
    }

    private companion object {
        /** auth.md §4.2 — one canonical form for every lookup, store and comparison. */
        fun normalize(email: String): String = email.trim().lowercase()
    }
}
