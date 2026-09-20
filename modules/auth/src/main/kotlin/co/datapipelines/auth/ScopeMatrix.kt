package co.datapipelines.auth

/**
 * The authoritative Operation matrix (auth.md §7.6) as an enforceable structure, on
 * **two axes**: the minimum [Scope] a CREDENTIAL must carry and the [Permission] — the
 * set of workspace roles — the caller must hold in the ACTIVE WORKSPACE (roles design
 * 2026-09-20 §2, ratified). This is the ONLY place operation-level minimums live in code;
 * REST controllers, the MCP dispatcher and the UI reference it rather than asserting
 * anything locally.
 *
 * The two axes are not the same ordering and must not be collapsed. [Scope] is a chain
 * (§7.5) that travels with a credential; [Permission] is a role SET per row and the roles
 * are not a chain at all — the promoter promotes but executes nothing, the author releases
 * but does not promote. The clearest place the axes disagree is execution: `execute` is
 * the second SCOPE but a viewer-level PERMISSION (D3), so a `read`-scoped key may not
 * execute while a viewer session may.
 *
 * [allowed] is the one function that answers both, and `ScopeInterceptor` /
 * `McpToolDispatcher` call it and nothing else.
 *
 * `ScopeMatrixSpecDriftTest` asserts both tables against the doc's own rows — the five role
 * columns AND the key-scope table — with a row-count guard, so adding a row to auth.md §7.6
 * without wiring it here fails the build (and vice versa). `RequiredScopeCoverageTest`
 * additionally asserts every handler declares an operation, and `MatrixRowReachabilityTest`
 * that every operation and tool is claimed by a handler — a row nothing uses is a lie in
 * the doc, never a default.
 */
object ScopeMatrix {
    /**
     * A REST endpoint family (§7.6 REST table) and its two minimums. `@RequiredScope`
     * is keyed on this enum rather than on a bare [Scope], so a handler declares
     * *which documented operation* it is and both minimums are read from the matrix —
     * one source of truth, not a scope copied by hand at each controller.
     *
     * Every constant's name appears verbatim in its §7.6 row (in code font), which is how
     * the drift test, the reachability test and the role walk find the row without a
     * hand-written label map.
     */
    enum class RestOperation(
        val minScope: Scope,
        val permission: Permission,
    ) {
        /**
         * Reads of pipelines, templates, datasource metadata, endpoints, the UI pages that show
         * them. Every role (§2 row 1); the promoter's lens is R2's.
         */
        READ_RESOURCES(Scope.READ, Permission.VIEW),

        /**
         * Execution metadata reads — the list, one execution, the UI executions screens and
         * their partials (D11, §2 row 2). Its own operation since 2026-09-20 rather than
         * [READ_RESOURCES], because its role set differs: the promoter reads NO executions
         * (ratified), and everyone below workspace admin reads only their OWN — a read-path
         * filter on `executed_by` the repository applies, not something a matrix row can say.
         */
        READ_EXECUTIONS(Scope.READ, Permission.EXECUTE),

        /** The result cursor (§2 row 2): the same role set and the same own-or-admin filter as [READ_EXECUTIONS]. */
        RETRIEVE_RESULT(Scope.READ, Permission.EXECUTE),
        EXECUTE_PIPELINE(Scope.EXECUTE, Permission.EXECUTE),
        CANCEL_EXECUTION(Scope.EXECUTE, Permission.EXECUTE),
        MUTATE_PIPELINES_TEMPLATES(Scope.AUTHOR, Permission.AUTHOR),

        /**
         * "Test a datasource connection" (§7.6). [Permission.EXECUTE] since 2026-09-20 — the
         * owner's ruling is that the connection test FOLLOWS EXECUTE: every role that may run a
         * pipeline against the datasource may ask whether it answers, and the promoter (who runs
         * nothing) may not. Before that it sat on the workspace-admin row. The scope stays
         * `author` on the credential axis: a live connection is opened with the instance's
         * stored credential and the datasource's health is written down (V9).
         */
        TEST_DATASOURCE(Scope.AUTHOR, Permission.EXECUTE),

        /**
         * "Introspect a datasource schema" (§7.6, datasources §7A): the read-only schema
         * endpoints. [Permission.VIEW] since 2026-09-20 (ratified: "introspection is reading") —
         * every role, the promoter included, who needs the shape of a datasource to judge a
         * promotion. The credential axis keeps `author`: a `read` key must not reach a live
         * connection.
         */
        INTROSPECT_DATASOURCE(Scope.AUTHOR, Permission.VIEW),

        /**
         * "Create / update / delete datasources" (§7.6, workspaces design D8): the ONE route
         * family both the workspace-bound and the INSTANCE (`global: true`) datasource writes
         * travel. `author` is the scope floor and workspace admin the role; the D8 gates — the
         * `member-datasources-enabled` config gate, the membership/binding checks, and the
         * super-admin requirement for an instance datasource — are `DatasourceWorkspaceRules`'
         * in-service decisions, not expressible in a scope or a role row.
         *
         * There used to be a second constant, `MUTATE_DATASOURCES` (`admin` / super admin), for
         * the instance half. No handler ever declared it — the same `POST /api/v1/datasources`
         * serves both halves and a handler declares one operation — so 177's reachability gate
         * (`RoleWalkE2eTest`) found it as a doc row nothing implemented and it was removed. The
         * rule it described is unchanged and enforced where it always was.
         */
        MUTATE_WORKSPACE_DATASOURCES(Scope.AUTHOR, Permission.WS_ADMIN),

        /**
         * "Manage own API keys — any authenticated" (§7.6). [Scope.READ] is the floor
         * of the §7.5 hierarchy: every scope implies it, so requiring `read` is exactly
         * "any authenticated principal" and nothing weaker exists to express. The real
         * guard on this operation is the key-scopes ⊆ creator-scopes subset check in
         * [ApiKeyService.issue] (§7.4), not a scope minimum. R3 (#179) reshapes this row.
         */
        MANAGE_OWN_API_KEYS(Scope.READ, Permission.VIEW),

        /**
         * "Get current principal — any authenticated" (§7.6 v2.5, `GET /api/v1/auth/me`,
         * rest-api §16.2). Same [Scope.READ] floor and same reasoning as
         * [MANAGE_OWN_API_KEYS]: `read` is the weakest scope the §7.5 hierarchy can
         * express, so requiring it IS "any authenticated principal".
         */
        CURRENT_PRINCIPAL(Scope.READ, Permission.VIEW),

        /**
         * "Set own theme preference" (§7.6, `PATCH /partials/profile/theme`): a mutation
         * of the caller's OWN user row and nothing else — the sibling of
         * [MANAGE_OWN_API_KEYS]. [Scope.READ] is the §7.5 floor, so this IS "any
         * authenticated principal": the operation writes only the principal's own row
         * (the handler resolves the caller's userId; there is no payload-chosen target),
         * so no scope above `read` is meaningful and none weaker exists to express.
         */
        PROFILE_PREFERENCE(Scope.READ, Permission.VIEW),
        USER_ADMINISTRATION(Scope.ADMIN, Permission.SUPER_ADMIN),

        /**
         * "The workspaces page and the workspace reads" (§7.6, D13 — 2026-09-20): the
         * `/workspaces` screen, `GET /api/v1/workspaces`, one workspace, its members. Narrowed
         * from every member to [Permission.WS_ADMIN]: the page administers members and roles,
         * and a viewer has nothing to do on it. The one thing every member keeps is the
         * SWITCHER, which is [WORKSPACE_SWITCH] — a different row on purpose.
         *
         * One exception survives inside [allowed]: a SESSION with no reachable workspace may
         * still call this — "which workspaces do I belong to" is how a zero-membership person
         * reaches the no-workspace page (ui-screens §4.13), and there is no role to judge when
         * there is no membership.
         */
        WORKSPACES_READ(Scope.READ, Permission.WS_ADMIN),

        /**
         * "Switch the active workspace" (D13, D14 — rule 10): `POST /workspace/switch`, the
         * shell's switcher. Every member, no role gate — switching needs a MEMBERSHIP in the
         * target, which `WorkspaceService.resolveSwitch` checks, and re-issues the session
         * token. Its own operation because [WORKSPACES_READ] narrowed to admins and the
         * switcher must not follow it.
         */
        WORKSPACE_SWITCH(Scope.READ, Permission.VIEW),

        /**
         * "Create a workspace" (§7.6, design §7/§9): a super admin's instance verb (D7).
         * [Scope.ADMIN] is unobtainable by a key since round 1 removed the `admin` key scope
         * (§7.4), so the scope axis alone already makes this session-only.
         */
        WORKSPACE_CREATE(Scope.ADMIN, Permission.SUPER_ADMIN),

        /**
         * "Update a workspace" (§7.6, design §5.4/§9): `author`, the same privilege class as
         * [MUTATE_WORKSPACE_DATASOURCES] — renaming a workspace IS workspace mutation. The real
         * gates stay in-handler: ownership and, for a key principal, the pinned-workspace
         * check (§5.6, design D3) — a role and a credential pin are not expressible as scopes.
         */
        MANAGE_WORKSPACE(Scope.AUTHOR, Permission.WS_ADMIN),

        /**
         * "Change own password — any authenticated" (§7.6, §5A.4): the honest floor
         * is [Scope.READ], the "any authenticated principal" convention of
         * [MANAGE_OWN_API_KEYS] — every session may rotate its OWN credential. The
         * real guards live in the handler and `LocalPasswordService`: the current
         * password is verified (a hijacked session cannot rotate), and the account
         * is the principal's own by construction.
         */
        CHANGE_OWN_PASSWORD(Scope.READ, Permission.VIEW),

        /**
         * "Serve a published endpoint" (§7.6/§7.7, 074): `GET` on the published subtree.
         *
         * `read` is the FLOOR, not the gate. The real authorisation is the path binding
         * (`EndpointAuthorizer`), and on an unbound endpoint a `user` key additionally needs
         * `execute` — enforced there, not here, because "is this key bound to this node" is not
         * expressible as a scope. An `endpoint`-kind key never reaches this floor at all:
         * `ScopeInterceptor` exempts it, since it carries no scopes by design (§7.7).
         */
        SERVE_PUBLISHED_ENDPOINT(Scope.READ, Permission.VIEW),

        /**
         * "Manage published endpoints" (§7.6, 074): publishing, unpublishing and binding.
         * `author` — publishing is an authoring act over a released pipeline (D9). Bindings
         * additionally require owner-or-admin, which is not a scope and lives in the handler.
         */
        MANAGE_ENDPOINTS(Scope.AUTHOR, Permission.AUTHOR),

        /**
         * "Register / import / unregister lake tables of a datasource" (§7.6, 089 §A): the
         * dp-lake catalog writes. `author` — the datasource-mutation floor, the same privilege
         * class as [MUTATE_WORKSPACE_DATASOURCES], but a DISTINCT constant because the §7.6
         * drift guard claims each constant by exactly one documented row. Mutating a GLOBAL
         * datasource's registry additionally requires admin — a workspaces D8 rule enforced in
         * the shared registry service, not a scope.
         */
        MUTATE_LAKE_TABLES(Scope.AUTHOR, Permission.AUTHOR),

        /**
         * "Release a version" (§7.6; versioning §3.1). [Permission.AUTHOR] since 2026-09-20
         * (D8: "release stays with authors and admins; the promoter promotes"). It was the
         * promoter's under RBAC round 1; the ruling that the promoter is an OPS role who authors
         * nothing moved the lever to the people who wrote the draft. Still its own constant:
         * the release check run, the audit event and the §7.6 row are release's, not
         * [MUTATE_PIPELINES_TEMPLATES]'s.
         */
        RELEASE_VERSION(Scope.AUTHOR, Permission.AUTHOR),

        /**
         * "Switch the served version" (O-1) — the rollback lever. [Permission.AUTHOR] since
         * 2026-09-20: with the promoter no longer releasing (D5), the `author || promoter`
         * predicate it used to carry collapsed to the author row.
         */
        SWITCH_SERVED_VERSION(Scope.AUTHOR, Permission.AUTHOR),

        /**
         * "Read the promotion page" (owner rule 13, 2026-09-20): `GET /promotion`. Author,
         * promoter and admins — the author who released sees what is promotable; the promote
         * ACTION on the page is [PROMOTE_VERSION], hidden from the author by role (114).
         */
        PROMOTION_READ(Scope.AUTHOR, Permission.PROMOTION_READ),

        /**
         * "Promote to the higher environment" (§7.6; versioning §10). [Permission.PROMOTE]:
         * the promoter's one verb and the admin's (D5, D8). The RECEIVING side is not this
         * operation: a promotion arrives on the server-key route family, gated by
         * `PromotionServerKeyFilter`.
         */
        PROMOTE_VERSION(Scope.AUTHOR, Permission.PROMOTE),

        /**
         * "Create / deactivate / reactivate a workspace" (design §5/§6, D-R11): instance verbs.
         * [Scope.ADMIN] is unobtainable by a key since round 1 removed the `admin` key scope
         * (§7.4), so the scope axis alone already makes this session-only; the permission axis
         * says WHICH session.
         */
        MANAGE_INSTANCE_WORKSPACES(Scope.ADMIN, Permission.SUPER_ADMIN),

        /**
         * "Add / remove members, set their role, invite" (D6, D20). A workspace admin's verb,
         * and the last-admin rule (`workspace.last_admin`) is a service-level refusal on top of
         * it — "would this leave zero admins" is a cross-row question no matrix row can answer.
         */
        MANAGE_WORKSPACE_MEMBERS(Scope.AUTHOR, Permission.WS_ADMIN),

        /**
         * "Grant / revoke a datasource to a workspace" (D-R7): the super admin's verb, the one
         * that decides who can SEE a datasource at all. A workspace admin registering a
         * datasource bound to their OWN workspace is [MUTATE_WORKSPACE_DATASOURCES]; handing it
         * to somebody else's workspace is this.
         */
        MANAGE_DATASOURCE_GRANTS(Scope.ADMIN, Permission.SUPER_ADMIN),
    }

    /**
     * All 41 MCP tools → minimum scope (auth.md §7.6 MCP table, mcp-server §6.2).
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
            // 120 — the skill docs as tools: the same property-of-the-build content as the
            // calculator catalog, so the same floor (the 072 reasoning, two rows up).
            "docs_list" to Scope.READ,
            "docs_get" to Scope.READ,
            // 140 — run the release checks: the REST twin's floor (EXECUTE_PIPELINE is
            // `execute`, the D-R3 verb — a viewer runs what they can read, and a check run
            // returns no row data beyond the one observed cell per check).
            "pipelines_run_checks" to Scope.EXECUTE,
        )

    /**
     * The role axis of the MCP table (roles design §2): the [Permission] the KEY'S ISSUER
     * must currently hold for each tool.
     *
     * It is a separate map rather than a field on a tool object because the tool surface is
     * a `Map<String, …>` keyed by wire name and the two axes are drift-tested independently
     * against their own doc columns — a tool missing from either map is a build failure
     * (`McpToolSurfaceSpecDriftTest`, `ScopeMatrixSpecDriftTest`), never a default.
     *
     * Where the two axes visibly disagree: `pipelines_execute` is `execute` SCOPE and
     * [Permission.EXECUTE] — which is viewer-level (D3). And the whole `datasources_*`
     * probe family is `author` SCOPE but only [Permission.VIEW]: those tools return row data,
     * which §2 grants every role ("introspection is reading", ratified 2026-09-20), while the
     * credential axis keeps a `read` key out of them (037 F). Neither axis is redundant; each
     * refuses something the other admits.
     */
    val MCP_TOOL_MIN_PERMISSION: Map<String, Permission> =
        mapOf(
            "pipelines_list" to Permission.VIEW,
            "pipelines_get" to Permission.VIEW,
            "templates_list" to Permission.VIEW,
            "templates_get" to Permission.VIEW,
            "templates_used_by" to Permission.VIEW,
            "datasources_list" to Permission.VIEW,
            "datasources_get" to Permission.VIEW,
            "calculators_list" to Permission.VIEW,
            "calculators_get" to Permission.VIEW,
            "datasources_get_table_stats" to Permission.VIEW,
            "endpoints_list" to Permission.VIEW,
            "endpoints_get" to Permission.VIEW,
            // D11 (2026-09-20): execution reads are the EXECUTE row — the promoter reads none,
            // and everyone below workspace admin reads their own (the tools filter on
            // `executed_by`; the matrix admits the role, the query admits the rows).
            "executions_list" to Permission.EXECUTE,
            "executions_get" to Permission.EXECUTE,
            "executions_get_result" to Permission.EXECUTE,
            // D3: viewers execute, run results and cancel their own runs; the promoter does not.
            "pipelines_execute" to Permission.EXECUTE,
            "executions_cancel" to Permission.EXECUTE,
            "pipelines_execute_node" to Permission.EXECUTE,
            // 140 — the release-check run is the same D3 verb as the execute it mirrors:
            // commissioned by a viewer, no row data beyond the observed cell.
            "pipelines_run_checks" to Permission.EXECUTE,
            // §2 row 8 (ratified): the read-only, capped row-data probes are reads — every role.
            "datasources_preview_rows" to Permission.VIEW,
            "sql_probe" to Permission.VIEW,
            "datasources_get_schemas" to Permission.VIEW,
            "datasources_get_tables" to Permission.VIEW,
            "datasources_get_columns" to Permission.VIEW,
            // §2 row 9 (ratified): the connection test FOLLOWS EXECUTE — every role that may
            // run a pipeline against the datasource may ask whether it answers; the promoter
            // runs nothing and may not. Was the workspace-admin row before 2026-09-20.
            "datasources_test" to Permission.EXECUTE,
            // D4: the authoring verbs.
            "pipelines_create" to Permission.AUTHOR,
            "pipelines_update" to Permission.AUTHOR,
            "templates_create" to Permission.AUTHOR,
            "templates_update" to Permission.AUTHOR,
            "templates_render" to Permission.AUTHOR,
            "templates_purge_draft" to Permission.AUTHOR,
            "endpoints_create" to Permission.AUTHOR,
            "endpoints_delete" to Permission.AUTHOR,
            "lake_tables_register" to Permission.AUTHOR,
            "lake_tables_import" to Permission.AUTHOR,
            "lake_tables_unregister" to Permission.AUTHOR,
            // 118 — D-S8: recording is an authoring act; viewers record nothing. Listing is a
            // read any member may make. The cross-workspace retire rule (ws_admin for a
            // DATASOURCE fact another workspace established) is the service's, not a matrix row.
            "semantics_record" to Permission.AUTHOR,
            "semantics_list" to Permission.VIEW,
            "semantics_retire" to Permission.AUTHOR,
            // 120 — the skill docs as tools: the manual the deployment ships, a read any
            // member may make (the calculators_list reasoning).
            "docs_list" to Permission.VIEW,
            "docs_get" to Permission.VIEW,
        )

    /** Minimum scope for an MCP tool, or `null` if the tool name is unknown. */
    fun requiredScopeForTool(tool: String): Scope? = MCP_TOOL_MIN_SCOPE[tool]

    /** The permission an MCP tool needs, or `null` if the tool name is unknown. */
    fun requiredPermissionForTool(tool: String): Permission? = MCP_TOOL_MIN_PERMISSION[tool]

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
     * - **Session principal** — the ROLE axis only. Sessions carry no scopes since round
     *   1 (D-R1: capability is the membership, and `JwtService.scopesFor` derived a global
     *   `author` for everybody, which is exactly the thing being removed). The role comes from
     *   [context], which `WorkspaceResolutionFilter` resolved for THIS request.
     * - **Key principal** — BOTH axes (D-R12). The key's own scope must reach [minScope], AND
     *   the ISSUER must still hold the permission in the pinned workspace. The issuer's role
     *   is re-read per request through `AuthCache`'s TTL, so a demoted issuer's key dies
     *   within one window (60 s by default) rather than at expiry.
     * - **Promotion principal** — not judged here at all. A server key's authority is a route
     *   family, enforced upstream by `PromotionServerKeyFilter` (§7.7); it holds no scopes and
     *   no membership, so asking this function would refuse it everywhere.
     *
     * A null [context] means the principal resolved no workspace — zero memberships, or a
     * deactivated one. Every workspace-scoped operation is refused, with `workspace.not_found`:
     * D-R5's rule is that a workspace you cannot reach is a workspace that does not exist. Two
     * session-only exceptions stand beside it: `WORKSPACES_READ` ("which workspaces do I belong
     * to" is meaningful when the answer is none — how the no-workspace page is reached), and the
     * super admin's INSTANCE verbs (the `SUPER_ADMIN`-permission operations — #113's
     * empty-instance recovery path; workspace-scoped operations stay refused even for them).
     */
    fun allowed(
        principal: AuthenticatedPrincipal,
        operation: RestOperation,
        context: WorkspaceContext?,
    ): Decision = allowed(principal, operation.name, operation.minScope, operation.permission, context)

    /** As [allowed], for an MCP tool: the same two axes, keyed by wire tool name. */
    fun allowedTool(
        principal: AuthenticatedPrincipal,
        tool: String,
        context: WorkspaceContext?,
    ): Decision {
        val scope = requiredScopeForTool(tool)
        val permission = requiredPermissionForTool(tool)
        // Fail closed: an implemented tool missing from EITHER axis must not run. Both
        // drift tests make this unreachable in a built artifact; the branch exists so a
        // tool added at runtime cannot become the one that is enforced on one axis only.
        if (scope == null || permission == null) {
            return Decision.Refused(
                code = AuthErrorCodes.SCOPE_INSUFFICIENT,
                message = "Tool '$tool' has no documented requirement on both axes and cannot be called.",
                userMessage = "You do not have permission to perform this action.",
                details = mapOf("tool" to tool),
            )
        }
        return allowed(principal, tool, scope, permission, context)
    }

    @Suppress("ReturnCount") // one guarded refusal per rule; a merged expression would hide which rule fired
    private fun allowed(
        principal: AuthenticatedPrincipal,
        operationName: String,
        minScope: Scope,
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
        // Both null-context exceptions below are SESSIONS-ONLY: a key always pins a context, so
        // a key never arrives here, and on a key the 404 the rule promises stays.
        val sessionWithoutContext = context == null && principal.authMethod != AuthMethod.API_KEY

        // ONE operation is meaningful with no workspace at all: listing the workspaces you
        // belong to — which is also how a zero-membership person reaches the no-workspace page
        // (ui-screens §4.13, 114 §C.3a) instead of a JSON 404 on the only screen that could
        // explain their state.
        if (sessionWithoutContext && operationName == RestOperation.WORKSPACES_READ.name) {
            return Decision.Allowed
        }

        // #113 — the empty-instance recovery carve-out. A super admin with NO reachable
        // workspace (every workspace deactivated — D-R10 has no last-active guard, and that is
        // deliberate: decommissioning the final workspace is a legitimate operator act) keeps
        // the INSTANCE verbs. The operations whose permission is SUPER_ADMIN are instance-level
        // by construction — create / deactivate / reactivate / delete a workspace, user
        // administration, instance datasources and their grants; none reads or writes a
        // workspace's content — and refusing them stranded the one principal who can repair an
        // empty deployment behind the 404 below (witnessed end-to-end by
        // SuperAdminRecoveryE2eTest: deactivate the last active workspace, then be unable to
        // reactivate or create one). The evidence is the user row's `is_admin`, not
        // a context this request does not have. A key holding `admin` scope is impossible since
        // O-2, so the credential-axis check has already refused every one of these operations
        // to a key. Workspace-scoped operations keep the 404.
        if (sessionWithoutContext && permission == Permission.SUPER_ADMIN && principal.isSuperAdmin) {
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
        return Decision.Refused(
            code = code,
            message = "Principal lacks the required role in workspace '${context.name}'",
            userMessage = "You do not have the role needed for this action in this workspace.",
            details =
                mapOf(
                    "operation" to operationName,
                    "required" to permission.wire,
                    "held" to
                        context
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
