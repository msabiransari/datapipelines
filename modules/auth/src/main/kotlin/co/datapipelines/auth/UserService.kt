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
                // display_name refreshes from the ID token on EVERY login (owner-ratified
                // 2026-08-28, 021 Deviation 3): no profile-edit feature exists, so a stored
                // name has no user-chosen referent to protect — freezing would leave an IdP
                // rename unrepresentable. The §6.1 bootstrap placeholder is replaced by this
                // same refresh at the first real sign-in.
                displayName = displayName,
                profilePictureUrl = pictureUrl,
                provider = provider,
                providerSubject = providerSubject,
            )
            authCache.invalidateUser(existing.id)
            return checkNotNull(userRepository.findById(existing.id)) { "User ${existing.id} vanished mid-provisioning" }
        }

        return createUser(
            normalizedEmail = normalized,
            displayName = displayName,
            pictureUrl = pictureUrl,
            provider = provider,
            providerSubject = providerSubject,
        )
    }

    /**
     * Pre-provisions the configured bootstrap admin (sample-data design §6.1, auth.md §4.4),
     * so `datasources.created_by` — `NOT NULL REFERENCES users(id)` — has a real, nameable
     * human to point at before anybody has logged in. Returns the actor row.
     *
     * **Not a second grant path.** It routes through the same [createUser] as §4.2's first
     * login, so `is_admin` is still decided by [isBootstrapAdmin] and the audit event is still
     * written exactly once, at row creation. When the row already exists — a restart, or the
     * admin logged in first, or an admin deliberately revoked admin — **nothing on it is
     * touched**: no re-grant, no identity rewrite, no `updated_at` bump.
     *
     * The placeholders (`provider = 'bootstrap'`, `provider_subject` = the email) satisfy the
     * NOT NULL columns and the `(provider, provider_subject)` uniqueness; the first real OIDC
     * login replaces them through §4.2's linking step.
     */
    fun provisionBootstrapActor(): User {
        val configured = authProperties.bootstrapAdminEmail?.let(::normalize)
        check(!configured.isNullOrEmpty()) {
            "datapipelines.auth.bootstrap-admin-email is required to pre-provision the bootstrap actor"
        }
        userRepository.findByEmail(configured)?.let { return it }
        return createUser(
            normalizedEmail = configured,
            displayName = configured.substringBefore('@'),
            pictureUrl = null,
            provider = BOOTSTRAP_PROVIDER,
            providerSubject = configured,
        )
    }

    /**
     * The ONE path that creates a `users` row (auth.md §4.4): insert, then — and only then —
     * the bootstrap-admin grant and its audit event. §4.2's first login and §6.1's
     * pre-provisioning are the same act at two different moments, not two mechanisms.
     */
    private fun createUser(
        normalizedEmail: String,
        displayName: String,
        pictureUrl: String?,
        provider: String,
        providerSubject: String,
    ): User {
        val created =
            userRepository.insert(
                email = normalizedEmail,
                displayName = displayName,
                profilePictureUrl = pictureUrl,
                provider = provider,
                providerSubject = providerSubject,
                isAdmin = isBootstrapAdmin(normalizedEmail),
            )
        if (created.isAdmin) {
            auditLogger.log(
                event = "auth.user.admin_granted",
                userId = created.id,
                details = mapOf("actor" to "bootstrap", "email" to normalizedEmail),
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

    companion object {
        /**
         * `users.provider` of a row that was pre-provisioned but never logged in
         * (design §6.1). It is the marker the §4.2 linking step reads to decide that this
         * login COMPLETES a placeholder identity rather than re-linking a real one.
         */
        const val BOOTSTRAP_PROVIDER = "bootstrap"

        /** auth.md §4.2 — one canonical form for every lookup, store and comparison. */
        private fun normalize(email: String): String = email.trim().lowercase()
    }
}
