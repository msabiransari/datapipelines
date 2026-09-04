// module-structure.md §5.10 — the cross-aggregate use-case layer. Sits BELOW `web` and
// `mcp-server` and ABOVE the domain modules: a use case that needs more than one aggregate
// lives here, a single-aggregate one lives with its aggregate (056/R6).
//
// Its §4.2 row allows `templates` and `datasources` as well; they are not declared yet
// because nothing here compiles against them — slice C's import services and promotion
// orchestrator are what will add them. Declared = what is used, per §4.2.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(project(":modules:pipeline-contract"))
    implementation(project(":modules:dag"))
    implementation(project(":modules:auth"))
    // 068: DatasourceCreateService — the ONE validated registration path REST and MCP share.
    // §4.2's row for this module already allowed `datasources`; it is declared now because
    // something here finally compiles against it (declared = what is used).
    implementation(project(":modules:datasources"))

    // ExecutionLauncher binds parameters and reserves idempotency keys before the surface
    // starts anything; the reservation store and ExecuteRequest are dag types, the principal
    // is an auth type, and the parameter values are Jackson trees.
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)

    testImplementation(libs.spring.boot.starter.test)
}
