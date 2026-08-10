package co.datapipelines.auth

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Minimal Spring Boot application for the OIDC integration test. Component scanning is
 * rooted at `co.datapipelines.auth`, so it wires the real [SecurityConfig], filters,
 * [OidcConfig], services and repositories against auto-configured Spring MVC, Security
 * and JDBC — the same beans the production app assembles, exercised end to end.
 */
@SpringBootApplication
class AuthTestApplication
