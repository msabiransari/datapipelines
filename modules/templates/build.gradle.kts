// module-structure.md §5.3 — allowed internal deps: typesystem, pipeline-contract.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(project(":modules:pipeline-contract")) // Parameter shape

    // Declared explicitly rather than leaned on transitively through typesystem's `api`:
    // module-structure §4.2 requires everything used at compile time to be listed
    // (TemplateJson binds the §3.1 wire shape with KotlinModule). Same catalog alias and
    // BOM-managed version pipeline-contract declares — no new artifact enters the build.
    implementation(libs.jackson.module.kotlin)
    implementation(libs.freemarker)
    implementation(libs.spring.boot.starter.jdbc) // TemplateRepository (§8.1)

    // TemplateRepositoryIntegrationTest runs against a real Postgres (§7.4: a module-local
    // integration test lives in this module's own src/test/kotlin, named *IntegrationTest).
    // Flyway is deliberately ABSENT: §3.1 rule 2 keeps it and the migration scripts in `app`.
    // The test executes app's real V1__initial_schema.sql through plain JDBC — same pattern as
    // pipeline-contract's PipelineRepositoryIntegrationTest.
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}
