package co.datapipelines.templates

import co.datapipelines.pipeline.TemplateDryRenderer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * Wires the templates module's beans (module-structure §8.2/§8.4).
 *
 * The repository is declared here like everything else (015 — no component-scanned
 * stereotypes anywhere); the rest take primitive config values ([TemplatesProperties])
 * rather than framework types, so they are assembled here by
 * constructor injection. This keeps [TemplateEngine], [TemplateValidator] and the rest
 * framework-agnostic and directly unit-testable, with the Spring knowledge confined to this
 * one file.
 *
 * ## Per-workspace engines (slice 2, T24)
 *
 * There is deliberately **no** singleton `TemplateEngine`/`TemplateRegistry` bean: template
 * names are unique only per workspace, so the render path's caches must be workspace-bound
 * or they collide across workspaces. [WorkspaceTemplateEngines] vends the bound pairs;
 * every consumer (REST render, MCP tools, pipeline validation, the execution path's
 * assemblers in `web`) resolves its workspace and picks the matching engine.
 */
@Configuration
@EnableConfigurationProperties(TemplatesProperties::class)
class TemplatesConfiguration {
    @Bean
    fun templateRepository(jdbc: NamedParameterJdbcTemplate): TemplateRepository = TemplateRepository(jdbc)

    /**
     * The used-by reverse service (040) — consumed by the template surfaces here (the delete
     * guard, the version list's in-use counts) and constructed inline by `mcp-server`'s tool
     * bean (stateless over the same two repositories, so two instances cannot disagree).
     */
    @Bean
    fun templateUsageService(
        repository: TemplateRepository,
        pipelines: co.datapipelines.pipeline.PipelineRepository,
    ): TemplateUsageService = TemplateUsageService(repository, pipelines)

    @Bean
    fun workspaceTemplateEngines(
        repository: TemplateRepository,
        properties: TemplatesProperties,
    ): WorkspaceTemplateEngines =
        WorkspaceTemplateEngines(
            repository = repository,
            cacheSize = properties.cacheSize,
            renderTimeoutMs = properties.renderTimeoutMs,
            // Output-size budget is Staging §8's to own; this is the engine-wide backstop against
            // pathological output. `dag` injects an engine rather than constructing it
            // (dag-executor §5.2), so it supplies the real per-execution budget per call —
            // `render(ref, context, maxOutputChars)` — not at construction. NOT a templates
            // config key (configuration.md §3.9).
            maxOutputChars = DEFAULT_MAX_OUTPUT_CHARS,
        )

    @Bean
    fun libraryResolver(engines: WorkspaceTemplateEngines): LibraryResolver = LibraryResolver(engines::registryFor)

    @Bean
    fun templateValidator(
        libraryResolver: LibraryResolver,
        properties: TemplatesProperties,
    ): TemplateValidator = TemplateValidator(libraryResolver, properties.maxBodyChars)

    @Bean
    fun templateDryRenderer(engines: WorkspaceTemplateEngines): TemplateDryRenderer = TemplateDryRendererImpl(engines)

    private companion object {
        /** 64M characters — a backstop against pathological output, not the real staging budget. */
        const val DEFAULT_MAX_OUTPUT_CHARS = 64L * 1024 * 1024
    }
}
