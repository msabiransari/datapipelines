package co.datapipelines.web.config

import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.StaleExecutionSweeper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

/**
 * The crash sweep, scheduled (deployment.md §6.2, ARCH-AUDIT M2).
 *
 * **The codebase's first `@Scheduled`** — this is deliberately the only `@EnableScheduling` in
 * the project (module-structure.md §5.6 records the surface). Decisions, once:
 *
 * - **Thread pool:** none configured, so Spring's default single-threaded scheduler runs the
 *   tick. The sweep is one idempotent `UPDATE` per minute; a pool would be decoration, and a
 *   single thread can never overlap two ticks of the same job.
 * - **`fixedDelay`, not `fixedRate`:** a slow tick (metadata DB busy) delays the next one
 *   instead of piling onto it. The sweep catches up by construction — staleness is measured
 *   from `started_at`, not from when the last tick ran.
 * - **The cadence is a code constant, not a configuration key:** one minute is far under any
 *   sane `stale-timeout-minutes` (default 60), and configuration.md is the only place a key may
 *   be defined — a knob nobody should turn is not worth a documented key.
 * - **The annotation lives here, not in `dag`:** `dag` ships no Spring configuration (see
 *   [EngineConfiguration]'s KDoc); it owns the idempotent [StaleExecutionSweeper], and this
 *   module — the assembling layer — owns the scheduling.
 */
@Configuration
@EnableScheduling
class SweepSchedulingConfiguration {
    @Bean
    fun staleExecutionSweeper(
        executions: ExecutionRepository,
        properties: ExecutionsProperties,
    ): StaleExecutionSweeper = StaleExecutionSweeper(executions, Duration.ofMinutes(properties.staleTimeoutMinutes))

    @Bean
    fun staleExecutionSweepScheduler(sweeper: StaleExecutionSweeper): StaleExecutionSweepScheduler = StaleExecutionSweepScheduler(sweeper)
}

/** The `@Scheduled` adapter over [StaleExecutionSweeper] — see [SweepSchedulingConfiguration]. */
class StaleExecutionSweepScheduler(
    private val sweeper: StaleExecutionSweeper,
) {
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MILLIS)
    fun sweep() {
        sweeper.sweepOnce()
    }

    companion object {
        /** One minute — see [SweepSchedulingConfiguration] for why this is not a config key. */
        const val SWEEP_INTERVAL_MILLIS = 60_000L
    }
}
