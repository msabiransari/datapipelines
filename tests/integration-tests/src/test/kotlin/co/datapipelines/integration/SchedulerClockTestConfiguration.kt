package co.datapipelines.integration

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The controlled scheduler clock (#342) — the `schedulerClock` bean [ScheduleBindingsE2eTest]'s
 * context runs on, in place of [java.time.Clock.systemUTC] (`SchedulerAutoConfiguration` steps
 * aside for a bean of this name).
 *
 * The scheduler's now is PINNED a few seconds after midnight in the schedule's zone
 * (America/New_York). At that pinned instant, yesterday's 23:45 is the latest occurrence the
 * pattern has produced — so the occurrence the suite makes due is the one the dispatcher's
 * latest-occurrence catch-up fires, whatever wall-clock moment the JVM runs at. The pin's own
 * day is the day AFTER the occurrence's, which is what pins the run row's `started_at` (the
 * ledger stamps it from this same clock) on D+1.
 *
 * The pin must stay between one 23:45 and the next: moved past today's 23:45 it re-creates the
 * #342 defect (today's occurrence becomes the latest, the dispatcher fires it instead of the
 * one made due, and the suite goes red on `the run the dispatcher fired was the occurrence we
 * made due`) — that flip is the fix's falsification. Five seconds after midnight keeps it a
 * full pattern period clear of both 23:45s, and in the real past (a pin in the real future
 * would leave the run task parked in db-scheduler until the wall clock caught up).
 */
@TestConfiguration
class SchedulerClockTestConfiguration {
    @Bean(name = ["schedulerClock"])
    fun schedulerClock(): Clock = CLOCK

    companion object {
        /** The schedule zone — the suite's scenario is specified in it, through DST or not. */
        val NY_ZONE: ZoneId = ZoneId.of("America/New_York")

        /**
         * The pinned scheduler now: today at 00:00:05 New York, where "today" is the class-load
         * day. Deriving the day from the wall clock once (instead of hard-coding a date) keeps
         * the suite off any calendar fixture; deriving `next_due_at` from THIS instant in the
         * suite (not from the JVM clock again) keeps the scenario immune to a real-midnight
         * crossing mid-suite.
         */
        val SCHEDULER_NOW: Instant =
            LocalDate.now(NY_ZONE).atStartOfDay(NY_ZONE).plusSeconds(PIN_SECONDS_AFTER_MIDNIGHT).toInstant()

        /** The clock every scheduler consumer in the suite's context reads. */
        val CLOCK: Clock = Clock.fixed(SCHEDULER_NOW, NY_ZONE)

        private const val PIN_SECONDS_AFTER_MIDNIGHT = 5L
    }
}
