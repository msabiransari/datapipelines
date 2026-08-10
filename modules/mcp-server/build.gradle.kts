// module-structure.md §5.8 — allowed internal deps: typesystem, pipeline-contract,
// templates, datasources, dag, auth. `web` is NOT a dependency and must not become
// one: mcp-server is a thin adapter over the same service layer, never over HTTP.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(project(":modules:pipeline-contract"))
    implementation(project(":modules:templates"))
    implementation(project(":modules:datasources"))
    implementation(project(":modules:dag"))
    implementation(project(":modules:auth"))

    // GATE G1 CLOSED 2026-08-07 — official Java MCP SDK. mcp-core carries the
    // Streamable HTTP servlet transport (HttpServletStreamableServerTransportProvider),
    // so no Spring-specific SDK artifact is required. Rationale + the
    // jackson2-not-jackson3 choice are documented in gradle/libs.versions.toml.
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)

    // Declared explicitly rather than leaned on transitively, per module-structure §4.2
    // ("everything used at compile time is listed"). Same catalog aliases every other
    // module already uses — no new artifact enters the build.
    //  - jackson: tool payloads and the pipeline/template bodies this module re-emits.
    //  - slf4j: transport + dispatch diagnostics (§4.3).
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)
    // pipelines_execute drives the coroutine executor from a blocking servlet thread
    // (mcp-server.md §6.2.3 — the tool call IS the wait), so `runBlocking` is needed.
    implementation(libs.kotlinx.coroutines.core)

    // DEVIATION from §5.8's external-dep list, following the precedent `auth` set and
    // reported to the orchestrator: the §5.8 public API this module must provide —
    // `McpServer` Spring Boot autoconfiguration, `McpAuthFilter` — compiles against
    // the Jakarta Servlet API, Spring Boot's servlet registration beans and Spring
    // Security's `SecurityContextHolder` (the filter reads the principal auth's
    // ApiKeyFilter already established, so `/mcp` has NO second key-validation path —
    // mcp-server.md §13). `compileOnly` so this library compiles against those types
    // without bundling an embedded server; `app`/`web` supply them at runtime (§5.9).
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.boot.starter.oauth2.client)

    // --- Tests -------------------------------------------------------------------
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.oauth2.client)
    testImplementation(libs.spring.boot.starter.test)
}
