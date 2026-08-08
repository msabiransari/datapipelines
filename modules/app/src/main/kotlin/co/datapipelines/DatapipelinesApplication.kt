package co.datapipelines

import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication

/**
 * Application entry point — main class `co.datapipelines.DatapipelinesApplicationKt`
 * (DEVELOPMENT.md §6). Component scanning is rooted at `co.datapipelines`, so every
 * module's beans are discovered from this package.
 *
 * TODO(auth module — module-structure.md §5.7): remove all three `exclude` entries
 *  below. `spring-boot-starter-oauth2-client` reaches this application's runtime
 *  classpath transitively through `web` → `auth`, so Spring Security's default
 *  autoconfiguration would otherwise lock every endpoint behind generated HTTP
 *  Basic credentials before the real chain exists. The real chain — generic OIDC
 *  login, the JWT filter, the API-key filter, and the scope interceptor — is
 *  specified in auth.md §8 and lands with `SecurityConfig` in the `auth` module.
 *  This exclusion is a P0 scaffold measure, NOT an interim auth scheme: while it
 *  is in place the application has NO authentication of any kind. It must be
 *  removed in the same change that introduces `SecurityConfig`.
 *
 *  `OAuth2ClientAutoConfiguration` is deliberately NOT excluded: it backs off on
 *  its own while no `spring.security.oauth2.client.registration.*` properties are
 *  set, and the servlet variant is deprecated in Spring Boot 3.5 (referencing it
 *  would fail the build under `allWarningsAsErrors`).
 */
@SpringBootApplication(
    exclude = [
        SecurityAutoConfiguration::class,
        UserDetailsServiceAutoConfiguration::class,
        // Actuator's management chain injects the HttpSecurity that
        // SecurityAutoConfiguration would have contributed; excluding one without
        // the other fails context startup outright.
        ManagementWebSecurityAutoConfiguration::class,
    ],
)
class DatapipelinesApplication

// SpreadOperator is suppressed rather than disabled globally: the rule's concern is
// the defensive array copy, which is real in hot paths but meaningless for a handful
// of command-line arguments copied exactly once at startup. `runApplication(*args)`
// is the canonical Spring Boot Kotlin entry point.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<DatapipelinesApplication>(*args)
}
