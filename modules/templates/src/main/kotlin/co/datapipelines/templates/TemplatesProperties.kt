package co.datapipelines.templates

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Typed configuration for the templates module (module-structure §8.3).
 *
 * Key names, defaults and env-var bindings are defined **once** in configuration.md §3.9 (D8);
 * the defaults here mirror that authority and exist for the code path, not as a second
 * definition. There is deliberately no output-size key here: that budget is owned by Staging §8
 * and injected into [TemplateEngine] rather than restated as a templates key
 * ([TemplatesConfiguration]).
 */
@ConfigurationProperties(prefix = "datapipelines.templates")
data class TemplatesProperties(
    /** Parsed-template cache entries (`datapipelines.templates.cache-size`). */
    val cacheSize: Int = 500,
    /** Hard wall-clock limit on a single render, ms (`datapipelines.templates.render-timeout-ms`). */
    val renderTimeoutMs: Long = 5000,
    /**
     * Max `body` length accepted at save (`datapipelines.templates.max-body-chars`). An over-cap
     * body is rejected with `template.validation.syntax_error` **before** it is parsed
     * (templates.md §4.2), which is what bounds the parse cost and heap an adversarial save can
     * command.
     */
    val maxBodyChars: Int = TemplateValidator.DEFAULT_MAX_BODY_CHARS,
)
