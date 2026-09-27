// module-structure.md §5.18 — the parameter engine (#194; parameter-engine design record §2.2).
// Allowed internal deps (§4.2): typesystem, graph, pipeline-contract, templates, datasources. Declared
// here only what compiles: the selector runtime (lane C) adds templates/datasources when it renders and
// runs through them, never before.
plugins { id("datapipelines.common-conventions") }

dependencies {
    // ParameterDeclaration / ParameterValueValidator / ParameterCoercion (the ONE value judge, record
    // P28), LogicalType, ColumnSchema, DatapipelinesException.
    implementation(project(":modules:typesystem"))
    // TemplateRef (the pin shape, record §3.4), PipelineNameGrammar (the set name, §3.1), the parameter
    // name grammar (§3.2), ReadLens, AuthoringGuard, ValidationFailure, and the ports the save-time
    // validator calls — TemplateDryRenderer, DatasourceRegistry, TemplateVersionStatuses,
    // TemplateReleaser (record §14 item 3: this module compiles against pipeline-contract).
    implementation(project(":modules:pipeline-contract"))
    // Dag<T> — the set's dependency graph (record P16): cycle refusal at save, topological order for the
    // dry run and (lane C) the evaluator. Same package as the executor's, `co.datapipelines.dag`.
    implementation(project(":modules:graph"))

    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.starter.jdbc) // ParameterSetRepository (§8.1), @ConfigurationProperties

    // The container suite runs the repository and the lifecycle against a real Postgres with the SHIPPED
    // migrations applied through plain JDBC (module-structure §7.4; Flyway stays in `app`, §3.1 rule 2),
    // and pins real `templates` rows (TemplateRepository) for the release cascade and the pin checks —
    // the `templates` edge is allowed by §4.2 and used by TESTS only; main never compiles against it.
    testImplementation(project(":modules:templates"))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}
