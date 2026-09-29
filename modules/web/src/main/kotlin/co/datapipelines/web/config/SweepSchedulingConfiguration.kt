package co.datapipelines.web.config

import co.datapipelines.executor.ExecutionProgress
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.JdbcExecutionProgress
import co.datapipelines.executor.StaleExecutionSweeper
import co.datapipelines.web.sse.SseJson
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.TaskSchedulerRouter
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
 * - **The jobs have a scheduler of their own (#316):** [taskScheduler], ONE daemon thread named
 *   `dp-scheduled`. Every `@Scheduled` job in the context ticks on it — this sweep, the pool reaper
 *   (`PoolReaperSchedulingConfiguration`) and the retention sweep (`RetentionSchedulingConfiguration`)
 *   — and nothing else does: no bean is wired to it; Spring's registrar looks it up. Until #316
 *   none was declared, and the registrar fell back to the context's unique
 *   `ScheduledExecutorService` — `WebSurfaceConfiguration`'s `sseLogScheduler`, the `dp-sse-log`
 *   thread that serves SSE replays — so a slow tick stalled every replay and a burst of replays
 *   delayed the sweep. The bean is NAMED `taskScheduler` as well as typed: the registrar takes the
 *   unique `TaskScheduler`, and that name when there are several, so a second `TaskScheduler`
 *   bean cannot send the jobs back to the SSE executor (`ScheduledJobsSchedulerTest`, and the
 *   assembled application's `ScheduledJobsSchedulerE2eTest`, observe every job's tick there).
 * - **One thread is enough:** three jobs, each tick one bounded statement or an in-memory walk
 *   (the retention tick has a two-second budget). One thread serialises them — a job waits at most
 *   for one other job's tick — and a second would fix nothing that matters: `fixedDelay` already
 *   keeps two ticks of ONE job from overlapping at any pool size. The count is a code constant, not
 *   a key — a knob nobody should turn is not worth a documented key.
 * - **Shutdown (#316):** no tick STARTS once the context begins to close. The scheduler stops
 *   taking ticks on Spring's `ContextClosedEvent` — before the scheduler admission gate, the
 *   execution drain ([ExecutionDrainLifecycle]) and the writers' drain ([PersistenceDrainLifecycle])
 *   run — so no job starts against a draining store. A tick already running finishes: the
 *   scheduler's own lifecycle phase ([ScheduledJobsTaskScheduler.SHUTDOWN_PHASE], below both drains
 *   and the web server's stop) waits for it, bounded by `spring.lifecycle.timeout-per-shutdown-phase`,
 *   while the metadata pool is still open; then [ScheduledJobsTaskScheduler] logs the stop.
 *   Deliberately NOT `waitForTasksToCompleteOnShutdown`: that flag makes Spring skip the early stop
 *   and keep starting ticks through both drains, until the bean is destroyed.
 * - **`fixedDelay`, not `fixedRate`:** a slow tick (metadata DB busy) delays the next one
 *   instead of piling onto it. The sweep catches up by construction — staleness is measured
 *   from `started_at`, not from when the last tick ran.
 * - **The cadence is a code constant, not a configuration key:** fifteen seconds
 *   ([StaleExecutionSweepScheduler.SWEEP_INTERVAL_MILLIS] says why, and why not the sixty this
 *   bullet once named), and configuration.md is the only place a key may be defined — a knob
 *   nobody should turn is not worth a documented key.
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
            // only as the backstop for rows a pre-V21 instance left with no heartbeat at all.
            Duration.ofSeconds(executor.heartbeatSeconds),
        )

    @Bean
    fun staleExecutionSweepScheduler(sweeper: StaleExecutionSweeper): StaleExecutionSweepScheduler = StaleExecutionSweepScheduler(sweeper)

    /**
     * The `@Scheduled` jobs' scheduler (#316) — see the class KDoc. Spring's shutdown defaults are
     * kept on purpose (the early stop on `ContextClosedEvent`, the coordinated stop in its phase);
     * the bounded await only lets the stop line say whether the thread ended.
     */
    @Bean(name = [TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME])
    fun taskScheduler(): ScheduledJobsTaskScheduler =
        ScheduledJobsTaskScheduler().apply {
            poolSize = ScheduledJobsTaskScheduler.THREADS
            phase = ScheduledJobsTaskScheduler.SHUTDOWN_PHASE
            setThreadFactory { runnable -> Thread(runnable, ScheduledJobsTaskScheduler.THREAD_NAME).apply { isDaemon = true } }
            setAwaitTerminationMillis(ScheduledJobsTaskScheduler.SHUTDOWN_AWAIT_MILLIS)
        }
}

/**
 * The scheduler every `@Scheduled` job ticks on (#316), configured by
 * [SweepSchedulingConfiguration.taskScheduler]. A plain [ThreadPoolTaskScheduler] plus one line: when
 * the context destroys it — after every lifecycle stop, the drains included, and after its own
 * coordinated stop has waited for a tick in flight — it logs whether its thread ended
 * (`event=shutdown.scheduled_jobs_stopped`) or a tick outlived the wait
 * (`event=shutdown.scheduled_jobs_incomplete`, WARN), the execution drain's complete/incomplete pair.
 */
class ScheduledJobsTaskScheduler : ThreadPoolTaskScheduler() {
    override fun shutdown() {
        super.shutdown()
        if (scheduledExecutor.isTerminated) {
            LOG.info("event=shutdown.scheduled_jobs_stopped thread={}", THREAD_NAME)
        } else {
            LOG.warn(
                "event=shutdown.scheduled_jobs_incomplete thread={} await_ms={} " +
                    "message=\"a scheduled tick was still running when the jobs' scheduler was destroyed\"",
                THREAD_NAME,
                SHUTDOWN_AWAIT_MILLIS,
            )
        }
    }

    companion object {
        /** The jobs' one thread — the name every scheduled job's log line carries. */
        const val THREAD_NAME = "dp-scheduled"

        /** One — see [SweepSchedulingConfiguration] for why one is enough. */
        const val THREADS = 1

        /**
         * Where the scheduler's coordinated stop waits for a tick in flight: below the writers' drain
         * ([PersistenceDrainLifecycle.PHASE]) and the web server's stop (`DEFAULT_PHASE - 2048`), so the
         * wait never delays the readiness flip or a drain. Pinned rather than left to Spring's executor
         * default, which is this value only since Spring 6.2 — before, executors took
         * `SmartLifecycle.DEFAULT_PHASE` and stopped FIRST, where this wait would run ahead of the
         * execution drain.
         */
        const val SHUTDOWN_PHASE = Int.MAX_VALUE / 2

        /**
         * How long destruction waits for the thread after interrupting it. Short on purpose: the
         * coordinated stop has already waited for a tick in flight, so the executor is normally
         * terminated by now, and only a tick that outlived `timeout-per-shutdown-phase` is still
         * running — it is cut when the metadata pool closes, and the next tick (on any replica)
         * redoes it; every job is idempotent.
         */
        const val SHUTDOWN_AWAIT_MILLIS = 2_000L

        private val LOG = LoggerFactory.getLogger(ScheduledJobsTaskScheduler::class.java)
    }
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
         * against a partial index over the live set (V21's `idx_executions_heartbeat`), so four
         * times as often is still nothing.
         */
        const val SWEEP_INTERVAL_MILLIS = 15_000L
    }
}
