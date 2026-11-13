// module-structure.md §5.23 — layer 0, TEST-ONLY: NO internal dependencies. The module holds the one shared
// Logback snapshot capture (#756): `SnapshotListAppender`, `capturingLogEvents` and the root-logger
// `capturingLogs`, which replaced nine module-local test-source copies (#362, #391, #484).
//
// Test-only by contract: every consumer declares it with `testImplementation`, never on a production
// configuration — `verifyModuleDependencies` (root build.gradle.kts, testOnlyModules) refuses any other
// edge, so Logback test tooling cannot reach a runtime classpath. Its sources live under src/main only so
// that consumers can depend on the module; nothing here is product code.
//
// External: logback-classic as `api` (BOM-managed, no version), because the helper's public signatures
// expose Logback's `Logger`, `ILoggingEvent` and `ListAppender` to every consumer's test sources.
plugins { id("datapipelines.common-conventions") }

dependencies {
    api(libs.logback.classic)
}
