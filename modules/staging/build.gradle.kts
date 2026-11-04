// module-structure.md §5.5 — allowed internal deps: typesystem.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))

    implementation(libs.h2)
    // The DuckDB staging engine (staging.md §8.5): compile-time, not runtimeOnly as in datasources,
    // because the stager writes through the driver's own appender and duplicate() — no JDBC
    // interface carries either. Catalog-pinned version (module-structure.md §5.5).
    implementation(libs.duckdb.jdbc)
    // Pool admission is a coroutine Semaphore and every staging operation
    // suspends on it (staging.md §9.2); the driver serializes nothing.
    implementation(libs.kotlinx.coroutines.core)
    // SLF4J API for the cleanup-failure logging in H2Staging.close() (staging.md §3.4;
    // module-structure.md §4.3 — every module logs via the SLF4J API). BOM-managed version.
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
