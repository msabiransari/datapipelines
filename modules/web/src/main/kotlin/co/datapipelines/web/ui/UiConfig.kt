package co.datapipelines.web.ui

import co.datapipelines.auth.UserRepository
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.ui.site.SiteDemoData
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * UI configuration — enables [UiProperties] property binding and declares the UI
 * collaborators explicitly (015, module-structure.md §8.4).
 * Thymeleaf is auto-configured by Spring Boot; the `error/` templates
 * in `templates/error/` are picked up by [org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration].
 *
 * One bean per UI collaborator, the DomainConfiguration shape (015 / module-structure §8.4): the
 * count grows with the screens, not with any complexity here — hence the function-count
 * suppression. The 20th arrived at the 348 merge: L3b's dashboards model and #348's workspace
 * model each added one.
 */
@Suppress("TooManyFunctions")
@Configuration
@EnableConfigurationProperties(UiProperties::class)
class UiConfig {
    @Bean
    fun oidcRegistrations(repository: ClientRegistrationRepository): OidcRegistrations = OidcRegistrations(repository)

    /** Bean name pinned: it is the name the scanned stereotype carried (see [ThemeResolver]). */
    @Bean(name = ["uiThemeResolver"])
    fun themeResolver(
        userRepository: UserRepository,
        uiProperties: UiProperties,
    ): ThemeResolver = ThemeResolver(userRepository, uiProperties)

    /** 033: the memoized in-product spec set (renders once at startup; see [DocsCatalog]). */
    @Bean
    fun docsCatalog(): DocsCatalog = DocsCatalog(javaClass.classLoader)

    /**
     * 116: the demo-data page's facts — the three vendored sample-data manifests, parsed
     * once at startup and failing fast when one is missing or unparsable (see [SiteDemoData]).
     */
    @Bean
    fun siteDemoData(): SiteDemoData = SiteDemoData(javaClass.classLoader)

    /**
     * 091: the API-key table's row model, shared by the page and BOTH partial responses —
     * the post-create refresh and the rows a revoke swaps in (see [ApiKeyRows]).
     */
    @Bean
    fun apiKeyRows(
        bindings: co.datapipelines.application.endpoints.EndpointKeyBindingRepository,
        // L5 — a `dashboard` key's folders read from their own table.
        dashboardBindings: co.datapipelines.application.dashboards.DashboardKeyBindingRepository,
    ): ApiKeyRows = ApiKeyRows(bindings, dashboardBindings)

    /** 047: the templates screen's one model, shared by the page and the partial controllers. */
    @Bean
    fun templateBrowseModel(
        templates: co.datapipelines.templates.TemplateService,
        // 194d — the COMPOSED reverse arrow: the screen's in-use counts cover parameter sets
        // too (the record's §8.4).
        usage: co.datapipelines.application.templates.TemplateUsage,
        executions: co.datapipelines.executor.ExecutionRepository,
        actorNames: ActorNames,
    ): TemplateBrowseModel = TemplateBrowseModel(templates, usage, executions, actorNames)

    /** 089 §A: the LAKE datasource detail's read-only tree model — stateless, one shared instance. */
    @Bean
    fun lakeTableBrowseModel(): LakeTableBrowseModel = LakeTableBrowseModel()

    /** 162 (#156): the Tables view's schemas→tables→columns tree model, every other dialect. */
    @Bean
    fun datasourceSchemaTreeBrowseModel(introspector: co.datapipelines.datasources.SchemaIntrospector): DatasourceSchemaTreeBrowseModel =
        DatasourceSchemaTreeBrowseModel(introspector)

    /** 079 §A: the rail's Pipelines/Templates badges, behind a 60s TTL (see [NavCounts]). */
    @Bean
    fun navCounts(
        pipelines: co.datapipelines.pipeline.PipelineRepository,
        templates: TemplateRepository,
    ): NavCounts = NavCounts(pipelines, templates)

    /** 106: the version/execution actor lookup both explorer details render. */
    @Bean
    fun actorNames(jdbc: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate): ActorNames = ActorNames(jdbc)

    /** 106: per-version run counts for the acting column's Versions tab. */
    @Bean
    fun pipelineRunStats(jdbc: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate): PipelineRunStats =
        PipelineRunStats(jdbc)

    /**
     * 097 §A: the datasources screen's one model, shared by the page and the partial
     * controllers — the projection this screen had been building twice (see
     * [DatasourceBrowseModel]).
     */
    @Bean
    fun datasourceBrowseModel(datasources: co.datapipelines.datasources.DatasourceRegistry): DatasourceBrowseModel =
        DatasourceBrowseModel(datasources)

    /**
     * 097 §B: the execution-history screen's one model, shared by the page and the partial —
     * which is what let `/executions` render its first fragment instead of a spinner.
     */
    @Bean
    fun executionHistoryBrowseModel(
        executions: co.datapipelines.executor.ExecutionRepository,
        pipelineNames: PipelineNames,
        pipelines: co.datapipelines.pipeline.PipelineRepository,
    ): ExecutionHistoryBrowseModel = ExecutionHistoryBrowseModel(executions, pipelineNames, pipelines)

    /** 097 §C: the admin user table's row model, shared by the page and the partial. */
    @Bean
    fun adminUsersBrowseModel(users: co.datapipelines.auth.UserService): AdminUsersBrowseModel = AdminUsersBrowseModel(users)

    /** #10 L3b: the dashboards screens' one model, shared by the page controller and the partials. */
    @Bean
    fun dashboardBrowseModel(
        dashboards: co.datapipelines.visualization.DashboardService,
        runtime: co.datapipelines.web.dashboards.runtime.DashboardRuntime,
        pipelines: co.datapipelines.visualization.PipelineReleaseFacts,
        sets: co.datapipelines.visualization.ParameterSetFacts,
        visualizations: co.datapipelines.visualization.VisualizationRepository,
        apiKeys: co.datapipelines.auth.ApiKeyRepository,
        dashboardBindings: co.datapipelines.application.dashboards.DashboardKeyBindingRepository,
    ): DashboardBrowseModel = DashboardBrowseModel(dashboards, runtime, pipelines, sets, visualizations.pins, apiKeys, dashboardBindings)

    /** #374: the Parameter Sets screens' browse model — the rail tree's levels and the catalog's flat list. */
    @Bean
    fun parameterSetsBrowseModel(sets: co.datapipelines.parameters.ParameterSetService): ParameterSetsBrowseModel =
        ParameterSetsBrowseModel(sets)

    /** #376: the Parameter Sets workspace's History tab — the page's first page and its pager partial, one model. */
    @Bean
    fun parameterSetEvaluationsBrowseModel(
        sets: co.datapipelines.parameters.ParameterSetService,
        evaluations: co.datapipelines.parameters.ParameterEvaluationRepository,
    ): ParameterSetEvaluationsBrowseModel = ParameterSetEvaluationsBrowseModel(sets, evaluations)

    /** #374: the canonical Parameter Sets workspace page's version-resolution model. */
    @Bean
    fun parameterSetsWorkspaceModel(sets: co.datapipelines.parameters.ParameterSetService): ParameterSetsWorkspaceModel =
        ParameterSetsWorkspaceModel(sets)

    /**
     * 161: the shell search palette's one model (#155) — pipelines, templates and executions,
     * each group read through the query the group's own screen already answers with.
     */
    @Bean
    fun searchBrowseModel(
        pipelines: co.datapipelines.pipeline.PipelineService,
        templates: co.datapipelines.templates.TemplateService,
        executions: co.datapipelines.executor.ExecutionRepository,
        pipelineNames: PipelineNames,
        lens: co.datapipelines.application.lens.PromoterLens,
    ): SearchBrowseModel = SearchBrowseModel(pipelines, templates, executions, pipelineNames, lens)

    /** #348: the canonical pipeline workspace read page's version-resolution model. */
    @Bean
    fun pipelineWorkspaceModel(pipelines: co.datapipelines.pipeline.PipelineService): PipelineWorkspaceModel =
        PipelineWorkspaceModel(pipelines)

    /** #400: the canonical dashboard workspace read page's version-resolution model. */
    @Bean
    fun dashboardWorkspaceModel(dashboards: co.datapipelines.visualization.DashboardService): DashboardWorkspaceModel =
        DashboardWorkspaceModel(dashboards)

    /** #400: the dashboard lifecycle dialogs' facts — the same dependency reads the release guard runs. */
    @Bean
    fun dashboardLifecycleDialogModel(
        dashboards: co.datapipelines.visualization.DashboardService,
        pipelines: co.datapipelines.visualization.PipelineReleaseFacts,
        sets: co.datapipelines.visualization.ParameterSetFacts,
        visualizations: co.datapipelines.visualization.VisualizationRepository,
        actorNames: ActorNames,
        authoring: co.datapipelines.pipeline.AuthoringGuard,
    ): DashboardLifecycleDialogModel =
        DashboardLifecycleDialogModel(dashboards, pipelines, sets, visualizations.pins, actorNames, authoring)

    /** 067: the pipelines explorer's one model, shared by the page and the partial controllers. */
    @Bean
    @Suppress("LongParameterList") // 106: the detail's three regions in one call need their sources
    fun pipelineBrowseModel(
        pipelines: co.datapipelines.pipeline.PipelineService,
        repository: co.datapipelines.pipeline.PipelineRepository,
        executions: co.datapipelines.executor.ExecutionRepository,
        endpoints: co.datapipelines.application.endpoints.PublishedEndpointRepository,
        datasources: co.datapipelines.pipeline.DatasourceRegistry,
        actorNames: ActorNames,
        runStats: PipelineRunStats,
        authoring: co.datapipelines.pipeline.AuthoringGuard,
        schedules: co.datapipelines.scheduler.ScheduleService,
        // #320 — the dashboards that pin a release; the Usage tab lists what the discard would be refused over.
        dashboards: co.datapipelines.pipeline.PipelineVersionConsumers,
    ): PipelineBrowseModel =
        PipelineBrowseModel(
            pipelines,
            repository,
            executions,
            endpoints,
            datasources,
            actorNames,
            runStats,
            authoring,
            schedules,
            dashboards,
        )

    /** 102: the lifecycle dialogs' facts — the same scans the services' own guards read. */
    @Bean
    @Suppress("LongParameterList") // one collaborator per §4.3d fact, exactly like the browse models
    fun pipelineLifecycleDialogModel(
        repository: co.datapipelines.pipeline.PipelineRepository,
        templates: co.datapipelines.pipeline.TemplateVersionStatuses,
        exclusiveTemplates: co.datapipelines.pipeline.ExclusiveDraftTemplates,
        runStats: PipelineRunStats,
        actorNames: ActorNames,
        authoring: co.datapipelines.pipeline.AuthoringGuard,
        usage: co.datapipelines.templates.TemplateUsageService,
        // 7e — the release dialog's needs-review rows read the SAME port the release warning does.
        reviewMarks: co.datapipelines.pipeline.TemplateReviewMarks,
        // #273 — the discard dialog's schedules evidence, the Usage tab's by-target read.
        schedules: co.datapipelines.scheduler.ScheduleService,
        // #320 — the dashboards that pin a release, the port the service's discard guard asks.
        dashboards: co.datapipelines.pipeline.PipelineVersionConsumers,
    ): PipelineLifecycleDialogModel =
        PipelineLifecycleDialogModel(
            repository,
            templates,
            exclusiveTemplates,
            runStats,
            actorNames,
            authoring,
            usage,
            reviewMarks,
            co.datapipelines.pipeline.PipelineDeserializer(),
            schedules,
            dashboards,
        )

    /** 102: the template twin of [pipelineLifecycleDialogModel]. */
    @Bean
    fun templateLifecycleDialogModel(
        templates: TemplateRepository,
        // #320 — the SAME composed reverse arrow the release service's guards refuse with.
        usage: co.datapipelines.application.templates.TemplateUsage,
        actorNames: ActorNames,
        authoring: co.datapipelines.pipeline.AuthoringGuard,
    ): TemplateLifecycleDialogModel = TemplateLifecycleDialogModel(templates, usage, actorNames, authoring)
}
