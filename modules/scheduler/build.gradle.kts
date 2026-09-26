// module-structure.md §5.16 — allowed internal deps: typesystem, pipeline-contract (the published
// name grammar ONLY; SchedulerBoundaryTest refuses any other co.datapipelines.pipeline import).
plugins { id("datapipelines.common-conventions") }

dependencies {
    // DatapipelinesException, the one exception base (§4.3), and the Jackson stack.
    implementation(project(":modules:typesystem"))
    // PipelineNameGrammar — a schedule name is spelled exactly like a pipeline's (scheduler design
    // revision §6, A2). Nothing else from this module may be used; the boundary test says so.
    implementation(project(":modules:pipeline-contract"))

    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.starter.jdbc) // the schedule repositories (§8.1) and TransactionTemplate
    // The durable queue (record §2.2). The starter brings db-scheduler core and spring-common; its
    // auto-configuration builds the Scheduler bean over the metadata DataSource wrapped in
    // TransactionAwareDataSourceProxy, which is what lets an enqueue join our transaction
    // (record §2.1, spike 1). db-scheduler types stay inside this module (record §6.2).
    implementation(libs.db.scheduler.spring.boot.starter)
    // The scheduler's counters and gauge (observability.md §4). Micrometer-core only — `app`
    // supplies the exporting registry, and SchedulerMetrics falls back to an in-memory one.
    implementation(libs.micrometer.core)

    // The scheduler's integration suites run against a real Postgres with the shipped migrations
    // applied through plain JDBC (§7.4; Flyway stays in `app`, §3.1 rule 2).
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}
