package co.datapipelines.mcp

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
 *    hole the dispatcher's `mcp.tool.write` audit would silently skip.
 *
 * This is a constant, not a bean, precisely because the tool bean is
 * `@ConditionalOnBean(PipelineExecutor::class)` (033/C4): a deployment without the engine
 * still renders the homepage, and "0 tools" there would be a lie.
 */
object McpToolCatalog {
    /**
     * One catalogued tool: its wire name and whether calling it can change stored
     * definitions or customer data. The flag is a DECLARED property of the entry — never
     * derived from the name — because which calls write is a semantic fact about the
     * tool, and name patterns drift (052, ruling R4).
     *
     * A read tool declared mutating is a harmless over-audit; a mutating tool declared
     * read is the hole — [McpToolDispatcher]'s `mcp.tool.write` event skips it.
     */
    data class Entry(
        val name: String,
        val mutating: Boolean,
    )

    /** §6.1's surface, in `tools/list` order — the order [McpServerAutoConfiguration.mcpTools] returns. */
    val ENTRIES: List<Entry> =
        listOf(
            Entry("pipelines_list", mutating = false),
            Entry("pipelines_get", mutating = false),
            Entry("pipelines_execute", mutating = true),
            Entry("pipelines_execute_node", mutating = true),
            Entry("pipelines_create", mutating = true),
            Entry("pipelines_update", mutating = true),
            Entry("templates_list", mutating = false),
            Entry("templates_get", mutating = false),
            Entry("templates_used_by", mutating = false),
            Entry("templates_create", mutating = true),
            Entry("templates_render", mutating = false),
            // 107 — the bounded D61/D62 self-service verb: hard-deletes a never-released,
            // unpinned, author-owned draft template. A write, so the `mcp.tool.write` audit's
            // business.
            Entry("templates_purge_draft", mutating = true),
            Entry("datasources_list", mutating = false),
            Entry("datasources_get", mutating = false),
            Entry("datasources_test", mutating = false),
            Entry("datasources_get_schemas", mutating = false),
            Entry("datasources_get_tables", mutating = false),
            Entry("datasources_get_columns", mutating = false),
            // 107 — the §7C catalog-stats read. `read` floor: estimates about shape, never
            // customer row data (the preview_rows reasoning does not apply to a COUNT the
            // engine already stored).
            Entry("datasources_get_table_stats", mutating = false),
            Entry("datasources_preview_rows", mutating = false),
            // 107 — the bounded free-SQL probe. Read-only by classification (a write-shaped
            // statement never reaches a connection), so not a writer — but its `sql` argument is
            // audited as a SHA-256 + length, never verbatim (the dispatcher's redaction).
            Entry("sql_probe", mutating = false),
            // 094 ruling 4: there is deliberately no `datasources_create`. Registering a
            // datasource means handing over a live database credential, and a credential passed
            // through a tool call transits the agent's context, its transcript and whatever the
            // client logs. 068 shipped the tool with that hazard documented in its description;
            // this round decided the hazard is not documentable away. People add datasources in
            // the UI (or an operator over REST / the bootstrap file); agents use them by name.
            Entry("executions_list", mutating = false),
            Entry("executions_get", mutating = false),
            Entry("executions_get_result", mutating = false),
            // 107 — cancellation IS a write (it ends a running execution), and the same-credential
            // rule makes the `mcp.tool.write` row the trace of WHOSE key stopped it.
            Entry("executions_cancel", mutating = true),
            // 072 — the calculator catalog. Read, and read in the strongest sense: they return a
            // property of the BUILD, identical for every caller and every workspace.
            Entry("calculators_list", mutating = false),
            Entry("calculators_get", mutating = false),
            // 074 — published endpoints. `endpoints_create` and `endpoints_delete` write the
            // registry, so they are the `mcp.tool.write` audit's business; list/get are reads.
            Entry("endpoints_create", mutating = true),
            Entry("endpoints_list", mutating = false),
            Entry("endpoints_get", mutating = false),
            Entry("endpoints_delete", mutating = true),
            // 089 §A — the dp-lake catalog. All three write the registry (and evict the pool
            // beside it), so all three are the `mcp.tool.write` audit's business.
            Entry("lake_tables_register", mutating = true),
            Entry("lake_tables_import", mutating = true),
            Entry("lake_tables_unregister", mutating = true),
            // 118 — the learned semantic layer. record and retire write `learned_facts`
            // (the `mcp.tool.write` audit's business, beside the domain's own
            // `semantics.recorded` / `semantics.retired` rows); list is a read.
            Entry("semantics_record", mutating = true),
            Entry("semantics_list", mutating = false),
            Entry("semantics_retire", mutating = true),
        )

    /** §6.1's names, in `tools/list` order — [ENTRIES] projected, so the two cannot drift. */
    val NAMES: List<String> = ENTRIES.map { it.name }

    /** The catalogued tools whose calls write — the drivers of the `mcp.tool.write` audit event. */
    val MUTATING: Set<String> = ENTRIES.filter { it.mutating }.map { it.name }.toSet()

    /** Whether [name] is a catalogued tool whose call can write. Unknown tools are reads; the dispatcher never runs them anyway. */
    fun isMutating(name: String): Boolean = name in MUTATING
}
