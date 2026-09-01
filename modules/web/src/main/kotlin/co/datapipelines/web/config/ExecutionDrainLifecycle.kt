package co.datapipelines.web.config

import co.datapipelines.executor.CancellationRegistry
import co.datapipelines.executor.ExecutionCancellationService
import org.slf4j.LoggerFactory
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The shutdown drain (deployment.md §8.3.1, dag-executor.md §8.3): the wiring
 * [ExecutionCancellationService.cancelAllLocal] always had and no production code called.
 *
 * ## The sequence, and why the order is the contract
 * [stop] runs three steps, in this order:
 *
 * 1. **Readiness flips first.** `ReadinessState.REFUSING_TRAFFIC` is published before anything
 *    is cancelled, so `/ready` starts failing while the process is still fully up and the load
 *    balancer / k8s Service bleeds traffic off. Flipping after the drain would keep fresh work
 *    flowing into an instance that is already cancelling it — the order, not the pair of
 *    actions, is what makes the drain useful.
 * 2. **Every local execution is cancelled through the ordinary path** (§8.3.2):
 *    `Statement.cancel()` on each registered statement first — this is what actually stops the
 *    query on the source database, so the drain is more than a status flip — then the root
 *    `Job`, then the executor's own `finally` writes `execution_aborted` (`reason: "shutdown"`)
 *    and the `ABORTED` status.
 * 3. **The flush is awaited**, bounded by [flushTimeoutMillis]. [CancellationRegistry.liveExecutions]
 *    reaches zero only from the executor's `finally` — after the Postgres status update and the
 *    event writes — so an empty registry means the bookkeeping flushed, and the bound means a
 *    wedged execution delays the SIGKILL deadline rather than hanging shutdown forever.
 *
 * The default [SmartLifecycle.getPhase] (`DEFAULT_PHASE`, the maximum) is load-bearing: lifecycle
 * beans stop highest-phase-first, so this drain stops **before** the web server's own
 * graceful-shutdown lifecycle (`DEFAULT_PHASE - 1024`) — SSE clients are still connected while
 * their terminal events are emitted — and before any singleton's `destroyMethod`, which is where
 * `executionScope.close()` runs. `cancelAllLocal()` therefore strictly precedes the scope's
 * `job.cancel()`, the ordering M1 was missing.
 *
 * The flush timeout is a code constant, not a configuration key: it must stay under Spring's
 * per-phase lifecycle timeout (`spring.lifecycle.timeout-per-shutdown-phase`, framework default
 * 30s) or the processor warns on every shutdown, and no operator outcome is improved by tuning it.
 */
class ExecutionDrainLifecycle(
    private val cancellationService: ExecutionCancellationService,
    private val registry: CancellationRegistry,
    private val publisher: ApplicationEventPublisher,
    private val flushTimeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)

    override fun start() {
        running.set(true)
    }

    override fun isRunning(): Boolean = running.get()

    override fun stop() {
        // Step 1 — BEFORE anything is cancelled (see the class KDoc; the order is the contract).
        AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC)
        log.info("event=shutdown.readiness_refused message=\"/ready now reports 503; the drain starts\"")

        // Step 2 — the ordinary cancellation path, statements first (§8.3.2).
        cancellationService.cancelAllLocal()
        log.info("event=shutdown.drain_cancelled live={} reason=shutdown", registry.liveExecutions)

        // Step 3 — bounded flush wait. cancelAllLocal() repeats each tick: an execution launched
        // in the sliver between the first cancel and the web server stopping (a request that
        // raced the readiness flip) registered after the first sweep and would otherwise run on.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushTimeoutMillis)
        while (registry.liveExecutions > 0 && System.nanoTime() < deadline) {
            Thread.sleep(pollIntervalMillis)
            cancellationService.cancelAllLocal()
        }
        val remaining = registry.liveExecutions
        if (remaining > 0) {
            log.warn(
                "event=shutdown.drain_incomplete live={} flush_timeout_ms={} " +
                    "message=\"executions still deregistering at the flush deadline; " +
                    "their rows may remain RUNNING past this instance's exit\"",
                remaining,
                flushTimeoutMillis,
            )
        } else {
            log.info("event=shutdown.drain_complete")
        }
        running.set(false)
    }

    companion object {
        /**
         * 20s — comfortably under the lifecycle processor's 30s per-phase default (see the class
         * KDoc). A normal cancel flushes in well under a second; this bound exists for the wedge.
         */
        const val DEFAULT_FLUSH_TIMEOUT_MILLIS = 20_000L

        /** Flush poll cadence — short, because a healthy drain finishes in the first few ticks. */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 100L

        private val log = LoggerFactory.getLogger(ExecutionDrainLifecycle::class.java)
    }
}
