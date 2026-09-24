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
     * The §4.2 find-or-create's answer: the row, and whether THIS call inserted it. The
     * OIDC callback reads [created] to send the sys-ops new-user notice on its create
     * branch only (auth.md §5A.8) — a returning user's login sends nothing.
     */
    data class Provisioned(
        val user: User,
        val created: Boolean,
    )

    /**
     * Find-or-create by email (auth.md §4.2). On an existing account the OIDC identity is
     * linked **only when it is compatible** (#187): the stored row is the bootstrap
     * placeholder (this login COMPLETES a pre-provisioned identity), or its
     * `(provider, provider_subject)` equals the incoming pair (the same person signing in
     * again — display name and picture refresh). Anything else — a different provider, a
     * different subject under the same provider, or a `local`/`system` row — refuses with
     * [IdentityMismatchException]: NO row is updated, and the caller redirects to
     * `/login?error=identity_mismatch`. An identity is linked once; re-linking is the super
     * admin's explicit [resetIdentity], never a side effect of a login.
     *
     * Bootstrap-admin (§4.4) fires **only when the row is created**: a later login
     * changes nothing, and after an admin deliberately revokes admin
     * (`auth.user.admin_revoked`) this path never re-grants it. Re-instating admin is
     * an explicit administrative operation ([grantAdmin]), not a side effect of
     * logging in.
     *
     * Returns [Provisioned] so the caller knows which branch ran: `created` is true exactly
     * when this call's insert won. The loser of the two-replica first-login race (below)
     * answers `created = false` — the winner's callback already announced the row.
     */
    fun findOrCreateByEmail(
        email: String,
        displayName: String,
        pictureUrl: String?,
        provider: String,
        providerSubject: String,
    ): Provisioned {
        val normalized = normalize(email)
        val existing = userRepository.findByEmail(normalized)
        if (existing != null) {
            return Provisioned(linkIfCompatible(existing, displayName, pictureUrl, provider, providerSubject), created = false)
        }

        return try {
            Provisioned(
                createUser(
                    normalizedEmail = normalized,
                    displayName = displayName,
                    pictureUrl = pictureUrl,
                    provider = provider,
                    providerSubject = providerSubject,
                ),
                created = true,
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
            Provisioned(linkIfCompatible(winner, displayName, pictureUrl, provider, providerSubject), created = false)
        }
    }

    /**
     * The §4.2 link decision for an account that already exists (#187). A bootstrap
     * placeholder is claimable by the first real sign-in; the SAME `(provider, subject)`
     * refreshes; everything else is a refusal — the stored identity is evidence, and a
     * login that cannot prove it is the same person must not overwrite it.
     */
    private fun linkIfCompatible(
        stored: User,
        displayName: String,
        pictureUrl: String?,
        provider: String,
        providerSubject: String,
    ): User {
        // #215 A.6 — a key identity or the System actor is never claimable by a sign-in, whatever
        // its provider says: the kind is checked before the provider, so even a row somebody
        // flipped to the bootstrap placeholder by hand stays unclaimable.
        val compatible =
            stored.isHuman &&
                (
                    stored.provider == BOOTSTRAP_PROVIDER ||
                        (stored.provider == provider && stored.providerSubject == providerSubject)
                )
        if (!compatible) {
            throw IdentityMismatchException(
                userId = stored.id,
                storedProvider = stored.provider,
                incomingProvider = provider,
            )
        }
        return linkIdentity(stored.id, displayName, pictureUrl, provider, providerSubject)
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
     * `pipeline_versions.created_by` and `pipeline_executions.executed_by` (both
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
                kind = UserKind.SYSTEM,
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
     * A key's own identity (#215, record §3.3, PK5): the `service` row an `endpoint` or `server`
     * key acts as — attribution, liveness, the per-key execution limit, its own results. Built
     * exactly like the System actor, so login is impossible by construction:
     *
     * - `provider` is [KEY_PROVIDER], reserved at startup like `system`, `local` and `bootstrap`
     *   (configuration.md §7) — no external identity can link to it;
     * - `email` is `<key id>@keys.invalid` (RFC 2606's unresolvable TLD) — no mail flow reaches it;
     * - `provider_subject` is the key id, `display_name` the key's name (keys have no rename, A6);
     * - no password and `is_admin = false` — and the local-password paths, user administration,
     *   memberships and invitations all refuse a non-`human` row besides.
     *
     * Called by `ApiKeyService.issue` INSIDE the issuance transaction (B4), so a key and its
     * identity exist together or not at all. The identity holds NO membership: its authority is
     * its key's role in the key's pinned workspace.
     */
    fun provisionIdentity(
        keyId: String,
        keyName: String,
    ): User =
        createUser(
            normalizedEmail = identityEmail(keyId),
            displayName = keyName,
            pictureUrl = null,
            provider = KEY_PROVIDER,
            providerSubject = keyId,
            kind = UserKind.SERVICE,
        )

    /**
     * Deactivates a key's identity — the revocation of its key (record §3.3: "Revoking the key
     * deactivates the identity"). The liveness cache is evicted at once; the key itself is already
     * revoked, so this is the belt that makes a revoked key's identity inert everywhere it is read.
     * Refuses a `human` row: a person is deactivated by user administration, never by a key.
     */
    fun deactivateIdentity(identityId: UUID) {
        val identity = userRepository.findById(identityId) ?: return
        check(identity.kind == UserKind.SERVICE) { "Only a key identity is deactivated with its key; $identityId is ${identity.kind.wire}" }
        if (userRepository.setActive(identityId, active = false)) authCache.invalidateUser(identityId)
    }

    /**
     * The ONE path that creates a `users` row (auth.md §4.4): insert, then — and only then —
     * the bootstrap-admin grant and its audit event. §4.2's first login and §6.1's
     * pre-provisioning are the same act at two different moments, not two mechanisms. Only a
     * `human` row can be the bootstrap admin: an identity's or the System actor's email is never
     * the configured one, and the kind says so too.
     */
    private fun createUser(
        normalizedEmail: String,
        displayName: String,
        pictureUrl: String?,
        provider: String,
        providerSubject: String,
        kind: UserKind = UserKind.HUMAN,
    ): User {
        val created =
            userRepository.insert(
                email = normalizedEmail,
                displayName = displayName,
                profilePictureUrl = pictureUrl,
                provider = provider,
                providerSubject = providerSubject,
                isAdmin = kind == UserKind.HUMAN && isBootstrapAdmin(normalizedEmail),
                kind = kind,
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
     * ([LOCAL_PROVIDER]) marks "no OIDC identity linked"; since #187 a later OIDC
     * sign-in with this email REFUSES rather than re-linking — moving the account
     * to OIDC is the super admin's explicit [resetIdentity]. [email] is normalized
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
        // #215: the key identities' domain is reserved the same way — no person is created in it.
        require(!normalizedEmail.endsWith("@$KEY_IDENTITY_DOMAIN")) {
            "@$KEY_IDENTITY_DOMAIN is reserved for key identities (auth.md §4.5); it has no local credential"
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

    /**
     * The row user administration may see and act on (#215 A3): a PERSON. A key identity or the
     * System actor answers null — "no such user" — on every user-admin route, before any mutation,
     * so an admin cannot act on an identity behind its key's back.
     */
    fun administrableUser(id: UUID): User? = snapshot(id)?.takeIf { it.isHuman }

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
    ): Boolean =
        administrableUser(targetId) != null &&
            setActive(targetId, active = false, actorId = actorId, event = "auth.user.deactivated")

    /** Reactivates [targetId] (§10.1 `auth.user.activated`). */
    fun activate(
        targetId: UUID,
        actorId: UUID,
    ): Boolean =
        administrableUser(targetId) != null &&
            setActive(targetId, active = true, actorId = actorId, event = "auth.user.activated")

    /** Grants admin (§10.1 `auth.user.admin_granted`, actor = the acting admin). */
    fun grantAdmin(
        targetId: UUID,
        actorId: UUID,
    ): Boolean =
        administrableUser(targetId) != null &&
            auditedFlip(targetId, actorId, "auth.user.admin_granted") { userRepository.grantAdmin(it) }

    /**
     * Revokes admin (§10.1 `auth.user.admin_revoked`). §4.4 is explicit that the
     * bootstrap path must not undo this on the next login — it fires only at row
     * creation, so a revoked admin stays revoked.
     */
    fun revokeAdmin(
        targetId: UUID,
        actorId: UUID,
    ): Boolean =
        administrableUser(targetId) != null &&
            auditedFlip(targetId, actorId, "auth.user.admin_revoked") { userRepository.revokeAdmin(it) }

    /**
     * Resets a user's linked identity to the bootstrap placeholder (#187, §4.2): the NEXT
     * OIDC sign-in with this email claims the row. The super admin's explicit answer to the
     * `auth.login.identity_mismatch` refusal — a login never re-links silently, so moving an
     * account between sign-in identities is a human decision, audited
     * (`auth.user.identity_reset`, actor + target). The row's liveness, admin flag, password
     * and memberships are untouched; a deactivated user stays deactivated (180).
     *
     * No transition → no event: a row already sitting on the placeholder is a no-op (§10.1).
     *
     * A PERSON's row only (#215 A.6): resetting a key identity or the System actor to the bootstrap
     * placeholder would make it claimable by the next sign-in with its email — true of the System
     * row before this slice. Refused here and in the SQL (`kind = 'human'`).
     */
    fun resetIdentity(
        targetId: UUID,
        actorId: UUID,
    ): Boolean {
        if (administrableUser(targetId) == null) return false
        val changed = userRepository.resetIdentityToBootstrap(targetId)
        if (changed) {
            authCache.invalidateUser(targetId)
            auditLogger.log(event = "auth.user.identity_reset", userId = targetId, details = mapOf("actor" to actorId.toString()))
        }
        return changed
    }

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

        /**
         * `users.provider` of a key's own identity (#215, §3.3). Reserved at startup exactly as
         * [SYSTEM_PROVIDER] is: an OIDC provider named `key` could otherwise link to the row.
         */
        const val KEY_PROVIDER = "key"

        /** A key identity's `users.email`: `<key id>@keys.invalid` — RFC 2606, unresolvable by construction. */
        fun identityEmail(keyId: String): String = "${keyId.lowercase()}@$KEY_IDENTITY_DOMAIN"

        /** The reserved domain of every key identity's email. */
        const val KEY_IDENTITY_DOMAIN = "keys.invalid"

        /** What the system actor is called wherever a display name is rendered (history, audit). */
        const val SYSTEM_ACTOR_DISPLAY_NAME = "System"

        /** auth.md §4.2 — one canonical form for every lookup, store and comparison. */
        private fun normalize(email: String): String = email.trim().lowercase()
    }
}
