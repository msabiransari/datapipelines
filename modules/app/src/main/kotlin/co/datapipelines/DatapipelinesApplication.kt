package co.datapipelines

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Application entry point — main class `co.datapipelines.DatapipelinesApplicationKt`
 * (DEVELOPMENT.md §6). Component scanning is rooted at `co.datapipelines`, so every
 * module's beans are discovered from this package: `auth`'s `SecurityConfig` /
 * `OidcConfig` (auth.md §8, §5.2), `web`'s engine/surface configuration
 * (module-structure.md §5.9), `templates`' and the repositories' stereotype beans, and
 * `mcp-server`'s autoconfiguration (dormant unless the engine beans exist — it is
 * `@ConditionalOnBean(PipelineExecutor)`).
 *
 * P7 removed the P0/P6a scaffold excludes (the three Spring Security autoconfig
 * excludes and the `co.datapipelines.(auth|web)` scan filter): the real security
 * chain from `auth` is the only chain now, so Boot's default chain must no longer be
 * kept out of the way. `OAuth2ClientAutoConfiguration` backs off on its own because
 * `OidcConfig` publishes the `ClientRegistrationRepository` bean.
 */
@SpringBootApplication
class DatapipelinesApplication

// SpreadOperator is suppressed rather than disabled globally: the rule's concern is
// the defensive array copy, which is real in hot paths but meaningless for a handful
// of command-line arguments copied exactly once at startup. `runApplication(*args)`
// is the canonical Spring Boot Kotlin entry point.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<DatapipelinesApplication>(*args)
}
