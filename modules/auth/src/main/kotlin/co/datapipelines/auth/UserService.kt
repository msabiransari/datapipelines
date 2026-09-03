package co.datapipelines.auth

import org.springframework.dao.DuplicateKeyException
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
            return linkIdentity(existing.id, displayName, pictureUrl, provider, providerSubject)
        }

        return try {
            createUser(
                normalizedEmail = normalized,
                displayName = displayName,
                pictureUrl = pictureUrl,
                provider = provider,
                providerSubject = providerSubject,
            )
        } catch (_: DuplicateKeyException) {
            // A user's two concurrent FIRST logins on different replicas both pass the
            // find above; one wins the insert. Losing must not 500 the login (ARCH-AUDIT M6):
            // re-read and link, exactly as if the row had already been there. Same
            // catch-and-reread shape as LocalPasswordService.createLocalUser.
            val winner =
                checkNotNull(userRepository.findByEmail(normalized)) {
                    "User $normalized lost the insert race but is absent on re-read"
                }
            return linkIdentity(winner.id, displayName, pictureUrl, provider, providerSubject)
        }
    }

    /**
     * The §4.2 identity (re)link for an account that already exists: refresh the stored
     * identity from the ID token, evict the liveness cache, return the fresh row.
     */
    private fun linkIdentity(
        id: UUID,
        displayName: String,
        pictureUrl: String?,
        provider: String,
        providerSubject: String,
    ): User {
        userRepository.updateIdentity(
            id = id,
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
        authCache.invalidateUser(id)
        return checkNotNull(userRepository.findById(id)) { "User $id vanished mid-provisioning" }
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
     *
     * Multi-instance first boot: two replicas can race the find-then-insert; the loser catches
     * `DuplicateKeyException` and re-reads the winner's row (ARCH-AUDIT M5).
     */
    fun provisionBootstrapActor(): User {
        val configured = authProperties.bootstrapAdminEmail?.let(::normalize)
        check(!configured.isNullOrEmpty()) {
            "datapipelines.auth.bootstrap-admin-email is required to pre-provision the bootstrap actor"
        }
        userRepository.findByEmail(configured)?.let { return it }
        return try {
            createUser(
                normalizedEmail = configured,
                displayName = configured.substringBefore('@'),
                pictureUrl = null,
                provider = BOOTSTRAP_PROVIDER,
                providerSubject = configured,
            )
        } catch (_: DuplicateKeyException) {
            // Two instances seeding against one fresh database race here (ARCH-AUDIT M5):
            // both pass the find above, one wins the insert, and the loser would otherwise
            // crash its own context startup inside afterSingletonsInstantiated(). The winner's
            // row is exactly what "create-if-absent" wants kept — re-read and return it, the
            // same catch-and-reread shape as LocalPasswordService.createLocalUser.
            checkNotNull(userRepository.findByEmail(configured)) {
                "Bootstrap actor $configured lost the insert race but is absent on re-read"
            }
        }
    }

    /**
     * Pre-provisions the **system service account** (auth.md §4.5, versioning §10.6 / R7) — the
     * actor every write the SYSTEM makes on nobody's behalf is stamped with, so
     * `pipeline_versions.created_by` and `pipeline_executions.triggered_by` (both
     * `NOT NULL REFERENCES users(id)`) point at a real, nameable row without inventing a human.
     *
     * Promotion is its first consumer: a promoted row carries no source user id that means
     * anything here, and the credential that authorised the push is a shared server key, not a
     * principal. The retention job, the stale-execution sweeper and every future automated write
     * take the SAME row through [systemActor] — one actor for the whole system, so nobody mints
     * a second.
     *
     * ## Login is disabled by construction, not by a flag
     *
     * - `provider` is [SYSTEM_PROVIDER], and no OIDC provider may be NAMED `system` — startup
     *   refuses it exactly as it refuses `bootstrap` and `local` (configuration.md §7), so no
     *   external identity can ever link to this row through §4.2's `linkIdentity`.
     * - `email` is [SYSTEM_ACTOR_EMAIL], under RFC 2606's reserved `.invalid` TLD: it cannot
     *   resolve, so no mail-based flow can reach it.
     * - The local-password paths refuse it ([LocalPasswordService]), so the row can never
     *   acquire a credential to log in WITH.
     *
     * Same create-if-absent contract as [provisionBootstrapActor]: an existing row is returned
     * untouched — no re-grant, no identity rewrite, no `updated_at` bump — and the two-replica
     * first-boot insert race is settled by catch-and-reread (ARCH-AUDIT M5).
     */
    fun provisionSystemActor(): User {
        userRepository.findByEmail(SYSTEM_ACTOR_EMAIL)?.let { return it }
        return try {
            createUser(
                normalizedEmail = SYSTEM_ACTOR_EMAIL,
                displayName = SYSTEM_ACTOR_DISPLAY_NAME,
                pictureUrl = null,
                provider = SYSTEM_PROVIDER,
                providerSubject = SYSTEM_ACTOR_SUBJECT,
            )
        } catch (_: DuplicateKeyException) {
            // Two instances seeding one fresh database race here, exactly as the bootstrap
            // actor does (ARCH-AUDIT M5): the loser re-reads the winner's row rather than
            // crashing its own context inside afterSingletonsInstantiated().
            checkNotNull(userRepository.findByEmail(SYSTEM_ACTOR_EMAIL)) {
                "System actor $SYSTEM_ACTOR_EMAIL lost the insert race but is absent on re-read"
            }
        }
    }

    /**
     * The ONE well-known lookup of the system service account (R7).
     *
     * Every non-user-bound write in the system stamps THIS row. It is provisioned at boot by
     * [SystemActorSeeder] before the connector accepts traffic, so absence here is a wiring
     * bug, not a runtime condition — hence the check rather than a null return.
     */
    fun systemActor(): User =
        checkNotNull(userRepository.findByEmail(SYSTEM_ACTOR_EMAIL)) {
            "The system actor row is absent; SystemActorSeeder must provision it at boot (auth.md §4.5)"
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

    /**
     * Creates an admin-created local account (auth.md §5A.1) through the ONE
     * creation path ([createUser]): the §4.4 bootstrap grant still fires exactly
     * here — an admin who creates the bootstrap address creates an admin, the
     * same rule as every other path — and the caller audits `auth.user.created`
     * with the acting admin's id. The `provider = 'local'` placeholder
     * ([LOCAL_PROVIDER]) marks "no OIDC identity linked"; §4.2's linking step
     * replaces it if the person later signs in via OIDC. [email] is normalized
     * by the caller.
     */
    fun createLocalAccount(
        normalizedEmail: String,
        displayName: String,
    ): User {
        // §4.5: the system service account is not an account anybody administers. Its row
        // already holds this email, so the insert would fail on the UNIQUE constraint anyway —
        // refusing here makes the reason legible instead of leaving it to a race-shaped error.
        require(normalizedEmail != SYSTEM_ACTOR_EMAIL) {
            "$SYSTEM_ACTOR_EMAIL is the reserved system service account (auth.md §4.5); it has no local credential"
        }
        return createUser(
            normalizedEmail = normalizedEmail,
            displayName = displayName,
            pictureUrl = null,
            provider = LOCAL_PROVIDER,
            providerSubject = normalizedEmail,
        )
    }

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

        /**
         * `users.provider` of an admin-created local account (auth.md §5A): no OIDC
         * identity exists, so — like [BOOTSTRAP_PROVIDER] — this is a placeholder with
         * meaning, replaced by §4.2's linking step if the person later signs in via
         * OIDC with the same email.
         */
        const val LOCAL_PROVIDER = "local"

        /**
         * `users.provider` of the SYSTEM service account (auth.md §4.5, R7) — the actor every
         * non-user-bound write is stamped with. Reserved at startup validation exactly as
         * [BOOTSTRAP_PROVIDER] and [LOCAL_PROVIDER] are: an OIDC provider named `system` would
         * be indistinguishable from it and could link an external identity to the row.
         */
        const val SYSTEM_PROVIDER = "system"

        /**
         * The system actor's `users.email`. RFC 2606 reserves the `.invalid` TLD as
         * permanently unresolvable, so this address cannot receive mail by construction — the
         * property that makes it safe to hold a row nobody owns.
         */
        const val SYSTEM_ACTOR_EMAIL = "system@system.invalid"

        /** The system actor's fixed `users.provider_subject` sentinel — never a real subject claim. */
        const val SYSTEM_ACTOR_SUBJECT = "system"

        /** What the system actor is called wherever a display name is rendered (history, audit). */
        const val SYSTEM_ACTOR_DISPLAY_NAME = "System"

        /** auth.md §4.2 — one canonical form for every lookup, store and comparison. */
        private fun normalize(email: String): String = email.trim().lowercase()
    }
}
