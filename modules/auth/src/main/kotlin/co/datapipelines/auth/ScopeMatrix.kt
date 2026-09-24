package co.datapipelines.auth

/**
 * The authorization matrix (auth.md §7.6) as an enforceable structure, on **two axes** until
 * #215 slice (b): the [Permission] a handler or tool DECLARES — held by the caller's ROLE in
 * the ACTIVE WORKSPACE, per [RolePermissions] — and the minimum [Scope] an API-KEY credential
 * must carry, read from [PERMISSION_MIN_SCOPE] (the A4 shim). This is the ONLY place the two
 * are judged; `ScopeInterceptor` and `McpToolDispatcher` call [allowed] / [allowedTool] and
 * nothing else, so the two surfaces cannot drift.
 *
 * Since #215 slice (a) a handler declares a catalog permission directly
 * (`@RequiredScope(Permission.PIPELINE_READ)`) and a tool's is its entry in
 * [MCP_TOOL_PERMISSION]; the thirty coarse `RestOperation`s and the two per-axis tool maps are
 * gone. The scope axis is unchanged: every permission carries the `minScope` of the operation
 * or tool it replaced, so every API key reaches exactly what it reached before (the record's
 * A4). Slice (b) removes scopes and this shim with them.
 *
 * `ScopeMatrixSpecDriftTest` asserts the catalog, the role table and the shim against auth.md
 * §7.6 in both directions; `RequiredScopeCoverageTest` that every handler declares a
 * permission; `MatrixRowReachabilityTest` that every permission is claimed — by a handler, a
 * tool, or a service check — and that every route and tool sits on the §7.6 row of the
 * permission it declares.
 */
object ScopeMatrix {
    /**
     * The A4 shim (#215 slice (a)): each permission's minimum KEY scope — the `minScope` of the
     * operation or MCP tool it replaced, so the credential axis answers exactly as it did.
     * Total over the catalog (a permission with no scope is a build failure,
     * `ScopeMatrixSpecDriftTest`) and single-valued: no permission spans surfaces that carried
     * different floors.
     *
     * The four permissions no handler or tool declares — `execution.read_all`,
     * `execution.cancel_all`, `server_key.create`, `server_key.revoke` — are asked by services
     * through [AuthenticatedPrincipal.holds], which reads no scope; their entries are nominal
     * (the floor of the rows they widen, `admin` for the two instance ones). Slice (b) deletes
     * this map with [Scope].
     */
    val PERMISSION_MIN_SCOPE: Map<Permission, Scope> =
        buildMap {
            listOf(
                Permission.PIPELINE_READ,
                Permission.TEMPLATE_READ,
                Permission.EXECUTION_READ,
                Permission.EXECUTION_RESULT_READ,
                Permission.EXECUTION_READ_ALL,
                Permission.DATASOURCE_READ,
                Permission.ENDPOINT_READ,
                // "Serve a published endpoint": `read` is the FLOOR, not the gate — the path
                // binding is (`EndpointAuthorizer`), and an `endpoint` key never reaches it.
                Permission.ENDPOINT_SERVE,
                Permission.SEMANTIC_READ,
                Permission.CALCULATOR_READ,
                Permission.DOCS_READ,
                // The receiving side's inventory — reached only as the PROMOTION principal.
                Permission.PROMOTION_INVENTORY_READ,
                // The self verbs: `read` is the weakest scope there is, i.e. "any authenticated".
                Permission.MCP_KEY_OWN,
                Permission.WORKSPACE_SWITCH,
                Permission.WORKSPACE_READ,
                Permission.PROFILE_READ,
                Permission.PROFILE_PREFERENCE,
                Permission.PROFILE_PASSWORD,
            ).forEach { put(it, Scope.READ) }
            listOf(
                Permission.PIPELINE_EXECUTE,
                Permission.PIPELINE_RUN_CHECKS,
                Permission.EXECUTION_CANCEL,
                Permission.EXECUTION_CANCEL_ALL,
            ).forEach { put(it, Scope.EXECUTE) }
            // The instance verbs: `admin` is unobtainable by a key since O-2, so the scope axis
            // alone makes them session-only.
            RolePermissions.INSTANCE.forEach { put(it, Scope.ADMIN) }
            // Everything else carried `author`: authoring, release, switch, the live-connection
            // datasource verbs (037 F: a `read` key must not reach row data or a connection),
            // endpoints, lake tables, facts, promotion, and the workspace admin's verbs.
            Permission.entries.forEach { putIfAbsent(it, Scope.AUTHOR) }
        }

    /**
     * Every MCP tool → the ONE permission it declares (auth.md §7.6's catalog, mcp-server §6.2).
     * The dispatcher reaches it only through [allowedTool], which fails closed on a tool that
     * is not here. It lives beside the catalog rather than on `McpToolCatalog`'s entries
     * because two readers outside `mcp-server` take a tool's KEY scope through
     * [requiredScopeForTool] — the site's tool list and the skill's generated `tools.md` — and
     * `auth` cannot see `mcp-server`; slice (b), which removes that scope column, is where the
     * declaration can move onto the entry.
     *
     * `datasources_preview_rows`, `sql_probe` and `pipelines_execute_node` are author-and-above
     * (record §2.3): they return customer ROW data, which only an `author`-scoped key could
     * reach before, and no browser route reaches them.
     */
    val MCP_TOOL_PERMISSION: Map<String, Permission> =
        mapOf(
            "pipelines_list" to Permission.PIPELINE_READ,
            "pipelines_get" to Permission.PIPELINE_READ,
            "pipelines_create" to Permission.PIPELINE_CREATE,
            "pipelines_update" to Permission.PIPELINE_UPDATE,
            "pipelines_execute" to Permission.PIPELINE_EXECUTE,
            "pipelines_run_checks" to Permission.PIPELINE_RUN_CHECKS,
            "pipelines_execute_node" to Permission.PIPELINE_EXECUTE_NODE,
            "templates_list" to Permission.TEMPLATE_READ,
            "templates_get" to Permission.TEMPLATE_READ,
            "templates_used_by" to Permission.TEMPLATE_READ,
            "templates_create" to Permission.TEMPLATE_CREATE,
            "templates_update" to Permission.TEMPLATE_UPDATE,
            "templates_purge_draft" to Permission.TEMPLATE_VERSION_MANAGE,
            "templates_render" to Permission.TEMPLATE_RENDER,
            // 7b — evaluating untrusted code on the server is the same authoring act as rendering (R6).
            "templates_evaluate" to Permission.TEMPLATE_EVALUATE,
            // D11: own runs unless `execution.read_all` — the tools filter; the matrix admits the role.
            "executions_list" to Permission.EXECUTION_READ,
            "executions_get" to Permission.EXECUTION_READ,
            "executions_get_result" to Permission.EXECUTION_RESULT_READ,
            // The same-credential rule is the tool's gate, not a permission.
            "executions_cancel" to Permission.EXECUTION_CANCEL,
            "datasources_list" to Permission.DATASOURCE_READ,
            "datasources_get" to Permission.DATASOURCE_READ,
            // 107 — the engine's stored ESTIMATES about shape, never row data.
            "datasources_get_table_stats" to Permission.DATASOURCE_READ,
            "datasources_get_schemas" to Permission.DATASOURCE_INTROSPECT,
            "datasources_get_tables" to Permission.DATASOURCE_INTROSPECT,
            "datasources_get_columns" to Permission.DATASOURCE_INTROSPECT,
            // Follows execute (ratified 2026-09-20): the promoter runs nothing and may not.
            "datasources_test" to Permission.DATASOURCE_TEST,
            "datasources_preview_rows" to Permission.DATASOURCE_PREVIEW_ROWS,
            "sql_probe" to Permission.DATASOURCE_SQL_PROBE,
            // 094: there is no datasource WRITE tool — no credential travels through an agent.
            "lake_tables_register" to Permission.LAKE_TABLE_MANAGE,
            "lake_tables_import" to Permission.LAKE_TABLE_MANAGE,
            "lake_tables_unregister" to Permission.LAKE_TABLE_MANAGE,
            "endpoints_list" to Permission.ENDPOINT_READ,
            "endpoints_get" to Permission.ENDPOINT_READ,
            "endpoints_create" to Permission.ENDPOINT_PUBLISH,
            "endpoints_delete" to Permission.ENDPOINT_UNPUBLISH,
            // 118 — D-S8; the cross-workspace retire rule is the service's (`datasource.manage`).
            "semantics_list" to Permission.SEMANTIC_READ,
            "semantics_record" to Permission.SEMANTIC_RECORD,
            "semantics_retire" to Permission.SEMANTIC_RETIRE,
            // 072 / 120 — properties of the BUILD, the same answer for every caller.
            "calculators_list" to Permission.CALCULATOR_READ,
            "calculators_get" to Permission.CALCULATOR_READ,
            "docs_list" to Permission.DOCS_READ,
            "docs_get" to Permission.DOCS_READ,
        )

    /** The permission an MCP tool declares, or `null` if the tool name is unknown. */
    fun requiredPermissionForTool(tool: String): Permission? = MCP_TOOL_PERMISSION[tool]

    /** A tool's minimum KEY scope — its permission's, through the shim — or `null` for an unknown tool. */
    fun requiredScopeForTool(tool: String): Scope? = requiredPermissionForTool(tool)?.let(PERMISSION_MIN_SCOPE::get)

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
     * **The** authorization function (RBAC design §2). `ScopeInterceptor` and
     * `McpToolDispatcher` call this and nothing else, so the two surfaces cannot drift and a
     * new surface cannot be enforced on one axis by accident.
     *
     * - **Session principal** — the ROLE axis only: does the membership's role (or the super
     *   admin's instance authority, D7) hold [permission] in [context]? Sessions carry no
     *   scopes since round 1 (D-R1).
     * - **Key principal** — BOTH axes (D-R12). The key's own scope must reach the permission's
     *   shim floor ([PERMISSION_MIN_SCOPE]), AND the ISSUER must still hold the permission in
     *   the pinned workspace, re-read per request through `AuthCache`'s TTL.
     * - **Promotion principal** — not judged here at all. A server key's authority is a route
     *   family, enforced upstream by `PromotionServerKeyFilter` (§7.7) — the only way to the
     *   [RolePermissions.FENCED] permissions its handlers declare.
     *
     * A null [context] means the principal resolved no workspace. Every workspace-scoped
     * permission is refused with `workspace.not_found` (D-R5). Two session-only exceptions: the
     * two "which workspaces do I belong to" permissions (`workspace.read`, the page;
     * `workspace.switch`, the REST list-own), and a super admin's [RolePermissions.INSTANCE]
     * permissions — #113's empty-instance recovery path.
     */
    fun allowed(
        principal: AuthenticatedPrincipal,
        permission: Permission,
        context: WorkspaceContext?,
    ): Decision = allowed(principal, permission.wire, permission, context)

    /** As [allowed], for an MCP tool: its declared permission, keyed by wire tool name. */
    fun allowedTool(
        principal: AuthenticatedPrincipal,
        tool: String,
        context: WorkspaceContext?,
    ): Decision {
        // Fail closed: an implemented tool with no declared permission must not run. The drift
        // and reachability tests make this unreachable in a built artifact; the branch exists so
        // a tool added at runtime cannot become the one nothing judges.
        val permission =
            requiredPermissionForTool(tool)
                ?: return Decision.Refused(
                    code = AuthErrorCodes.SCOPE_INSUFFICIENT,
                    message = "Tool '$tool' declares no permission and cannot be called.",
                    userMessage = "You do not have permission to perform this action.",
                    details = mapOf("tool" to tool),
                )
        return allowed(principal, tool, permission, context)
    }

    @Suppress("ReturnCount") // one guarded refusal per rule; a merged expression would hide which rule fired
    private fun allowed(
        principal: AuthenticatedPrincipal,
        operationName: String,
        permission: Permission,
        context: WorkspaceContext?,
    ): Decision {
        // versioning §10.6 — a PROMOTION principal pins no workspace at all: the payload names
        // its own target and the receiver resolves it there. Its authority is a route family,
        // already enforced by `PromotionServerKeyFilter` upstream, so judging it here would
        // refuse it everywhere on the null-context branch below. Found by
        // `PromotionTwoDeploymentE2eTest`, which turned every promotion into a 404: this
        // function's own KDoc described the branch and the code did not have it.
        if (principal.authMethod == AuthMethod.PROMOTION) return Decision.Allowed

        // The credential axis, keys only (D-R12), through the A4 shim. A permission the shim
        // does not know is refused, never defaulted: a floor nobody wrote down is no floor.
        val minScope =
            PERMISSION_MIN_SCOPE[permission]
                ?: return Decision.Refused(
                    code = AuthErrorCodes.SCOPE_INSUFFICIENT,
                    message = "Permission '${permission.wire}' has no scope floor and cannot be granted.",
                    userMessage = "You do not have permission to perform this action.",
                    details = mapOf("operation" to operationName),
                )
        if (principal.authMethod == AuthMethod.API_KEY && !Scope.satisfies(principal.scopes, minScope)) {
            return Decision.Refused(
                code = AuthErrorCodes.SCOPE_INSUFFICIENT,
                message = "Principal lacks required scope for this operation",
                userMessage = "You do not have permission to perform this action.",
                details =
                    mapOf(
                        "operation" to operationName,
                        "required" to minScope.wire,
                        "held" to principal.scopes.map { it.wire }.sorted(),
                    ),
            )
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

        // #113 — the empty-instance recovery carve-out. A super admin with NO reachable
        // workspace (every workspace deactivated — D-R10 has no last-active guard, and that is
        // deliberate: decommissioning the final workspace is a legitimate operator act) keeps
        // the INSTANCE permissions — create / deactivate / reactivate / delete a workspace, user
        // administration, instance datasource grants, server keys; none reads or writes a
        // workspace's content — and refusing them stranded the one principal who can repair an
        // empty deployment behind the 404 below (witnessed end-to-end by
        // SuperAdminRecoveryE2eTest). The evidence is the user row's `is_admin`, not a context
        // this request does not have. A key holding `admin` scope is impossible since O-2, so
        // the credential-axis check has already refused every one of these to a key.
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

        // D-R12's distinct refusal: the KEY is fine, its issuer's role changed. A caller must
        // be told to get a new key from an author rather than to retry with this one — which
        // `auth.role_required` alone would not say.
        val code =
            if (principal.authMethod == AuthMethod.API_KEY) {
                AuthErrorCodes.KEY_ISSUER_ROLE_LOST
            } else {
                AuthErrorCodes.ROLE_REQUIRED
            }
        // #215 A.6: `required` is the catalog permission; `held` is the ROLE it was judged
        // against (for a key, its issuer's) — informative only, no code compares it.
        return Decision.Refused(
            code = code,
            message = "Principal lacks the required role in workspace '${context.name}'",
            userMessage = "You do not have the role needed for this action in this workspace.",
            details =
                mapOf(
                    "operation" to operationName,
                    "required" to permission.wire,
                    "held" to context.heldRole,
                    "workspace" to context.name,
                ),
        )
    }

    /**
     * The privilege-escalation guard (auth.md §7.4): a new key's scopes must be a
     * subset of the creator's *effective* scopes. A `read` session cannot mint an
     * `author` key. Returns true when [requested] is allowed for [creatorScopes].
     */
    fun keyScopesWithinCreator(
        requested: Collection<Scope>,
        creatorScopes: Collection<Scope>,
    ): Boolean {
        val effective = Scope.effective(creatorScopes)
        return requested.all { it in effective }
    }
}
