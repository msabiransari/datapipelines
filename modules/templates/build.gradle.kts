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

// SampleDataExamplesContentTest reads repo files at RUNTIME — invisible to Gradle's
// up-to-date checking, so a stale test result would survive an examples.json edit and an
// ordinary `./gradlew build` would report green on the old verdict (049: the exact silence
// that let the published artifact drift). Declaring them as test inputs makes the guard
// re-run when the content it guards changes; the exit gate's --rerun-tasks remains the
// belt to this braces. One examples file and one bootstrap yml per sample-data family.
tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file("scripts/sample-data/content/examples.json"))
    inputs.file(rootProject.layout.projectDirectory.file("scripts/sample-data-trade/content/examples.json"))
    inputs.file(rootProject.layout.projectDirectory.file("deploy/sample-data/bootstrap-datasources-nyc.yml"))
    inputs.file(rootProject.layout.projectDirectory.file("deploy/sample-data/bootstrap-datasources-census.yml"))
}
