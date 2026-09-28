// module-structure.md §5.19 — layer 0 beside `typesystem` and `graph`: NO internal dependencies.
// The module holds the generic batching writer (#266): an ordered, bounded, partitioned queue in
// front of a store, with a group commit, a bounded wait that falls back to a direct write, a
// singles retry that isolates a poison row, and a bounded shutdown drain. It knows nothing of
// audit rows, execution events or Redis — each store supplies a `BatchSink` — so it can sit below
// every module that owns a store (`auth` for `audit_log`, `web` for the event record and the replay
// log) without inheriting any of them.
//
// External: kotlinx-coroutines-core (the bounded suspending record the event emitter awaits from
// the executor's coroutines) and slf4j-api (the WARNs a failed batch, a saturated queue and an
// incomplete drain owe the operator). No Spring, no Micrometer, no JDBC: metrics are hooks the
// wiring binds (`BatchingHooks`), and the sinks live with the stores they write.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
