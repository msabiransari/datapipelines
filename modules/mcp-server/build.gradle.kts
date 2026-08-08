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
}
