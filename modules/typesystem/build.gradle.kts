// module-structure.md §5.1 — layer 0, no internal dependencies.
plugins { id("datapipelines.common-conventions") }

dependencies {
    // Jackson is the project-wide JSON stack (enums.md §1 @JsonValue mapping).
    api(libs.jackson.module.kotlin)

    // TEST-ONLY (186): H2RestrictedSessionTest drives the two-phase open against the real
    // pinned driver — the helper is JDK-only by design, and this entry keeps it that way.
    testImplementation(libs.h2)
}
