package co.datapipelines

import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

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
// TODO(P7 app wiring — module-structure.md §5.7 / task #10): remove this exclude AND the
//  three autoconfig excludes above together, then wire auth for real. The `auth` module is
//  merged and independently tested (150 tests), but its Spring config is NOT scanned by the
//  app yet: `OidcConfig` requires `datapipelines.auth.base-url` and builds real
//  ClientRegistrations (OIDC discovery) from the google/microsoft providers in
//  application.yml, so scanning it fails the smoke test's context load. Wiring auth into the
//  running app needs a test OIDC provider (a Keycloak Testcontainer or a stub
//  ClientRegistrationRepository) + base-url, plus support for a provider-less (API-key-only)
//  deployment — that is P7 integration work, not an interim auth scheme. While this exclude is
//  in place the application has NO authentication of any kind (same as the P0 scaffold state).
@ComponentScan(
    basePackages = ["co.datapipelines"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["co\\.datapipelines\\.auth\\..*"]),
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
