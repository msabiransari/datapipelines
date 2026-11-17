// module-structure.md §5.20 — visualizations and dashboards (#10; the dashboard implementation spec §1,
// the design record D29). Allowed internal deps (§4.2): typesystem, pipeline-contract, templates, parameters.
// `templates` is allowed and NOT declared: every template fact this module needs arrives through
// pipeline-contract's ports (TemplateDryRenderer.lookup/transformContract, TemplateVersionStatuses,
// TemplateReleaser), which `templates` implements — the house "allowed ahead, undeclared" rule.
plugins { id("datapipelines.common-conventions") }

dependencies {
    // LogicalType (an input contract's column vocabulary), DatapipelinesException.
    implementation(project(":modules:typesystem"))
    // PipelineNameGrammar (the artifact names), ReadLens, AuthoringGuard, WriteSurface, CreateLifecycle,
    // PipelineVersionStatus, ValidationFailure/Result, TemplateRef, TransformContractView and the template
    // ports the validator and the release cascade go through.
    implementation(project(":modules:pipeline-contract"))
    // ParameterSetRepository — the production ParameterSetFacts reads a pinned set release's parameter
    // names and their dependents from it (spec §1: "the parameter-set repository for the set pin").
    implementation(project(":modules:parameters"))

    implementation(libs.jackson.module.kotlin)
    // The chart document v1's JSON Schema (draft 2020-12) at save — the schemas load from this module's
    // classpath only; the registry never fetches (ChartSchemaValidator).
    implementation(libs.json.schema.validator)
    // Jackson stays at the product's pin (#860): networknt 2.0.8's POM asks for Jackson 2.22.1 and its Jackson
    // modules import jackson-bom 2.22.1, which wins over the conventions' platform(jackson-bom 2.21.7) under
    // default resolution — the lake module's precedent (modules/lake/build.gradle.kts, Iceberg's 2.22.2), same
    // mechanism. Enforced at the only declaration that brings 2.0.8, so every consumer (mcp-server, web, app, the
    // test projects) resolves the same Jackson, the MCP SDK's own edge to the validator included.
    implementation(enforcedPlatform(libs.jackson.bom))
    testImplementation(enforcedPlatform(libs.jackson.bom))
    implementation(libs.spring.boot.starter.jdbc) // the two repositories (§8.1), @ConfigurationProperties

    // The container suite runs the repositories and the lifecycle against a real Postgres with the SHIPPED
    // migrations applied through plain JDBC (module-structure §7.4; Flyway stays in `app`, §3.1 rule 2).
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}
