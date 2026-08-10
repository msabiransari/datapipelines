package co.datapipelines.auth

/**
 * The authoritative Scope ↔ Operation matrix (auth.md §7.6) as an enforceable
 * structure. This is the ONLY place operation-level scope minimums live in code;
 * REST controllers, the MCP dispatcher and UI reference it rather than asserting
 * scopes locally. Scopes are hierarchical (§7.5) — the mapped scope is the minimum.
 *
 * `ScopeMatrixSpecDriftTest` asserts both tables against the doc's own rows, with a
 * row-count guard, so adding a row to auth.md §7.6 without wiring it here fails the
 * build (and vice versa).
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
    ) {
        READ_RESOURCES(Scope.READ),
        RETRIEVE_RESULT(Scope.READ),
        EXECUTE_PIPELINE(Scope.EXECUTE),
        CANCEL_EXECUTION(Scope.EXECUTE),
        MUTATE_PIPELINES_TEMPLATES(Scope.AUTHOR),
        TEST_DATASOURCE(Scope.AUTHOR),
        MUTATE_DATASOURCES(Scope.ADMIN),

        /**
         * "Manage own API keys — any authenticated" (§7.6). [Scope.READ] is the floor
         * of the §7.5 hierarchy: every scope implies it, so requiring `read` is exactly
         * "any authenticated principal" and nothing weaker exists to express. The real
         * guard on this operation is the key-scopes ⊆ creator-scopes subset check in
         * [ApiKeyService.issue] (§7.4), not a scope minimum.
         */
        MANAGE_OWN_API_KEYS(Scope.READ),

        /**
         * "Get current principal — any authenticated" (§7.6 v2.5, `GET /api/v1/auth/me`,
         * rest-api §16.2). Same [Scope.READ] floor and same reasoning as
         * [MANAGE_OWN_API_KEYS]: `read` is the weakest scope the §7.5 hierarchy can
         * express, so requiring it IS "any authenticated principal".
         *
         * The controller is web's at P6a; this module owns only the matrix constant, so
         * that the documented row has a wired minimum the moment the endpoint lands.
         */
        CURRENT_PRINCIPAL(Scope.READ),
        USER_ADMINISTRATION(Scope.ADMIN),
    }

    /**
     * All 15 MCP tools → minimum scope (auth.md §7.6 MCP table, mcp-server §6.2).
     * The dispatcher looks a tool's requirement up here via [requiredScopeForTool].
     */
    val MCP_TOOL_MIN_SCOPE: Map<String, Scope> =
        mapOf(
            "pipelines_list" to Scope.READ,
            "pipelines_get" to Scope.READ,
            "templates_list" to Scope.READ,
            "templates_get" to Scope.READ,
            "datasources_list" to Scope.READ,
            "datasources_get" to Scope.READ,
            "executions_list" to Scope.READ,
            "executions_get" to Scope.READ,
            "executions_get_result" to Scope.READ,
            "pipelines_execute" to Scope.EXECUTE,
            "pipelines_create" to Scope.AUTHOR,
            "pipelines_update" to Scope.AUTHOR,
            "templates_create" to Scope.AUTHOR,
            "templates_render" to Scope.AUTHOR,
            "datasources_test" to Scope.AUTHOR,
        )

    /** Minimum scope for an MCP tool, or `null` if the tool name is unknown. */
    fun requiredScopeForTool(tool: String): Scope? = MCP_TOOL_MIN_SCOPE[tool]

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
