// module-structure.md §5.2 — allowed internal deps: typesystem.
plugins { id("datapipelines.common-conventions") }

dependencies {
    // `api`, not `implementation`: the public API exposes ColumnSchema (§4.2 note).
    api(project(":modules:typesystem"))

    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.starter.jdbc) // PipelineRepository (§8.1)

    // PipelineRepositoryIntegrationTest runs against a real Postgres (§7.4: a module-local
    // integration test lives in this module's own src/test/kotlin, named *IntegrationTest).
    // Flyway is deliberately ABSENT: §3.1 rule 2 puts the Flyway dependency and every
    // migration script in `app` alone. The test executes app's real V1__initial_schema.sql
    // through plain JDBC instead, so it still runs the shipped schema — see the test's KDoc.
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}
