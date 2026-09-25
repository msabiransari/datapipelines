package co.datapipelines.web.config

import co.datapipelines.auth.KeyRetentionPurge
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionEventRetention
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

/**
 * The `execution_events` retention job, scheduled (metadata-db §8.1, deployment.md §6.2,
 * 050/T60) — **M2's sibling** (`SweepSchedulingConfiguration`), matching its standing
 * decisions:
 *
 * - **No second `@EnableScheduling`:** the sweep configuration's annotation is the context's
 *   one; `@Scheduled` here rides the same default single-thread scheduler. Both jobs are one
 *  cheap statement each, so a shared thread is the pool that fits.
 * - **`fixedDelay`, not `fixedRate`:** a slow tick (metadata DB busy) delays the next instead
 *  of piling on. Retention catches up by construction — the cutoff is `now − retention`, not
 *  a tick-aligned slot.
 * - **The cadence is a code constant, not a configuration key:** one hour is far under any
 *  sane retention window (default 7 days); a knob nobody should turn is not worth a
 *  documented key (same rule the sweep set).
 * - **The annotation lives here, not in `dag`:** `dag` ships no Spring configuration; it owns
 *  the idempotent [ExecutionEventRetention], this module owns the scheduling.
 */
@Configuration
class RetentionSchedulingConfiguration {
    @Bean
    fun executionEventRetention(
        events: ExecutionEventRepository,
        properties: ExecutionsProperties,
    ): ExecutionEventRetention = ExecutionEventRetention(events, Duration.ofDays(properties.eventRetentionDays))

    @Bean
    fun executionEventRetentionScheduler(
        retention: ExecutionEventRetention,
        keyPurge: KeyRetentionPurge,
    ): ExecutionEventRetentionScheduler = ExecutionEventRetentionScheduler(retention, keyPurge)
}

/**
 * The `@Scheduled` adapter over [ExecutionEventRetention] — see [RetentionSchedulingConfiguration].
 *
 * Keys v2 A17/B5: the key/identity purge runs here as the sweep's LAST step — the one delete the
 * product performs, of revoked keys and their identities once nothing references them. It runs
 * after the event retention so a purge never competes with it for the sweep's hour.
 */
class ExecutionEventRetentionScheduler(
    private val retention: ExecutionEventRetention,
    private val keyPurge: KeyRetentionPurge,
) {
    @Scheduled(fixedDelay = RETENTION_INTERVAL_MILLIS)
    fun retain() {
        retention.retainOnce()
        keyPurge.purgeOnce()
    }

    companion object {
        /** One hour — see [RetentionSchedulingConfiguration] for why this is not a config key. */
        const val RETENTION_INTERVAL_MILLIS = 3_600_000L
    }
}
