// module-structure.md §5.2 — allowed internal deps: typesystem.
plugins { id("datapipelines.common-conventions") }

dependencies {
    // `api`, not `implementation`: the public API exposes ColumnSchema (§4.2 note).
    api(project(":modules:typesystem"))

    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.starter.jdbc) // PipelineRepository (§8.1)
}
