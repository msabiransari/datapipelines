package co.datapipelines.web.config

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.PoolLifecycleMetrics
import co.datapipelines.web.metrics.MicrometerPoolLifecycleMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled

/**
 * The retired-pool reaper, scheduled (datasources.md §5.2, 094) — the third sibling of
 * `SweepSchedulingConfiguration` and `RetentionSchedulingConfiguration`, matching their standing
 * decisions:
 *
 * - **No second `@EnableScheduling`:** the sweep configuration's annotation is the context's
 *   one; `@Scheduled` here rides the same default single-thread scheduler. The tick reads one
 *   in-memory queue — usually empty — and closes what has drained.
 * - **`fixedDelay`, not `fixedRate`:** a slow tick delays the next instead of piling on. The
 *   reaper catches up by construction: every decision is made from the pool's CURRENT active
 *   count and its own absolute deadline, never from how long ago the last tick ran.
 * - **The cadence is a code constant, not a configuration key.** Five seconds, and this is the
 *   one place these three jobs differ — the sweep and the retention job tick at a minute and an
 *   hour because their subjects are measured in minutes and days. This one bounds two things a
 *   minute would blur: how long a *drained* pool's Hikari house-keeper thread and socket
 *   survive after the query that was using them finished, and how far past its CEILING a hung
 *   pool runs before the hard close. With a default ceiling of 90 s, a 60 s cadence would make
 *   "the ceiling" mean anything from 90 to 150 s; 5 s makes the documented number true.
 *   (Reading the round's "the existing `@Scheduled` sweep cadence" as *the sweep's shape and
 *   scheduler*, not its literal 60 000 — recorded in the handback as a deliberate deviation.)
 * - **The annotation lives here, not in `datasources`:** that module ships no Spring
 *   configuration; it owns the idempotent [DatasourceRegistry.reapRetiredPools], and this
 *   module — the assembling layer — owns the scheduling.
 */
@Configuration
class PoolReaperSchedulingConfiguration {
    @Bean
    fun datasourcePoolReaperScheduler(datasources: DatasourceRegistry): DatasourcePoolReaperScheduler =
        DatasourcePoolReaperScheduler(datasources)

    /**
     * The pool-retirement instruments (observability.md §4.1). A real
     * [MeterRegistry]-backed implementation, never a no-op: the ceiling firing is exactly the
     * kind of event a deployment learns about from a counter and an alert, not from a log it
     * happens to read. It lives beside the reaper rather than in `DomainConfiguration`
     * because this is the pool-lifecycle configuration — and because that class is at its
     * `TooManyFunctions` ceiling, which is the same signal read a different way.
     */
    @Bean
    fun poolLifecycleMetrics(meterRegistry: MeterRegistry): PoolLifecycleMetrics = MicrometerPoolLifecycleMetrics(meterRegistry)
}

/** The `@Scheduled` adapter over [DatasourceRegistry.reapRetiredPools] — see [PoolReaperSchedulingConfiguration]. */
class DatasourcePoolReaperScheduler(
    private val datasources: DatasourceRegistry,
) {
    @Scheduled(fixedDelay = REAP_INTERVAL_MILLIS)
    fun reap() {
        datasources.reapRetiredPools()
    }

    companion object {
        /** Five seconds — see [PoolReaperSchedulingConfiguration] for why, and why not sixty. */
        const val REAP_INTERVAL_MILLIS = 5_000L
    }
}
