// module-structure.md §5.1 — layer 0, no internal dependencies.
plugins { id("datapipelines.common-conventions") }

dependencies {
    // Jackson is the project-wide JSON stack (enums.md §1 @JsonValue mapping).
    api(libs.jackson.module.kotlin)
}
