package co.datapipelines.datasources

import java.time.Instant

/**
 * The registered datasource audit event names ([Enums §15](../../../../../../../docs/enums.md),
 * authored by [datasources.md §7.4](../../../../../../../docs/datasources.md)).
 *
 * These are the **wire** strings written to `audit_log.event`; they are declared here rather than
 * imported because `datasources` may depend on `typesystem` alone (module-structure §5.4), and
 * [DatasourceAuditEventsSpecDriftTest] fails if this list and enums.md §15 ever disagree — the
 * same cross-boundary drift guard [DatasourceErrorCodesSpecDriftTest] applies to the error codes.
 */
object DatasourceAuditEvents {
    /** Credential decrypted to build a connection pool (lazy first lease, §5.2). */
    const val POOL_BUILD = "datasource.pool_build"

    /** Pool rebuilt after a datasource update — the evict-on-update path of §5.2. */
    const val POOL_REBUILD = "datasource.pool_rebuild"

    /** Explicit connection test (`POST /api/v1/datasources/{name}/test`, §8.1). */
    const val CONNECTION_TEST = "datasource.connection_test"

    /**
     * Master-key rotation re-encryption pass (§7.3).
     *
     * **Emitted by nothing in v1** — §7.3 defers the rotation *flow* to v1.1 and only the name is
     * registered. The constant exists so the flow, when it lands, cannot invent a second spelling.
     */
    const val KEY_ROTATION = "datasource.key_rotation"

    /** Every registered name — the drift-test surface. */
    val ALL = listOf(POOL_BUILD, POOL_REBUILD, CONNECTION_TEST, KEY_ROTATION)
}

/**
 * One §7.4 audit record: written at each **credential-decryption point**, never per lease.
 *
 * @param timestamp when the decryption happened (UTC).
 * @param datasourceName the datasource whose credential was decrypted.
 * @param event one of [DatasourceAuditEvents].
 * @param actor the operator's user id for operator-initiated actions, or
 *   [SYSTEM_ACTOR] for an executor-initiated pool build.
 * @param cause the execution id and node id that took the first lease, when the trigger is a
 *   `pool_build` from an execution — null when the module cannot know it (see [SYSTEM_ACTOR]).
 */
data class DatasourceAuditEvent(
    val timestamp: Instant,
    val datasourceName: String,
    val event: String,
    val actor: String,
    val cause: Cause? = null,
) {
    /** The §7.4 "cause" of an executor-initiated pool build. */
    data class Cause(
        val executionId: String,
        val nodeId: String,
    )

    companion object {
        /**
         * The system principal §7.4 names for executor-initiated pool builds.
         *
         * It is also what a [DatasourceRegistry.testConnection] event carries: §6.1 froze that
         * method's signature without an actor parameter, so the module has no user id to record.
         * The web layer knows the caller and can enrich the event when it wires a real sink.
         */
        const val SYSTEM_ACTOR = "system"
    }
}

/**
 * Where §7.4 audit events go.
 *
 * A `fun interface` sibling of [DatasourceReferences], and for the same reason: the `audit_log`
 * table belongs to the application, which `datasources` cannot depend on (module-structure §5.4).
 * `app` wires this onto the shared audit-log writer at assembly; [NONE] is a no-op so the module
 * — and every test that does not care — stays dependent on `typesystem` alone.
 *
 * Implementations must not throw: an audit sink failure must not fail a pool build. The registry
 * does not defend against a throwing sink, so a real implementation swallows and logs its own
 * failures.
 */
fun interface DatasourceAuditSink {
    /** Records one decryption-point event (§7.4). */
    fun record(event: DatasourceAuditEvent)

    companion object {
        /** The default: events are produced and discarded. */
        val NONE = DatasourceAuditSink { }
    }
}
