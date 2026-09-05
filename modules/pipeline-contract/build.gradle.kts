// module-structure.md §5.2 — allowed internal deps: typesystem, calculators.
plugins { id("datapipelines.common-conventions") }

dependencies {
    // `api`, not `implementation`: the public API exposes ColumnSchema (§4.2 note).
    api(project(":modules:typesystem"))

    // 072 §12.10: the validator asks the registry whether a `kind` exists and what its inputs
    // are. `api`, not `implementation`, for the same reason as typesystem above: `CalculatorKind`
    // and `CalculatorInput` appear in this module's public surface (the MCP catalog tools read
    // them through it), and the direction is one-way by design — `calculators` knows nothing
    // about pipelines, JSON literals or error codes.
    api(project(":modules:calculators"))

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
