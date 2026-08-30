// module-structure.md §5.11 — allowed internal deps: app (full context, end-to-end).
plugins { id("datapipelines.common-conventions") }

dependencies {
    testImplementation(project(":modules:app"))

    // The tracer-bullet E2E seeds API keys via SQL, and the stored hash must be a real
    // Argon2id of the full key (auth.md §7.2). The P7 brief asked for auth's
    // `Argon2SecretHasher` used as a plain class — but module-structure §4.2 allows
    // integration-tests ONLY `:modules:app`, and `verifyModuleDependencies` enforces
    // that mechanically, so the hash is computed with the same pinned library and the
    // same parameters (2 / 19 456 / 1, see SecretHasher.kt) instead. Reported to the
    // orchestrator. argon2-jvm's encoded hash is self-describing, so auth's bounded
    // bean verifies it unchanged; no literal hash enters a fixture (HIGH-2).
    testImplementation(libs.argon2.jvm)

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
    // The SSE stream's `data:` payloads are parsed as JSON; app exposes its own
    // dependencies as `implementation`, so jackson is declared explicitly here (same
    // catalog alias app's tests already use).
    testImplementation(libs.jackson.module.kotlin)
    // Konsist cross-module architecture guards (module-structure.md §7.8): they scan
    // every module's sources, so they live in the cross-module test suite.
    testImplementation(libs.konsist)
}

// 025 B4: the jar smoke (JarSmokeE2eTest) boots the REAL bootJar — the packaged
// classpath, not exploded classes. The task dependency guarantees the jar exists and is
// current whenever the suite runs; the test itself fails with build instructions if the
// file is missing (never silently skips — a guard that can skip is not a guard).
tasks.named("test") {
    dependsOn(":modules:app:bootJar")
}
