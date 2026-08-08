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

    testImplementation(libs.kotlinx.coroutines.test)
}
