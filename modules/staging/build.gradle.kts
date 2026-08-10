// module-structure.md §5.5 — allowed internal deps: typesystem.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))

    implementation(libs.h2)
    // The single staging connection is serialized by an explicit Mutex
    // (staging.md §9.2), not by the driver.
    implementation(libs.kotlinx.coroutines.core)
    // SLF4J API for the cleanup-failure logging in H2Staging.close() (staging.md §3.4;
    // module-structure.md §4.3 — every module logs via the SLF4J API). BOM-managed version.
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
