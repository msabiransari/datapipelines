package co.datapipelines.auth

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Supplies the "does this workspace still own content" answer for delete (design §8
 * `workspace.in_use`). The counts live in `pipeline-contract`'s and `datasources`' tables,
 * which auth cannot see (module-structure §4.2) — so auth declares the port and the
 * aggregation layer wires it, exactly like [PersonalWorkspaceSeeder] and
 * `datasources`' `DatasourceReferences`.
 */
fun interface WorkspaceContentCheck {
    /**
     * Non-deleted content counts owned by [workspaceId], keyed by kind
     * (`pipelines`, `templates`, `datasources`); kinds with zero rows are omitted.
     */
    fun nonDeletedCounts(workspaceId: UUID): Map<String, Int>

    companion object {
        val NONE = WorkspaceContentCheck { emptyMap() }
    }
}

/**
 * Workspace membership resolution and provisioning (design §5/§7), plus the CRUD and
 * member-management service paths the REST surface (design §9) calls.
 *
 * Every read goes through [AuthCache]'s 60s liveness discipline — the identical window
 * `users.is_active` already accepts (design §4), so workspace revocation takes effect
 * within ~1 minute, immediately on the instance that performed the mutation.
 *
 * ## Provisioning modes (design §7, configuration.md §3.17)
 * [create] is the service path all three modes share:
 * - `auto-per-user` / `self-serve`: any authenticated user creates; `auto-per-user`
 *   additionally provisions a personal workspace on first login ([ensurePersonalWorkspace]).
 * - `closed`: only a global `admin` creates — anything else is
 *   [WorkspaceCreationForbiddenException].
 *
 * ## The no-oracle rule (design §8, the 019 precedent)
 * Unknown-workspace and not-a-member are the SAME 403 [WorkspaceMembershipRequiredException]
 * for every principal except a global admin, who could otherwise see any workspace and so
 * gets a real 404 [WorkspaceNotFoundException]. Management refusals (a member who is not
 * the owner) reuse the 403 so a workspace's existence stays unprobeable.
 *
 * ## The pinned-workspace rule (auth.md §5.6, design D3)
 * The four management paths ([updateDisplayName], [delete], [addMember], [removeMember])
 * refuse an API-key principal whose pinned workspace differs from the path-name target —
 * the same no-oracle 403, so "pinned elsewhere" and "not a member" stay indistinguishable.
 * Exempt (no EXISTING workspace is overreached): [create] (no target exists yet; the
 * caller gains ownership of a NEW workspace only) and the `open-join` self-join in
 * [addMember] (only the caller's OWN membership, in a workspace the deployment declared
 * open). Sessions are untouched (their active workspace is switchable by design).
 *
 * ## Personal-workspace names (design §7)
 * Derived from the lowercased email local-part, sanitized to the `[a-z0-9_-]+` (1–63)
 * name rule, and collision-suffixed (`alice`, `alice-2`, `alice-3`, …) because the
 * namespace is global and two `alice@` accounts must not race for one name.
 */
class WorkspaceService(
    private val workspaceRepository: WorkspaceRepository,
    private val userRepository: UserRepository,
    private val authCache: AuthCache,
    private val workspacesProperties: WorkspacesProperties,
    private val lastUsedWorkspaceStore: LastUsedWorkspaceStore?,
    private val auditLogger: AuditLogger,
    private val personalWorkspaceSeeder: PersonalWorkspaceSeeder? = null,
    private val contentCheck: WorkspaceContentCheck = WorkspaceContentCheck.NONE,
) {
    private val log = LoggerFactory.getLogger(WorkspaceService::class.java)

    /** [userId]'s memberships through the liveness cache (D13 window, design §4). */
    fun memberships(userId: UUID): List<WorkspaceMembership> = authCache.memberships(userId) { workspaceRepository.membershipsOf(it) }

    /**
     * Resolves a `DP-Workspace` switch (design §5.1): the workspace named [name], when it
     * exists and [principal] may see it — a member, or a global `admin` (D4 bypass).
     * Anything else is [WorkspaceMembershipRequiredException], indistinguishable between
     * "no such workspace" and "not a member" so the header cannot probe existence.
     */
    fun resolveSwitch(
        principal: AuthenticatedPrincipal,
        name: String,
    ): WorkspaceContext {
        val workspace = authCache.workspaceByName(name) { workspaceRepository.findByName(it) }
        val allowed =
            workspace != null &&
                (principal.isAdmin || memberships(principal.userId).any { it.workspaceId == workspace.id })
        if (!allowed) throw WorkspaceMembershipRequiredException()
        return WorkspaceContext(workspace.id, workspace.name)
    }

    /**
     * The active workspace for a session request that sent no `DP-Workspace` header
     * (design §5.1): the JWT's stamped [claimName] when the membership is still live
     * (revocation within the D13 window), else the principal's first membership, else
     * null — a user with zero memberships (`closed` mode) authenticates fine and every
     * workspace-scoped operation then 403s at [AuthenticatedPrincipal.requireWorkspace].
     */
    fun resolveForSession(
        principal: AuthenticatedPrincipal,
        claimName: String?,
    ): WorkspaceContext? {
        val memberships = memberships(principal.userId)
        claimName?.let { claimed ->
            memberships.firstOrNull { it.workspaceName == claimed }?.let { return WorkspaceContext(it.workspaceId, it.workspaceName) }
            // A stamped membership that no longer exists falls through to first-membership:
            // the claim is a convenience, not an entitlement.
        }
        return memberships.firstOrNull()?.let { WorkspaceContext(it.workspaceId, it.workspaceName) }
    }

    /**
     * What login stamps as `active_workspace` (design §5.1): last-used when it still
     * resolves to a live membership, else first membership, else — `auto-per-user` only —
     * the freshly provisioned personal workspace. Null when there is nothing to stamp
     * (zero memberships under `self-serve`/`closed`).
     */
    fun workspaceForLogin(
        user: User,
        email: String,
    ): WorkspaceContext? {
        val memberships = memberships(user.id)
        lastUsedWorkspaceStore?.lastUsed(user.id)?.let { last ->
            memberships.firstOrNull { it.workspaceName == last }?.let { return WorkspaceContext(it.workspaceId, it.workspaceName) }
        }
        memberships.firstOrNull()?.let { return WorkspaceContext(it.workspaceId, it.workspaceName) }
        if (workspacesProperties.provisioningMode != WorkspaceProvisioningMode.AUTO_PER_USER) return null
        val provisioned = ensurePersonalWorkspace(user, email)
        return WorkspaceContext(provisioned.id, provisioned.name)
    }

    /**
     * The `auto-per-user` first-login provisioning hook (design §7, auth.md §4.2):
     * creates the user's `is_personal` workspace, or returns the existing personal one
     * when a previous login already did (a crashed login must not mint a second).
     * Creator enters as `owner`.
     *
     * On a freshly created workspace the D9 [PersonalWorkspaceSeeder] fires last (see its
     * KDoc); it is not re-run for a workspace that already existed.
     *
     * ## The name race (048/§C, the check-then-act 036's M5/M6 did not reach)
     * [availablePersonalName] asks `nameExists` and then inserts, and `workspaces.name` is
     * globally unique — so two logins racing on one fresh database used to hand the loser a
     * raw `DuplicateKeyException` out of a login. Both interleavings are now tolerated in the
     * house catch-and-re-read shape:
     *
     *  - **The same user twice** (two pods, two tabs): the loser re-reads its memberships and
     *    returns the winner's workspace rather than minting a second personal one.
     *  - **Two different users whose emails share a local-part** (`alice@a.com`,
     *    `alice@b.com`): the loser has no membership to find — the name belongs to somebody
     *    else — so it allocates the next free name and inserts again.
     *
     * One narrow window stays open and is deliberately not closed here: the same-user loser
     * can return a workspace whose seeding is still in flight on the winner's thread, so its
     * first screen may be a moment early. Closing it needs a lock spanning two processes,
     * which the D9 hook has no place to take, and the loser must not seed a second copy — a
     * re-import of the same examples collides on `uq_pipelines_workspace_name` and refuses
     * with `pipeline.validation.duplicate_name`, which would turn a cosmetic race into a
     * failed login.
     */
    fun ensurePersonalWorkspace(
        user: User,
        email: String,
    ): Workspace {
        existingWorkspace(user.id)?.let { return it }
        repeat(PERSONAL_NAME_ATTEMPTS) {
            try {
                return provisionPersonalWorkspace(user, availablePersonalName(email))
            } catch (_: org.springframework.dao.DuplicateKeyException) {
                // The atomic authority spoke: somebody took the name between the check and the
                // insert. If it was this user's own concurrent login, its workspace is now
                // ours to return; otherwise fall through and allocate the next free name.
                authCache.invalidateMemberships(user.id)
                existingWorkspace(user.id)?.let { return it }
            }
        }
        error("Could not provision a personal workspace for ${user.id} in $PERSONAL_NAME_ATTEMPTS attempts")
    }

    /** [userId]'s first membership resolved to its workspace row, or null when it has none. */
    private fun existingWorkspace(userId: UUID): Workspace? =
        memberships(userId).firstOrNull()?.let { existing ->
            checkNotNull(workspaceRepository.findById(existing.workspaceId)) {
                "Membership ${existing.workspaceId} resolved to no workspace row"
            }
        }

    /** The insert, its audit row and the D9 seeding — the part that must not run twice. */
    private fun provisionPersonalWorkspace(
        user: User,
        name: String,
    ): Workspace {
        val created = workspaceRepository.create(name, displayName = name, isPersonal = true, createdBy = user.id)
        authCache.invalidateMemberships(user.id)
        auditLogger.log(
            event = "auth.workspace.provisioned",
            userId = user.id,
            details = mapOf("workspace" to created.name, "mode" to WorkspaceProvisioningMode.AUTO_PER_USER.wire),
        )
        // D9 (sample-data design §6.1): seed the configured examples into the workspace that
        // now exists. Deliberately AFTER the audit row — the workspace was provisioned either
        // way — and deliberately NOT guarded: a seeding failure must fail the login loudly
        // rather than hand the user a workspace that is quietly missing its examples.
        personalWorkspaceSeeder?.seed(created.id, user.id)
        return created
    }

    /**
     * The workspace-creation service path every mode shares (design §7; the REST CRUD
     * surface is design §9). `closed` refuses non-admins; the other modes allow any
     * authenticated user. The creator enters as `owner`. Name failures are catalogued:
     * [WorkspaceNameInvalidException] / [WorkspaceDuplicateNameException].
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct refusal to its own catalogued code
    fun create(
        principal: AuthenticatedPrincipal,
        name: String,
        displayName: String,
    ): Workspace {
        if (workspacesProperties.provisioningMode == WorkspaceProvisioningMode.CLOSED && !principal.isAdmin) {
            throw WorkspaceCreationForbiddenException(workspacesProperties.provisioningMode)
        }
        if (!NAME_REGEX.matches(name)) throw WorkspaceNameInvalidException(name)
        if (workspaceRepository.nameExists(name)) throw WorkspaceDuplicateNameException(name)
        val created =
            try {
                workspaceRepository.create(name, displayName, isPersonal = false, createdBy = principal.userId)
            } catch (_: org.springframework.dao.DuplicateKeyException) {
                // The atomic authority: a racing create wins between the pre-check and here —
                // the SAME catalogued answer, never the raw constraint violation.
                throw WorkspaceDuplicateNameException(name)
            }
        authCache.invalidateMemberships(principal.userId)
        auditLogger.log(
            event = "auth.workspace.created",
            userId = principal.userId,
            details = mapOf("workspace" to created.name),
        )
        return created
    }

    /**
     * The caller's own workspaces (design §9 "list-own"): membership rows joined to their
     * workspaces, oldest first. A global admin gets exactly the same shape — no implicit
     * merged view (the ratified 019 ruling: admin addresses other workspaces per-request
     * via `DP-Workspace`, not through this listing).
     */
    fun listOwn(principal: AuthenticatedPrincipal): List<WorkspaceMembership> = memberships(principal.userId)

    /** The `open-join` joinable listing (design §7): every live workspace the principal does NOT belong to. */
    fun joinable(principal: AuthenticatedPrincipal): List<Workspace> =
        if (!workspacesProperties.openJoin) {
            emptyList()
        } else {
            workspaceRepository.findAll().filter { ws -> memberships(principal.userId).none { it.workspaceId == ws.id } }
        }

    /**
     * One workspace by name, when the principal may see it (design §9 "read"): a member, or
     * a global admin. Members share the 019 no-oracle 403 for unknown names; only an admin
     * gets the 404 (they could otherwise see any workspace). Read through the liveness cache,
     * like [resolveSwitch].
     */
    fun read(
        principal: AuthenticatedPrincipal,
        name: String,
    ): Workspace {
        val workspace = authCache.workspaceByName(name) { workspaceRepository.findByName(it) }
        if (principal.isAdmin) {
            if (workspace == null) throw WorkspaceNotFoundException(name)
            return workspace
        }
        if (workspace == null || memberships(principal.userId).none { it.workspaceId == workspace.id }) {
            throw WorkspaceMembershipRequiredException()
        }
        return workspace
    }

    /** Renames the display name (design §9; `name` is immutable v1). Owner-or-admin. */
    fun updateDisplayName(
        principal: AuthenticatedPrincipal,
        name: String,
        displayName: String,
    ): Workspace {
        requirePinnedWorkspace(principal, name)
        val workspace = read(principal, name)
        requireOwnerOrAdmin(principal, workspace)
        val updated =
            workspaceRepository.updateDisplayName(workspace.id, displayName)
                // The row vanished between read() and the write. The no-oracle line holds
                // even on the race (022 review, below-cap): a member must not learn from a
                // 404-vs-403 split that the workspace existed a moment ago.
                ?: throw if (principal.isAdmin) WorkspaceNotFoundException(name) else WorkspaceMembershipRequiredException()
        authCache.invalidateWorkspace(name)
        auditLogger.log(
            event = "auth.workspace.updated",
            userId = principal.userId,
            details = mapOf("workspace" to name),
        )
        return updated
    }

    /**
     * Soft-deletes the workspace (design §9): refused with [WorkspaceInUseException] while it
     * still owns non-deleted pipelines/templates/datasources ([WorkspaceContentCheck]).
     * Owner-or-admin. Every member's membership cache is invalidated so the disappearance is
     * immediate, not a 60s surprise.
     *
     * ## The accepted check-then-act race (022/F10, 025 A3 — design §11 documents the decision)
     *
     * The content count and the soft delete are not one transaction, and cannot be: the
     * counted tables belong to three other modules (module-structure §4.2), reached through
     * the [WorkspaceContentCheck] port. A content-creating request that resolved this
     * workspace before the soft delete and commits after the count strands its rows —
     * invisible to every listing (memberships join `is_deleted = FALSE`), with the name
     * permanently taken. Closing it requires either a cross-module locking protocol every
     * content-save path joins, or content-table triggers whose refusals map to no catalogued
     * code — both rejected for v1 in the design note. What v1 DOES do is detect: a
     * post-delete recount that finds content emits `auth.workspace.stranded_content`
     * (audit + ERROR log) instead of leaving the strand silent. The detector is
     * best-effort — a commit landing after the recount still strands silently.
     */
    fun delete(
        principal: AuthenticatedPrincipal,
        name: String,
    ) {
        requirePinnedWorkspace(principal, name)
        val workspace = read(principal, name)
        requireOwnerOrAdmin(principal, workspace)
        val counts = contentCheck.nonDeletedCounts(workspace.id).filterValues { it > 0 }
        if (counts.isNotEmpty()) throw WorkspaceInUseException(name, counts)
        val members = workspaceRepository.findMembersOf(workspace.id)
        workspaceRepository.softDelete(workspace.id)
        members.forEach { authCache.invalidateMemberships(it.userId) }
        authCache.invalidateWorkspace(name)
        auditLogger.log(
            event = "auth.workspace.deleted",
            userId = principal.userId,
            details = mapOf("workspace" to name),
        )
        // The race detector (see KDoc): best-effort, never a refusal — the deletion stands.
        val stranded = contentCheck.nonDeletedCounts(workspace.id).filterValues { it > 0 }
        if (stranded.isNotEmpty()) {
            log.error(
                "Workspace '{}' was deleted but {} landed concurrently and is now stranded " +
                    "(invisible to listings, name held). Recover by SQL: un-delete the workspace " +
                    "or remove the stranded rows.",
                name,
                stranded,
            )
            auditLogger.log(
                event = "auth.workspace.stranded_content",
                userId = principal.userId,
                details = mapOf("workspace" to name, "counts" to stranded),
            )
        }
    }

    /** The member listing (design §9): any member of the workspace, or a global admin. */
    fun members(
        principal: AuthenticatedPrincipal,
        name: String,
    ): List<WorkspaceMemberRow> {
        val workspace = read(principal, name)
        return workspaceRepository.findMembersOf(workspace.id)
    }

    /**
     * Adds a member (design §9). Owner-or-admin — except the `open-join` self-service
     * path: when `open-join` is on and [email] is the caller's own, any authenticated
     * principal joins (design §7). The email is resolved here so the caller's 404 mapping
     * (the house unknown-user stand-in — §13.7 has no `auth.user.not_found`) and the
     * membership write are one transaction of intent; unknown emails surface as
     * [UnknownMemberEmailException] with the email, which the web layer maps.
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct refusal to its own catalogued code
    fun addMember(
        principal: AuthenticatedPrincipal,
        name: String,
        email: String,
    ): WorkspaceMemberRow {
        val normalized = email.trim().lowercase()
        val selfJoin = normalized == principal.email
        // SESSION principals only. The exemption exists so a human can use the shipped
        // self-service join, and that UI is session-gated already
        // (`WorkspacesUiController.requireSessionPrincipal`) — so no key needs it.
        //
        // Extending it to API keys was a real hole, caught in review before merge: the
        // open-join branch below resolves the target by NAME with read()'s membership
        // check deliberately skipped, so a key pinned to G could write a
        // `workspace_members` row into any live workspace A. That row then outlives
        // revocation of the key, and membership alone passes the checks in `read` and
        // `members` — neither of which consults the pin — so the joined workspace's full
        // roster (emails, display names, user ids) is readable at scope `read`, for every
        // workspace in the deployment. The KDoc's "it touches only the caller's OWN
        // membership" was true; "no existing workspace is overreached" was not.
        val openSelfJoin = selfJoin && workspacesProperties.openJoin && principal.authMethod != AuthMethod.API_KEY
        if (!openSelfJoin) {
            requirePinnedWorkspace(principal, name)
        }
        val workspace =
            if (openSelfJoin) {
                // design §7 self-service: resolve the target WITHOUT read()'s membership
                // pre-check — it would 403 every non-member before the self-join branch
                // ever ran (022 review F4: open-join was unreachable). Under open-join
                // joinable() already lists every live workspace to everyone, so a plain
                // not-found here opens no existence oracle.
                authCache.workspaceByName(name) { workspaceRepository.findByName(it) }
                    ?: throw WorkspaceNotFoundException(name)
            } else {
                read(principal, name)
            }
        if (!selfJoin) {
            requireOwnerOrAdmin(principal, workspace)
        } else if (!workspacesProperties.openJoin && !isOwnerOrAdmin(principal, workspace)) {
            // Joining your own email without open-join is still just an add: the caller
            // must be owner/admin. A non-owner self-add is the membership 403, same as
            // any other non-owner management act — no oracle created.
            throw WorkspaceMembershipRequiredException()
        }
        val user =
            userRepository.findByEmail(normalized)
                ?: throw UnknownMemberEmailException(normalized)
        val row = workspaceRepository.addMember(workspace.id, user.id)
        authCache.invalidateMemberships(user.id)
        auditLogger.log(
            event = "auth.workspace.member_added",
            userId = principal.userId,
            details = mapOf("workspace" to name, "member" to normalized),
        )
        return row ?: error("membership for $normalized in $name vanished after insert")
    }

    /** Unknown member email at [addMember] — mapped by the web layer to the §16.3 unknown-user stand-in. */
    class UnknownMemberEmailException(
        val email: String,
    ) : IllegalStateException("No user with email '$email'.")

    /**
     * Removes a membership (design §9). Owner-or-admin. Removing an OWNER is refused with
     * [WorkspaceInUseException] (`blocked_by: owner_membership`): ownership transfer is not
     * a v1 operation, and a workspace left without its owner would be unmanageable — the
     * delete-blocked shape is the honest 409 the catalog has for "this removal would orphan
     * the workspace".
     */
    fun removeMember(
        principal: AuthenticatedPrincipal,
        name: String,
        userId: UUID,
    ) {
        requirePinnedWorkspace(principal, name)
        val workspace = read(principal, name)
        requireOwnerOrAdmin(principal, workspace)
        val target =
            workspaceRepository.findMemberRow(workspace.id, userId)
                ?: throw WorkspaceMembershipRequiredException()
        if (target.role == WorkspaceRole.OWNER) {
            throw WorkspaceInUseException(name, emptyMap(), blockedBy = "owner_membership")
        }
        workspaceRepository.removeMember(workspace.id, userId)
        authCache.invalidateMemberships(userId)
        auditLogger.log(
            event = "auth.workspace.member_removed",
            userId = principal.userId,
            details = mapOf("workspace" to name, "member_user_id" to userId.toString()),
        )
    }

    private fun isOwnerOrAdmin(
        principal: AuthenticatedPrincipal,
        workspace: Workspace,
    ): Boolean {
        if (principal.isAdmin) return true
        return memberships(principal.userId).any { it.workspaceId == workspace.id && it.role == WorkspaceRole.OWNER }
    }

    /**
     * The pinned-workspace rule for key principals (auth.md §5.6, design D3): an API key's
     * workspace is fixed at issuance, so a key may manage ONLY the workspace it is pinned
     * to. Authorizing against the user's whole membership set instead — as these handlers
     * address their target by path name — would let a key pinned to A manage B whenever its
     * owner belongs to both, defeating the pin `WorkspaceResolutionFilter` hard-refuses
     * `DP-Workspace` to protect (025 review, blocking). Sessions are untouched: their
     * active workspace is switchable by design, so no pin exists to honor.
     *
     * The refusal is the SAME no-oracle 403 [requireOwnerOrAdmin] raises — "pinned
     * elsewhere" and "not a member" must stay indistinguishable, or the pin itself becomes
     * an existence oracle. Two exemptions, both because there is no EXISTING workspace the
     * key could overreach into: [create] (there is no target workspace yet; creation grants
     * the caller ownership of a NEW workspace only, and the §7.6 `author` floor plus the
     * per-mode refusal are its gates) and the `open-join` self-join in [addMember] (it
     * touches only the caller's OWN membership in a workspace the deployment declared open;
     * the joiner enters as `member`, and the key's active workspace stays pinned).
     */
    private fun requirePinnedWorkspace(
        principal: AuthenticatedPrincipal,
        name: String,
    ) {
        if (principal.authMethod == AuthMethod.API_KEY && principal.workspaceName != name) {
            throw WorkspaceMembershipRequiredException()
        }
    }

    /**
     * The owner-or-admin gate (design §5.4): a member who is not the owner gets the same
     * 403 as a non-member — role probing is an oracle too ("this workspace exists, I am in
     * it, someone else owns it" is a disclosure the no-oracle rule exists to prevent).
     */
    private fun requireOwnerOrAdmin(
        principal: AuthenticatedPrincipal,
        workspace: Workspace,
    ) {
        if (!isOwnerOrAdmin(principal, workspace)) throw WorkspaceMembershipRequiredException()
    }

    /** True when [principal] may operate in [workspaceId] — member or global `admin` (D4). Read-through the liveness cache. */
    fun canAccess(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
    ): Boolean = canAccess(principal.userId, principal.isAdmin, workspaceId)

    /** As [canAccess], for callers holding the identity as data (API-key issuance, §7.4). */
    fun canAccess(
        userId: UUID,
        isAdmin: Boolean,
        workspaceId: UUID,
    ): Boolean = isAdmin || memberships(userId).any { it.workspaceId == workspaceId }

    /**
     * The membership guard API-key issuance extends (auth.md §7.4): a key may only be
     * pinned to a workspace its creator can access. Throws [WorkspaceMembershipRequiredException].
     */
    fun requireAccess(
        userId: UUID,
        isAdmin: Boolean,
        workspaceId: UUID,
    ) {
        if (!canAccess(userId, isAdmin, workspaceId)) throw WorkspaceMembershipRequiredException()
    }

    /** First free collision-suffixed name for [email]'s sanitized local-part. */
    private fun availablePersonalName(email: String): String {
        val base = sanitizeName(email.substringBefore('@'))
        var candidate = base
        var suffix = 2
        while (workspaceRepository.nameExists(candidate)) {
            candidate = "$base-${suffix++}"
        }
        return candidate
    }

    private companion object {
        /** metadata-db §4.11 — `[a-z0-9_-]+`, 1–63, immutable. */
        val NAME_REGEX = Regex("[a-z0-9_-]{1,63}")

        /**
         * Lowercased email local-part → a valid workspace name: invalid character runs
         * collapse to one `-`, edge dashes/underscores trim away, and an empty result
         * (a local-part of pure punctuation) falls back to `personal` rather than
         * failing a login over a name.
         */
        fun sanitizeName(localPart: String): String {
            val sanitized =
                localPart
                    .lowercase()
                    .replace(Regex("[^a-z0-9_-]+"), "-")
                    .trim('-', '_')
                    .take(MAX_NAME_LENGTH)
            return sanitized.ifEmpty { "personal" }
        }

        /** metadata-db §4.11 — workspace names are 1–63 chars. */
        private const val MAX_NAME_LENGTH = 63

        /**
         * How many times a lost name race is re-allocated before the login gives up. A retry
         * re-scans from the base name, so two attempts already cover a concurrent pair; the
         * third exists so an unlucky burst does not surface as a 500, and the bound exists so
         * a pathological repository can never spin.
         */
        private const val PERSONAL_NAME_ATTEMPTS = 3
    }
}
