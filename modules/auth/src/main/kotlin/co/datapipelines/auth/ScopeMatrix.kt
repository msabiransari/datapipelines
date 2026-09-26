package co.datapipelines.auth

/**
 * The authorization matrix (auth.md §7.6): the ONE place a principal is judged against the
 * catalog [Permission] a handler or tool declares. `ScopeInterceptor` and `McpToolDispatcher` call
 * [allowed] / [allowedTool] and nothing else, so the two surfaces cannot drift.
 *
 * Since #215 slice (b) the answer is a ROLE's, and only a role's (record PK8 — scopes are gone):
 *
 * - a SESSION holds its membership's role in the active workspace, or a super admin's D7
 *   authority ([RolePermissions]);
 * - a KEY of every kind (keys v2 A13) holds its own [KeyRole] — for an `mcp` key one of the
 *   MEMBER roles chosen at creation (`author`, `promoter`, `workspace_admin`), for the other
 *   kinds their transport role (`api_caller`, `promotion_receiver`) — and nothing else
 *   (record §3.2): the key role IS the answer. There is no derivation from a membership and
 *   no cap; never a super admin's authority (B1). Since #239 an `mcp` key's member role is
 *   ANSWERED through the installed [PermissionResolver] — the same question a session's
 *   `holds` asks, the production resolver reading the same member column, so no observable
 *   answer changed — which is what lets the isolated-permission witness isolate a tool again
 *   (`PermissionSeamE2eTest`). The two transport roles are read from the table directly;
 *   the record's "separate wire tests use actual persisted roles" stays true for them.
 *
 * `ScopeMatrixSpecDriftTest` asserts the catalog and every role column (member and key) against
 * auth.md §7.6 in both directions; `RequiredScopeCoverageTest` that every handler declares a
 * permission; `MatrixRowReachabilityTest` that every permission is claimed and every route and tool
 * sits on the row of the permission it declares. (The object keeps its historical name; nothing in
 * it is about scopes any more.)
 */
object ScopeMatrix {
    /**
     * The permissions a SESSION may hold with no workspace context at all (see [allowed]): the
     * workspaces page and the REST list-own — "which workspaces do I belong to" is a question
     * whose answer may be none.
     */
    private val NO_CONTEXT_SESSION_PERMISSIONS: Set<Permission> = setOf(Permission.WORKSPACE_READ, Permission.WORKSPACE_SWITCH)

    /**
     * The verdict of one authorization question (RBAC design §2). A sealed pair rather than a
     * boolean so the REFUSAL carries its catalogued code and its details to the boundary that
     * writes them — the interceptor and the MCP dispatcher render the same decision two ways,
     * and neither re-derives why.
     */
    sealed interface Decision {
        /** The operation may proceed. */
        data object Allowed : Decision

        /**
         * The operation is refused. [code] is a §13.7/§13.12 catalogued code; [details] is what
         * the error body's `details` object carries.
         */
        data class Refused(
            val code: String,
            val message: String,
            val userMessage: String,
            val details: Map<String, Any?> = emptyMap(),
        ) : Decision
    }

    /**
     * **The** authorization function. `ScopeInterceptor` and `McpToolDispatcher` call this and
     * nothing else.
     *
     * - A principal with a [KeyRole] (every key since keys v2 — an `mcp` key of the chosen
     *   member role, an `endpoint` key, a server key's promotion peer — the config-value peer
     *   included) is judged by that role's column. For the two TRANSPORT roles the column is
     *   read directly, and no workspace is needed to answer it: the promotion receiver takes a
     *   batch for any workspace (B6), and an `endpoint` key's pinned workspace was judged live
     *   when its credential became a principal. An `mcp` key's MEMBER role asks the installed
     *   [PermissionResolver] with the key's pinned workspace (#239) — the same call a session's
     *   `holds` makes, the production resolver answering the same member column.
     * - Every other principal is judged by its [context]'s role — a session's membership (or a
     *   super admin's D7 authority).
     *
     * A null [context] means the principal resolved no workspace: every workspace permission is
     * refused with `workspace.not_found` (D-R5). Two session-only exceptions: the two "which
     * workspaces do I belong to" permissions, and a super admin's [RolePermissions.INSTANCE]
     * permissions — #113's empty-instance recovery path. No key is ever a super admin (B1).
     */
    fun allowed(
        principal: AuthenticatedPrincipal,
        permission: Permission,
        context: WorkspaceContext?,
    ): Decision = allowed(principal, permission.wire, permission, context)

    /**
     * As [allowed], for an MCP tool: [permission] is the one the tool's catalog entry declares
     * (`McpToolCatalog.Entry.permission`). A tool that declares none is refused — fail closed, with
     * [AuthErrorCodes.PERMISSION_UNDECLARED]: the drift and reachability tests make it unreachable
     * in a built artifact, and the branch exists so a tool added at runtime cannot be the one
     * nothing judges.
     */
    fun allowedTool(
        principal: AuthenticatedPrincipal,
        tool: String,
        permission: Permission?,
        context: WorkspaceContext?,
    ): Decision {
        val declared =
            permission
                ?: return Decision.Refused(
                    code = AuthErrorCodes.PERMISSION_UNDECLARED,
                    message = "Tool '$tool' declares no permission and cannot be called.",
                    userMessage = "You do not have permission to perform this action.",
                    details = mapOf("tool" to tool),
                )
        return allowed(principal, tool, declared, context)
    }

    @Suppress("ReturnCount") // one guarded refusal per rule; a merged expression would hide which rule fired
    private fun allowed(
        principal: AuthenticatedPrincipal,
        operationName: String,
        permission: Permission,
        context: WorkspaceContext?,
    ): Decision {
        // A key that acts as its own identity (record §3.2): its key role is the whole answer.
        // An `mcp` key's MEMBER role is asked of the installed resolver (#239) — the same call a
        // session's `holds` makes, so the isolated-permission witness isolates a tool; the
        // production resolver reads the same member column. The two transport roles keep the
        // direct read: the record's B4 wire-truth sentence stays true for them, and
        // [PermissionResolver]'s KDoc says why one decision must not split across two paths.
        principal.keyRole?.let { role ->
            val memberRole = role.asMemberRole()
            val held =
                if (memberRole == null) {
                    permission in RolePermissions.of(role)
                } else {
                    PermissionResolution.resolver.holds(context?.id, memberRole, false, permission)
                }
            if (held) return Decision.Allowed
            return roleRefusal(AuthErrorCodes.ROLE_REQUIRED, operationName, permission, role.wire, context?.name)
        }
        // A promotion principal ALWAYS carries the promotion_receiver role (the filter stamps it on
        // both credential shapes). One without it is a wiring defect, and it fails closed.
        if (principal.authMethod == AuthMethod.PROMOTION) {
            return roleRefusal(AuthErrorCodes.ROLE_REQUIRED, operationName, permission, null, null)
        }

        // D-R5: no reachable workspace means the workspace does not exist, for this caller.
        //
        // Both null-context exceptions below are SESSIONS-ONLY: a key always pins a context, so
        // a key never arrives here, and on a key the 404 the rule promises stays.
        val sessionWithoutContext = context == null && principal.authMethod != AuthMethod.API_KEY

        // Two permissions are meaningful with no workspace at all: listing the workspaces you
        // belong to — the page (`workspace.read`) and the REST list-own on the switcher's row
        // (`workspace.switch`) — which is also how a zero-membership person reaches the
        // no-workspace page (ui-screens §4.13, 114 §C.3a) instead of a JSON 404 on the only
        // screen that could explain their state. A switch itself still needs a membership in
        // the target, which the handler checks.
        if (sessionWithoutContext && permission in NO_CONTEXT_SESSION_PERMISSIONS) {
            return Decision.Allowed
        }

        // #113 — the empty-instance recovery carve-out. A super admin SESSION with NO reachable
        // workspace (every workspace deactivated — D-R10 has no last-active guard, and that is
        // deliberate: decommissioning the final workspace is a legitimate operator act) keeps
        // the INSTANCE permissions — create / deactivate / reactivate / delete a workspace, user
        // administration, instance datasource grants, server keys; none reads or writes a
        // workspace's content — and refusing them stranded the one principal who can repair an
        // empty deployment behind the 404 below (witnessed end-to-end by
        // SuperAdminRecoveryE2eTest). The evidence is the user row's `is_admin`, not a context
        // this request does not have. No key is a super admin (B1), so no key reaches it.
        if (sessionWithoutContext && permission in RolePermissions.INSTANCE && principal.isSuperAdmin) {
            return Decision.Allowed
        }
        if (context == null) {
            return Decision.Refused(
                code = WorkspaceErrorCodes.NOT_FOUND,
                message = "No active workspace resolved for this principal",
                userMessage = "We couldn't find that workspace.",
                details = mapOf("operation" to operationName),
            )
        }

        if (context.permits(permission)) return Decision.Allowed

        // D-R12's distinct refusal: the KEY is fine, the role it was judged against does not
        // reach this — a caller must be told to ask for a role rather than to retry with this
        // key. (Keys v2: every key carries its own role, so this arm answers a context-judged
        // key principal only — the promotion config-value peer and any legacy path that built
        // a key principal without a keyRole.)
        val code =
            if (principal.authMethod == AuthMethod.API_KEY) {
                AuthErrorCodes.KEY_ISSUER_ROLE_LOST
            } else {
                AuthErrorCodes.ROLE_REQUIRED
            }
        return roleRefusal(code, operationName, permission, context.heldRole, context.name)
    }

    /**
     * #215 A.6: `required` is the catalog permission; `held` is the ROLE it was judged against —
     * the member role (for the MCP key, the capped one), `super_admin`, or the key role —
     * informative only, no code compares it.
     */
    private fun roleRefusal(
        code: String,
        operationName: String,
        permission: Permission,
        heldRole: String?,
        workspace: String?,
    ): Decision.Refused =
        Decision.Refused(
            code = code,
            message =
                workspace?.let { "Principal lacks the required role in workspace '$it'" }
                    ?: "Principal's role does not hold the required permission",
            userMessage = "You do not have the role needed for this action in this workspace.",
            details =
                buildMap {
                    put("operation", operationName)
                    put("required", permission.wire)
                    heldRole?.let { put("held", it) }
                    workspace?.let { put("workspace", it) }
                },
        )
}
