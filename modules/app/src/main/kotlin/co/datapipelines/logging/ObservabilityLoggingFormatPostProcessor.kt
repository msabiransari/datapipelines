package co.datapipelines.logging

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Binds `datapipelines.observability.logging.format` BEFORE logging initialises (#337, D2;
 * observability.md §3.1/§3.5, configuration.md §3.15).
 *
 * An [EnvironmentPostProcessor] is Boot's native point: the post-processors run on
 * `ApplicationEnvironmentPreparedEvent` inside the `EnvironmentPostProcessorApplicationListener`
 * (order `HIGHEST_PRECEDENCE + 10`), one step before the `LoggingApplicationListener`
 * (`HIGHEST_PRECEDENCE + 20`) configures the console encoder — verified against the 3.5.16 jar's
 * listener orders, not recalled. Registered last among post-processors (no order declared), so
 * `application.yml`, the posture files and the environment are already resolved when the switch is
 * read.
 *
 * Two things happen here. The value is VALIDATED against the closed set — `json` (the §3.1
 * structured line) or `console` (the human-readable development format) — and an unknown value
 * refuses startup before any logging configuration exists, with the offending value named. And for
 * `json` the structured encoder's redaction wiring is added to the environment
 * (`logging.structured.json.*`: the redacting customizer, the redacting stack-trace printer and the
 * §3.1 field-name renames) together with `spring.main.banner-mode=off` (stdout must be records
 * only), which `FormatSwitchingEncoder`'s structured delegate reads through the
 * logger context. The console side needs no property: the encoder carries its own redacting
 * pattern, because Boot's `logging.pattern.console` route mangles any pattern value containing
 * regex braces or colons (logback's variable parser consumes it as a `${...}` default).
 *
 * `logback-spring.xml` resolves the same switch key for the encoder via `springProperty`, so this
 * post-processor's job is exactly: refuse what is not one of the two values, and supply the
 * structured side's wiring.
 */
class ObservabilityLoggingFormatPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication?,
    ) {
        if (environment.propertySources.contains(PROPERTY_SOURCE_NAME)) return
        val format = environment.getProperty(SWITCH_KEY)?.trim()?.lowercase() ?: DEFAULT_FORMAT
        val mapped = structuredProperties(format)
        if (mapped.isNotEmpty()) {
            environment.propertySources.addFirst(MapPropertySource(PROPERTY_SOURCE_NAME, mapped))
        }
    }

    companion object {
        const val SWITCH_KEY: String = "datapipelines.observability.logging.format"

        /** configuration.md §3.15's documented default — the YAML carries the same value. */
        const val DEFAULT_FORMAT: String = "json"

        const val PROPERTY_SOURCE_NAME: String = "datapipelinesStructuredLogging"

        /**
         * The switch's whole effect, as environment properties. Pure so the packaged-output proof
         * can apply the SAME mapping in a child JVM without booting Spring, and so an unknown
         * value fails here — the entry point — rather than as a cryptic binding error later.
         */
        fun structuredProperties(format: String): Map<String, String> =
            when (format) {
                "json" -> {
                    mapOf(
                        "logging.structured.json.customizer" to
                            "co.datapipelines.logging.RedactingJsonMembersCustomizer",
                        "logging.structured.json.stacktrace.printer" to
                            "co.datapipelines.logging.RedactingStackTracePrinter",
                        "logging.structured.json.rename.logger_name" to "logger",
                        "logging.structured.json.rename.thread_name" to "thread",
                        // Boot prints its banner to stdout BEFORE logging exists; under json that
                        // stdout is a stream of records, so the banner would be the one non-JSON
                        // residue per boot (#337-b). Bound after this post-processor runs, so it
                        // wins over an operator's spring.main.banner-mode too — deliberately: a
                        // banner in a machine-readable stream is a defect, not a preference.
                        "spring.main.banner-mode" to "off",
                    )
                }

                "console" -> {
                    emptyMap()
                }

                else -> {
                    error(
                        "$SWITCH_KEY: unknown value '$format' — expected 'json' or 'console'; refusing to start",
                    )
                }
            }
    }
}
