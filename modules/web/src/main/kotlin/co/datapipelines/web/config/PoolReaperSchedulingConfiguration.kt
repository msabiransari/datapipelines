package co.datapipelines.web.config

import co.datapipelines.application.datasources.ManagedLakeTableRepository
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.ManagedLakeCatalog
import co.datapipelines.datasources.pooling.PoolLifecycleMetrics
import co.datapipelines.executor.BoundedJdbc
import co.datapipelines.lake.LakeProperties
import co.datapipelines.persistence.FailureShape
import co.datapipelines.web.metrics.MicrometerPoolLifecycleMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

/**
 * The retired-pool reaper, scheduled (datasources.md §5.2, 094) — the third sibling of
 * `SweepSchedulingConfiguration` and `RetentionSchedulingConfiguration`, matching their standing
 * decisions:
 *
 * - **No second `@EnableScheduling`:** the sweep configuration's annotation is the context's
 *   one; `@Scheduled` here rides the jobs' own scheduler, its one `dp-scheduled` thread (#316 —
 *   not Spring's default, and no longer the SSE log streamer's `dp-sse-log` thread it fell back to
 *   before; the sweep configuration's KDoc has the decision). The tick walks the live pool map for
 *   managed lake generations past their age bound and retires them (#818c), then reads one in-memory
 *   queue — usually empty — and closes what has drained. Neither half issues a statement.
 * - **`fixedDelay`, not `fixedRate`:** a slow tick delays the next instead of piling on. The
 *   reaper catches up by construction: every decision is made from the pool's CURRENT active
 *   count and its own absolute deadline, never from how long ago the last tick ran.
 * - **The cadence is a code constant, not a configuration key.** Five seconds, and this is the
 *   one place these three jobs differ — the sweep and the retention job tick at fifteen seconds and
 *   an hour because their subjects are measured in beats and days. This one bounds two things a
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
     * #818 (dp-lake record §10 RULING 4 item 2) — the managed lake's freshness tick, the reaper's sibling on the
     * same `dp-scheduled` thread. Unlike the reaper's, its cadence IS a key (`reads.freshness-tick-seconds`),
     * because it is one term of the `LakeRules` inequality an operator tunes. Inert without a managed generation:
     * the registry's pass iterates live managed generations only, and the regrant returns at once when the lake
     * is disabled.
     *
     * #818b A — every statement the tick issues runs over [statements], a dedicated template bounded at
     * [ManagedLakeFreshnessScheduler.FRESHNESS_STATEMENT_TIMEOUT_SECONDS] (the sweep's and the retention job's
     * `BoundedJdbc` pattern, never the shared template's timeout). The tick issues two kinds, and only these:
     * the managed catalog read (one per live managed generation, over the same SQL and disabled-lake rule as
     * the build's read: [LakeConfiguration.managedLakeCatalogOver]) and the regrant's one INSERT.
     */
    @Bean
    fun managedLakeFreshnessScheduler(
        datasources: DatasourceRegistry,
        registrar: ManagedLakeDatasourceRegistrar,
        jdbc: NamedParameterJdbcTemplate,
        lakeProperties: ObjectProvider<LakeProperties>,
    ): ManagedLakeFreshnessScheduler {
        val statements =
            BoundedJdbc.bounded(
                jdbc,
                ManagedLakeFreshnessScheduler.FRESHNESS_STATEMENT_TIMEOUT_SECONDS,
                "FRESHNESS_STATEMENT_TIMEOUT_SECONDS",
            )
        return ManagedLakeFreshnessScheduler(
            datasources = datasources,
            registrar = registrar,
            catalog =
                LakeConfiguration.managedLakeCatalogOver(
                    ManagedLakeTableRepository(statements),
                    enabled = lakeProperties.ifAvailable?.enabled == true,
                ),
            grants = DatasourceGrantRepository(statements),
        )
    }

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

/**
 * The `@Scheduled` adapter over the managed lake's freshness pass (#818): one catalog read per live managed
 * generation, a stale one retired, then the grant to workspaces created since the last tick. `fixedDelay`, as
 * the reaper, so a slow catalog read delays the next tick instead of piling on.
 *
 * #818b A — both steps read and write through [catalog] and [grants], the bean's statement-bounded pair, so a
 * statement queued behind a lock is cancelled at [FRESHNESS_STATEMENT_TIMEOUT_SECONDS] and the shared
 * `dp-scheduled` thread returns to the reaper. Each step is isolated as the retention tick's are: a step that
 * throws is one WARN (`event=lake.freshness_tick_failed step=<name>`, class and SQLState only), the next step
 * runs, and nothing reaches the scheduler, so the next tick runs on schedule. The registry's pass already turns
 * a failed catalog read into its own WARN (`lake.freshness_read_failed`); the catch here holds what escapes.
 * Failed ticks are not added to `ScheduledJob`'s failed-tick counter: that enum lives outside this lane.
 */
class ManagedLakeFreshnessScheduler(
    private val datasources: DatasourceRegistry,
    private val registrar: ManagedLakeDatasourceRegistrar,
    private val catalog: ManagedLakeCatalog,
    private val grants: DatasourceGrantRepository,
) {
    @Scheduled(fixedDelayString = TICK_SECONDS_PLACEHOLDER, initialDelayString = TICK_SECONDS_PLACEHOLDER, timeUnit = TimeUnit.SECONDS)
    fun tick() {
        step("refresh") {
            val retired = datasources.refreshManagedGenerations(catalog)
            // The brief's DoS bound: a retire storm is bounded by the pool count, and the count is logged per tick.
            if (retired > 0) {
                log.info(
                    "event=lake.freshness_tick_retired generations={} message=\"freshness tick retired stale managed generations\"",
                    retired,
                )
            }
        }
        step("regrant") { registrar.regrant(grants) }
    }

    @Suppress("TooGenericExceptionCaught") // the point IS any failure: one WARN line, the next step and tick run
    private inline fun step(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            // The class and the SQLState only — no message, no stack (a store's message can carry a row).
            log.warn(
                "event=lake.freshness_tick_failed step={} error={} sql_state={} " +
                    "message=\"freshness tick step failed; the next step and the next tick still run\"",
                name,
                FailureShape.cause(e),
                FailureShape.sqlState(e),
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ManagedLakeFreshnessScheduler::class.java)

        /**
         * #818b A — the bound on each statement of the tick, a JDBC `queryTimeout` (Postgres cancels with SQLState
         * `57014`). Not a configuration key, for the reaper's reason: it bounds how long the tick can hold the
         * `dp-scheduled` thread the 5 s reaper shares, so a tick blocked on both of its statements delays a reap by
         * at most 2 × 5 s. A healthy catalog read and the grant INSERT take milliseconds, so the bound fires only on
         * a statement queued behind a lock. A cancelled read retires nothing (the generation serves until its age
         * bound); a cancelled INSERT grants nothing and the next tick retries the same idempotent INSERT.
         */
        const val FRESHNESS_STATEMENT_TIMEOUT_SECONDS = 5

        /** `reads.freshness-tick-seconds`, defaulted as application.yml defaults it (the drift test pins that value). */
        const val TICK_SECONDS_PLACEHOLDER = "\${datapipelines.lake.reads.freshness-tick-seconds:60}"
    }
}

/**
 * The `@Scheduled` adapter over [DatasourceRegistry.reapRetiredPools] — see [PoolReaperSchedulingConfiguration].
 *
 * #818c (record §10 RULING 4 amended 2026-11-05, item 3) — the pass first retires every managed lake generation past
 * `reads.max-generation-age-seconds`, with no borrower (`ConnectionPoolManager.retireExpiredGenerations`), then reaps,
 * so an idle server's over-age generation is retired within one [ReaperDelay] of its age bound and hard-closed within
 * one more of its ceiling. The pass issues no statement: the retirement is a clock comparison over the live map.
 * Each retirement logs `lake.generation_expired trigger=reaper`; the pass logs its count once, only when it is not
 * zero (`lake.reaper_retired`, the freshness tick's pattern). The count is at most the live managed generation
 * count, one per managed datasource name.
 */
class DatasourcePoolReaperScheduler(
    private val datasources: DatasourceRegistry,
) {
    @Scheduled(fixedDelay = REAP_INTERVAL_MILLIS)
    fun reap() {
        val outcome = datasources.reapRetiredPools()
        if (outcome.expired > 0) {
            log.info(
                "event=lake.reaper_retired generations={} message=\"the pool reaper retired managed generations past their age bound\"",
                outcome.expired,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DatasourcePoolReaperScheduler::class.java)

        /** Five seconds — see [PoolReaperSchedulingConfiguration] for why, and why not sixty. [ReaperDelay] starts from it. */
        const val REAP_INTERVAL_MILLIS = 5_000L
    }
}
