// module-structure.md §5.6 — allowed internal deps: typesystem, pipeline-contract,
// templates, datasources, staging. Deliberately NOT auth: the executor is handed
// an already-authenticated principal by its caller (§4.2 note).
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(project(":modules:pipeline-contract"))
    implementation(project(":modules:templates"))
    implementation(project(":modules:datasources"))
    implementation(project(":modules:staging"))

    implementation(libs.kotlinx.coroutines.core)
    // Redis: result store, idempotency keys, cancellation flags. One of exactly
    // two modules allowed to depend on it (§3.1 rule 3). Lettuce is the starter default.
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.jdbc) // Execution/ExecutionEvent repositories
    implementation(libs.micrometer.core)          // executor metrics (dag-executor.md §15.3)

    // Declared explicitly rather than leaned on transitively (module-structure §4.2 requires
    // every compile-time dependency to be listed), following the precedent `templates` set:
    // same catalog alias, same BOM-managed version, no new artifact enters the build.
    //  - jackson: result-store payloads and the four JSONB columns this module writes
    //    (parameters_json, error_json, node_stats_json, payload_json). ColumnSchema and friends
    //    are Kotlin data classes with no @JsonCreator, so the Kotlin module is required, not
    //    convenient — nothing can reconstruct a stored schema without it.
    //  - slf4j: cancellation/result-store diagnostics (§4.3 — every module logs via the API).
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    // H2 backs both the real per-execution tempdb and the stand-in "source database" in unit
    // tests, so the executor is exercised against a real driver rather than a mocked Connection.
    testImplementation(libs.h2)
    // Module-local integration tests (§7.4 — *IntegrationTest in this module's own src/test):
    // a Redis container for the result store / idempotency / cancel flags (D9, D7) and a
    // Postgres container running app's real V1 migration for the two repositories.
    // `testcontainers` (core) is the GenericContainer Redis rides on — there is no
    // org.testcontainers:redis module; same catalog alias `auth` already uses, no new artifact.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}
