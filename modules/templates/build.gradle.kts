// module-structure.md §5.3 — allowed internal deps: typesystem, pipeline-contract.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(project(":modules:pipeline-contract")) // Parameter shape

    implementation(libs.freemarker)
    implementation(libs.spring.boot.starter.jdbc) // TemplateRepository (§8.1)
}
