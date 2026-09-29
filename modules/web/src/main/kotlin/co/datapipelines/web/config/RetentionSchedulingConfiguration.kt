package co.datapipelines.web.config

import co.datapipelines.auth.AuditLogRetention
import co.datapipelines.auth.AuditProperties
import co.datapipelines.auth.KeyRetentionPurge
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionEventRetention
import co.datapipelines.persistence.FailureShape
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

/**
 * The retention sweep, scheduled (metadata-db §8, deployment.md §6.2): the `execution_events`
 * retention (§8.1, 050/T60), the keys purge (keys v2 A17/B5) and the `audit_log` retention (§8.2,
 * #310), one hourly tick — **M2's sibling** (`SweepSchedulingConfiguration`), matching its
 * standing decisions:
 *
 * - **No second `@EnableScheduling`:** the sweep configuration's annotation is the context's
 *   one; `@Scheduled` here rides the same single thread as every other scheduled job — the jobs'
 *   own scheduler, `dp-scheduled` (#316; the sweep configuration's KDoc has the decision). Until
 *   #316 that thread was `WebSurfaceConfiguration`'s `sseLogScheduler`, the one that serves SSE
 *   replays, which Spring fell back to while no `TaskScheduler` bean existed. Every step is still a
 *   bounded statement or a bounded loop of them ([AuditLogRetention]'s batch ceiling and
 *   two-second time budget): the stale sweep and the pool reaper wait behind this tick.
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
    fun auditLogRetention(
        jdbc: NamedParameterJdbcTemplate,
        properties: AuditProperties,
        meterRegistry: MeterRegistry,
    ): AuditLogRetention = AuditLogRetention(jdbc, properties.retentionDays, meterRegistry)

    @Bean
    fun executionEventRetentionScheduler(
        retention: ExecutionEventRetention,
        keyPurge: KeyRetentionPurge,
        auditRetention: AuditLogRetention,
    ): ExecutionEventRetentionScheduler = ExecutionEventRetentionScheduler(retention, keyPurge, auditRetention)
}

/**
 * The `@Scheduled` adapter over the retention sweep's three steps — see
 * [RetentionSchedulingConfiguration]. In order:
 *
 * 1. [ExecutionEventRetention] — `execution_events` past their execution's retention;
 * 2. [KeyRetentionPurge] (keys v2 A17/B5) — revoked keys and their identities once nothing
 *    references them;
 * 3. [AuditLogRetention] (#310) — `audit_log` rows older than `datapipelines.audit.retention-days`.
 *    Last, so the purges before it never compete with a backlog for the sweep's hour. An audit
 *    row naming a key's identity is one of the references that keeps that key (step 2), so an
 *    identity whose last audit rows expire here is purged by the NEXT tick's step 2, an hour on.
 *
 * ## Each step is isolated
 * A step that throws is one ERROR line naming it (`event=retention.step_failed step=<name>`), and
 * the tick moves on to the next step: before #310 the keys purge was the last step, so its
 * exception reaching Spring's scheduler stopped nothing — with the audit retention after it, the
 * same exception would skip the audit retention every hour it recurred. Each step already turns
 * a metadata-DB fault into its own WARN; this catches what is left, so nothing reaches the
 * scheduler thread the stale-execution sweep and the pool reaper share.
 */
class ExecutionEventRetentionScheduler(
    private val retention: ExecutionEventRetention,
    private val keyPurge: KeyRetentionPurge,
    private val auditRetention: AuditLogRetention,
) {
    @Scheduled(fixedDelay = RETENTION_INTERVAL_MILLIS)
    fun retain() {
        step("execution_events") { retention.retainOnce() }
        step("keys") { keyPurge.purgeOnce() }
        step("audit_log") { auditRetention.purgeOnce() }
    }

    @Suppress("TooGenericExceptionCaught") // the point IS any failure: one ERROR line, the next step runs
    private inline fun step(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            // The class and the SQLState only — no message, no stack (a store's message can carry a row; the 310 pass).
            LOG.error(
                "event=retention.step_failed step={} error={} sql_state={}",
                name,
                e.javaClass.simpleName,
                FailureShape.sqlState(e),
            )
        }
    }

    companion object {
        /** One hour — see [RetentionSchedulingConfiguration] for why this is not a config key. */
        const val RETENTION_INTERVAL_MILLIS = 3_600_000L

        private val LOG = LoggerFactory.getLogger(ExecutionEventRetentionScheduler::class.java)
    }
}
