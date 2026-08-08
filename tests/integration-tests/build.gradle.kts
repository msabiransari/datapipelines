// module-structure.md §5.11 — allowed internal deps: app (full context, end-to-end).
plugins { id("datapipelines.common-conventions") }

dependencies {
    testImplementation(project(":modules:app"))

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    // One container module per supported dialect that needs a real server.
    // DuckDB, SQLite and H2 are embedded (no container). There is no DB2 module —
    // DB2 is not a supported dialect (type-system.md §5 / datasources.md §4.1).
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.testcontainers.oracle.xe)
    testImplementation(libs.rest.assured)
}
