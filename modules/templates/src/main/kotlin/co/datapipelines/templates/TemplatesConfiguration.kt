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
 */
@Configuration
@EnableConfigurationProperties(TemplatesProperties::class)
class TemplatesConfiguration {
    @Bean
    fun templateRepository(jdbc: NamedParameterJdbcTemplate): TemplateRepository = TemplateRepository(jdbc)

    @Bean
    fun templateRegistry(
        repository: TemplateRepository,
        properties: TemplatesProperties,
    ): TemplateRegistry = RepositoryTemplateRegistry(repository, properties.cacheSize)

    @Bean
    fun libraryResolver(registry: TemplateRegistry): LibraryResolver = LibraryResolver(registry)

    @Bean
    fun templateValidator(
        libraryResolver: LibraryResolver,
        properties: TemplatesProperties,
    ): TemplateValidator = TemplateValidator(libraryResolver, properties.maxBodyChars)

    // Named `sqlTemplateEngine`, not `templateEngine`: the `web` module pulls in
    // spring-boot-starter-thymeleaf, whose autoconfiguration already registers a bean named
    // `templateEngine` (Thymeleaf's SpringTemplateEngine). Bean-definition overriding is off by
    // default in Spring Boot, so a name clash fails context startup. Injection is by type.
    @Bean
    fun sqlTemplateEngine(
        registry: TemplateRegistry,
        properties: TemplatesProperties,
    ): TemplateEngine =
        TemplateEngine(
            registry = registry,
            cacheSize = properties.cacheSize,
            renderTimeoutMs = properties.renderTimeoutMs,
            // Output-size budget is Staging §8's to own; this is the engine-wide backstop against
            // pathological output. `dag` injects this engine rather than constructing it
            // (dag-executor §5.2), so it supplies the real per-execution budget per call —
            // `render(ref, context, maxOutputChars)` — not at construction. NOT a templates
            // config key (configuration.md §3.9).
            maxOutputChars = DEFAULT_MAX_OUTPUT_CHARS,
        )

    @Bean
    fun templateDryRenderer(
        engine: TemplateEngine,
        registry: TemplateRegistry,
    ): TemplateDryRenderer = TemplateDryRendererImpl(engine, registry)

    private companion object {
        /** 64M characters — a backstop against pathological output, not the real staging budget. */
        const val DEFAULT_MAX_OUTPUT_CHARS = 64L * 1024 * 1024
    }
}
