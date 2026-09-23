// module-structure.md §5.15 — layer 0 beside `typesystem`: the ONLY internal dependency is
// `typesystem`, for the canonical [LogicalType]/[ColumnSchema] the type gate checks against
// and the egress encoding rule the DATE/TIME/TIMESTAMP normalisation reuses.
//
// The engine evaluates UNTRUSTED script bodies as pure functions of their JSON input
// (transform-nodes design §4.1, D-T4). Two facts make that safe rather than hoped-for:
//
// 1. The layering row (§4.2 + the root build's allowed-dependency map) — no route to a
//    database, HTTP client or Spring context is even compilable, and `ScriptingPurityTest`
//    refuses an I/O, network or JDBC import in `src/main` the `CalculatorPurityTest` way.
// 2. The resource bounds are measured, not believed: `JsonataBreachTest` runs the bomb
//    corpus and writes `build/reports/jsonata-breach.md`, whose table is what
//    dag-executor.md publishes. (`ScriptEngine.capabilities` reports which limits the
//    engine can actually enforce; the heap is explicitly NOT one of them in-process.)
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(libs.jsonata)
    // Canonical JSON (§5.5): one ObjectMapper, configured here, private to the module.
    // BOM-managed (the conventions apply the Spring Boot BOM and the jackson-bom override).
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // The pool's abandonment log (§4.3): SLF4J API only; the registry lives in `app`.
    implementation(libs.slf4j.api)
}

// The breach suite must be able to EXHAUST a JVM to prove the heap is unbounded
// (transform-nodes design §4.5): a bomb that OOMs a 1g test JVM says nothing about a
// 512m production child, and a larger heap would let the big cases pass silently
// instead of recording their honest outcome. 512m is the whole module's test heap —
// every other suite here is allocation-light by design.
tasks.named<Test>("test") {
    maxHeapSize = "512m"
}
