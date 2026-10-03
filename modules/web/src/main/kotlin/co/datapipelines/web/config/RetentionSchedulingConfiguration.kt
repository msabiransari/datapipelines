package co.datapipelines.web.config

import co.datapipelines.auth.AuditLogRetention
import co.datapipelines.auth.AuditProperties
import co.datapipelines.auth.KeyRetentionPurge
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionEventRetention
import co.datapipelines.parameters.ParameterEvaluationRepository
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.persistence.FailureShape
import co.datapipelines.visualization.DashboardRefreshRepository
import co.datapipelines.web.dashboards.runtime.DashboardRefreshRetention
import co.datapipelines.web.parameters.ParameterEvaluationRetention
import co.datapipelines.web.parameters.ParameterEvaluationSweeper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

/**
 * The retention sweep, scheduled (metadata-db §8, deployment.md §6.2): the `execution_events`
 * retention (§8.1, 050/T60), the finished dashboard refreshes (#10 L2), the parameter-evaluation history's
 * stale sweep and retention (#376, §8.5/§8.1), the keys purge (keys v2 A17/B5) and the `audit_log` retention (§8.2,
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

    /** Finished dashboard refreshes ride the event retention's tick and cutoff (#10 L2, metadata-db §8.1). */
    @Bean
    fun dashboardRefreshRetention(
        jdbc: NamedParameterJdbcTemplate,
        properties: ExecutionsProperties,
    ): DashboardRefreshRetention = DashboardRefreshRetention(DashboardRefreshRepository(jdbc), properties.eventRetentionDays)

    /**
     * #376: a RUNNING evaluation record past the evaluate deadline (plus the sweeper's margin) is closed INCOMPLETE. The
     * repository is built here from the jdbc template, as the dashboard refreshes' is, so the scheduling slice
     * (`ScheduledJobsSchedulerTest`) loads this configuration without the engine's; the deadline is the engine's own
     * `ParametersConfig` bean, which the application always has — the slice, which has none, gets the key's default.
     */
    @Bean
    fun parameterEvaluationSweeper(
        jdbc: NamedParameterJdbcTemplate,
        parameters: ObjectProvider<ParametersConfig>,
    ): ParameterEvaluationSweeper =
        ParameterEvaluationSweeper(
            ParameterEvaluationRepository(jdbc),
            parameters.getIfAvailable(::ParametersConfig).evaluateTimeoutSeconds,
        )

    /** #376: finished evaluation records ride the event retention's tick and cutoff — no key of their own (§11.5). */
    @Bean
    fun parameterEvaluationRetention(
        jdbc: NamedParameterJdbcTemplate,
        properties: ExecutionsProperties,
    ): ParameterEvaluationRetention = ParameterEvaluationRetention(ParameterEvaluationRepository(jdbc), properties.eventRetentionDays)

    @Bean
    fun executionEventRetentionScheduler(
        retention: ExecutionEventRetention,
        keyPurge: KeyRetentionPurge,
        auditRetention: AuditLogRetention,
        dashboardRefreshRetention: DashboardRefreshRetention,
        evaluationSweeper: ParameterEvaluationSweeper,
        evaluationRetention: ParameterEvaluationRetention,
    ): ExecutionEventRetentionScheduler =
        ExecutionEventRetentionScheduler(
            retention,
            keyPurge,
            auditRetention,
            dashboardRefreshRetention,
            evaluationSweeper,
            evaluationRetention,
        )
}

/**
 * The `@Scheduled` adapter over the retention sweep's six steps — see
 * [RetentionSchedulingConfiguration]. In order:
 *
 * 1. [ExecutionEventRetention] — `execution_events` past their execution's retention;
 * 2. [DashboardRefreshRetention] (#10 L2) — finished dashboard refreshes, on the same cutoff as the events that
 *    describe them (metadata-db §8.1);
 * 3. [ParameterEvaluationSweeper] (#376) — evaluation records a lost instance or a failed terminal write left `RUNNING`
 *    past the evaluate deadline become `INCOMPLETE` (metadata-db §8.5) — before the retention, so they age out with
 *    the rest;
 * 4. [ParameterEvaluationRetention] (#376) — finished evaluation records past the event retention, one bounded batch
 *    (metadata-db §8.1);
 * 5. [KeyRetentionPurge] (keys v2 A17/B5) — revoked keys and their identities once nothing
 *    references them;
 * 6. [AuditLogRetention] (#310) — `audit_log` rows older than `datapipelines.audit.retention-days`.
 *    Last, so the purges before it never compete with a backlog for the sweep's hour. An audit
 *    row naming a key's identity is one of the references that keeps that key (step 3), so an
 *    identity whose last audit rows expire here is purged by the NEXT tick's step 5, an hour on.
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
    private val dashboardRefreshRetention: DashboardRefreshRetention,
    private val evaluationSweeper: ParameterEvaluationSweeper,
    private val evaluationRetention: ParameterEvaluationRetention,
) {
    @Scheduled(fixedDelay = RETENTION_INTERVAL_MILLIS)
    fun retain() {
        step("execution_events") { retention.retainOnce() }
        // #10 L2: finished dashboard refreshes go with the events that describe them (metadata-db §8.1).
        step("dashboard_refreshes") { dashboardRefreshRetention.retainOnce() }
        // #376: the stale sweep first, so a record it closes ages out with the rest; then the retention batch.
        step("parameter_evaluation_sweep") { evaluationSweeper.sweepOnce() }
        step("parameter_evaluations") { evaluationRetention.retainOnce() }
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
