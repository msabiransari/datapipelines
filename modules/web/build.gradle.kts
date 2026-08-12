// module-structure.md §5.9 — the aggregation layer. Lists every module it touches
// EXPLICITLY (§4.2): it could reach most of them transitively through mcp-server,
// and declaring them is what makes the table checkable.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(project(":modules:pipeline-contract"))
    implementation(project(":modules:templates"))
    implementation(project(":modules:datasources"))
    implementation(project(":modules:staging"))
    implementation(project(":modules:dag"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:mcp-server"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.validation)
    // Redis: post-completion SSE event log (1h) + per-user rate-limit counters.
    // The durable 7-day record is dag's ExecutionEventRepository (D9).
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.micrometer.core)
    implementation(libs.jackson.module.kotlin)

    // DEVIATION from §5.9's external-dep list, following the precedent `auth` and
    // `mcp-server` set, and reported to the orchestrator. All three are existing catalog
    // aliases — no new artifact enters the build:
    //  - oauth2-client: web reads the principal `auth`'s filters put in
    //    `SecurityContextHolder` and registers its own servlet filters ahead of the
    //    Spring Security chain, so `spring-security-core`/`-web` are COMPILE-time types
    //    here, not merely runtime ones. `auth` declares this starter `implementation`,
    //    which puts it on web's runtime classpath but not its compile classpath.
    //  - coroutines: `PipelineExecutor.execute` and `EventEmitter.emit` are `suspend`;
    //    the SSE surface drives them from a servlet thread.
    //  - slf4j: request/SSE diagnostics (observability §3.4 — every module logs via the API).
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    runtimeOnly(libs.webjars.bootstrap)
    runtimeOnly(libs.webjars.htmx)

    // --- Tests -------------------------------------------------------------------
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // Module-local integration tests: a Postgres container running app's real V1
    // migration (executions + execution_events) and a Redis container for the SSE event
    // log, the result cursor and the rate-limit counters. Same aliases `dag` and `auth`
    // already use; `testcontainers` (core) is the GenericContainer Redis rides on.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    // Real forward-only cursors for the result-store round trip (the fixture dag's own
    // RedisResultStoreIntegrationTest uses); the alias already exists in the catalog.
    testImplementation(libs.h2)
    // The integration test drives dag's @Repository classes against a Postgres container
    // directly; spring-jdbc is their implementation detail, so tests need it explicitly.
    testImplementation(libs.spring.boot.starter.jdbc)
}
