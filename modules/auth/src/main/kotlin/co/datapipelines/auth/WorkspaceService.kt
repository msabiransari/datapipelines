package co.datapipelines.auth

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Supplies the "does this workspace still own content" answer for delete
 * (`workspace.in_use`). The counts live in `pipeline-contract`'s and `datasources`' tables,
 * which auth cannot see (module-structure §4.2) — so auth declares the port and the
 * aggregation layer wires it, exactly like `datasources`' `DatasourceReferences`.
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
 * Is a workspace live enough to act in? The seam D-R10's fifth effect needs: the scheduler
 * (092, not merged) consults this before firing anything, so a deactivated workspace's
 * schedules do not run — and it consults ONE answer, the same one the request path uses,
 * rather than re-deriving "deactivated" from a column it happens to be able to read.
 *
 * Declared as an interface with the service as its only implementation so a scheduler in
 * another module can depend on the QUESTION without depending on `WorkspaceService` (which
 * would drag the whole auth surface into a job runner).
 */
fun interface WorkspaceLiveness {
    /** True when [workspaceId] is neither soft-deleted nor deactivated (design §6). */
    fun isActive(workspaceId: UUID): Boolean
}

/**
 * Workspace membership resolution and the CRUD / member-management service paths the REST
 * surface calls (RBAC design §5, §6; the role model is §1).
 *
 * Every read goes through [AuthCache]'s 60s liveness discipline — the identical window
 * `users.is_active` already accepts — so a demotion, a removal or a deactivation takes
 * effect within ~1 minute, immediately on the instance that performed the mutation. That
 * window is also what D-R12 promises for a key whose issuer lost the role.
 *
 * ## Provisioning is gone (D-R11)
 * Workspaces are created by super admins. `auto-per-user`, `self-serve` and `open-join` went
 * with round 1, along with `PersonalWorkspaceSeeder` and `ensurePersonalWorkspace`: the
 * out-of-the-box workspace is `demo` ([DemoWorkspaceSeeder]) and a user with no membership
 * becomes a VIEWER of it on first login.
 *
 * ## The 404 rule (D-R5)
 * A workspace the caller cannot reach — unknown, non-member, or deactivated — is
 * [WorkspaceNotFoundException], the same 404 body a genuinely missing row produces. Never a
 * 403. The old `workspace.membership_required` survives in exactly one place: a principal
 * with ZERO memberships that addressed no workspace at all
 * ([AuthenticatedPrincipal.requireWorkspace]), where there is no name to protect.
 *
 * ## Super admins (D-R8)
 * They resolve ANY workspace, through the SAME path, with [MembershipFlags.implicit] set
 * when they hold no explicit membership — which is what the `acting_via=super_admin` audit
 * flag reads. Being a super admin does not make a DEACTIVATED workspace selectable; it makes
 * it visible.
 */
class WorkspaceService(
    private val workspaceRepository: WorkspaceRepository,
    private val userRepository: UserRepository,
    private val authCache: AuthCache,
    private val lastUsedWorkspaceStore: LastUsedWorkspaceStore?,
    private val auditLogger: AuditLogger,
    private val contentCheck: WorkspaceContentCheck = WorkspaceContentCheck.NONE,
) : WorkspaceLiveness {
    private val log = LoggerFactory.getLogger(WorkspaceService::class.java)

    /** [userId]'s memberships through the liveness cache (the D13 window). */
    fun memberships(userId: UUID): List<WorkspaceMembership> = authCache.memberships(userId) { workspaceRepository.membershipsOf(it) }

    /** [userId]'s memberships in SELECTABLE workspaces — what the switcher lists (design §6). */
    fun activeMemberships(userId: UUID): List<WorkspaceMembership> = memberships(userId).filter { it.workspaceActive }

    override fun isActive(workspaceId: UUID): Boolean = workspaceRepository.findById(workspaceId)?.isActive == true

    /**
     * Resolves a `DP-Workspace` switch (design §5.1) to the context the request runs in,
     * flags included. Unknown, non-member and deactivated are one [WorkspaceNotFoundException]
     * (D-R5), so the header can probe nothing.
     */
    fun resolveSwitch(
        principal: AuthenticatedPrincipal,
        name: String,
    ): WorkspaceContext = contextFor(principal, name) ?: throw WorkspaceNotFoundException(name)

    /**
     * The active workspace for a session request that sent no `DP-Workspace` header: the JWT's
     * stamped [claimName] when it still resolves to a live, active membership, else the first
     * active membership, else null — a principal with nothing to select authenticates fine and
     * every workspace-scoped operation then refuses at [ScopeMatrix.allowed]'s null-context
     * branch.
     *
     * A stamped claim that no longer resolves falls through rather than failing: the claim is a
     * convenience, not an entitlement, and a workspace deactivated since login must not lock a
     * user out of the ones they can still reach.
     */
    fun resolveForSession(
        principal: AuthenticatedPrincipal,
        claimName: String?,
    ): WorkspaceContext? {
        claimName?.let { claimed -> contextFor(principal, claimed)?.let { return it } }
        activeMemberships(principal.userId).firstOrNull()?.let { return context(it) }
        // D-R8: a super admin with no membership at all still has somewhere to be — the first
        // active workspace on the instance. Without it the one principal who can fix an empty
        // deployment is the one principal who cannot act in it.
        if (principal.isSuperAdmin) {
            workspaceRepository.findAllActive().firstOrNull()?.let {
                return WorkspaceContext(it.id, it.name, MembershipFlags.IMPLICIT_SUPER_ADMIN)
            }
        }
        return null
    }

    /**
     * What login stamps as `active_workspace`: last-used when it still resolves, else the
     * first active membership. Null when the user belongs to nothing selectable — round 2
     * draws the "no workspace" page; round 1 returns the state (design §5).
     */
    fun workspaceForLogin(
        user: User,
        @Suppress("UNUSED_PARAMETER") email: String,
    ): WorkspaceContext? {
        val memberships = activeMemberships(user.id)
        lastUsedWorkspaceStore?.lastUsed(user.id)?.let { last ->
            memberships.firstOrNull { it.workspaceName == last }?.let { return context(it) }
        }
        return memberships.firstOrNull()?.let { context(it) }
    }

    /**
     * Creates a workspace (D-R11: super admins only — the capability gate is
     * [ScopeMatrix.RestOperation.MANAGE_INSTANCE_WORKSPACES] at the interceptor, and this
     * re-asserts it because a service must not depend on having been called from a governed
     * route). The creator enters as the workspace ADMIN.
     */
    fun create(
        principal: AuthenticatedPrincipal,
        name: String,
        displayName: String,
    ): Workspace {
        requireSuperAdmin(principal)
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
        audit(principal, "auth.workspace.created", created.name, mapOf("workspace" to created.name))
        return created
    }

    /**
     * The caller's own workspaces. A super admin gets every workspace on the instance
     * (D-R8), deactivated ones included and marked — design §6 puts them in the listing
     * greyed with their date, and hiding them would hide the only screen that can reactivate.
     */
    fun listOwn(principal: AuthenticatedPrincipal): List<WorkspaceMembership> =
        if (principal.isSuperAdmin) {
            workspaceRepository.findAll().map {
                WorkspaceMembership(
                    workspaceId = it.id,
                    workspaceName = it.name,
                    flags = MembershipFlags.superAdminOver(flagsIn(it.id, principal.userId)),
                    joinedAt = it.createdAt,
                    workspaceActive = it.isActive,
                )
            }
        } else {
            memberships(principal.userId)
        }

    /**
     * One workspace by name when the principal may see it, else the 404 (D-R5). Super admins
     * see any workspace, deactivated included.
     */
    fun read(
        principal: AuthenticatedPrincipal,
        name: String,
    ): Workspace {
        val workspace = authCache.workspaceByName(name) { workspaceRepository.findByName(it) } ?: throw WorkspaceNotFoundException(name)
        val visible = principal.isSuperAdmin || (workspace.isActive && isMember(principal.userId, workspace.id))
        if (!visible) throw WorkspaceNotFoundException(name)
        return workspace
    }

    /** Renames the display name (`name` is immutable v1). Workspace admin or super admin. */
    fun updateDisplayName(
        principal: AuthenticatedPrincipal,
        name: String,
        displayName: String,
    ): Workspace {
        val workspace = read(principal, name)
        requireCapability(principal, workspace, Capability.WS_ADMIN)
        val updated =
            workspaceRepository.updateDisplayName(workspace.id, displayName)
                // The row vanished between read() and the write: the 404 rule answers the race
                // the same way it answers everything else.
                ?: throw WorkspaceNotFoundException(name)
        authCache.invalidateWorkspace(name)
        audit(principal, "auth.workspace.updated", name, mapOf("workspace" to name))
        return updated
    }

    /**
     * Deactivates a workspace (D-R10, super admin). Nothing is purged — ever. The five effects
     * (design §6) fall out of readers consulting [Workspace.isActive]: it stops being
     * selectable ([resolveForSession]/[resolveSwitch]), its endpoints and keys are refused, the
     * scheduler skips it through [WorkspaceLiveness], and a super admin's listing greys it.
     *
     * Every member's membership snapshot is invalidated so the disappearance is immediate on
     * this instance rather than a 60s surprise.
     */
    fun deactivate(
        principal: AuthenticatedPrincipal,
        name: String,
    ): Workspace {
        requireSuperAdmin(principal)
        val workspace = read(principal, name)
        if (!workspaceRepository.deactivate(workspace.id, principal.userId)) {
            throw WorkspaceInactiveException(name)
        }
        invalidateEveryone(workspace)
        audit(principal, "workspace.deactivated", name, mapOf("workspace" to name))
        return workspaceRepository.findById(workspace.id) ?: error("workspace ${workspace.id} vanished after deactivate")
    }

    /** Reactivates a workspace (design §6, super admin, audited). */
    fun reactivate(
        principal: AuthenticatedPrincipal,
        name: String,
    ): Workspace {
        requireSuperAdmin(principal)
        val workspace = read(principal, name)
        if (!workspaceRepository.reactivate(workspace.id)) {
            // Already active: the same 404 body, because "it was not deactivated" is not a
            // fact this surface owes a caller who could not have addressed it wrongly.
            throw WorkspaceNotFoundException(name)
        }
        invalidateEveryone(workspace)
        audit(principal, "workspace.reactivated", name, mapOf("workspace" to name))
        return workspaceRepository.findById(workspace.id) ?: error("workspace ${workspace.id} vanished after reactivate")
    }

    /**
     * Soft-deletes the workspace: refused with [WorkspaceInUseException] while it still owns
     * non-deleted content. Super admin only since D-R10 — deactivation is the operation
     * operators want, and delete stays for the empty-workspace case.
     *
     * ## The accepted check-then-act race (022/F10, 025 A3)
     * The content count and the soft delete are not one transaction and cannot be: the counted
     * tables belong to three other modules (module-structure §4.2), reached through
     * [WorkspaceContentCheck]. A content-creating request that resolved this workspace before
     * the delete and commits after the count strands its rows. v1 DETECTS rather than prevents:
     * a post-delete recount emits `auth.workspace.stranded_content` instead of leaving the
     * strand silent. Best-effort — a commit landing after the recount still strands silently.
     */
    fun delete(
        principal: AuthenticatedPrincipal,
        name: String,
    ) {
        requireSuperAdmin(principal)
        val workspace = read(principal, name)
        val counts = contentCheck.nonDeletedCounts(workspace.id).filterValues { it > 0 }
        if (counts.isNotEmpty()) throw WorkspaceInUseException(name, counts)
        val members = workspaceRepository.findMembersOf(workspace.id)
        workspaceRepository.softDelete(workspace.id)
        members.forEach { authCache.invalidateMemberships(it.userId) }
        authCache.invalidateWorkspace(name)
        audit(principal, "auth.workspace.deleted", name, mapOf("workspace" to name))
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

    /** The member listing: any member of the workspace, or a super admin. */
    fun members(
        principal: AuthenticatedPrincipal,
        name: String,
    ): List<WorkspaceMemberRow> {
        val workspace = read(principal, name)
        return workspaceRepository.findMembersOf(workspace.id)
    }

    /**
     * Adds a member with [flags] (design §1; workspace admin or super admin). A member added
     * with no flags is a VIEWER — the D-R11 default and the one the demo path uses.
     *
     * The email is resolved here so the unknown-user mapping and the membership write are one
     * transaction of intent; unknown emails surface as [UnknownMemberEmailException], which the
     * web layer maps to the house §16.3 stand-in.
     *
     * Idempotent by the repository's `ON CONFLICT DO NOTHING`: re-adding an existing member
     * returns them unchanged rather than silently resetting their flags to the request's —
     * changing an existing member's role is [setMemberFlags], which the last-admin rule guards.
     */
    fun addMember(
        principal: AuthenticatedPrincipal,
        name: String,
        email: String,
        flags: MembershipFlags = MembershipFlags.VIEWER,
    ): WorkspaceMemberRow {
        val normalized = email.trim().lowercase()
        val workspace = read(principal, name)
        requireCapability(principal, workspace, Capability.WS_ADMIN)
        val user = userRepository.findByEmail(normalized) ?: throw UnknownMemberEmailException(normalized)
        val row = workspaceRepository.addMember(workspace.id, user.id, normalize(flags))
        authCache.invalidateMemberships(user.id)
        audit(
            principal,
            "workspace.member_added",
            name,
            mapOf("workspace" to name, "member" to normalized, "flags" to wire(flags)),
        )
        return row ?: error("membership for $normalized in $name vanished after insert")
    }

    /**
     * Replaces a member's capability flags (design §1). Refuses to demote the LAST admin with
     * [WorkspaceLastAdminException] — a workspace with no admin is unmanageable, and the
     * refusal names the fix ("give someone else the admin role first") rather than the rule.
     */
    fun setMemberFlags(
        principal: AuthenticatedPrincipal,
        name: String,
        userId: UUID,
        flags: MembershipFlags,
    ): WorkspaceMemberRow {
        val workspace = read(principal, name)
        requireCapability(principal, workspace, Capability.WS_ADMIN)
        val target = workspaceRepository.findMemberRow(workspace.id, userId) ?: throw WorkspaceNotFoundException(name)
        val normalized = normalize(flags)
        if (target.flags.admin && !normalized.admin) requireAnotherAdmin(workspace, userId)
        workspaceRepository.setFlags(workspace.id, userId, normalized)
        authCache.invalidateMemberships(userId)
        audit(
            principal,
            "workspace.member_flags_changed",
            name,
            mapOf(
                "workspace" to name,
                "member_user_id" to userId.toString(),
                "from" to wire(target.flags),
                "to" to wire(normalized),
            ),
        )
        return workspaceRepository.findMemberRow(workspace.id, userId)
            ?: error("membership for $userId in $name vanished after update")
    }

    /**
     * Removes a membership. Workspace admin or super admin; refuses the LAST admin
     * ([WorkspaceLastAdminException]) for the same reason [setMemberFlags] does.
     *
     * A user id that names no member of this workspace answers with the workspace's own 404
     * (D-R5): "there is no such member here" and "there is no such workspace for you" must not
     * be distinguishable, or the member list becomes probeable one id at a time.
     */
    fun removeMember(
        principal: AuthenticatedPrincipal,
        name: String,
        userId: UUID,
    ) {
        val workspace = read(principal, name)
        requireCapability(principal, workspace, Capability.WS_ADMIN)
        val target = workspaceRepository.findMemberRow(workspace.id, userId) ?: throw WorkspaceNotFoundException(name)
        if (target.flags.admin) requireAnotherAdmin(workspace, userId)
        workspaceRepository.removeMember(workspace.id, userId)
        authCache.invalidateMemberships(userId)
        audit(
            principal,
            "workspace.member_removed",
            name,
            mapOf("workspace" to name, "member_user_id" to userId.toString()),
        )
    }

    /** Unknown member email at [addMember] — mapped by the web layer to the §16.3 unknown-user stand-in. */
    class UnknownMemberEmailException(
        val email: String,
    ) : IllegalStateException("No user with email '$email'.")

    /**
     * The context [principal] would run in inside [name] — flags resolved — or null when the
     * workspace does not exist FOR THEM (D-R5: unknown, non-member, or deactivated).
     *
     * The one resolution path (D-R8: "super admins resolve any workspace THROUGH THE SAME
     * PATH, with the audit flag"). A deactivated workspace is not selectable by anyone,
     * super admin included: design §6's first effect has no exception, and a super admin who
     * needs to act inside one reactivates it first — an audited, reversible step.
     */
    fun contextFor(
        principal: AuthenticatedPrincipal,
        name: String,
    ): WorkspaceContext? {
        val workspace = authCache.workspaceByName(name) { workspaceRepository.findByName(it) } ?: return null
        if (!workspace.isActive) return null
        val explicit = memberships(principal.userId).firstOrNull { it.workspaceId == workspace.id }
        return when {
            principal.isSuperAdmin ->
                WorkspaceContext(workspace.id, workspace.name, MembershipFlags.superAdminOver(explicit?.flags))
            explicit != null -> WorkspaceContext(workspace.id, workspace.name, explicit.flags)
            else -> null
        }
    }

    /**
     * The membership guard API-key issuance extends (auth.md §7.4): a key may only be pinned to
     * a workspace its creator can reach, and — since D-R12/O-2 — only by an AUTHOR there.
     * Throws [WorkspaceNotFoundException] for the unreachable case (D-R5) and
     * [RoleRequiredException] for the viewer.
     */
    fun requireIssuanceCapability(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
    ): WorkspaceContext {
        val workspace = workspaceRepository.findById(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId.toString())
        val context = contextFor(principal, workspace.name) ?: throw WorkspaceNotFoundException(workspace.name)
        if (!Capability.AUTHOR.satisfiedBy(context.flags)) {
            throw RoleRequiredException(Capability.AUTHOR, context.flags.held(), workspace.name)
        }
        return context
    }

    /**
     * The flags a KEY's issuer currently holds in the key's pinned workspace, or null when the
     * workspace is unreachable for them now (D-R12: removed issuer, deactivated workspace).
     * Read per request through the cache, which is what bounds the demotion window at one TTL.
     */
    fun issuerFlags(
        issuerId: UUID,
        issuerIsSuperAdmin: Boolean,
        workspaceId: UUID,
    ): MembershipFlags? {
        val explicit = memberships(issuerId).firstOrNull { it.workspaceId == workspaceId && it.workspaceActive }
        return when {
            issuerIsSuperAdmin -> MembershipFlags.superAdminOver(explicit?.flags)
            else -> explicit?.flags
        }
    }

    /** True when [principal] may operate in [workspaceId] — member or super admin (D-R8). */
    fun canAccess(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
    ): Boolean = canAccess(principal.userId, principal.isSuperAdmin, workspaceId)

    /** As [canAccess], for callers holding the identity as data. */
    fun canAccess(
        userId: UUID,
        isSuperAdmin: Boolean,
        workspaceId: UUID,
    ): Boolean = isSuperAdmin || isMember(userId, workspaceId)

    private fun isMember(
        userId: UUID,
        workspaceId: UUID,
    ): Boolean = memberships(userId).any { it.workspaceId == workspaceId }

    private fun flagsIn(
        workspaceId: UUID,
        userId: UUID,
    ): MembershipFlags? = memberships(userId).firstOrNull { it.workspaceId == workspaceId }?.flags

    private fun context(membership: WorkspaceMembership): WorkspaceContext =
        WorkspaceContext(membership.workspaceId, membership.workspaceName, membership.flags)

    /**
     * The `admin → author` invariant (design §1), applied before every write. The database's
     * `chk_workspace_member_admin_authors` is the authority; normalising here means a caller
     * that ticks "admin" alone gets the workspace admin it asked for rather than a constraint
     * violation with no catalogued code.
     */
    private fun normalize(flags: MembershipFlags): MembershipFlags =
        if (flags.admin) flags.copy(author = true) else flags

    private fun requireSuperAdmin(principal: AuthenticatedPrincipal) {
        if (!principal.isSuperAdmin) {
            throw RoleRequiredException(Capability.SUPER_ADMIN, emptySet())
        }
    }

    private fun requireCapability(
        principal: AuthenticatedPrincipal,
        workspace: Workspace,
        capability: Capability,
    ) {
        val flags =
            if (principal.isSuperAdmin) {
                MembershipFlags.superAdminOver(flagsIn(workspace.id, principal.userId))
            } else {
                flagsIn(workspace.id, principal.userId) ?: throw WorkspaceNotFoundException(workspace.name)
            }
        if (!capability.satisfiedBy(flags)) throw RoleRequiredException(capability, flags.held(), workspace.name)
    }

    /** The last-admin rule: [excluding] is the member about to lose admin (design §1). */
    private fun requireAnotherAdmin(
        workspace: Workspace,
        excluding: UUID,
    ) {
        val remaining = workspaceRepository.findMembersOf(workspace.id).count { it.flags.admin && it.userId != excluding }
        if (remaining == 0) throw WorkspaceLastAdminException(workspace.name)
    }

    private fun invalidateEveryone(workspace: Workspace) {
        workspaceRepository.findMembersOf(workspace.id).forEach { authCache.invalidateMemberships(it.userId) }
        authCache.invalidateWorkspace(workspace.name)
    }

    /**
     * Every workspace mutation's audit row, with D-R8's `acting_via` when the actor is a super
     * admin operating outside their own memberships. One helper so no verb can forget the flag
     * — the failure mode the design calls out by name.
     */
    private fun audit(
        principal: AuthenticatedPrincipal,
        event: String,
        workspaceName: String,
        details: Map<String, Any?>,
    ) {
        val actingVia =
            if (principal.isSuperAdmin && flagsIn(workspaceIdOf(workspaceName), principal.userId) == null) {
                mapOf(AuditLogger.ACTING_VIA to AuditLogger.ACTING_VIA_SUPER_ADMIN)
            } else {
                emptyMap<String, Any?>()
            }
        auditLogger.log(event = event, userId = principal.userId, details = details + actingVia)
    }

    /** The id behind an already-resolved name; a vanished row audits without the membership probe. */
    private fun workspaceIdOf(name: String): UUID =
        authCache.workspaceByName(name) { workspaceRepository.findByName(it) }?.id ?: NIL_WORKSPACE

    private fun wire(flags: MembershipFlags): List<String> =
        buildList {
            if (flags.author) add("author")
            if (flags.promoter) add("promoter")
            if (flags.admin) add("admin")
        }

    private companion object {
        /** metadata-db §4.11 — `[a-z0-9_-]+`, 1–63, immutable. */
        val NAME_REGEX = Regex("[a-z0-9_-]{1,63}")

        /** Stands in for "the workspace is already gone" in the audit path; never persisted. */
        val NIL_WORKSPACE: UUID = UUID(0, 0)
    }
}
