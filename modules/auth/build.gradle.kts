// module-structure.md §5.7 — allowed internal deps: typesystem (shared exception base only).
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))

    implementation(libs.argon2.jvm)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    // Spring Security web/config + OAuth2 client + Jose (auth.md §12.2).
    implementation(libs.spring.boot.starter.oauth2.client)
    // No BouncyCastle (removed 2026-08-07, security review MEDIUM-6 — see §5.7).
    // Argon2id comes from argon2-jvm; JWT signing from jjwt over the JDK providers.
    implementation(libs.spring.boot.starter.jdbc) // user / key / audit repositories

    // DEVIATION from §5.7's external-dep list (reported to orchestrator): the §8.1
    // SecurityConfig / ScopeInterceptor code the spec mandates compiles against Spring
    // MVC (WebMvcConfigurer, HandlerInterceptor) and the Jakarta Servlet API, which the
    // oauth2-client starter does not bring. Declared `compileOnly` so this library
    // compiles against those types WITHOUT bundling an embedded server — the running
    // application supplies Spring MVC + Tomcat at runtime via :modules:web (§5.9/§5.10).
    compileOnly(libs.spring.boot.starter.web)

    // --- Tests -------------------------------------------------------------------
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    // Postgres driver for the repository + OIDC integration Testcontainers.
    testImplementation(libs.postgresql)
}

// The OIDC integration test drives the browser code-flow with java.net.http and manages
// cookies by hand (the authorization-request + dp_session cookies are Secure, which a
// CookieHandler will not resend over the test's plain-HTTP hops). Setting the `Cookie`
// header directly requires opting it out of the JDK's restricted-header list.
tasks.named<Test>("test") {
    systemProperty("jdk.httpclient.allowRestrictedHeaders", "cookie")
}
