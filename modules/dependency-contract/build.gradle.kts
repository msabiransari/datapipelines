// module-structure.md §5.22 — layer 0 beside `graph`: NO internal dependencies, and no external main
// dependency either. The module holds the neutral business-object dependency contract of the
// dependency-graph design record (§3.1, #868 G1): immutable graph types, the provider contract, the
// family registry, the bounded walk and its internal fingerprint.
//
// Kotlin/JDK only, on purpose (record §3.1): no Spring, JDBC, auth, pipeline, template, scheduler or
// renderer. Persistence, transactions and the family adapters live in `application/dependencies`;
// the Spring composition lives in `web`. DependencyContractBoundaryTest refuses the imports a Gradle
// edge alone could not fence. The house test dependencies (JUnit 5, MockK, Kotest assertions) come
// from the conventions plugin.
plugins { id("datapipelines.common-conventions") }

dependencies {
    // Section F's wire test validates the §11.1 goldens against the JSON schema (main resources) — test
    // scope only, the catalog entry #860 added (networknt 2.0.8).
    testImplementation(libs.json.schema.validator)
    // Jackson stays at the product's pin for the validator's POM (2.22.1), the same mechanism and reason as
    // modules/visualization/build.gradle.kts — test scope only here, so main stays stdlib-only.
    testImplementation(enforcedPlatform(libs.jackson.bom))
}
