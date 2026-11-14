package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.maintenance.keep.KeepFixtures.NEW_YORK
import co.datapipelines.lake.maintenance.keep.KeepFixtures.UTC
import co.datapipelines.lake.maintenance.keep.KeepFixtures.at
import co.datapipelines.lake.maintenance.keep.KeepFixtures.days
import co.datapipelines.lake.maintenance.keep.KeepFixtures.history
import co.datapipelines.lake.maintenance.keep.KeepFixtures.rule
import co.datapipelines.lake.profiles.KeepDuration
import co.datapipelines.lake.profiles.KeepPeriod
import co.datapipelines.lake.profiles.KeepUnit
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/** #960 T8 (record row K1, the calendar half) and the tag names of record call 42 item 1. */
class KeepCalendarTest {
    private fun label(
        period: KeepPeriod,
        date: String,
    ) = KeepLabel(period, KeepCalendar.startOf(period, LocalDate.parse(date)))

    @Test
    fun `the record's five names`() {
        label(KeepPeriod.DAY, "2026-10-31").tagName shouldBe "dp-keep-day-2026-10-31"
        label(KeepPeriod.WEEK, "2026-10-28").tagName shouldBe "dp-keep-week-2026-W44"
        label(KeepPeriod.MONTH, "2026-10-17").tagName shouldBe "dp-keep-month-2026-10"
        label(KeepPeriod.QUARTER, "2026-11-30").tagName shouldBe "dp-keep-quarter-2026-Q4"
        label(KeepPeriod.YEAR, "2026-06-01").tagName shouldBe "dp-keep-year-2026"
    }

    @Test
    fun `ISO week labels at a year's edge use the ISO week-year`() {
        label(KeepPeriod.WEEK, "2026-12-31").tagName shouldBe "dp-keep-week-2026-W53"
        label(KeepPeriod.WEEK, "2027-01-04").tagName shouldBe "dp-keep-week-2027-W01"
        label(KeepPeriod.WEEK, "2024-12-31").tagName shouldBe "dp-keep-week-2025-W01"
        label(KeepPeriod.WEEK, "2021-01-01").tagName shouldBe "dp-keep-week-2020-W53"
        label(KeepPeriod.WEEK, "2021-01-01").start shouldBe LocalDate.parse("2020-12-28")
    }

    @Test
    fun `a name parses back to its label only in its exact spelling`() {
        listOf(
            label(KeepPeriod.DAY, "2028-02-29"),
            label(KeepPeriod.WEEK, "2026-12-28"),
            label(KeepPeriod.WEEK, "2024-12-30"),
            label(KeepPeriod.MONTH, "2026-01-31"),
            label(KeepPeriod.QUARTER, "2026-08-15"),
            label(KeepPeriod.YEAR, "2026-12-31"),
        ).forEach { KeepCalendar.parse(it.tagName) shouldBe it }
        listOf(
            "dp-hold-audit",
            "dp-keep-",
            "dp-keep-day-2026-02-30",
            "dp-keep-day-2026-2-03",
            "dp-keep-week-2025-W53",
            "dp-keep-week-2026-W1",
            "dp-keep-month-2026-13",
            "dp-keep-quarter-2026-Q5",
            "dp-keep-year-26",
            "dp-keep-fortnight-2026-01",
            "main",
        ).forEach { KeepCalendar.parse(it) shouldBe null }
    }

    @Test
    fun `T8 31 January plus one month ends on the last day of February`() {
        KeepCalendar.keepEnd(label(KeepPeriod.DAY, "2026-01-30"), KeepDuration(1, KeepUnit.MONTHS), UTC) shouldBe at("2026-02-28T00:00:00Z")
        KeepCalendar.keepEnd(label(KeepPeriod.MONTH, "2026-01-01"), KeepDuration(1, KeepUnit.MONTHS), UTC) shouldBe
            at("2026-03-01T00:00:00Z")
    }

    @Test
    fun `T8 29 February plus one year ends on 28 February`() {
        KeepCalendar.keepEnd(label(KeepPeriod.DAY, "2028-02-28"), KeepDuration(1, KeepUnit.YEARS), UTC) shouldBe at("2029-02-28T00:00:00Z")
    }

    @Test
    fun `T8 a day of 23 hours and a day of 25 hours in the profile's zone`() {
        val spring = label(KeepPeriod.DAY, "2026-03-07")
        KeepCalendar.periodEnd(spring, NEW_YORK) shouldBe at("2026-03-08T05:00:00Z")
        val springEnd = KeepCalendar.keepEnd(spring, KeepDuration(1, KeepUnit.DAYS), NEW_YORK)
        springEnd shouldBe at("2026-03-09T04:00:00Z")
        Duration.between(KeepCalendar.periodEnd(spring, NEW_YORK), springEnd) shouldBe Duration.ofHours(23)

        val autumn = label(KeepPeriod.DAY, "2026-10-31")
        KeepCalendar.periodEnd(autumn, NEW_YORK) shouldBe at("2026-11-01T04:00:00Z")
        val autumnEnd = KeepCalendar.keepEnd(autumn, KeepDuration(1, KeepUnit.DAYS), NEW_YORK)
        autumnEnd shouldBe at("2026-11-02T05:00:00Z")
        Duration.between(KeepCalendar.periodEnd(autumn, NEW_YORK), autumnEnd) shouldBe Duration.ofHours(25)

        val dayRule = rule(KeepPeriod.DAY, 1, KeepUnit.DAYS)
        val plan =
            KeepReconcile.plan(
                listOf(dayRule),
                NEW_YORK,
                at("2026-03-01T00:00:00Z"),
                history(1L to "2026-03-01T12:00:00Z"),
                at("2026-03-08T12:00:00Z"),
            )
        plan.creates.single().end shouldBe springEnd
    }

    @Test
    fun `T8 a snapshot 100 days old held for 90 days gets the age end minus its commit time, never the rule's duration`() {
        val now = at("2026-11-11T12:00:00Z")
        val committed = now - Duration.ofDays(100)
        val plan = KeepReconcile.plan(listOf(days(KeepPeriod.DAY, 90)), UTC, committed, history(7L to committed.toString()), now)
        val today = plan.creates.single { it.name == "dp-keep-day-2026-11-10" }
        today.end shouldBe at("2027-02-09T00:00:00Z")
        today.maxRefAgeMs shouldBe Duration.between(committed, today.end).toMillis()
        today.maxRefAgeMs shouldNotBe Duration.ofDays(90).toMillis()
        today.maxRefAgeMs shouldBe Duration.ofDays(190).minusHours(12).toMillis()
    }
}
