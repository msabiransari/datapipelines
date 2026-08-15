package co.datapipelines.mcp

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import io.modelcontextprotocol.server.McpStatelessSyncServer
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean

/**
 * The `mcp-server` module's Spring Boot autoconfiguration (module-structure §5.8, §8.2).
 *
 * It contributes the whole MCP surface — the 18 tools, the three prompts, the resource catalog, the
 * transport servlet at `/mcp` and [McpAuthFilter] in front of it — from collaborators the other
 * modules already publish. Nothing here re-implements a service: `mcp-server` is a thin adapter
 * over the same service layer the REST controllers use (§5.8), which is why every dependency
 * below is somebody else's bean.
 *
 * The whole configuration is gated on [PipelineExecutor] being present, so a context that has not
 * assembled the engine (a slice test, a docs build) simply has no MCP surface rather than failing
 * to start.
 */
@AutoConfiguration
@ConditionalOnBean(PipelineExecutor::class)
class McpServerAutoConfiguration {
    /** The 18 tools of §6.1, in `tools/list` order. */
    @Suppress("LongParameterList")
    @Bean
    @ConditionalOnMissingBean
    fun mcpTools(
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        datasources: DatasourceRegistry,
        introspector: SchemaIntrospector,
        executions: ExecutionRepository,
        executor: PipelineExecutor,
        resultStore: ResultStore,
        resultUrls: ResultUrlFactory,
        executorConfig: ExecutorConfig,
        pipelineValidator: PipelineValidator,
        templateValidator: TemplateValidator,
        templateEngine: TemplateEngine,
        // P7: the recording execution path `web` supplies in the assembled application.
        // A provider, because `web`'s bean exists only where the engine is fully wired —
        // in a bare module context the tool falls back to the shared executor (records
        // nothing); see McpExecutionRunner.
        executionRunner: ObjectProvider<McpExecutionRunner>,
    ): List<McpTool> {
        val deserializer = PipelineDeserializer()
        val serializer = PipelineSerializer()
        return listOf(
            PipelinesListTool(pipelines),
            PipelinesGetTool(pipelines),
            PipelineExecuteTool(
                pipelines,
                executor,
                resultStore,
                resultUrls,
                deserializer,
                executorConfig.result,
                executionRunner.getIfAvailable(),
            ),
            PipelinesCreateTool(pipelines, deserializer, pipelineValidator, serializer),
            PipelinesUpdateTool(pipelines, deserializer, pipelineValidator, serializer),
            TemplatesListTool(templates),
            TemplatesGetTool(templates),
            TemplatesCreateTool(templates, templateValidator),
            TemplatesRenderTool(templates, templateEngine),
            DatasourcesListTool(datasources),
            DatasourcesGetTool(datasources),
            DatasourcesTestTool(datasources),
            DatasourcesGetSchemasTool(introspector),
            DatasourcesGetTablesTool(introspector),
            DatasourcesGetColumnsTool(introspector),
            ExecutionsListTool(executions),
            ExecutionsGetTool(executions),
            ExecutionsGetResultTool(executions, resultStore, resultUrls, executorConfig.result),
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun mcpToolDispatcher(
        tools: List<McpTool>,
        auditLogger: AuditLogger,
    ): McpToolDispatcher = McpToolDispatcher(tools, auditLogger)

    @Bean
    @ConditionalOnMissingBean
    fun mcpPromptCatalog(): McpPromptCatalog = McpPromptCatalog()

    @Bean
    @ConditionalOnMissingBean
    fun mcpResourceCatalog(
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        datasources: DatasourceRegistry,
        executions: ExecutionRepository,
    ): McpResourceCatalog = McpResourceCatalog(pipelines, templates, datasources, executions)

    @Bean
    @ConditionalOnMissingBean
    fun mcpResourceReader(
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        datasources: DatasourceRegistry,
        executions: ExecutionRepository,
        events: ExecutionEventRepository,
    ): McpResourceReader = McpResourceReader(pipelines, templates, datasources, executions, events)

    @Bean
    @ConditionalOnMissingBean
    fun mcpTransport(): HttpServletStatelessServerTransport = McpServerFactory.transport()

    @Bean
    @ConditionalOnMissingBean
    fun mcpServer(
        transport: HttpServletStatelessServerTransport,
        dispatcher: McpToolDispatcher,
        prompts: McpPromptCatalog,
        catalog: McpResourceCatalog,
        reader: McpResourceReader,
    ): McpStatelessSyncServer = McpServerFactory.server(transport, dispatcher, prompts, catalog, reader, SERVER_VERSION)

    /**
     * Registers the transport servlet at `/mcp`.
     *
     * `mcpServer` is depended on rather than merely built: constructing the server is what installs
     * the request handler on the transport, so a servlet registered without it would answer every
     * JSON-RPC call with "no handler".
     */
    @Bean
    fun mcpServletRegistration(
        transport: HttpServletStatelessServerTransport,
        @Suppress("UNUSED_PARAMETER") mcpServer: McpStatelessSyncServer,
    ): ServletRegistrationBean<HttpServletStatelessServerTransport> =
        ServletRegistrationBean(transport, McpServerFactory.ENDPOINT).apply {
            setName("mcpTransport")
            isAsyncSupported = true
        }

    @Bean
    @ConditionalOnMissingBean
    fun mcpAuthFilter(errorWriter: AuthErrorWriter): McpAuthFilter = McpAuthFilter(errorWriter)

    /**
     * Registers [McpAuthFilter] on `/mcp` **after** the Spring Security chain
     * ([SecurityProperties.DEFAULT_FILTER_ORDER]), because it reads the principal that chain
     * established. Registering it earlier would make every MCP request look unauthenticated.
     */
    @Bean
    fun mcpAuthFilterRegistration(filter: McpAuthFilter): FilterRegistrationBean<McpAuthFilter> =
        FilterRegistrationBean(filter).apply {
            addUrlPatterns(McpServerFactory.ENDPOINT)
            order = SecurityProperties.DEFAULT_FILTER_ORDER + FILTER_ORDER_OFFSET
            isAsyncSupported = true
        }

    private companion object {
        /**
         * `serverInfo.version` (§5.1) — the datapipelines.co release version.
         *
         * A constant rather than a config key: configuration.md defines no `datapipelines.mcp.*`
         * key and it is the only authority for config (D8). `app` overrides the `mcpServer` bean if
         * it wants the real build version.
         */
        const val SERVER_VERSION = "1.0.0"

        /** Far enough after the security chain that the SecurityContext is populated. */
        const val FILTER_ORDER_OFFSET = 10
    }
}
