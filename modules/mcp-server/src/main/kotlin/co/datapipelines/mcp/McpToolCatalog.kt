package co.datapipelines.mcp

import co.datapipelines.auth.Permission

/**
 * The single authority for the shipped MCP tool surface, in `tools/list` order
 * (mcp-server.md §6.1) — 033/C1, extended 052 with the mutating declaration.
 *
 * Before this catalog existed, four places each kept their own copy of the list: the
 * production `@Bean` in [McpServerAutoConfiguration], the `shippedTools()` fixture in
 * `McpToolSurfaceSpecDriftTest`, the fixture in `McpServerWiringTest`, and — worst, because
 * nothing guarded it — the marketing page's hardcoded "18". A 19th tool added to the bean
 * but not a fixture left every guard green while the server shipped more than the spec.
 *
 * The bindings, each asserted by a test that can go red:
 *  - the production bean's output must equal this list exactly (`McpToolCatalogBindingTest`
 *    constructs the REAL `@Bean` method's output, mocks and all — not a fixture);
 *  - `McpToolSurfaceSpecDriftTest` takes its count from [NAMES] and builds its tool set
 *    from the same real bean call;
 *  - the marketing page renders [NAMES].size at request time (`SiteController`), and
 *    `WebsiteFactsGuardTest` asserts four-way agreement with mcp-server.md §6.1;
 *  - every entry carries its [Entry.mutating] declaration, and the known writers are
 *    flagged (`McpToolCatalogBindingTest`, 052) — a mutating tool declared read is the
 *    hole the dispatcher's `mcp.tool.write` audit would silently skip;
 *  - every entry declares the catalog [Entry.permission] a caller must hold (#215 slice (b),
 *    moved here from the auth matrix's tool table): the dispatcher judges the call against it
 *    and nothing else, and `ScopeMatrixSpecDriftTest` / `MatrixRowReachabilityTest` hold the
 *    declarations to auth.md §7.6's tool rows.
 *
 * This is a constant, not a bean, precisely because the tool bean is
 * `@ConditionalOnBean(PipelineExecutor::class)` (033/C4): a deployment without the engine
 * still renders the homepage, and "0 tools" there would be a lie.
 */
object McpToolCatalog {
    /**
     * One catalogued tool: its wire name, whether calling it can change stored definitions or
     * customer data, and the catalog [permission] a caller must hold (#215). Both are DECLARED
     * properties of the entry — never derived from the name — because which calls write and what
     * a call needs are semantic facts about the tool, and name patterns drift (052, ruling R4).
     *
     * A read tool declared mutating is a harmless over-audit; a mutating tool declared
     * read is the hole — [McpToolDispatcher]'s `mcp.tool.write` event skips it.
     */
    data class Entry(
        val name: String,
        val mutating: Boolean,
        val permission: Permission,
    )

    /** §6.1's surface, in `tools/list` order — the order [McpServerAutoConfiguration.mcpTools] returns. */
    val ENTRIES: List<Entry> =
        listOf(
            Entry("pipelines_list", mutating = false, permission = Permission.PIPELINE_READ),
            Entry("pipelines_get", mutating = false, permission = Permission.PIPELINE_READ),
            Entry("pipelines_execute", mutating = true, permission = Permission.PIPELINE_EXECUTE),
            Entry("pipelines_execute_node", mutating = true, permission = Permission.PIPELINE_EXECUTE_NODE),
            Entry("pipelines_create", mutating = true, permission = Permission.PIPELINE_CREATE),
            Entry("pipelines_update", mutating = true, permission = Permission.PIPELINE_UPDATE),
            Entry("templates_list", mutating = false, permission = Permission.TEMPLATE_READ),
            Entry("templates_get", mutating = false, permission = Permission.TEMPLATE_READ),
            Entry("templates_used_by", mutating = false, permission = Permission.TEMPLATE_READ),
            Entry("templates_create", mutating = true, permission = Permission.TEMPLATE_CREATE),
            // 117 — the draft write REST PUT /templates makes, mirror of pipelines_update. The
            // agent's edit verb: parse-only validation, then TemplateDraftService.write.
            Entry("templates_update", mutating = true, permission = Permission.TEMPLATE_UPDATE),
            Entry("templates_render", mutating = false, permission = Permission.TEMPLATE_RENDER),
            // 7b — the transform evaluator (sql_probe's twin): reads one version and evaluates
            // over a caller-supplied input; nothing is staged and nothing is stored.
            Entry("templates_evaluate", mutating = false, permission = Permission.TEMPLATE_EVALUATE),
            // 107 — the bounded D61/D62 self-service verb: hard-deletes a never-released,
            // unpinned, author-owned draft template. A write, so the `mcp.tool.write` audit's
            // business.
            Entry("templates_purge_draft", mutating = true, permission = Permission.TEMPLATE_VERSION_MANAGE),
            Entry("datasources_list", mutating = false, permission = Permission.DATASOURCE_READ),
            Entry("datasources_get", mutating = false, permission = Permission.DATASOURCE_READ),
            Entry("datasources_test", mutating = false, permission = Permission.DATASOURCE_TEST),
            Entry("datasources_get_schemas", mutating = false, permission = Permission.DATASOURCE_INTROSPECT),
            Entry("datasources_get_tables", mutating = false, permission = Permission.DATASOURCE_INTROSPECT),
            Entry("datasources_get_columns", mutating = false, permission = Permission.DATASOURCE_INTROSPECT),
            // 107 — the §7C catalog-stats read. `read` floor: estimates about shape, never
            // customer row data (the preview_rows reasoning does not apply to a COUNT the
            // engine already stored).
            Entry("datasources_get_table_stats", mutating = false, permission = Permission.DATASOURCE_READ),
            Entry("datasources_preview_rows", mutating = false, permission = Permission.DATASOURCE_PREVIEW_ROWS),
            // 107 — the bounded free-SQL probe. Read-only by classification (a write-shaped
            // statement never reaches a connection), so not a writer — but its `sql` argument is
            // audited as a SHA-256 + length, never verbatim (the dispatcher's redaction).
            Entry("sql_probe", mutating = false, permission = Permission.DATASOURCE_SQL_PROBE),
            // 094 ruling 4: there is deliberately no `datasources_create`. Registering a
            // datasource means handing over a live database credential, and a credential passed
            // through a tool call transits the agent's context, its transcript and whatever the
            // client logs. 068 shipped the tool with that hazard documented in its description;
            // this round decided the hazard is not documentable away. People add datasources in
            // the UI (or an operator over REST / the bootstrap file); agents use them by name.
            Entry("executions_list", mutating = false, permission = Permission.EXECUTION_READ),
            Entry("executions_get", mutating = false, permission = Permission.EXECUTION_READ),
            Entry("executions_get_result", mutating = false, permission = Permission.EXECUTION_RESULT_READ),
            // 107 — cancellation IS a write (it ends a running execution), and the same-credential
            // rule makes the `mcp.tool.write` row the trace of WHOSE key stopped it.
            Entry("executions_cancel", mutating = true, permission = Permission.EXECUTION_CANCEL),
            // 072 — the calculator catalog. Read, and read in the strongest sense: they return a
            // property of the BUILD, identical for every caller and every workspace.
            Entry("calculators_list", mutating = false, permission = Permission.CALCULATOR_READ),
            Entry("calculators_get", mutating = false, permission = Permission.CALCULATOR_READ),
            // 074 — published endpoints. `endpoints_create` and `endpoints_delete` write the
            // registry, so they are the `mcp.tool.write` audit's business; list/get are reads.
            Entry("endpoints_create", mutating = true, permission = Permission.ENDPOINT_PUBLISH),
            Entry("endpoints_list", mutating = false, permission = Permission.ENDPOINT_READ),
            Entry("endpoints_get", mutating = false, permission = Permission.ENDPOINT_READ),
            Entry("endpoints_delete", mutating = true, permission = Permission.ENDPOINT_UNPUBLISH),
            // 089 §A — the dp-lake catalog. All three write the registry (and evict the pool
            // beside it), so all three are the `mcp.tool.write` audit's business.
            Entry("lake_tables_register", mutating = true, permission = Permission.LAKE_TABLE_MANAGE),
            Entry("lake_tables_import", mutating = true, permission = Permission.LAKE_TABLE_MANAGE),
            Entry("lake_tables_unregister", mutating = true, permission = Permission.LAKE_TABLE_MANAGE),
            // 118 — the learned semantic layer. record and retire write `learned_facts`
            // (the `mcp.tool.write` audit's business, beside the domain's own
            // `semantics.recorded` / `semantics.retired` rows); list is a read.
            Entry("semantics_record", mutating = true, permission = Permission.SEMANTIC_RECORD),
            Entry("semantics_list", mutating = false, permission = Permission.SEMANTIC_READ),
            Entry("semantics_retire", mutating = true, permission = Permission.SEMANTIC_RETIRE),
            // 120 — the skill docs as tools (R3): the same SkillDocs bytes the resources
            // serve, for clients that fetch resources reluctantly or never. A property of
            // the BUILD, like the calculator catalog — reads in the strongest sense.
            Entry("docs_list", mutating = false, permission = Permission.DOCS_READ),
            Entry("docs_get", mutating = false, permission = Permission.DOCS_READ),
            // 140 — the release-check run. It writes one `pipeline_check_runs` row per check,
            // so it is the `mcp.tool.write` audit's business even though it changes no
            // definition: the rows are the server's own observed values, and WHO commissioned
            // them is exactly what the write event records.
            Entry("pipelines_run_checks", mutating = true, permission = Permission.PIPELINE_RUN_CHECKS),
        )

    /** §6.1's names, in `tools/list` order — [ENTRIES] projected, so the two cannot drift. */
    val NAMES: List<String> = ENTRIES.map { it.name }

    /** The catalogued tools whose calls write — the drivers of the `mcp.tool.write` audit event. */
    val MUTATING: Set<String> = ENTRIES.filter { it.mutating }.map { it.name }.toSet()

    /** The permission [name] declares, or null for a name the catalog does not carry — which the dispatcher refuses (fail closed). */
    fun permissionOf(name: String): Permission? = BY_NAME[name]?.permission

    private val BY_NAME: Map<String, Entry> = ENTRIES.associateBy { it.name }

    /** Whether [name] is a catalogued tool whose call can write. Unknown tools are reads; the dispatcher never runs them anyway. */
    fun isMutating(name: String): Boolean = name in MUTATING
}
