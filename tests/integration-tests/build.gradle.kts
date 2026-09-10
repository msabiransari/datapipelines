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

    // 112 — `WorkspaceIsolationSweepTest` reflects the application's OWN registered route
    // table (`RequestMappingHandlerMapping`) so a route added tomorrow is swept without
    // anybody remembering to add it. That needs the MVC types on the test compile classpath;
    // `:modules:app` declares them `implementation`, so they do not travel transitively.
    // This is a LIBRARY, not a module dependency — `verifyModuleDependencies` still holds the
    // module rule (module-structure §4.2: integration-tests may depend on `:modules:app` alone).
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    // One container module per supported dialect that needs a real server.
    // DuckDB, SQLite and H2 are embedded (no container). There is no DB2 module —
    // DB2 is not a supported dialect (type-system.md §5 / datasources.md §4.1).
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.testcontainers.oracle.xe)
    // The 089 §F MinIO suite's bucket/upload client (bucket create + PUTs of the
    // generated parquet and the checked-in Iceberg fixture). Test-scoped; the
    // url-connection client keeps Netty off the classpath (see the toml note).
    testImplementation(libs.awssdk.s3)
    testImplementation(libs.awssdk.url.connection.client)
    testImplementation(libs.rest.assured)
    // The SSE stream's `data:` payloads are parsed as JSON; app exposes its own
    // dependencies as `implementation`, so jackson is declared explicitly here (same
    // catalog alias app's tests already use).
    testImplementation(libs.jackson.module.kotlin)
    // Konsist cross-module architecture guards (module-structure.md §7.8): they scan
    // every module's sources, so they live in the cross-module test suite.
    testImplementation(libs.konsist)

    // The 089 §F four-engine E2E registers a MYSQL datasource and the app resolves the
    // Connector/J driver BY NAME at pool build. The driver is flag-gated (-Pmysql) out of
    // the production runtime (datasources §10.2), so the test JVM adds it to the test
    // runtime explicitly — the same declared exception modules/datasources makes for its
    // own per-dialect container suites, and likewise excluded from lock state below.
    testRuntimeOnly(libs.mysql.connector.j)
}

// mysql-connector-j is on this module's test runtime WITHOUT the -Pmysql flag (see the
// dependency's note above), and the ONE committed gradle.lockfile must validate both flag
// states — modules/datasources' ignoredDependencies block is the authority for the pattern.
dependencyLocking {
    ignoredDependencies.add("com.mysql:mysql-connector-j")
}

// 025 B4: the jar smoke (JarSmokeE2eTest) boots the REAL bootJar — the packaged
// classpath, not exploded classes. The task dependency guarantees the jar exists and is
// current whenever the suite runs; the test itself fails with build instructions if the
// file is missing (never silently skips — a guard that can skip is not a guard).
tasks.named<Test>("test") {
    dependsOn(":modules:app:bootJar")
    // The whole module runs in ONE forked JVM, and Spring's context cache keeps every
    // suite's context — fifteen-odd full applications, their pools and the two-context
    // suites' second boots — alive until JVM exit (SharedE2e's KDoc states the model).
    // The default heap (a quarter of the host's RAM — 4 GB on a 16 GB box) ran OUT in
    // the full-module run: java.lang.OutOfMemoryError booting the alphabetically-late
    // suites, reproduced twice on 2026-09-08 once 089 §F added three more context-booting
    // suites (the MinIO suite and the two-context lake registry suite). A ceiling, not an
    // allocation — the JVM takes only what the cache actually needs.
    maxHeapSize = "6g"
}
