package co.datapipelines.web.config

import co.datapipelines.executor.ExecutionProgress
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.JdbcExecutionProgress
import co.datapipelines.executor.StaleExecutionSweeper
import co.datapipelines.web.sse.SseJson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

/**
 * The liveness of a RUNNING execution row: the heartbeat that keeps it alive, the sink that keeps
 * it current, and the sweep that reaps it when the instance holding it dies (108 §D, deployment.md
 * §6.2, ARCH-AUDIT M2).
 *
 * The progress sink sits here rather than in [EngineConfiguration] for more than the size ceiling
 * that forced the move: it and the sweep are the two halves of one mechanism. The sink writes
 * `heartbeat_at`, the sweep reads it, and `heartbeat-seconds` is the one number both depend on —
 * putting them in different files is how the sweep's cutoff and the beat that feeds it drift
 * apart.
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
    /**
     * The live-progress sink (108 §D) — `dag` produces the progress, this layer owns where it goes.
     *
     * `SseJson.mapper`, deliberately the SAME mapper the terminal write uses: node stats carry
     * `java.time` values, and the mapper that cannot serialize them is exactly how
     * `execution_started` went missing from the durable record once before (T36). Passing the
     * serializer in rather than letting `dag` choose one is what makes the two shapes unable to
     * drift.
     */
    @Bean
    fun executionProgress(
        executions: ExecutionRepository,
        executor: ExecutorProperties,
    ): ExecutionProgress =
        JdbcExecutionProgress(
            executions = executions,
            nodeStatsJson = { stats -> SseJson.mapper.writeValueAsString(stats) },
            throttleMillis = Duration.ofSeconds(executor.progressWriteIntervalSeconds).toMillis(),
        )

    @Bean
    fun staleExecutionSweeper(
        executions: ExecutionRepository,
        properties: ExecutionsProperties,
        executor: ExecutorProperties,
    ): StaleExecutionSweeper =
        StaleExecutionSweeper(
            executions,
            Duration.ofMinutes(properties.staleTimeoutMinutes),
            // 108 §D: the sweep's real cutoff is three of these; `stale-timeout-minutes` survives
            // only as the backstop for rows a pre-V20 instance left with no heartbeat at all.
            Duration.ofSeconds(executor.heartbeatSeconds),
        )

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
        /**
         * Fifteen seconds — see [SweepSchedulingConfiguration] for why this is not a config key.
         *
         * It was sixty until 108 §D, which was the right cadence for a sixty-MINUTE cutoff and the
         * wrong one for a forty-five-second one: a tick slower than the cutoff makes the cutoff a
         * fiction, and the promise "a crashed instance's rows are reaped in under a minute" is a
         * promise about tick + cutoff, not about the cutoff alone. The tick itself is one UPDATE
         * against a partial index over the live set (V20's `idx_executions_heartbeat`), so four
         * times as often is still nothing.
         */
        const val SWEEP_INTERVAL_MILLIS = 15_000L
    }
}
