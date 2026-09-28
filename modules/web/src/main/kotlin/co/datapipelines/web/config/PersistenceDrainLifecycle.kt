package co.datapipelines.web.config

import co.datapipelines.persistence.BatchingWriter
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The batching writers' shutdown drain (#266; observability §7, dag-executor §10).
 *
 * Stops every [BatchingWriter] in the context — the audit log's (`auth`) and the event record's and
 * replay log's (`web`) — at [PHASE], which is the one point in the shutdown sequence where both of
 * its preconditions hold:
 *
 * - AFTER [ExecutionDrainLifecycle] (`DEFAULT_PHASE - 1`): every execution has been cancelled and
 *   has run its `finally`, and every event it emitted was AWAITED — so an execution-event item still
 *   queued here has no execution waiting on it.
 * - AFTER the web server's graceful drain (`DEFAULT_PHASE - 1024`, `server.shutdown: graceful`): no
 *   request is in flight, so no new audit row is coming from one. And BEFORE the server itself stops
 *   (`DEFAULT_PHASE - 2048`) and the singletons — the Hikari pool, the Redis connection factory — are
 *   destroyed, so the drain still has a store to write to.
 *
 * Each writer — all of them at once — flushes what it holds for at most `shutdown-drain-ms` and then reports what it could
 * not write (`event=persistence.drain_incomplete`, the count): the only loss the writers themselves
 * can cause, and it can only hit an item nobody was waiting on. A write that arrives after this
 * phase — nothing should — is written directly by its caller. Each writer's bean `destroyMethod`
 * is the backstop for a context that never started this lifecycle.
 */
class PersistenceDrainLifecycle(
    private val writers: List<BatchingWriter<*>>,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)

    override fun start() {
        running.set(true)
    }

    override fun isRunning(): Boolean = running.get()

    override fun getPhase(): Int = PHASE

    override fun stop() {
        log.info("event=shutdown.persistence_drain_started writers={}", writers.joinToString(",") { it.name })
        // In PARALLEL: each drain is bounded by `shutdown-drain-ms`, and the phase's budget must
        // cover one of them, not their sum.
        writers
            .map { writer ->
                Thread({
                    val report = writer.stop()
                    log.info("event=shutdown.persistence_drained writer={} lost={} in_flight={}", writer.name, report.lost, report.inFlight)
                }, "dp-persist-drain-${writer.name}").apply { start() }
            }.forEach { it.join() }
        running.set(false)
    }

    companion object {
        /** Between the web server's graceful drain (`DEFAULT_PHASE - 1024`) and its stop (`DEFAULT_PHASE - 2048`). */
        const val PHASE: Int = SmartLifecycle.DEFAULT_PHASE - 1536

        private val log = LoggerFactory.getLogger(PersistenceDrainLifecycle::class.java)
    }
}
