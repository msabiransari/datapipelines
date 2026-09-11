package co.datapipelines.auth

/**
 * The authoritative Operation matrix (auth.md §7.6) as an enforceable structure, on
 * **two axes** (RBAC design §2): the minimum [Scope] a CREDENTIAL must carry and the
 * minimum [Capability] the caller must hold in the ACTIVE WORKSPACE. This is the ONLY
 * place operation-level minimums live in code; REST controllers, the MCP dispatcher and
 * the UI reference it rather than asserting anything locally.
 *
 * The two axes are not the same ordering and must not be collapsed. [Scope] is a chain
 * (§7.5) that travels with a credential; [Capability] is a set of predicates over the
 * membership row (D-R1) and is not a chain at all — an author may not `release`, a
 * promoter may not author. The clearest place they disagree is execution: `execute` is
 * the second SCOPE but the viewer-level CAPABILITY (D-R3), so a `read`-scoped key may
 * not execute while a viewer session may.
 *
 * [allowed] is the one function that answers both, and `ScopeInterceptor` /
 * `McpToolDispatcher` call it and nothing else.
 *
 * `ScopeMatrixSpecDriftTest` asserts both tables against the doc's own rows, with a
 * row-count guard, so adding a row to auth.md §7.6 without wiring it here fails the
 * build (and vice versa). `RequiredScopeCoverageTest` additionally asserts every handler's
 * operation has a capability — a missing row is a red build, never a default.
 */
object ScopeMatrix {
    /**
     * A REST endpoint family (§7.6 REST table) and its minimum scope. `@RequiredScope`
     * is keyed on this enum rather than on a bare [Scope], so a handler declares
     * *which documented operation* it is and the minimum is read from the matrix —
     * one source of truth, not a scope copied by hand at each controller.
     */
    enum class RestOperation(
        val minScope: Scope,
        val capability: Capability,
    ) {
        READ_RESOURCES(Scope.READ, Capability.VIEW),
        RETRIEVE_RESULT(Scope.READ, Capability.VIEW),
        EXECUTE_PIPELINE(Scope.EXECUTE, Capability.EXECUTE),
        CANCEL_EXECUTION(Scope.EXECUTE, Capability.EXECUTE),
        MUTATE_PIPELINES_TEMPLATES(Scope.AUTHOR, Capability.AUTHOR),

        /**
         * "Test a datasource connection" (§7.6). [Capability.WS_ADMIN], not `author`: the
         * design's §1 table puts "register a datasource bound to THIS workspace; test it" on
         * the workspace-admin row, and testing is not a read — it opens a live connection with
         * the instance's stored credential and writes the datasource's health down (V9). The
         * MCP twin `datasources_test` sits on the same capability for the same reason, while
         * the read-only probes beside it are viewer verbs.
         */
        TEST_DATASOURCE(Scope.AUTHOR, Capability.WS_ADMIN),

        /**
         * "Introspect a datasource schema" (§7.6, datasources §7A): the three read-only schema
         * endpoints. `author` like [TEST_DATASOURCE] — each opens a live connection against the
         * datasource, and the stated consumer is pipeline authoring.
         */
        INTROSPECT_DATASOURCE(Scope.AUTHOR, Capability.AUTHOR),

        /**
         * "Create / update / delete workspace-bound datasources" (§7.6, workspaces design
         * D8): `author` is the scope floor; the D8 gates — the `member-datasources-enabled`
         * config gate and the membership/binding checks — are enforced by the handler, not
         * expressible in a scope. Global datasource CUD stays [MUTATE_DATASOURCES] (`admin`).
         */
        MUTATE_WORKSPACE_DATASOURCES(Scope.AUTHOR, Capability.WS_ADMIN),
        MUTATE_DATASOURCES(Scope.ADMIN, Capability.SUPER_ADMIN),

        /**
         * "Manage own API keys — any authenticated" (§7.6). [Scope.READ] is the floor
         * of the §7.5 hierarchy: every scope implies it, so requiring `read` is exactly
         * "any authenticated principal" and nothing weaker exists to express. The real
         * guard on this operation is the key-scopes ⊆ creator-scopes subset check in
         * [ApiKeyService.issue] (§7.4), not a scope minimum.
         */
        MANAGE_OWN_API_KEYS(Scope.READ, Capability.VIEW),

        /**
         * "Get current principal — any authenticated" (§7.6 v2.5, `GET /api/v1/auth/me`,
         * rest-api §16.2). Same [Scope.READ] floor and same reasoning as
         * [MANAGE_OWN_API_KEYS]: `read` is the weakest scope the §7.5 hierarchy can
         * express, so requiring it IS "any authenticated principal".
         *
         * The controller is web's at P6a; this module owns only the matrix constant, so
         * that the documented row has a wired minimum the moment the endpoint lands.
         */
        CURRENT_PRINCIPAL(Scope.READ, Capability.VIEW),

        /**
         * "Set own theme preference" (§7.6, `PATCH /partials/profile/theme`): a mutation
         * of the caller's OWN user row and nothing else — the sibling of
         * [MANAGE_OWN_API_KEYS]. [Scope.READ] is the §7.5 floor, so this IS "any
         * authenticated principal": the operation writes only the principal's own row
         * (the handler resolves the caller's userId; there is no payload-chosen target),
         * so no scope above `read` is meaningful and none weaker exists to express.
         */
        PROFILE_PREFERENCE(Scope.READ, Capability.VIEW),
        USER_ADMINISTRATION(Scope.ADMIN, Capability.SUPER_ADMIN),

        /**
         * "List / read own workspaces & members" (§7.6, workspaces design §5.4/§9):
         * list-own and read are any-authenticated — a member listing/read of one's own
         * workspaces. `read` is the §7.5 floor, the same "any authenticated" convention as
         * [MANAGE_OWN_API_KEYS].
         */
        WORKSPACES_READ(Scope.READ, Capability.VIEW),

        /**
         * "Create a workspace (per provisioning mode)" (§7.6, design §7/§9): `author` is
         * the scope floor, the same privilege class as [MUTATE_WORKSPACE_DATASOURCES] —
         * creation MUTATES the workspace set, and an API key authenticates on every path
         * CSRF-exempt, so a `read` floor let a leaked read key mint workspaces outright
         * (025 defect round). The per-mode refusal (`closed` → non-admin) is
         * [WorkspaceCreationForbiddenException], raised by the handler — a provisioning
         * mode is not expressible in a scope.
         */
        WORKSPACE_CREATE(Scope.ADMIN, Capability.SUPER_ADMIN),

        /**
         * "Update a workspace / manage its members" (§7.6, design §5.4/§9): `author`, the
         * same privilege class as [MUTATE_WORKSPACE_DATASOURCES] — renaming a workspace or
         * editing its membership IS workspace mutation. The real gates stay in-handler:
         * ownership (workspace `owner` role or global admin) and, for a key principal, the
         * pinned-workspace check (§5.6, design D3) — a role and a credential pin are not
         * expressible as scopes.
         */
        MANAGE_WORKSPACE(Scope.AUTHOR, Capability.WS_ADMIN),

        /**
         * "Change own password — any authenticated" (§7.6, §5A.4): the honest floor
         * is [Scope.READ], the "any authenticated principal" convention of
         * [MANAGE_OWN_API_KEYS] — every session may rotate its OWN credential. The
         * real guards live in the handler and `LocalPasswordService`: the current
         * password is verified (a hijacked session cannot rotate), and the account
         * is the principal's own by construction. A mutating endpoint on a governed
         * path with an honest floor — the §5A lane contract, not a shortcut around it.
         */
        CHANGE_OWN_PASSWORD(Scope.READ, Capability.VIEW),

        /**
         * "Serve a published endpoint" (§7.6/§7.7, 074): `GET /api/x/…`.
         *
         * `read` is the FLOOR, not the gate. The real authorisation is the path binding
         * (`EndpointAuthorizer`), and on an unbound endpoint a `user` key additionally needs
         * `execute` — enforced there, not here, because "is this key bound to this node" is not
         * expressible as a scope. An `endpoint`-kind key never reaches this floor at all:
         * `ScopeInterceptor` exempts it, since it carries no scopes by design (§7.7).
         */
        SERVE_PUBLISHED_ENDPOINT(Scope.READ, Capability.VIEW),

        /**
         * "Manage published endpoints" (§7.6, 074): publishing, unpublishing and binding.
         * `author` — publishing is an authoring act over a released pipeline. Bindings
         * additionally require owner-or-admin, which is not a scope and lives in the handler.
         */
        MANAGE_ENDPOINTS(Scope.AUTHOR, Capability.AUTHOR),

        /**
         * "Register / import / unregister lake tables of a datasource" (§7.6, 089 §A): the
         * dp-lake catalog writes. `author` — the datasource-mutation floor, the same privilege
         * class as [MUTATE_WORKSPACE_DATASOURCES], but a DISTINCT constant because the §7.6
         * drift guard claims each constant by exactly one documented row. Mutating a GLOBAL
         * datasource's registry additionally requires admin — a workspaces D8 rule enforced in
         * the shared registry service, not a scope.
         */
        MUTATE_LAKE_TABLES(Scope.AUTHOR, Capability.AUTHOR),

        /**
         * "Release a version" (§7.6; versioning §3.1). Its own operation class since RBAC
         * round 1: release was [MUTATE_PIPELINES_TEMPLATES] while capability was global, and
         * folding it back in would hand every author the release lever the design deliberately
         * withholds (D-R2 — the promoter exists precisely so "can edit" and "can release" are
         * two answers). Scope stays `author`: the credential axis has no promoter (design §1).
         */
        RELEASE_VERSION(Scope.AUTHOR, Capability.PROMOTE),

        /**
         * "Switch the served version" (O-1) — the rollback lever. Author, promoter and
         * workspace admin all hold it and none implies the others, which is why
         * [Capability.SWITCH] exists as its own predicate rather than as one of the two
         * neighbouring rows.
         */
        SWITCH_SERVED_VERSION(Scope.AUTHOR, Capability.SWITCH),

        /**
         * "Promote to the higher environment" (§7.6; versioning §10). [Capability.PROMOTE] with
         * [RELEASE_VERSION] — the DevOps pair. The RECEIVING side is not this operation: a
         * promotion arrives on the server-key route family, gated by `PromotionServerKeyFilter`.
         */
        PROMOTE_VERSION(Scope.AUTHOR, Capability.PROMOTE),

        /**
         * "Create / deactivate / reactivate a workspace" (design §5/§6, D-R11): instance verbs.
         * [Scope.ADMIN] is unobtainable by a key since round 1 removed the `admin` key scope
         * (§7.4), so the scope axis alone already makes this session-only; the capability axis
         * says WHICH session.
         */
        MANAGE_INSTANCE_WORKSPACES(Scope.ADMIN, Capability.SUPER_ADMIN),

        /**
         * "Add / remove members and set their flags" (design §1, §5.4). A workspace admin's
         * verb, and the last-admin rule (`workspace.last_admin`) is a service-level refusal on
         * top of it — "would this leave zero admins" is a cross-row question no matrix row can
         * answer.
         */
        MANAGE_WORKSPACE_MEMBERS(Scope.AUTHOR, Capability.WS_ADMIN),

        /**
         * "Grant / revoke a datasource to a workspace" (D-R7): the super admin's verb, the one
         * that decides who can SEE a datasource at all. A workspace admin registering a
         * datasource bound to their OWN workspace is [MUTATE_WORKSPACE_DATASOURCES]; handing it
         * to somebody else's workspace is this.
         */
        MANAGE_DATASOURCE_GRANTS(Scope.ADMIN, Capability.SUPER_ADMIN),
    }

    /**
     * All 38 MCP tools → minimum scope (auth.md §7.6 MCP table, mcp-server §6.2).
     * The dispatcher looks a tool's requirement up here via [requiredScopeForTool].
     *
     * `datasources_preview_rows` and `pipelines_execute_node` are `author` (037 F), matching
     * every other datasource tool and deliberately NOT `read`: they are the first tools that
     * return arbitrary customer ROW DATA — not metadata, not the results of a pipeline someone
     * deliberately authored — and a read-scoped key should not acquire that reach. `sql_probe`
     * (107) is `author` under the same rule — it returns live rows.
     *
     * `templates_used_by` is `read` (040 D7): it returns which pipelines reference which
     * template version — reference structure any workspace reader may already see by reading
     * the pipelines themselves — never customer row data. `datasources_get_table_stats` (107)
     * is `read` on the same reasoning: the engine's own catalog ESTIMATES about shape, never
     * row data.
     */
    val MCP_TOOL_MIN_SCOPE: Map<String, Scope> =
        mapOf(
            "pipelines_list" to Scope.READ,
            "pipelines_get" to Scope.READ,
            "templates_list" to Scope.READ,
            "templates_get" to Scope.READ,
            "templates_used_by" to Scope.READ,
            "datasources_list" to Scope.READ,
            "datasources_get" to Scope.READ,
            "executions_list" to Scope.READ,
            "executions_get" to Scope.READ,
            "executions_get_result" to Scope.READ,
            "pipelines_execute" to Scope.EXECUTE,
            "pipelines_create" to Scope.AUTHOR,
            "pipelines_update" to Scope.AUTHOR,
            "templates_create" to Scope.AUTHOR,
            "templates_update" to Scope.AUTHOR,
            "templates_render" to Scope.AUTHOR,
            "datasources_test" to Scope.AUTHOR,
            "datasources_get_schemas" to Scope.AUTHOR,
            "datasources_get_tables" to Scope.AUTHOR,
            "datasources_get_columns" to Scope.AUTHOR,
            "datasources_preview_rows" to Scope.AUTHOR,
            // 094 ruling 4: `datasources_create` is GONE from this matrix because it is gone
            // from the surface. Registering a datasource means handing over a live database
            // credential, and no credential travels through an agent — people add datasources in
            // the UI, operators over REST or the bootstrap file, and agents use them by name.
            "pipelines_execute_node" to Scope.AUTHOR,
            // 072 — the calculator catalog is a property of the build: no workspace data, no
            // customer rows, the same answer for every caller. `read` is the honest floor.
            "calculators_list" to Scope.READ,
            "calculators_get" to Scope.READ,
            // 074 — publishing exposes a released pipeline at a URL: an authoring act. The reads
            // sit on the same floor every other listing does.
            "endpoints_create" to Scope.AUTHOR,
            "endpoints_list" to Scope.READ,
            "endpoints_get" to Scope.READ,
            "endpoints_delete" to Scope.AUTHOR,
            // 089 §A — the dp-lake catalog writes sit on the datasource-mutation floor, like
            // `datasources_create`; mutating a GLOBAL datasource's registry additionally
            // requires admin, a D8 rule inside the shared registry service, not a scope.
            "lake_tables_register" to Scope.AUTHOR,
            "lake_tables_import" to Scope.AUTHOR,
            "lake_tables_unregister" to Scope.AUTHOR,
            // 107 — the probes. `datasources_get_table_stats` reads the engine's stored
            // ESTIMATES (metadata, never row data) and sits on `read` with templates_used_by;
            // `sql_probe` returns live rows and sits on `author` with preview_rows (037 F).
            "datasources_get_table_stats" to Scope.READ,
            "sql_probe" to Scope.AUTHOR,
            // 107 — cancel an execution: the REST twin's floor (CANCEL_EXECUTION is EXECUTE);
            // the same-credential rule is a handler gate, not expressible as a scope.
            "executions_cancel" to Scope.EXECUTE,
            // 107 — purge a never-released, unpinned, author-owned draft template: authoring.
            "templates_purge_draft" to Scope.AUTHOR,
            // 118 — the learned semantic layer. Recording and retiring are authoring acts
            // (D-S8: the same bar as writing a pipeline that reads the datasource); the
            // listing returns facts ABOUT the data, never row data, so it sits on `read`.
            "semantics_record" to Scope.AUTHOR,
            "semantics_list" to Scope.READ,
            "semantics_retire" to Scope.AUTHOR,
        )

    /**
     * The capability axis of the MCP table (RBAC design §2): the minimum workspace
     * [Capability] the KEY'S ISSUER must currently hold for each tool.
     *
     * It is a separate map rather than a field on a tool object because the tool surface is
     * a `Map<String, …>` keyed by wire name and the two axes are drift-tested independently
     * against their own doc columns — a tool missing from either map is a build failure
     * (`McpToolSurfaceSpecDriftTest`, `ScopeMatrixSpecDriftTest`), never a default.
     *
     * Where the two axes visibly disagree: `pipelines_execute` is `execute` SCOPE and
     * [Capability.EXECUTE] — which is viewer-level (D-R3). And the whole `datasources_*`
     * probe family is `author` SCOPE but only [Capability.VIEW]: those tools return row data,
     * which the design's §1 table grants a viewer outright ("sql_probe, preview_rows — ✓
     * read-only SELECT, capped"), while the credential axis keeps a `read` key out of them
     * (037 F). Neither axis is redundant; each refuses something the other admits.
     */
    val MCP_TOOL_MIN_CAPABILITY: Map<String, Capability> =
        mapOf(
            "pipelines_list" to Capability.VIEW,
            "pipelines_get" to Capability.VIEW,
            "templates_list" to Capability.VIEW,
            "templates_get" to Capability.VIEW,
            "templates_used_by" to Capability.VIEW,
            "datasources_list" to Capability.VIEW,
            "datasources_get" to Capability.VIEW,
            "executions_list" to Capability.VIEW,
            "executions_get" to Capability.VIEW,
            "executions_get_result" to Capability.VIEW,
            "calculators_list" to Capability.VIEW,
            "calculators_get" to Capability.VIEW,
            "datasources_get_table_stats" to Capability.VIEW,
            "endpoints_list" to Capability.VIEW,
            "endpoints_get" to Capability.VIEW,
            // D-R3: viewers execute, run results and cancel their own runs.
            "pipelines_execute" to Capability.EXECUTE,
            "executions_cancel" to Capability.EXECUTE,
            "pipelines_execute_node" to Capability.EXECUTE,
            // D-R3 / design §1 row 3: the read-only, capped row-data probes are viewer verbs.
            "datasources_preview_rows" to Capability.VIEW,
            "sql_probe" to Capability.VIEW,
            "datasources_get_schemas" to Capability.VIEW,
            "datasources_get_tables" to Capability.VIEW,
            "datasources_get_columns" to Capability.VIEW,
            // A live connection is opened and the datasource's own health is written down:
            // an operational act, not a read (design §1 keeps "test it" on the ws-admin row).
            "datasources_test" to Capability.WS_ADMIN,
            // D-R4: the authoring verbs.
            "pipelines_create" to Capability.AUTHOR,
            "pipelines_update" to Capability.AUTHOR,
            "templates_create" to Capability.AUTHOR,
            "templates_update" to Capability.AUTHOR,
            "templates_render" to Capability.AUTHOR,
            "templates_purge_draft" to Capability.AUTHOR,
            "endpoints_create" to Capability.AUTHOR,
            "endpoints_delete" to Capability.AUTHOR,
            "lake_tables_register" to Capability.AUTHOR,
            "lake_tables_import" to Capability.AUTHOR,
            "lake_tables_unregister" to Capability.AUTHOR,
            // 118 — D-S8: recording is an authoring act; viewers record nothing. Listing is a
            // read any member may make. The cross-workspace retire rule (ws_admin for a
            // DATASOURCE fact another workspace established) is the service's, not a matrix row.
            "semantics_record" to Capability.AUTHOR,
            "semantics_list" to Capability.VIEW,
            "semantics_retire" to Capability.AUTHOR,
        )

    /** Minimum scope for an MCP tool, or `null` if the tool name is unknown. */
    fun requiredScopeForTool(tool: String): Scope? = MCP_TOOL_MIN_SCOPE[tool]

    /** Minimum workspace capability for an MCP tool, or `null` if the tool name is unknown. */
    fun requiredCapabilityForTool(tool: String): Capability? = MCP_TOOL_MIN_CAPABILITY[tool]

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
     * new operation cannot be enforced on one axis by accident.
     *
     * - **Session principal** — the CAPABILITY axis only. Sessions carry no scopes since round
     *   1 (D-R1: capability is the membership, and `JwtService.scopesFor` derived a global
     *   `author` for everybody, which is exactly the thing being removed). The flags come from
     *   [context], which `WorkspaceResolutionFilter` resolved for THIS request.
     * - **Key principal** — BOTH axes (D-R12). The key's own scope must reach [minScope], AND
     *   the ISSUER must still hold the capability in the pinned workspace. The issuer's flags
     *   are re-read per request through `AuthCache`'s TTL, so a demoted issuer's key dies
     *   within one window (60 s by default) rather than at expiry.
     * - **Promotion principal** — not judged here at all. A server key's authority is a route
     *   family, enforced upstream by `PromotionServerKeyFilter` (§7.7); it holds no scopes and
     *   no membership, so asking this function would refuse it everywhere.
     *
     * A null [context] means the principal resolved no workspace — zero memberships, or a
     * deactivated one. Every workspace-scoped operation is refused, with `workspace.not_found`:
     * D-R5's rule is that a workspace you cannot reach is a workspace that does not exist.
     */
    fun allowed(
        principal: AuthenticatedPrincipal,
        operation: RestOperation,
        context: WorkspaceContext?,
    ): Decision = allowed(principal, operation.name, operation.minScope, operation.capability, context)

    /** As [allowed], for an MCP tool: the same two axes, keyed by wire tool name. */
    fun allowedTool(
        principal: AuthenticatedPrincipal,
        tool: String,
        context: WorkspaceContext?,
    ): Decision {
        val scope = requiredScopeForTool(tool)
        val capability = requiredCapabilityForTool(tool)
        // Fail closed: an implemented tool missing from EITHER axis must not run. Both
        // drift tests make this unreachable in a built artifact; the branch exists so a
        // tool added at runtime cannot become the one that is enforced on one axis only.
        if (scope == null || capability == null) {
            return Decision.Refused(
                code = AuthErrorCodes.SCOPE_INSUFFICIENT,
                message = "Tool '$tool' has no documented requirement on both axes and cannot be called.",
                userMessage = "You do not have permission to perform this action.",
                details = mapOf("tool" to tool),
            )
        }
        return allowed(principal, tool, scope, capability, context)
    }

    @Suppress("ReturnCount") // one guarded refusal per rule; a merged expression would hide which rule fired
    private fun allowed(
        principal: AuthenticatedPrincipal,
        operationName: String,
        minScope: Scope,
        capability: Capability,
        context: WorkspaceContext?,
    ): Decision {
        // versioning §10.6 — a PROMOTION principal pins no workspace at all: the payload names
        // its own target and the receiver resolves it there. Its authority is a route family,
        // already enforced by `PromotionServerKeyFilter` upstream, so judging it here would
        // refuse it everywhere on the null-context branch below. Found by
        // `PromotionTwoDeploymentE2eTest`, which turned every promotion into a 404: this
        // function's own KDoc described the branch and the code did not have it.
        if (principal.authMethod == AuthMethod.PROMOTION) return Decision.Allowed

        // The credential axis, keys only (D-R12). A session's scope set is empty by design.
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
        // ONE operation is meaningful with no workspace at all: listing the workspaces you
        // belong to — which is also how a zero-membership person reaches the no-workspace page
        // (ui-screens §4.13, 114 §C.3a) instead of a JSON 404 on the only screen that could
        // explain their state. Sessions only: a key always pins a context, so a key never
        // arrives here, and `WORKSPACES_READ` on a key stays the 404 the rule promises.
        if (context == null && operationName == RestOperation.WORKSPACES_READ.name && principal.authMethod != AuthMethod.API_KEY) {
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

        if (capability.satisfiedBy(context.flags)) return Decision.Allowed

        // D-R12's distinct refusal: the KEY is fine, its issuer's role changed. A caller must
        // be told to get a new key from an author rather than to retry with this one — which
        // `auth.role_required` alone would not say.
        val code =
            if (principal.authMethod == AuthMethod.API_KEY) {
                AuthErrorCodes.KEY_ISSUER_ROLE_LOST
            } else {
                AuthErrorCodes.ROLE_REQUIRED
            }
        return Decision.Refused(
            code = code,
            message = "Principal lacks the required role in workspace '${context.name}'",
            userMessage = "You do not have the role needed for this action in this workspace.",
            details =
                mapOf(
                    "operation" to operationName,
                    "required" to capability.wire,
                    "held" to
                        context.flags
                            .held()
                            .map { it.wire }
                            .sorted(),
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
