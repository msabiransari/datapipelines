// module-structure.md §5.18 — the parameter engine (#194; parameter-engine design record §2.2).
// Allowed internal deps (§4.2): typesystem, graph, pipeline-contract, templates, datasources — all five
// declared since lane C: the selector runtime renders through `templates` and runs through `datasources`.
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
    // The selector runtime (record §6.3, lane C): SelectorRunner renders a pinned template through the
    // workspace's TemplateEngine (WorkspaceTemplateEngines — the render guards, the per-workspace caches)…
    implementation(project(":modules:templates"))
    // …and runs it through the datasource registry's pools: the workspace-visible live read (P31), the
    // ReadOnlyStatementLease with its gate and its discard handle (record §2.5), ResultRowReader's canonical
    // decoding and schemaOf (P11 — types from metadata).
    implementation(project(":modules:datasources"))

    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.starter.jdbc) // ParameterSetRepository (§8.1), @ConfigurationProperties
    // The evaluator (record P16, §5.2 step 3): one coroutine per parameter awaiting its parents, the
    // evaluate's deadline a withTimeout on the AWAIT, and the SelectorPool's suspending slot wait.
    // BOM-managed (the version `dag` already resolves).
    implementation(libs.kotlinx.coroutines.core)

    // The container suite runs the repository and the lifecycle against a real Postgres with the SHIPPED
    // migrations applied through plain JDBC (module-structure §7.4; Flyway stays in `app`, §3.1 rule 2),
    // pins real `templates` rows (TemplateRepository) for the release cascade and the pin checks, and —
    // since lane C — is also the CUSTOMER datasource the real SelectorRunner probes and evaluates against.
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}

// SampleDataParameterSetsContentTest reads repo files at RUNTIME — invisible to Gradle's up-to-date
// checking, so a stale test result would survive an examples.json edit (the silence 049 named for the
// templates twin of this suite). Declaring them as test inputs makes the guard re-run when the content
// it guards changes; the exit gate's --rerun-tasks remains the belt to this braces.
tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file("scripts/sample-data/content/examples.json"))
    inputs.file(rootProject.layout.projectDirectory.file("deploy/sample-data/bootstrap-datasources-nyc.yml"))
}
