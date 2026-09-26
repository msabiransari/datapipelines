package co.datapipelines.scheduler

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The `datapipelines.scheduler.*` keys ([Configuration §3.29](configuration.md), the one definition —
 * D8; scheduler design revision §7.2). The defaults here equal the documented ones and
 * application.yml's; `SchedulerConfigKeysSpecDriftTest` holds the three together.
 *
 * `threads`, `polling-interval-seconds`, `heartbeat-interval-seconds` and `shutdown-wait-seconds`
 * are handed to db-scheduler's starter through its own `db-scheduler.*` keys in application.yml, so
 * the library's defaults (10 threads, a 30-minute shutdown wait — A19) never apply.
 */
@ConfigurationProperties(prefix = "datapipelines.scheduler")
data class SchedulerProperties(
    /** This instance dispatches; `false` is API mode — the client exists, nothing polls (record §1, A19). */
    val enabled: Boolean = true,
    /** db-scheduler worker threads. No task holds one for a pipeline's runtime (record §2.2). */
    val threads: Int = 2,
    /** db-scheduler's poll for due tasks. */
    val pollingIntervalSeconds: Long = 10,
    /** db-scheduler's execution heartbeat; a task is dead after [MISSED_HEARTBEATS_LIMIT] missed beats. */
    val heartbeatIntervalSeconds: Long = 30,
    /** The dispatcher's and the reconciler's fixed delay. */
    val tickIntervalSeconds: Long = 10,
    /** Scheduled executions in flight on this instance — the system identity's slot budget (R4). */
    val maxConcurrentRuns: Int = 4,
    /** How long after its occurrence (or its recording, for catch-up and manual runs) a run may still be admitted (record §3). */
    val latenessSeconds: Long = 600,
    /** The `latest` policy's reach: an older missed occurrence is summarized, never caught up (record §3). */
    val catchUpMaxAgeSeconds: Long = 86_400,
    /** How long shutdown waits for in-flight launches; also db-scheduler's `shutdown-max-wait` (record §7.2, A7). */
    val shutdownWaitSeconds: Long = 5,
    /** B18 guard (L4): two consecutive occurrences closer than this are refused at save. */
    val minIntervalSeconds: Long = 300,
    /** B18 guard (L4): live schedules per workspace. */
    val maxSchedulesPerWorkspace: Int = 100,
) {
    init {
        require(threads >= 1) { "datapipelines.scheduler.threads must be at least 1, was $threads" }
        require(pollingIntervalSeconds >= 1) { "datapipelines.scheduler.polling-interval-seconds must be at least 1" }
        require(heartbeatIntervalSeconds >= 1) { "datapipelines.scheduler.heartbeat-interval-seconds must be at least 1" }
        require(tickIntervalSeconds >= 1) { "datapipelines.scheduler.tick-interval-seconds must be at least 1" }
        require(maxConcurrentRuns >= 1) { "datapipelines.scheduler.max-concurrent-runs must be at least 1" }
        require(latenessSeconds in 1..MAX_LATENESS_SECONDS) {
            "datapipelines.scheduler.lateness-seconds must be 1..$MAX_LATENESS_SECONDS, was $latenessSeconds"
        }
        require(catchUpMaxAgeSeconds in 1..MAX_CATCH_UP_SECONDS) {
            // The dispatcher scans the catch-up window occurrence by occurrence (ScheduleDispatcher.WINDOW_CAP):
            // a week of a one-minute pattern is 10 080 matches, inside the cap.
            "datapipelines.scheduler.catch-up-max-age-seconds must be 1..$MAX_CATCH_UP_SECONDS, was $catchUpMaxAgeSeconds"
        }
        require(shutdownWaitSeconds in 1..MAX_SHUTDOWN_WAIT_SECONDS) {
            // Spring's per-phase lifecycle timeout is 30 s and the drain's flush bound 20 s (record §7.2):
            // a longer wait would be cut off by the framework, not honoured.
            "datapipelines.scheduler.shutdown-wait-seconds must be 1..$MAX_SHUTDOWN_WAIT_SECONDS, was $shutdownWaitSeconds"
        }
        require(minIntervalSeconds >= MINUTE_SECONDS) {
            // Public cron has minute precision (record §8): a floor below one minute guards nothing.
            "datapipelines.scheduler.min-interval-seconds must be at least $MINUTE_SECONDS, was $minIntervalSeconds"
        }
        require(maxSchedulesPerWorkspace >= 1) { "datapipelines.scheduler.max-schedules-per-workspace must be at least 1" }
    }

    val lateness: Duration get() = Duration.ofSeconds(latenessSeconds)
    val catchUpMaxAge: Duration get() = Duration.ofSeconds(catchUpMaxAgeSeconds)
    val shutdownWait: Duration get() = Duration.ofSeconds(shutdownWaitSeconds)
    val minInterval: Duration get() = Duration.ofSeconds(minIntervalSeconds)
    val tickInterval: Duration get() = Duration.ofSeconds(tickIntervalSeconds)

    /**
     * How long a `starting` claim may go without an execution record before the reconciler calls it
     * `unknown` / `start_unconfirmed` (record §2.1): db-scheduler's own dead-execution window, so
     * the reconciler never judges a claim the library would still consider alive.
     */
    val startGrace: Duration get() = Duration.ofSeconds(heartbeatIntervalSeconds * MISSED_HEARTBEATS_LIMIT)

    companion object {
        /** db-scheduler's `missed-heartbeats-limit` — the record's "six missed heartbeats" (§7.2), fixed. */
        const val MISSED_HEARTBEATS_LIMIT = 6

        /** Upper bound on [latenessSeconds]: one day — a run later than that is not "late", it is a different day's. */
        const val MAX_LATENESS_SECONDS = 86_400L

        /** Upper bound on [catchUpMaxAgeSeconds]: one week (see the check). */
        const val MAX_CATCH_UP_SECONDS = 604_800L

        /** Upper bound on [shutdownWaitSeconds] — under the drain's 20 s flush and Spring's 30 s phase. */
        const val MAX_SHUTDOWN_WAIT_SECONDS = 15L

        private const val MINUTE_SECONDS = 60L
    }
}
