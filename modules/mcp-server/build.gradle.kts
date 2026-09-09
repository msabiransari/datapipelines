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
}

// ---------------------------------------------------------------------------
// 095 — the skill leaves the repo. One source (.agents/skills/datapipelines/),
// four deliveries: the connect-time handshake, the MCP resource, the HTTP URL,
// and the Claude Code plugin. Everything below serves the last three.
// ---------------------------------------------------------------------------

// The skill ships IN THE JAR, so a deployment serves the manual it actually runs
// (the same structural argument 033 made for docs/*.md in the web jar).
//
// READ THIS BEFORE MOVING IT. `web`'s processResources packages root `docs/*.md` and
// `DocsCatalog` FAILS FAST at init on any packaged doc with no declared group — a
// docs-only commit broke main on 2026-09-01 for exactly that reason. The skill is
// therefore packaged into its OWN classpath directory, `skill/`, never under `docs/`:
// `DocsCatalog`'s `classpath*:docs/*.md` scan cannot see it, and no grouping decision
// is owed. `SkillPackagingTest` asserts the packaged bytes equal the repo file for
// SKILL.md and every reference.
//
// It is packaged HERE and not in `web` because `mcp-server` is the module that must
// read it (the `datapipelines://docs/skill` resource) and `mcp-server` must not depend
// on `web` (§5.8). `web` depends on `mcp-server`, so one packaging block serves both
// surfaces; two would be two copies to drift apart.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir(".agents/skills/datapipelines")) {
        into("skill")
        include("SKILL.md", "references/*.md")
    }
}

/** The skill directory in the repo — the ONE source every delivery copies from. */
val skillSource = rootProject.layout.projectDirectory.dir(".agents/skills/datapipelines")

// §B — references/tools.md is RENDERED from McpToolCatalog + each tool's definition,
// never typed. See SkillToolsDoc's KDoc for why (three hand-typed tool counts were
// stale simultaneously). The committed file is this task's output — committed so a raw
// checkout and the plugin are complete without running a build — and
// SkillToolsDocDriftTest fails when the two disagree.
tasks.register<JavaExec>("skillToolsDoc") {
    group = "documentation"
    description = "Renders .agents/skills/datapipelines/references/tools.md from the shipped MCP tool catalog."
    dependsOn("testClasses")
    mainClass.set("co.datapipelines.mcp.SkillToolsDocMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    // Resolved in doFirst: a Provider passed through vararg `args` stringifies instead
    // of unwrapping (the same footgun web's websiteExport documents).
    val out = rootProject.layout.projectDirectory.file(".agents/skills/datapipelines/references/tools.md")
    doFirst { args(out.asFile.absolutePath) }
}

// §C4 — the Claude Code plugin's skill directory is a COPY, not a symlink: a
// marketplace is fetched with git, and a symlink pointing out of the plugin directory
// is a broken install (the plugin docs' own rule — "outside marketplace: skipped").
// PluginSkillCopyDriftTest fails when the copy is not byte-identical to the source.
tasks.register<Copy>("pluginSkillCopy") {
    group = "documentation"
    description = "Copies the skill into plugins/datapipelines/skills/datapipelines (the Claude Code plugin)."
    dependsOn("skillToolsDoc")
    val target = rootProject.layout.projectDirectory.dir("plugins/datapipelines/skills/datapipelines")
    // Wipe first. A Copy ADDS and never removes, so a file deleted from the skill would
    // survive in the plugin forever — and the drift test would still pass, because it
    // compares the files that exist on both sides (measured, 2026-09-04, 073 §F).
    doFirst { target.asFile.deleteRecursively() }
    from(skillSource) { include("SKILL.md", "references/*.md") }
    into(target)
}

/** Regenerates every derived copy of the skill — run this after editing SKILL.md. */
tasks.register("skillArtifacts") {
    group = "documentation"
    description = "Renders references/tools.md and refreshes the plugin's copy of the skill."
    dependsOn("skillToolsDoc", "pluginSkillCopy")
}
