package co.datapipelines.mcp

/**
 * The single authority for the shipped MCP tool NAMES, in `tools/list` order (mcp-server.md
 * §6.1) — 033/C1.
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
 *    `WebsiteFactsGuardTest` asserts four-way agreement with mcp-server.md §6.1.
 *
 * This is a constant, not a bean, precisely because the tool bean is
 * `@ConditionalOnBean(PipelineExecutor::class)` (033/C4): a deployment without the engine
 * still renders the homepage, and "0 tools" there would be a lie.
 */
object McpToolCatalog {
    /** §6.1's list, in `tools/list` order — the order [McpServerAutoConfiguration.mcpTools] returns. */
    val NAMES: List<String> =
        listOf(
            "pipelines_list",
            "pipelines_get",
            "pipelines_execute",
            "pipelines_create",
            "pipelines_update",
            "templates_list",
            "templates_get",
            "templates_create",
            "templates_render",
            "datasources_list",
            "datasources_get",
            "datasources_test",
            "datasources_get_schemas",
            "datasources_get_tables",
            "datasources_get_columns",
            "executions_list",
            "executions_get",
            "executions_get_result",
        )
}
