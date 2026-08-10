package co.datapipelines.executor

import co.datapipelines.datasources.DatasourceAuditEvent
import co.datapipelines.datasources.DatasourceAuditEvents
import co.datapipelines.datasources.DatasourceAuditSink
import java.util.UUID

/**
 * Adds the §7.4 **cause** to an executor-triggered pool build's audit event
 * ([datasources.md §7.4](../../../../../../../docs/datasources.md)).
 *
 * `DatasourceAuditEvent.cause` is defined as "the execution id and node id that took the first
 * lease", and `DefaultDatasourceRegistry` cannot fill it: it emits `pool_build` from inside the
 * pool factory, with an actor of `system` and no idea which execution asked. The executor is the
 * only layer that holds that context, so it wraps the sink and stamps the cause on the way past.
 *
 * ## Why a `ThreadLocal` is the right carrier here, and where its limit is
 *
 * A pool build happens **synchronously on the leasing thread**, inside
 * `ConcurrentHashMap.computeIfAbsent` under `poolFor` → `leaseConnection`. [withCause] therefore
 * wraps a strictly blocking, non-suspending call: no dispatch, no thread hop, nothing to lose the
 * value across. Passing the context through the coroutine context instead would not help — the
 * registry is plain blocking Java that knows nothing about coroutines.
 *
 * The limit is the same statement: [body] must not suspend. It is typed `() -> T`, so it cannot.
 */
class ExecutionAwareAuditSink(
    private val delegate: DatasourceAuditSink,
) : DatasourceAuditSink {
    private val current = ThreadLocal<DatasourceAuditEvent.Cause?>()

    /**
     * Stamps the cause on a `pool_build` event that does not already carry one, then delegates.
     *
     * Only `pool_build` is enriched: `pool_rebuild` and `connection_test` are operator-initiated
     * and already carry a real actor, and inventing an execution for them would be a lie in the
     * audit trail.
     */
    override fun record(event: DatasourceAuditEvent) {
        val cause = current.get()
        val enriched =
            if (cause != null && event.cause == null && event.event == DatasourceAuditEvents.POOL_BUILD) {
                event.copy(cause = cause)
            } else {
                event
            }
        delegate.record(enriched)
    }

    /**
     * Runs [body] with the pool-build cause set to ([executionId], [nodeId]).
     *
     * Nests correctly: the previous value is restored rather than cleared, so a lease taken while
     * another cause is in scope cannot silently erase it.
     */
    fun <T> withCause(
        executionId: UUID,
        nodeId: String,
        body: () -> T,
    ): T {
        val previous = current.get()
        current.set(DatasourceAuditEvent.Cause(executionId.toString(), nodeId))
        try {
            return body()
        } finally {
            current.set(previous)
        }
    }
}
