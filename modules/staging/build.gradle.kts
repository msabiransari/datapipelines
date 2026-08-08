// module-structure.md §5.5 — allowed internal deps: typesystem.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))

    implementation(libs.h2)
    // The single staging connection is serialized by an explicit Mutex
    // (staging.md §9.2), not by the driver.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
