package co.datapipelines.auth

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Workspace membership resolution and provisioning (design §5/§7).
 *
 * Every read goes through [AuthCache]'s 60s liveness discipline — the identical window
 * `users.is_active` already accepts (design §4), so workspace revocation takes effect
 * within ~1 minute, immediately on the instance that performed the mutation.
 *
 * ## Provisioning modes (design §7, configuration.md §3.17)
 * [create] is the service path all three modes share (the REST surface is slice 021):
 * - `auto-per-user` / `self-serve`: any authenticated user creates; `auto-per-user`
 *   additionally provisions a personal workspace on first login ([ensurePersonalWorkspace]).
 * - `closed`: only a global `admin` creates — anything else is
 *   [WorkspaceCreationForbiddenException].
 *
 * ## Personal-workspace names (design §7)
 * Derived from the lowercased email local-part, sanitized to the `[a-z0-9_-]+` (1–63)
 * name rule, and collision-suffixed (`alice`, `alice-2`, `alice-3`, …) because the
 * namespace is global and two `alice@` accounts must not race for one name.
 */
class WorkspaceService(
    private val workspaceRepository: WorkspaceRepository,
    private val authCache: AuthCache,
    private val workspacesProperties: WorkspacesProperties,
    private val lastUsedWorkspaceStore: LastUsedWorkspaceStore?,
    private val auditLogger: AuditLogger,
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
     */
    fun ensurePersonalWorkspace(
        user: User,
        email: String,
    ): Workspace {
        memberships(user.id).firstOrNull()?.let { existing ->
            return checkNotNull(workspaceRepository.findById(existing.workspaceId)) {
                "Membership ${existing.workspaceId} resolved to no workspace row"
            }
        }
        val name = availablePersonalName(email)
        val created = workspaceRepository.create(name, displayName = name, isPersonal = true, createdBy = user.id)
        authCache.invalidateMemberships(user.id)
        auditLogger.log(
            event = "auth.workspace.provisioned",
            userId = user.id,
            details = mapOf("workspace" to created.name, "mode" to WorkspaceProvisioningMode.AUTO_PER_USER.wire),
        )
        return created
    }

    /**
     * The workspace-creation service path every mode shares (design §7; the REST CRUD
     * surface is slice 021). `closed` refuses non-admins; the other modes allow any
     * authenticated user. The creator enters as `owner`.
     */
    fun create(
        principal: AuthenticatedPrincipal,
        name: String,
        displayName: String,
    ): Workspace {
        if (workspacesProperties.provisioningMode == WorkspaceProvisioningMode.CLOSED && !principal.isAdmin) {
            throw WorkspaceCreationForbiddenException(workspacesProperties.provisioningMode)
        }
        require(NAME_REGEX.matches(name)) {
            "Workspace name '$name' does not match ${NAME_REGEX.pattern} (workspace.validation.name_invalid lands with slice 021)"
        }
        val created = workspaceRepository.create(name, displayName, isPersonal = false, createdBy = principal.userId)
        authCache.invalidateMemberships(principal.userId)
        auditLogger.log(
            event = "auth.workspace.created",
            userId = principal.userId,
            details = mapOf("workspace" to created.name),
        )
        return created
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
    }
}
