package co.datapipelines.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The closed `job` tag set of `datapipelines.scheduled_jobs.tick_failed` (observability §4.1; #326's
 * four, #636's four). The values are literals, never read back from the enum under test: a job added,
 * renamed or dropped changes a dashboard's series and must change §4.1 with it.
 */
@Timeout(5)
class ScheduledJobTest {
    @Test
    fun `the job tags are the eight documented in observability 4_1 - in declaration order`() {
        ScheduledJob.entries.map { it.tag } shouldBe
            listOf(
                "stale_sweep",
                "event_retention",
                "audit_retention",
                "keys_purge",
                "dashboard_refresh_sweep",
                "dashboard_refresh_retention",
                "parameter_evaluation_sweep",
                "parameter_evaluation_retention",
            )
    }

    @Test
    fun `every tag is its constant's name in lower case - one spelling per job`() {
        ScheduledJob.entries.forEach { it.tag shouldBe it.name.lowercase() }
    }

    @Test
    fun `the meter and its one tag keep their documented names`() {
        ScheduledJob.FAILED_TICK_METER shouldBe "datapipelines.scheduled_jobs.tick_failed"
        ScheduledJob.JOB_TAG shouldBe "job"
    }
}
