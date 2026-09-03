// module-structure.md §5.12 — allowed internal deps: app (full context, end-to-end).
// Separately invoked via the root `browserTest` task; NOT part of build/check.
plugins { id("datapipelines.common-conventions") }

dependencies {
    testImplementation(project(":modules:app"))

    // Seeding the local admin needs a real Argon2id hash of the known password —
    // same declared-exception rationale as tests/integration-tests (§5.11's note).
    testImplementation(libs.argon2.jvm)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.playwright)
}
