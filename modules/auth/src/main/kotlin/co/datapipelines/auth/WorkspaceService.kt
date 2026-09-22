package co.datapipelines.auth

import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
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
 * The login-minted MCP key hook (D16, roles design §3.3): mint the user's one `user` key
 * for the workspace they just entered — or do nothing when they already hold one.
 *
 * A PORT, not a call into `ApiKeyService`: that service already depends on
 * [WorkspaceService] (issuance's membership guard), so a direct call back would be a
 * constructor cycle. The aggregation layer binds this to `ApiKeyService.mintLoginKey`
 * through a lazy provider. The rule lives HERE — on the one path both login handlers and
 * the switcher converge on — for the same reason the invitation materialise does: the
 * owner's rule is about LOGGING IN, not about which provider did it.
 */
fun interface McpKeyMint {
    /** Mints the login key for [user] in [context]'s workspace when none is live; a no-op otherwise. */
    fun mint(
        user: User,
        context: WorkspaceContext,
    )
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
 * They resolve ANY workspace, through the SAME path, with [WorkspaceContext.implicit] set
 * when they hold no explicit membership — which is what the `acting_via=super_admin` audit
 * flag reads. Being a super admin does not make a DEACTIVATED workspace selectable; it makes
 * it visible.
 *
 * ## Why this class and EVERY public method on it are `open`
 * [removeMember] is a multi-statement metadata write carried by
 * `@Transactional("metadataTransactionManager")` — the pipeline-contract (S3) rule. The
 * named manager is the one Spring transaction resource (the metadata database); the
 * annotation opens the class to CGLIB, which `kotlin("plugin.spring")` does NOT do for a
 * method-level annotation, so the class and every public method are `open` by hand: a final
 * method on a CGLIB proxy runs on the proxy with null fields and dies at first use — the
 * silent trap `TransactionRollbackIntegrationTest` guards (it asserts every transactional
 * bean is proxied AND declares no final public method). Private members stay final: they are
 * reached from inside an already-delegated `open` method.
 */
@Suppress("TooManyFunctions") // the workspace surface IS this class: resolution + CRUD + members + deactivation
open class WorkspaceService(
    private val workspaceRepository: WorkspaceRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val userRepository: UserRepository,
    private val authCache: AuthCache,
    private val lastUsedWorkspaceStore: LastUsedWorkspaceStore?,
    private val auditLogger: AuditLogger,
    private val invitationRepository: WorkspaceInvitationRepository,
    private val authProperties: AuthProperties,
    private val contentCheck: WorkspaceContentCheck = WorkspaceContentCheck.NONE,
    private val demoWorkspaceSeeder: DemoWorkspaceSeeder? = null,
    /**
     * The D16 login-mint port — nullable so auth-only test slices build the service with
     * no key machinery; the application always binds it (AuthConfiguration). The mint
     * itself decides "already holds one" and "owes a password change"; this service only
     * decides WHERE the hook fires.
     */
    private val mcpKeyMint: McpKeyMint? = null,
) : WorkspaceLiveness {
    private val log = LoggerFactory.getLogger(WorkspaceService::class.java)

    /** [userId]'s memberships through the liveness cache (the D13 window). */
    open fun memberships(userId: UUID): List<WorkspaceMembership> = authCache.memberships(userId) { workspaceRepository.membershipsOf(it) }

    /** [userId]'s memberships in SELECTABLE workspaces — what the switcher lists (design §6). */
    open fun activeMemberships(userId: UUID): List<WorkspaceMembership> = memberships(userId).filter { it.workspaceActive }

    /**
     * Through the liveness cache since 180 (D15): this is the read `PrincipalLiveness` makes
     * on every key validation, so it costs zero queries within the TTL, and
     * [deactivate]/[reactivate] evict it so the answer changes on this instance at once.
     */
    override fun isActive(workspaceId: UUID): Boolean = authCache.isWorkspaceActive(workspaceId) { workspaceRepository.findById(it) }

    /**
     * Resolves a `DP-Workspace` switch (design §5.1) to the context the request runs in,
     * role included. Unknown, non-member and deactivated are one [WorkspaceNotFoundException]
     * (D-R5), so the header can probe nothing.
     */
    open fun resolveSwitch(
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
    open fun resolveForSession(
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
                return WorkspaceContext.superAdminOver(it.id, it.name, explicitRole = null)
            }
        }
        return null
    }

    /**
     * What login stamps as `active_workspace` (design §5.1): last-used when it still resolves,
     * else the first active membership, else — D-R11 — a fresh VIEWER membership of `demo`.
     *
     * **The materialise step comes first (113, auth.md §4.6):** every invitation for this
     * email becomes a membership, in the same statement that deletes the invitations, BEFORE
     * the resolution below reads anything — so an invited user's first login lands them in
     * the invited workspace with the invited role, and the demo join never fires for them.
     * This is the ONE place both credential paths converge (OIDC's success handler and the
     * local login controller both call it), which is why the hook lives here and not in
     * either login handler: the owner's rule is about LOGGING IN, not about which provider
     * did it. It runs on every login, not only the first — a no-op statement when no
     * invitation exists, and it is what lets an invitation into a workspace that was
     * deactivated at invite time materialise once the workspace is reactivated (the rows
     * wait, 113 §B.5).
     *
     * The demo join lives here rather than in the two login handlers so both credential paths
     * (OIDC and local) get one answer; it fires only for a user with NO membership at all, so
     * somebody removed from `demo` on purpose is not re-added by their next login — the same
     * rule O-3 states for the workspace itself. A materialised invitation IS a membership,
     * which is exactly what keeps D-R11's promise: the invited workspace replaces the
     * `demo` default, it is not added beside it.
     *
     * Null when there is nothing to stamp: no membership and no active `demo` (deactivated, or
     * never seeded). Round 1 returns that state; round 2 draws the "no workspace" page.
     *
     * **The D16 mint rides the resolution:** every non-null answer mints the user's one MCP
     * key for that workspace on the way out ([McpKeyMint]) — login is exactly when "entered
     * a workspace" becomes true, and a key that already exists makes the mint a no-op, so a
     * second login mints nothing. A user who owes a password change gets nothing until the
     * first login or switch after it (the mint's own gate).
     */
    open fun workspaceForLogin(
        user: User,
        email: String,
    ): WorkspaceContext? {
        val normalized = email.trim().lowercase()
        val materialised = invitationRepository.materialiseFor(normalized, user.id)
        if (materialised.isNotEmpty()) {
            // The resolution below reads memberships through the cache; these memberships
            // were born one statement ago, so the cache must never see the pre-materialise
            // snapshot. The audit carries the INVITER (auth.md §10) — the actor who made the
            // decision being executed here is the new user's login, not the login itself.
            authCache.invalidateMemberships(user.id)
            materialised.forEach { row ->
                auditLogger.log(
                    event = "workspace.invitation_materialised",
                    userId = user.id,
                    details =
                        mapOf(
                            "workspace" to row.workspaceName,
                            "email" to normalized,
                            "role" to row.role.wire,
                            "inviter" to row.invitedBy.toString(),
                        ),
                )
            }
        }
        val memberships = activeMemberships(user.id)
        lastUsedWorkspaceStore?.lastUsed(user.id)?.let { last ->
            memberships.firstOrNull { it.workspaceName == last }?.let { return minted(user, context(it)) }
        }
        memberships.firstOrNull()?.let { return minted(user, context(it)) }
        return demoWorkspaceSeeder
            ?.joinDemoIfUnaffiliated(user.id)
            ?.also { authCache.invalidateMemberships(user.id) }
            ?.let { minted(user, it) }
    }

    /**
     * The switch half of D16: a successful switch IS an entry into that workspace, so it
     * mints exactly as login does. Called by the switch handler once the target has
     * resolved (a refused switch mints nothing — the resolution threw).
     */
    open fun mintMcpKeyOnEntry(
        user: User,
        context: WorkspaceContext,
    ) {
        mcpKeyMint?.mint(user, context)
    }

    /** Resolution plus the D16 mint — the one shape every login answer takes. */
    private fun minted(
        user: User,
        context: WorkspaceContext,
    ): WorkspaceContext {
        mintMcpKeyOnEntry(user, context)
        return context
    }

    /**
     * Creates a workspace (D-R11: super admins only — the capability gate is
     * [ScopeMatrix.RestOperation.MANAGE_INSTANCE_WORKSPACES] at the interceptor, and this
     * re-asserts it because a service must not depend on having been called from a governed
     * route). The creator enters as the workspace ADMIN.
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct refusal to its own catalogued code
    open fun create(
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
    open fun listOwn(principal: AuthenticatedPrincipal): List<WorkspaceMembership> =
        if (principal.isSuperAdmin) {
            workspaceRepository.findAll().map {
                WorkspaceMembership(
                    workspaceId = it.id,
                    workspaceName = it.name,
                    // The row's OWN role (viewer when implicit): the listing says what the
                    // membership carries; the instance authority is the principal's, not the row's.
                    role = roleIn(it.id, principal.userId) ?: WorkspaceRole.VIEWER,
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
    open fun read(
        principal: AuthenticatedPrincipal,
        name: String,
    ): Workspace {
        requirePinnedWorkspace(principal, name)
        val workspace = authCache.workspaceByName(name) { workspaceRepository.findByName(it) } ?: throw WorkspaceNotFoundException(name)
        val visible = principal.isSuperAdmin || (workspace.isActive && isMember(principal.userId, workspace.id))
        if (!visible) throw WorkspaceNotFoundException(name)
        return workspace
    }

    /** Renames the display name (`name` is immutable v1). Workspace admin or super admin. */
    open fun updateDisplayName(
        principal: AuthenticatedPrincipal,
        name: String,
        displayName: String,
    ): Workspace {
        val workspace = read(principal, name)
        requirePermission(principal, workspace, Permission.WS_ADMIN)
        val updated =
            workspaceRepository.updateDisplayName(workspace.id, displayName)
                // The row vanished between read() and the write: the 404 rule answers the race
                // the same way it answers everything else.
                ?: throw WorkspaceNotFoundException(name)
        authCache.invalidateWorkspace(updated)
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
    open fun deactivate(
        principal: AuthenticatedPrincipal,
        name: String,
    ): Workspace {
        // read() FIRST: the pin and the 404 rule are the outermost gates, so a workspace this
        // caller cannot reach answers "no such workspace" whatever their role — a role refusal
        // reached before the 404 would confirm the workspace exists.
        val workspace = read(principal, name)
        requireSuperAdmin(principal)
        if (!workspaceRepository.deactivate(workspace.id, principal.userId)) {
            throw WorkspaceInactiveException(name)
        }
        invalidateEveryone(workspace)
        audit(principal, "workspace.deactivated", name, mapOf("workspace" to name))
        return workspaceRepository.findById(workspace.id) ?: error("workspace ${workspace.id} vanished after deactivate")
    }

    /** Reactivates a workspace (design §6, super admin, audited). */
    open fun reactivate(
        principal: AuthenticatedPrincipal,
        name: String,
    ): Workspace {
        // read() FIRST: the pin and the 404 rule are the outermost gates, so a workspace this
        // caller cannot reach answers "no such workspace" whatever their role — a role refusal
        // reached before the 404 would confirm the workspace exists.
        val workspace = read(principal, name)
        requireSuperAdmin(principal)
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
    open fun delete(
        principal: AuthenticatedPrincipal,
        name: String,
    ) {
        // read() FIRST, for the reason [deactivate] gives.
        val workspace = read(principal, name)
        requireSuperAdmin(principal)
        val counts = contentCheck.nonDeletedCounts(workspace.id).filterValues { it > 0 }
        if (counts.isNotEmpty()) throw WorkspaceInUseException(name, counts)
        val members = workspaceRepository.findMembersOf(workspace.id)
        workspaceRepository.softDelete(workspace.id)
        members.forEach { authCache.invalidateMemberships(it.userId) }
        authCache.invalidateWorkspace(workspace)
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
    open fun members(
        principal: AuthenticatedPrincipal,
        name: String,
    ): List<WorkspaceMemberRow> {
        val workspace = read(principal, name)
        return workspaceRepository.findMembersOf(workspace.id)
    }

    /**
     * The members listing WITH its pending invitations (113 §B.4): the REST `GET …/members`
     * returns the two arrays separately, because a client that counts members must not count
     * ghosts. [members] stays the members-only read for the UI's per-workspace count.
     */
    open fun membersWithInvitations(
        principal: AuthenticatedPrincipal,
        name: String,
    ): MemberListing {
        val workspace = read(principal, name)
        return MemberListing(
            members = workspaceRepository.findMembersOf(workspace.id),
            invitations = invitationRepository.findByWorkspace(workspace.id),
        )
    }

    /** The members listing's two arrays — real memberships and pending invitations, never mixed. */
    data class MemberListing(
        val members: List<WorkspaceMemberRow>,
        val invitations: List<WorkspaceInvitation>,
    )

    /**
     * Revokes a pending invitation (113 §B.4). Workspace admin or super admin. An email with
     * no invitation here answers [WorkspaceInvitationNotFoundException] — the workspace
     * resolved, so the not-found thing is the invitation (`workspace.invitation.not_found`,
     * 404).
     *
     * A DEACTIVATED workspace is revocable-for-the-super-admin on purpose: its pending
     * invitations wait (§B.5), and cleaning them up before reactivation is exactly the kind
     * of tidying a super admin inside a greyed workspace is for. Members cannot reach the
     * workspace at all (the 404 rule), so nothing leaks.
     */
    open fun revokeInvitation(
        principal: AuthenticatedPrincipal,
        name: String,
        email: String,
    ) {
        val normalized = email.trim().lowercase()
        val workspace = read(principal, name)
        requirePermission(principal, workspace, Permission.WS_ADMIN)
        if (!invitationRepository.delete(workspace.id, normalized)) {
            throw WorkspaceInvitationNotFoundException(name, normalized)
        }
        audit(
            principal,
            "workspace.invitation_revoked",
            name,
            mapOf("workspace" to name, "email" to normalized),
        )
    }

    /**
     * Adds a member with [role] (D6, D20; workspace admin or super admin). The default is
     * VIEWER — the D-R11 default and the one the demo path uses.
     *
     * ## The invitation branch (113, auth.md §4.6)
     * When no `users` row holds [email], the request does not fail: it creates an INVITATION
     * keyed by the email — upsert on the same (workspace, email), so a second invite
     * REPLACES the role and the latest admin decision wins, audited every time — and
     * returns [AddMemberOutcome.Invited]. The login path materialises it into a real
     * membership when the row comes into existence. An invitation for a user who ALREADY
     * exists is never created: the existing-user branch makes them a member at once.
     *
     * An email the §4.3 domain allowlist would refuse AT LOGIN is refused HERE
     * ([InvitationDomainNotAllowedException] — the login code, at a 400): an invitation
     * that could never be honoured is a trap, so refuse it early, naming the same rule the
     * login would have refused it with. A deactivated workspace refuses the verb too
     * ([WorkspaceInactiveException] — reachable only by a super admin, per the 404 rule).
     *
     * The email is normalized lowercase before every lookup and store (§4.2), so
     * `Bob@Company.com` and `bob@company.com` are one invitee, one row, one person.
     *
     * The email is resolved here so the unknown-user mapping and the membership write are one
     * transaction of intent. Idempotent by the repository's `ON CONFLICT DO NOTHING`:
     * re-adding an existing member returns them unchanged rather than silently resetting
     * their role to the request's — changing an existing member's role is [setMemberRole],
     * which the last-admin rule guards.
     */
    open fun addMember(
        principal: AuthenticatedPrincipal,
        name: String,
        email: String,
        role: WorkspaceRole = WorkspaceRole.VIEWER,
    ): AddMemberOutcome {
        val normalized = email.trim().lowercase()
        val workspace = read(principal, name)
        requirePermission(principal, workspace, Permission.WS_ADMIN)
        // §B.5: a deactivated workspace refuses invites. A member never gets here (their 404
        // rule answered already); this is the super admin, who CAN see the greyed workspace,
        // being told the same thing the members-verbs would tell any other role.
        if (!workspace.isActive) throw WorkspaceInactiveException(name)
        val user = userRepository.findByEmail(normalized)
        if (user != null) {
            val row =
                workspaceRepository.addMember(workspace.id, user.id, role)
                    ?: error("membership for $normalized in $name vanished after insert")
            authCache.invalidateMemberships(user.id)
            audit(
                principal,
                "workspace.member_added",
                name,
                mapOf("workspace" to name, "member" to normalized, "role" to role.wire),
            )
            return AddMemberOutcome.Added(row)
        }
        // The invitation branch: the person does not exist yet. Refuse emails the allowlist
        // would refuse at login BEFORE storing anything (§B.1), then upsert — the latest
        // admin decision wins, and the upsert is audited every time it fires.
        if (!authProperties.isDomainAllowed(normalized)) {
            throw InvitationDomainNotAllowedException(normalized)
        }
        invitationRepository.upsert(workspace.id, normalized, role, principal.userId)
        audit(
            principal,
            "workspace.member_invited",
            name,
            mapOf("workspace" to name, "email" to normalized, "role" to role.wire),
        )
        return AddMemberOutcome.Invited(email = normalized, role = role)
    }

    /** What [addMember] did: a real membership now exists, or an invitation now does. */
    sealed interface AddMemberOutcome {
        /** The user row existed; the membership was written — the pre-113 behaviour. */
        data class Added(
            val row: WorkspaceMemberRow,
        ) : AddMemberOutcome

        /**
         * No user row existed; an invitation was created (or its role replaced). The login
         * path materialises it when the person first exists. Distinguishable from [Added] by
         * the HTTP status (202 vs 200) AND the body, so a client never mistakes a ghost for
         * a member.
         */
        data class Invited(
            val email: String,
            val role: WorkspaceRole,
        ) : AddMemberOutcome
    }

    /**
     * Replaces a member's role (D1, D20). Refuses to demote the LAST admin with
     * [WorkspaceLastAdminException] — a workspace with no admin is unmanageable, and the
     * refusal names the fix ("give someone else the admin role first") rather than the rule.
     */
    open fun setMemberRole(
        principal: AuthenticatedPrincipal,
        name: String,
        userId: UUID,
        role: WorkspaceRole,
    ): WorkspaceMemberRow {
        val workspace = read(principal, name)
        requirePermission(principal, workspace, Permission.WS_ADMIN)
        val target = workspaceRepository.findMemberRow(workspace.id, userId) ?: throw WorkspaceNotFoundException(name)
        requireNotSelf(principal, workspace, userId)
        if (target.role == WorkspaceRole.WORKSPACE_ADMIN && role != WorkspaceRole.WORKSPACE_ADMIN) {
            requireAnotherAdmin(workspace, userId)
        }
        workspaceRepository.setRole(workspace.id, userId, role)
        authCache.invalidateMemberships(userId)
        audit(
            principal,
            "workspace.member_role_changed",
            name,
            mapOf(
                "workspace" to name,
                "member_user_id" to userId.toString(),
                "from" to target.role.wire,
                "to" to role.wire,
            ),
        )
        return workspaceRepository.findMemberRow(workspace.id, userId)
            ?: error("membership for $userId in $name vanished after update")
    }

    /**
     * Removes a membership. Workspace admin or super admin; refuses the LAST admin
     * ([WorkspaceLastAdminException]) for the same reason [setMemberRole] does.
     *
     * A user id that names no member of this workspace answers with the workspace's own 404
     * (D-R5): "there is no such member here" and "there is no such workspace for you" must not
     * be distinguishable, or the member list becomes probeable one id at a time.
     *
     * **The member's login-minted key ends with the membership** (roles record §3.7, ruling 1,
     * #200): a key is tied to user + workspace, so the membership row's removal revokes the
     * `user` key pinned here in the SAME act, audited `auth.api_key.revoked_by_admin` with
     * reason `member_removed`. Before #200 a removed member's key kept authenticating through
     * `ApiKeyService.pinnedContext`'s viewer fallback — reading, running and reading results
     * in a workspace they had been removed from. The whole sequence is ONE metadata
     * transaction: a failure removes nothing and revokes nothing.
     */
    @Transactional("metadataTransactionManager")
    open fun removeMember(
        principal: AuthenticatedPrincipal,
        name: String,
        userId: UUID,
    ) {
        val workspace = read(principal, name)
        requirePermission(principal, workspace, Permission.WS_ADMIN)
        val target = workspaceRepository.findMemberRow(workspace.id, userId) ?: throw WorkspaceNotFoundException(name)
        requireNotSelf(principal, workspace, userId)
        if (target.role == WorkspaceRole.WORKSPACE_ADMIN) requireAnotherAdmin(workspace, userId)
        workspaceRepository.removeMember(workspace.id, userId)
        // §3.7 ruling 1 — the key the membership minted dies with it. Null when the member
        // held no live key (never minted, already rotated): nothing to evict, nothing to audit.
        val revokedKeyId = apiKeyRepository.revokeUserKeyForWorkspace(userId, workspace.id)
        // Evicted now AND again after the commit. This method runs inside one metadata
        // transaction, so a validation racing the removal on another connection reads the
        // still-live row (READ COMMITTED never blocks on the uncommitted UPDATE) and would
        // re-admit it for the cache's TTL; the after-commit eviction is the one that wins the
        // race, the immediate one keeps the common path short (#200 review M1).
        val evict: () -> Unit = {
            authCache.invalidateMemberships(userId)
            revokedKeyId?.let { authCache.invalidateKey(it) }
        }
        evict()
        afterCommitOrNow(evict)
        audit(
            principal,
            "workspace.member_removed",
            name,
            mapOf("workspace" to name, "member_user_id" to userId.toString()),
        )
        revokedKeyId?.let {
            audit(
                principal,
                "auth.api_key.revoked_by_admin",
                name,
                mapOf(
                    "workspace" to name,
                    "target_user_id" to userId.toString(),
                    "reason" to "member_removed",
                ),
                keyId = it,
            )
        }
    }

    /**
     * Revokes a member's login-minted key WITHOUT removing them (roles record §3.7, ruling 3,
     * #200) — the admin's recovery lever beside [removeMember], and the one answer to a lost
     * or leaked credential that needs no identity event: no password reset fires, no rotation
     * is scheduled (§3.7 ruling 2), the member's SESSION keeps working, and their next login
     * or workspace entry mints a fresh key. Workspace admin or super admin
     * (`MANAGE_WORKSPACE_MEMBERS`, the members row of §7.6); the same 404 rule for a
     * non-member. Idempotent: a member with no live key is already in the state the verb asks
     * for, so nothing is revoked and nothing audited.
     */
    open fun revokeMemberKey(
        principal: AuthenticatedPrincipal,
        name: String,
        userId: UUID,
    ) {
        val workspace = read(principal, name)
        requirePermission(principal, workspace, Permission.WS_ADMIN)
        workspaceRepository.findMemberRow(workspace.id, userId) ?: throw WorkspaceNotFoundException(name)
        requireNotSelf(principal, workspace, userId)
        val revokedKeyId = apiKeyRepository.revokeUserKeyForWorkspace(userId, workspace.id)
        if (revokedKeyId != null) {
            authCache.invalidateKey(revokedKeyId)
            audit(
                principal,
                "auth.api_key.revoked_by_admin",
                name,
                mapOf(
                    "workspace" to name,
                    "target_user_id" to userId.toString(),
                    "reason" to "admin_revoked",
                ),
                keyId = revokedKeyId,
            )
        }
    }

    /**
     * Which members of [name] hold a live login-minted key (#200) — the members row's
     * "has a key" state. Admin-only here in the service, not only at the callers (#200 review
     * L1): the answer never leaves the admin's own workspace, and it carries owner ids only —
     * no key id, no prefix, no plaintext (the members row is not a key listing).
     */
    open fun liveUserKeyOwnerIds(
        principal: AuthenticatedPrincipal,
        name: String,
    ): Set<UUID> {
        val workspace = read(principal, name)
        requirePermission(principal, workspace, Permission.WS_ADMIN)
        return apiKeyRepository.liveUserKeyOwnerIds(workspace.id)
    }

    /**
     * The house pattern (`MailNotifier`): inside a transaction, after its commit; otherwise
     * now. Cache evictions that must outlive a transactional write go through here — an
     * eviction that runs before the commit can be undone by a concurrent reload.
     */
    private fun afterCommitOrNow(task: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = task()
                },
            )
        } else {
            task()
        }
    }

    /**
     * KEPT for the UI's add-member form ([co.datapipelines.web.ui.WorkspacesUiController],
     * which catches it for the `user_not_found` banner) — but the REST surface no longer
     * raises it: since 113, an unknown email at [addMember] creates an INVITATION
     * ([AddMemberOutcome.Invited]) instead of failing. The UI form therefore invites
     * silently today; rendering the invited outcome on the workspaces screen (and retiring
     * this class) is 114's/post-merge work. No production path throws it.
     */
    class UnknownMemberEmailException(
        val email: String,
    ) : IllegalStateException("No user with email '$email'.")

    /**
     * The context [principal] would run in inside [name] — role resolved — or null when the
     * workspace does not exist FOR THEM (D-R5: unknown, non-member, or deactivated).
     *
     * The one resolution path (D-R8: "super admins resolve any workspace THROUGH THE SAME
     * PATH, with the audit flag"). A deactivated workspace is not selectable by anyone,
     * super admin included: design §6's first effect has no exception, and a super admin who
     * needs to act inside one reactivates it first — an audited, reversible step.
     */
    open fun contextFor(
        principal: AuthenticatedPrincipal,
        name: String,
    ): WorkspaceContext? {
        if (principal.authMethod == AuthMethod.API_KEY && principal.workspaceName != name) return null
        val workspace = authCache.workspaceByName(name) { workspaceRepository.findByName(it) } ?: return null
        if (!workspace.isActive) return null
        val explicit = memberships(principal.userId).firstOrNull { it.workspaceId == workspace.id }
        return when {
            principal.isSuperAdmin -> {
                WorkspaceContext.superAdminOver(workspace.id, workspace.name, explicit?.role)
            }

            explicit != null -> {
                WorkspaceContext(workspace.id, workspace.name, explicit.role)
            }

            else -> {
                null
            }
        }
    }

    /**
     * The membership guard API-key issuance extends (auth.md §7.4): a key may only be pinned to
     * a workspace its creator can reach, and — since D-R12/O-2 — only by an AUTHOR there.
     * Throws [WorkspaceNotFoundException] for the unreachable case (D-R5) and
     * [RoleRequiredException] for the viewer.
     */
    @Suppress("ThrowsCount") // three distinct refusals: unknown workspace, unreachable, wrong role
    open fun requireIssuancePermission(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
    ): WorkspaceContext {
        val workspace = workspaceRepository.findById(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId.toString())
        val context = contextFor(principal, workspace.name) ?: throw WorkspaceNotFoundException(workspace.name)
        if (!context.permits(Permission.AUTHOR)) {
            throw RoleRequiredException(Permission.AUTHOR, context.held(), workspace.name)
        }
        return context
    }

    /**
     * The context a KEY's issuer currently holds in the key's pinned workspace, or null when
     * the workspace is unreachable for them now (D-R12: removed issuer, deactivated workspace).
     * Read per request through the cache, which is what bounds the demotion window at one TTL.
     */
    open fun issuerContext(
        issuerId: UUID,
        issuerIsSuperAdmin: Boolean,
        workspaceId: UUID,
        workspaceName: String,
    ): WorkspaceContext? {
        val explicit = memberships(issuerId).firstOrNull { it.workspaceId == workspaceId && it.workspaceActive }
        return when {
            issuerIsSuperAdmin -> WorkspaceContext.superAdminOver(workspaceId, workspaceName, explicit?.role)
            explicit != null -> WorkspaceContext(workspaceId, workspaceName, explicit.role)
            else -> null
        }
    }

    /** True when [principal] may operate in [workspaceId] — member or super admin (D-R8). */
    open fun canAccess(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
    ): Boolean = canAccess(principal.userId, principal.isSuperAdmin, workspaceId)

    /** As [canAccess], for callers holding the identity as data. */
    open fun canAccess(
        userId: UUID,
        isSuperAdmin: Boolean,
        workspaceId: UUID,
    ): Boolean = isSuperAdmin || isMember(userId, workspaceId)

    /**
     * The pinned-workspace rule for key principals (auth.md §5.6, D-R9; the 025 review's
     * BLOCKING finding, kept through round 1 by re-derivation rather than by inheritance).
     *
     * A key's workspace is fixed at issuance, so a key resolves EXACTLY ONE workspace. Without
     * this, every path built on [read] would authorize against the key OWNER's whole membership
     * set — and a key pinned to `acme` would rename, delete and re-member `globex` the moment
     * its owner belonged to both, defeating the pin that `WorkspaceResolutionFilter` hard-refuses
     * `DP-Workspace` to protect. That is not a hypothetical: it is the hole 025 shipped and
     * 66fa930 closed, and RBAC round 1 rewrote every one of those paths.
     *
     * Enforced HERE, at the one resolution both reads and management verbs pass through, rather
     * than as a guard on each verb — a new verb built on [read] inherits it, and a guard list is
     * a thing you can forget to add to.
     *
     * The refusal is the D-R5 404, like every other unreachable workspace: "pinned elsewhere"
     * and "does not exist" must stay indistinguishable, or the pin itself becomes an oracle.
     * Sessions are untouched — their active workspace is switchable by design, so no pin exists
     * to honour. A super admin's key is NOT exempt: the pin is a property of the credential, not
     * of the person, and a leaked key must not become a skeleton key because its owner is
     * privileged.
     */
    private fun requirePinnedWorkspace(
        principal: AuthenticatedPrincipal,
        name: String,
    ) {
        if (principal.authMethod == AuthMethod.API_KEY && principal.workspaceName != name) {
            throw WorkspaceNotFoundException(name)
        }
    }

    private fun isMember(
        userId: UUID,
        workspaceId: UUID,
    ): Boolean = memberships(userId).any { it.workspaceId == workspaceId }

    private fun roleIn(
        workspaceId: UUID,
        userId: UUID,
    ): WorkspaceRole? = memberships(userId).firstOrNull { it.workspaceId == workspaceId }?.role

    private fun context(membership: WorkspaceMembership): WorkspaceContext =
        WorkspaceContext(membership.workspaceId, membership.workspaceName, membership.role)

    private fun requireSuperAdmin(principal: AuthenticatedPrincipal) {
        if (!principal.isSuperAdmin) {
            throw RoleRequiredException(Permission.SUPER_ADMIN, emptySet())
        }
    }

    /**
     * #208 (owner ruling 2026-09-22): nobody administers their OWN membership — not their role,
     * not their login-minted key, not their removal. Judged AFTER the 404 rule so a non-member
     * super admin addressing themselves still gets the workspace's own not-found.
     */
    private fun requireNotSelf(
        principal: AuthenticatedPrincipal,
        workspace: Workspace,
        userId: UUID,
    ) {
        if (userId == principal.userId) throw WorkspaceSelfMembershipException(workspace.name)
    }

    private fun requirePermission(
        principal: AuthenticatedPrincipal,
        workspace: Workspace,
        permission: Permission,
    ) {
        val explicit = roleIn(workspace.id, principal.userId)
        val context =
            if (principal.isSuperAdmin) {
                WorkspaceContext.superAdminOver(workspace.id, workspace.name, explicit)
            } else {
                WorkspaceContext(workspace.id, workspace.name, explicit ?: throw WorkspaceNotFoundException(workspace.name))
            }
        if (!context.permits(permission)) throw RoleRequiredException(permission, context.held(), workspace.name)
    }

    /** The last-admin rule: [excluding] is the member about to lose admin (design §1). */
    private fun requireAnotherAdmin(
        workspace: Workspace,
        excluding: UUID,
    ) {
        val remaining =
            workspaceRepository
                .findMembersOf(workspace.id)
                .count { it.role == WorkspaceRole.WORKSPACE_ADMIN && it.userId != excluding }
        if (remaining == 0) throw WorkspaceLastAdminException(workspace.name)
    }

    private fun invalidateEveryone(workspace: Workspace) {
        workspaceRepository.findMembersOf(workspace.id).forEach { authCache.invalidateMemberships(it.userId) }
        authCache.invalidateWorkspace(workspace)
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
        keyId: String? = null,
    ) {
        val actingVia =
            if (principal.isSuperAdmin && roleIn(workspaceIdOf(workspaceName), principal.userId) == null) {
                mapOf(AuditLogger.ACTING_VIA to AuditLogger.ACTING_VIA_SUPER_ADMIN)
            } else {
                emptyMap<String, Any?>()
            }
        auditLogger.log(event = event, userId = principal.userId, keyId = keyId, details = details + actingVia)
    }

    /** The id behind an already-resolved name; a vanished row audits without the membership probe. */
    private fun workspaceIdOf(name: String): UUID =
        authCache.workspaceByName(name) { workspaceRepository.findByName(it) }?.id ?: NIL_WORKSPACE

    private companion object {
        /** metadata-db §4.11 — `[a-z0-9_-]+`, 1–63, immutable. */
        val NAME_REGEX = Regex("[a-z0-9_-]{1,63}")

        /** Stands in for "the workspace is already gone" in the audit path; never persisted. */
        val NIL_WORKSPACE: UUID = UUID(0, 0)
    }
}
