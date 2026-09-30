package co.datapipelines.executor

import kotlinx.coroutines.ThreadContextElement
import org.slf4j.MDC
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * The execution's log context (observability.md §3.3, #337): the request's `correlation_id` and the
 * execution's `execution_id`, carried on the SLF4J MDC of every thread the execution's coroutines run
 * on, for the whole run.
 *
 * A [ThreadContextElement] is what makes that survive suspension: kotlinx installs the snapshot on
 * every resumption ([updateThreadContext]) and restores the thread's own previous MDC on every
 * suspension ([restoreThreadContext]) — so the executor's `dag-executor-N` threads never wear one
 * execution's ids while running another's. The element is built from the REQUEST's ids, not from the
 * launching thread's MDC: a scheduled run has no MDC to copy, and every trigger (REST, MCP,
 * scheduled, dashboards) reaches [PipelineExecutor.execute] with the ids already known.
 *
 * ## Where it must be installed explicitly
 *
 * Elements flow through `withContext`/`launch`/`async(dispatcher.context)` — a dispatcher added to a
 * context does not drop its other elements. It does NOT flow into a FRESH `CoroutineScope`, which
 * inherits nothing: the per-node deadline scope (`PipelineExecutor.runWithNodeDeadline`) adds this
 * element explicitly, rebuilt from the same request, so a node's body — and every pool task that body
 * submits — logs with the execution's ids.
 *
 * ## Scope
 *
 * Deliberately NOT propagated past the execution's own coroutine: the batching writer's threads (one
 * batch spans many callers — a line there must not wear one caller's id), the scheduled jobs' and
 * SSE schedulers (no request), and the mail worker (a claim row outlives the request that queued it).
 * observability.md §3.3 records that list as part of the feature.
 *
 * The pool threads reached from INSIDE node work (script evaluation, selector statements, template
 * render/parse, cancel re-issue) get the submitter's MDC by their own capture at submission, in their
 * own modules — `LogContext` does not reach them, because `dag` sits below none of those modules'
 * dependency direction (module-structure §4.2).
 *
 * The MDC key strings are duplicated from `auth`'s `AuthErrorWriter.MDC_KEY` only as a compile-time
 * constant here (`dag` must not depend on `auth`); a drift test in `web`'s tests asserts the two are
 * the same string, so the single origin the record demands cannot silently fork.
 */
class LogContext(
    correlationId: UUID?,
    executionId: UUID,
) : ThreadContextElement<Map<String, String>?> {
    private val snapshot: Map<String, String> =
        buildMap {
            correlationId?.let { put(MDC_CORRELATION_ID, it.toString()) }
            put(MDC_EXECUTION_ID, executionId.toString())
        }

    override val key: CoroutineContext.Key<*> = Key

    override fun updateThreadContext(context: CoroutineContext): Map<String, String>? {
        val previous = MDC.getCopyOfContextMap()
        MDC.setContextMap(snapshot)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Map<String, String>?,
    ) {
        if (oldState == null) MDC.clear() else MDC.setContextMap(oldState)
    }

    companion object {
        /** observability §3.1 / auth's `AuthErrorWriter.MDC_KEY` — the same slot, one drift test. */
        const val MDC_CORRELATION_ID: String = "correlation_id"

        /** observability §3.1 — the execution's own id. */
        const val MDC_EXECUTION_ID: String = "execution_id"

        val Key: CoroutineContext.Key<LogContext> =
            object : CoroutineContext.Key<LogContext> {}
    }
}
