// module-structure.md §5.8 — allowed internal deps: typesystem, calculators, pipeline-contract,
// templates, datasources, dag, auth, application. `web` is NOT a dependency and must not become
// one: mcp-server is a thin adapter over the same service layer, never over HTTP.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    // 072: `calculators_list` / `calculators_get` project the registry straight onto the wire.
    implementation(project(":modules:calculators"))
    implementation(project(":modules:pipeline-contract"))
    implementation(project(":modules:templates"))
    implementation(project(":modules:datasources"))
    implementation(project(":modules:dag"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:application"))

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
    // 107 — `mcpCallAudit` builds the audit-join reader from the metadata
    // `NamedParameterJdbcTemplate`. Same `compileOnly` discipline as the servlet types above:
    // compile against it, never bundle it — `app` supplies spring-jdbc at runtime via `dag`.
    compileOnly(libs.spring.boot.starter.jdbc)

    // --- Tests -------------------------------------------------------------------
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.oauth2.client)
    testImplementation(libs.spring.boot.starter.test)
    // 139 — RealShippedTools passes the metadata jdbc the entry-point checks read through;
    // `compileOnly` above is not on the test classpath, so name it here too.
    testImplementation(libs.spring.boot.starter.jdbc)
}

// ---------------------------------------------------------------------------
// 242a — the manual is RENDERED, not copied (docs/superpowers/specs/
// 2026-09-25-agent-docs-by-area-design.md, ratified). The narrative resources
// live in src/main/resources/skill/ (packaged by the standard processResources)
// and the DocRenderer assembles the served set from them plus the catalogs at
// boot. The build-time tasks that mirrored .agents/skills/datapipelines/ — the
// skill copy, skillToolsDoc, pluginSkillCopy, skillArtifacts — are gone with
// the directory they mirrored: the plugin is a pointer now, and the per-area
// tools references are rendered from the live catalog at boot.
//
// docsExport renders the SAME set a build-time harness (see below) and writes
// it to <repo>/build/skill-docs/ for scripts/docs-audit.sh (checks A–C run
// over the exported files) and for a human who wants to read what the server
// serves without booting it.
// ---------------------------------------------------------------------------

val docsExportDir = rootProject.layout.buildDirectory.dir("skill-docs")

/**
 * The export runs the renderer outside Spring. It reuses the test source set's
 * `SkillDocsExportMain` (beside `SkillToolsDocMain`, which it replaces), which needs the real
 * tool instances with mocked collaborators — `RealShippedTools` is test-source-set machinery,
 * the SiteExportMain precedent. It ALSO needs `web`'s `ApiErrorDocCatalog` (the DocErrorCatalog
 * port's implementation, which lives with ApiErrorCatalog in `web`): the classpath below adds
 * web's classes at EXECUTION time only. No project dependency is declared — `mcp-server` does
 * not compile against `web` (module-structure §5.8); the main loads the class reflectively and
 * fails loudly when it is absent, and this task declares the dependency that guarantees it.
 */
tasks.register<JavaExec>("docsExport") {
    group = "documentation"
    description = "Renders the served document set to build/skill-docs/ for the docs audit and human reading."
    dependsOn("testClasses", ":modules:web:classes")
    mainClass.set("co.datapipelines.mcp.SkillDocsExportMainKt")
    // web's main OUTPUT only (the ApiErrorDocCatalog classes) — no configuration resolution,
    // which Gradle 9 forbids across projects at execution time. Everything the catalog's own
    // imports need (spring-web, jackson, auth) is already on this module's test classpath.
    classpath =
        sourceSets["test"].runtimeClasspath +
            project(":modules:web").sourceSets["main"].output
    doFirst {
        args(docsExportDir.get().asFile.absolutePath)
        docsExportDir.get().asFile.deleteRecursively()
        docsExportDir.get().asFile.mkdirs()
    }
}
