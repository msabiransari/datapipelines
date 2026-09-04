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
            Entry("datasources_list", mutating = false),
            Entry("datasources_get", mutating = false),
            Entry("datasources_test", mutating = false),
            Entry("datasources_get_schemas", mutating = false),
            Entry("datasources_get_tables", mutating = false),
            Entry("datasources_get_columns", mutating = false),
            Entry("datasources_preview_rows", mutating = false),
            Entry("datasources_create", mutating = true),
            Entry("executions_list", mutating = false),
            Entry("executions_get", mutating = false),
            Entry("executions_get_result", mutating = false),
        )

    /** §6.1's names, in `tools/list` order — [ENTRIES] projected, so the two cannot drift. */
    val NAMES: List<String> = ENTRIES.map { it.name }

    /** The catalogued tools whose calls write — the drivers of the `mcp.tool.write` audit event. */
    val MUTATING: Set<String> = ENTRIES.filter { it.mutating }.map { it.name }.toSet()

    /** Whether [name] is a catalogued tool whose call can write. Unknown tools are reads; the dispatcher never runs them anyway. */
    fun isMutating(name: String): Boolean = name in MUTATING
}
