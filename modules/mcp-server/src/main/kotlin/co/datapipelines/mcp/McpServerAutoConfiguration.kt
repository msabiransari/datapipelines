package co.datapipelines.mcp

import co.datapipelines.application.ExecutionLauncher
import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.application.mcp.McpCallAudit
import co.datapipelines.application.semantics.FactEnrichment
import co.datapipelines.application.semantics.SemanticsService
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.WorkspaceTemplateEngines
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
 * It contributes the whole MCP surface — the 42 tools, the three prompts, the resource catalog, the
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
    /**
     * 139 — the entry-point checks, built by the 037 discipline: stateless over collaborators
     * [mcpTools] already holds, so the tools cannot describe a gate the server does not ship. The
     * audit-log reader is the SAME metadata jdbc the mcpCallAudit bean wraps. Its own function
     * because [mcpTools] sits at detekt's LongMethod ceiling (139 and 140 each grew it).
     */
    private fun entryPointChecks(
        jdbc: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate,
        templates: TemplateRepository,
        introspector: SchemaIntrospector,
        datasources: DatasourceRegistry,
    ): Pair<TableLearningCheck, TemplateRenderFreshness> {
        val learnings =
            co.datapipelines.application.mcp
                .McpToolLearnings(jdbc)
        return TableLearningCheck(templates, introspector, datasources, learnings) to
            TemplateRenderFreshness(templates, learnings)
    }

    /** The 42 tools of §6.1, in `tools/list` order. */
    @Suppress("LongParameterList")
    @Bean
    @ConditionalOnMissingBean
    fun mcpTools(
        pipelines: PipelineRepository,
        pipelineService: PipelineService,
        templates: TemplateRepository,
        datasources: DatasourceRegistry,
        introspector: SchemaIntrospector,
        executions: ExecutionRepository,
        executor: PipelineExecutor,
        resultStore: ResultStore,
        resultUrls: ResultUrlFactory,
        executorConfig: ExecutorConfig,
        templateValidator: TemplateValidator,
        templateEngines: WorkspaceTemplateEngines,
        environment: org.springframework.core.env.Environment,
        // P7: the recording execution path `web` supplies in the assembled application.
        // A provider, because `web`'s bean exists only where the engine is fully wired —
        // in a bare module context the tool falls back to the shared executor (records
        // nothing); see McpExecutionRunner.
        executionRunner: ObjectProvider<McpExecutionRunner>,
        // 056/D6: the shared launch decision. A provider for the same reason as above — the
        // bean exists where the engine is fully wired; without it the execute tool runs every
        // call, its pre-056 behaviour.
        launcher: ObjectProvider<ExecutionLauncher>,
        // 068: the ONE validated datasource-registration path, shared with POST /api/v1/datasources.
        // A plain (required) parameter, not a provider: this whole bean is @ConditionalOnBean on
        // the engine, so wherever the tools exist the assembled application has declared it too.
        // 074 — the SAME publish service POST /api/v1/endpoints calls, so a publish cannot skip
        // the read-only rule by arriving over MCP.
        endpointPublishService: EndpointPublishService,
        // 089 §A — the SAME lake-table registry the REST /tables endpoints call, so a
        // registration over MCP crosses the same validation, D8 gate and pool invalidation.
        lakeTableRegistryService: LakeTableRegistryService,
        // 107 — the SAME cancellation service `DELETE /api/v1/executions/{id}` calls (086's
        // Redis-flag-first, local-second semantics live there, not here), plus the audit-log
        // read the same-credential rule joins on. Plain parameters, the 068/074 pattern.
        cancellationService: ExecutionCancellationService,
        mcpCallAudit: McpCallAudit,
        // 139 — the metadata jdbc, for the audit-log read behind the entry-point checks
        // (the mcpCallAudit bean's collaborator, reached directly: the checks are built
        // inline by the 037 discipline, so they need no bean of their own).
        jdbc: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate,
        // 107: the launch-time audit row executions_cancel joins on — pipelines_execute blocks
        // until the execution is terminal, so the dispatcher's end-of-call row comes too late.
        auditSink: co.datapipelines.auth.AuditEventSink,
        // 118 — the learned semantic layer: the SAME service and enrichment the REST twins use
        // (declared by `web`'s SemanticsConfiguration, the LakeConfiguration precedent), so a
        // fact recorded over MCP is validated, audited and served exactly as anywhere else.
        semanticsService: SemanticsService,
        factEnrichment: FactEnrichment,
        // 140 — the SAME check runner REST POST …/checks/run, the UI and the release gate ride
        // (declared by `web`'s ChecksConfiguration), so an MCP check run crosses the same probe
        // contract and persists the same pipeline_check_runs rows. A plain parameter, the
        // 068/074 pattern.
        checkRunner: co.datapipelines.application.checks.PipelineCheckRunner,
        // 178 — the promoter lens (declared by `web`'s PromotionConfiguration as the `application`
        // port) and the template read façade (declared by `templates`), so the read tools
        // narrow exactly as REST and the UI do. Plain parameters, the 068/074 pattern.
        lens: PromoterLens,
        templateService: TemplateService,
        // 7b — the SAME evaluation service POST /api/v1/templates/evaluate calls (declared by
        // `web`'s EngineConfiguration), so an evaluation over MCP crosses the same pool, caps
        // and invariants. A plain parameter, the 068/074 pattern.
        templateEvaluateService: co.datapipelines.application.templates.TemplateEvaluateService,
        // 117/7e — the template draft write REST PUT /templates goes through (declared by `web`'s
        // TemplateLifecycleConfiguration). Injected, no longer built here: since 7e it carries the
        // citation store, and one instance for both surfaces is what keeps the write rule single.
        templateDrafts: co.datapipelines.templates.TemplateDraftService,
    ): List<McpTool> {
        // The authoring capability (versioning §5.5), read from the same property web's
        // guard bean reads — built locally so this module needs no bean from `web`; the
        // flag is immutable configuration, so two instances cannot disagree. The PIPELINE
        // write tools no longer need it: PipelineService checks it (056), which is the point
        // of a service layer. The template tools still do, until slice B.
        val authoring = AuthoringGuard.from(environment)
        // 037's two data-visibility services, built from collaborators already in this method:
        // stateless, so inline construction adds no wiring (the 037 fence touched no `app` bean).
        val sqlRunner = co.datapipelines.datasources.SqlRunner(datasources)
        val nodeResolver = co.datapipelines.templates.NodeSqlResolver(pipelines, templates, templateEngines)
        // 040's used-by service, same inline-construction discipline (the templates module's
        // configuration declares the bean `web` consumes; this module builds its own).
        val usage = co.datapipelines.templates.TemplateUsageService(templates, pipelines)
        val (tableLearning, renderFreshness) = entryPointChecks(jdbc, templates, introspector, datasources)
        return listOf(
            PipelinesListTool(pipelineService, lens),
            PipelinesGetTool(pipelineService, usage, lens),
            PipelineExecuteTool(
                pipelines = pipelineService,
                executor = executor,
                executions = executions,
                resultStore = resultStore,
                resultUrls = resultUrls,
                launcher = launcher.getIfAvailable(),
                resultConfig = executorConfig.result,
                executionRunner = executionRunner.getIfAvailable(),
                launchAudit = auditSink,
                renderFreshness = renderFreshness,
            ),
            PipelinesExecuteNodeTool(nodeResolver, datasources, sqlRunner, renderFreshness),
            PipelinesCreateTool(pipelineService, pipelines, tableLearning),
            PipelinesUpdateTool(pipelineService, tableLearning),
            TemplatesListTool(templateService, lens),
            TemplatesGetTool(templateService, lens),
            TemplatesUsedByTool(usage, lens),
            TemplatesCreateTool(templates, authoring, templateValidator, templateDrafts),
            TemplatesUpdateTool(templates, templateDrafts, templateValidator),
            TemplatesRenderTool(templates, templateEngines),
            // 7b — the transform evaluator (record §9.1): the SAME service the REST evaluate
            // route calls, a plain parameter by the 068/074 pattern.
            TemplatesEvaluateTool(templateEvaluateService),
            // 107 — the bounded purge: sole-DRAFT, author-owned, unpinned only.
            TemplatesPurgeDraftTool(templates, usage, authoring),
            DatasourcesListTool(datasources, factEnrichment, lens),
            DatasourcesGetTool(datasources, factEnrichment, lens),
            DatasourcesTestTool(datasources),
            DatasourcesGetSchemasTool(introspector, datasources),
            DatasourcesGetTablesTool(introspector, datasources, factEnrichment),
            DatasourcesGetColumnsTool(introspector, datasources, factEnrichment),
            DatasourcesGetTableStatsTool(introspector, datasources),
            DatasourcesPreviewRowsTool(datasources, sqlRunner, introspector),
            // 107 — the bounded probe, same inline-construction discipline as `sqlRunner`. The
            // staging MODE rides along so the tempdb scratch check parses like the real tempdb.
            SqlProbeTool(
                datasources,
                SqlProbe(datasources),
                tempdbMode = environment.getProperty("datapipelines.staging.h2.mode", SqlProbe.DEFAULT_SCRATCH_MODE),
            ),
            ExecutionsListTool(executions),
            ExecutionsGetTool(executions),
            ExecutionsGetResultTool(executions, resultStore, resultUrls, executorConfig.result),
            ExecutionsCancelTool(executions, cancellationService, mcpCallAudit),
            // 072: no collaborators at all — the catalog is a compile-time constant, which is
            // exactly why these two need no workspace, no repository and no registry.
            CalculatorsListTool(),
            CalculatorsGetTool(),
        ) + EndpointsTools.all(endpointPublishService, pipelines) + LakeTableTools.all(datasources, lakeTableRegistryService) +
            SemanticsTools.all(datasources, semanticsService, introspector, lens) +
            // 120 — the skill docs as tools: no collaborators at all, the 072 reasoning —
            // the content is the packaged build artifact, read through the same SkillDocs
            // loader the resources use.
            DocsTools.all() +
            // 140 — the release-check run, appended after the docs tools (the 117/107 append
            // rule). Takes the service for the working-version resolution and the shared
            // runner for everything else.
            listOf(PipelineRunChecksTool(pipelineService, checkRunner))
    }

    @Bean
    @ConditionalOnMissingBean
    fun mcpToolDispatcher(
        tools: List<McpTool>,
        auditLogger: AuditLogger,
    ): McpToolDispatcher = McpToolDispatcher(tools, auditLogger)

    /**
     * 107 — the audit-log read `executions_cancel`'s same-credential rule joins on
     * (key id × the call's correlation id; see [McpCallAudit]'s KDoc for why the execution row
     * alone cannot answer it). Built here for the same reason the dispatcher is: the metadata
     * `NamedParameterJdbcTemplate` is a bean wherever the engine is assembled.
     */
    @Bean
    @ConditionalOnMissingBean
    fun mcpCallAudit(jdbc: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate): McpCallAudit = McpCallAudit(jdbc)

    @Bean
    @ConditionalOnMissingBean
    fun mcpPromptCatalog(): McpPromptCatalog = McpPromptCatalog()

    @Bean
    @ConditionalOnMissingBean
    fun mcpResourceCatalog(
        pipelines: PipelineService,
        templates: TemplateService,
        datasources: DatasourceRegistry,
        executions: ExecutionRepository,
        // 178 — the promoter lens on the catalogue.
        lens: PromoterLens,
    ): McpResourceCatalog = McpResourceCatalog(pipelines, templates, datasources, executions, lens)

    @Bean
    @ConditionalOnMissingBean
    fun mcpResourceReader(
        // The SERVICE, not the repository: the pipeline resource serves the working version
        // (D56) and that resolution lives in PipelineService.
        pipelines: PipelineService,
        templates: TemplateService,
        datasources: DatasourceRegistry,
        executions: ExecutionRepository,
        events: ExecutionEventRepository,
        // 120 — the reader's `mcp.resource.read` rows share the dispatcher's sink.
        auditSink: co.datapipelines.auth.AuditEventSink,
        // 178 — the promoter lens on the pipeline and template resources.
        lens: PromoterLens,
    ): McpResourceReader = McpResourceReader(pipelines, templates, datasources, executions, events, auditSink, lens)

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
