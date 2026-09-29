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
    implementation(libs.spring.boot.starter.jdbc) // the two repositories (§8.1), @ConfigurationProperties

    // The container suite runs the repositories and the lifecycle against a real Postgres with the SHIPPED
    // migrations applied through plain JDBC (module-structure §7.4; Flyway stays in `app`, §3.1 rule 2).
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}
