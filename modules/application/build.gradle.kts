// module-structure.md §5.10 — the cross-aggregate use-case layer. Sits BELOW `web` and
// `mcp-server` and ABOVE the domain modules: a use case that needs more than one aggregate
// lives here, a single-aggregate one lives with its aggregate (056/R6).
//
// Its §4.2 row allows `templates` and `datasources` as well; they are not declared yet
// because nothing here compiles against them — slice C's import services and promotion
// orchestrator are what will add them. Declared = what is used, per §4.2.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(project(":modules:pipeline-contract"))
    implementation(project(":modules:dag"))
    implementation(project(":modules:auth"))
    // 068: DatasourceCreateService — the ONE validated registration path REST and MCP share.
    // §4.2's row for this module already allowed `datasources`; it is declared now because
    // something here finally compiles against it (declared = what is used).
    implementation(project(":modules:datasources"))

    // ExecutionLauncher binds parameters and reserves idempotency keys before the surface
    // starts anything; the reservation store and ExecuteRequest are dag types, the principal
    // is an auth type, and the parameter values are Jackson trees.
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)

    // 074 — the published-endpoint registry and key bindings are JDBC repositories over
    // `published_endpoints` / `endpoint_key_bindings`, the cross-aggregate tables this layer
    // owns (pipelines + keys + workspaces meet in them). Same reason `auth` declares it.
    implementation(libs.spring.boot.starter.jdbc)

    // DEVIATION from §5.13's external-dep list, reported to the orchestrator, and the SAME
    // deviation `auth` §5.7 already carries with the same justification: EndpointMatcher uses
    // Spring's PathPatternParser as a LIBRARY (design §4.1) — it is the URI-template matcher
    // the framework already ships and the one Spring's own mapping uses, so matching a
    // published path here and matching a route there cannot drift. Declared `compileOnly` so
    // this library compiles against those types WITHOUT bundling an embedded server; the
    // running application supplies spring-web via :modules:web (§5.9/§5.10).
    compileOnly(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
    // 074 — the endpoint services collaborate with concrete repositories and PipelineService;
    // mockk mocks final classes, which is what lets those units be tested here rather than only
    // through the web module's integration suite (where this module earns no coverage).
    testImplementation(libs.mockk)

    // 083 §D — the three JDBC classes here (PublishedEndpointRepository,
    // EndpointKeyBindingRepository, EndpointServeAudit) were covered only by the WEB module's
    // integration suite, which is why this module sat at 84.9% against an 84 floor. They get a
    // Postgres of their own: every property they carry — a transaction-scoped advisory lock, an
    // ON CONFLICT, a UNIQUE violation mapped to a catalog code, a JSONB ->> comparison — is a
    // statement about Postgres that a mocked JdbcTemplate cannot make. Same fixture shape as
    // every other module's SharedPostgres (DEVELOPMENT.md §9.1); the driver was already on this
    // module's test runtime classpath.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}
