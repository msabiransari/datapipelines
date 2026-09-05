package co.datapipelines.calculators

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The calendar and time kinds, one behaviour at a time.
 *
 * Two things this suite deliberately does NOT do. It does not assert a kind's output for "today" —
 * every input is fixed, because a test whose expectation depends on the day it runs is a test that
 * will be deleted the first Monday it fails. And it does not re-test `java.time`: the cases here
 * are the ones where a *choice* was made — which year a fiscal year is named after, what a month
 * boundary means in fiscal mode, where a business-day walk stops, what happens in the DST gap.
 */
class DateKindsTest {
    private fun evaluate(
        kind: String,
        vararg inputs: Pair<String, Any?>,
    ): Any? = CalculatorRegistry.require(kind).evaluate(inputs.toMap())

    private fun date(iso: String) = LocalDate.parse(iso)

    // ---- quarter, ISO, day-of-week, month length ----

    @Test
    fun `quarter_of_year covers all four boundaries`() {
        evaluate("quarter_of_year", "date" to date("2026-01-01")) shouldBe 1
        evaluate("quarter_of_year", "date" to date("2026-03-31")) shouldBe 1
        evaluate("quarter_of_year", "date" to date("2026-04-01")) shouldBe 2
        evaluate("quarter_of_year", "date" to date("2026-09-30")) shouldBe 3
        evaluate("quarter_of_year", "date" to date("2026-10-01")) shouldBe 4
        evaluate("quarter_of_year", "date" to date("2026-12-31")) shouldBe 4
    }

    @Test
    fun `iso_week and iso_year disagree with the calendar year at the turn - which is the point`() {
        // 2027-01-01 is a Friday; its ISO week belongs to 2026. A pipeline bucketing by ISO week
        // and labelling with the CALENDAR year puts those days in the wrong bucket, silently.
        evaluate("iso_year", "date" to date("2027-01-01")) shouldBe 2026
        evaluate("iso_week", "date" to date("2027-01-01")) shouldBe 53
        evaluate("iso_year", "date" to date("2026-01-01")) shouldBe 2026
        evaluate("iso_week", "date" to date("2026-01-01")) shouldBe 1
    }

    @Test
    fun `day_of_week counts from week_start, not from Monday`() {
        // 2026-08-14 is a Friday.
        evaluate("day_of_week", "date" to date("2026-08-14"), "week_start" to "monday") shouldBe 5
        evaluate("day_of_week", "date" to date("2026-08-14"), "week_start" to "sunday") shouldBe 6
        evaluate("day_of_week", "date" to date("2026-08-14"), "week_start" to "friday") shouldBe 1
        // Omitted: the documented default.
        evaluate("day_of_week", "date" to date("2026-08-14")) shouldBe 5
    }

    @Test
    fun `days_in_month knows February in both kinds of year`() {
        evaluate("days_in_month", "date" to date("2027-02-01")) shouldBe 28
        evaluate("days_in_month", "date" to date("2028-02-29")) shouldBe 29
        evaluate("days_in_month", "date" to date("2026-04-30")) shouldBe 30
        evaluate("days_in_month", "date" to date("2026-12-01")) shouldBe 31
    }

    // ---- fiscal: both a calendar year and a mid-year start ----

    @Test
    fun `fiscal_year with a 01-01 start is the calendar year`() {
        evaluate("fiscal_year", "date" to date("2026-01-01"), "fiscal_start" to "01-01") shouldBe 2026
        evaluate("fiscal_year", "date" to date("2026-12-31"), "fiscal_start" to "01-01") shouldBe 2026
    }

    @Test
    fun `fiscal_year with a 09-15 start is labelled by the year it STARTS in`() {
        // The day before the start still belongs to the year that began 14 months earlier.
        evaluate("fiscal_year", "date" to date("2026-09-14"), "fiscal_start" to "09-15") shouldBe 2025
        evaluate("fiscal_year", "date" to date("2026-09-15"), "fiscal_start" to "09-15") shouldBe 2026
        evaluate("fiscal_year", "date" to date("2027-01-31"), "fiscal_start" to "09-15") shouldBe 2026
    }

    @Test
    fun `fiscal_quarter with a 01-01 start is the calendar quarter`() {
        listOf("2026-01-01" to 1, "2026-03-31" to 1, "2026-04-01" to 2, "2026-07-01" to 3, "2026-12-31" to 4)
            .forEach { (day, expected) ->
                evaluate("fiscal_quarter", "date" to date(day), "fiscal_start" to "01-01") shouldBe expected
            }
    }

    @Test
    fun `fiscal_quarter with a 09-15 start moves every boundary by the same offset`() {
        listOf(
            "2026-09-15" to 1,
            "2026-12-14" to 1,
            "2026-12-15" to 2,
            "2027-03-15" to 3,
            "2027-06-15" to 4,
            "2027-09-14" to 4,
            "2027-09-15" to 1,
        ).forEach { (day, expected) ->
            evaluate("fiscal_quarter", "date" to date(day), "fiscal_start" to "09-15") shouldBe expected
        }
    }

    @Test
    fun `a 02-29 fiscal start resolves to 02-28 in a non-leap year`() {
        // ConfigValidator accepts 02-29 for exactly this reason: MonthDay.atYear clamps, so the
        // fiscal year still has a first day in every year rather than three years in four.
        evaluate("period_start", "date" to date("2027-06-01"), "unit" to "year", "mode" to "fiscal", "fiscal_start" to "02-29") shouldBe
            date("2027-02-28")
        evaluate("period_start", "date" to date("2028-06-01"), "unit" to "year", "mode" to "fiscal", "fiscal_start" to "02-29") shouldBe
            date("2028-02-29")
    }

    // ---- periods ----

    @Test
    fun `period_start and period_end bracket the calendar periods`() {
        val day = date("2026-08-14")
        evaluate("period_start", "date" to day, "unit" to "week", "week_start" to "monday") shouldBe date("2026-08-10")
        evaluate("period_end", "date" to day, "unit" to "week", "week_start" to "monday") shouldBe date("2026-08-16")
        evaluate("period_start", "date" to day, "unit" to "week", "week_start" to "sunday") shouldBe date("2026-08-09")
        evaluate("period_start", "date" to day, "unit" to "month") shouldBe date("2026-08-01")
        evaluate("period_end", "date" to day, "unit" to "month") shouldBe date("2026-08-31")
        evaluate("period_start", "date" to day, "unit" to "quarter") shouldBe date("2026-07-01")
        evaluate("period_end", "date" to day, "unit" to "quarter") shouldBe date("2026-09-30")
        evaluate("period_start", "date" to day, "unit" to "year") shouldBe date("2026-01-01")
        evaluate("period_end", "date" to day, "unit" to "year") shouldBe date("2026-12-31")
    }

    @Test
    fun `fiscal mode moves quarters and years but never months`() {
        val day = date("2026-08-14")
        evaluate("period_start", "date" to day, "unit" to "year", "mode" to "fiscal", "fiscal_start" to "09-15") shouldBe
            date("2025-09-15")
        evaluate("period_start", "date" to day, "unit" to "quarter", "mode" to "fiscal", "fiscal_start" to "09-15") shouldBe
            date("2026-06-15")
        evaluate("period_end", "date" to day, "unit" to "quarter", "mode" to "fiscal", "fiscal_start" to "09-15") shouldBe
            date("2026-09-14")

        // A month is a month in both modes — documented, and pinned so nobody "fixes" it.
        evaluate("period_start", "date" to day, "unit" to "month", "mode" to "fiscal", "fiscal_start" to "09-15") shouldBe
            date("2026-08-01")
    }

    @Test
    fun `prior_period steps whole periods back from the period containing the date`() {
        val day = date("2026-08-14")
        evaluate("prior_period", "date" to day, "unit" to "quarter") shouldBe date("2026-04-01")
        evaluate("prior_period", "date" to day, "unit" to "quarter", "offset" to 3) shouldBe date("2025-10-01")
        evaluate("prior_period", "date" to day, "unit" to "month") shouldBe date("2026-07-01")
        evaluate("prior_period", "date" to day, "unit" to "year") shouldBe date("2025-01-01")
        // Offset 0 is the current period's start — legal, and the identity a caller may rely on.
        evaluate("prior_period", "date" to day, "unit" to "month", "offset" to 0) shouldBe date("2026-08-01")
    }

    @Test
    fun `date_trunc snaps back and never forward`() {
        evaluate("date_trunc", "date" to date("2026-08-14"), "unit" to "day") shouldBe date("2026-08-14")
        evaluate("date_trunc", "date" to date("2026-08-14"), "unit" to "week") shouldBe date("2026-08-10")
        evaluate("date_trunc", "date" to date("2026-08-01"), "unit" to "month") shouldBe date("2026-08-01")
        evaluate("date_trunc", "date" to date("2026-12-31"), "unit" to "year") shouldBe date("2026-01-01")
    }

    // ---- arithmetic ----

    @Test
    fun `date_diff truncates partial units and goes negative backwards`() {
        evaluate("date_diff", "from" to date("2026-01-01"), "to" to date("2026-08-14"), "unit" to "month") shouldBe 7
        evaluate("date_diff", "from" to date("2026-01-01"), "to" to date("2026-01-31"), "unit" to "month") shouldBe 0
        evaluate("date_diff", "from" to date("2026-01-01"), "to" to date("2026-08-14"), "unit" to "day") shouldBe 225
        evaluate("date_diff", "from" to date("2026-01-01"), "to" to date("2026-08-14"), "unit" to "quarter") shouldBe 2
        evaluate("date_diff", "from" to date("2026-08-14"), "to" to date("2026-01-01"), "unit" to "month") shouldBe -7
        evaluate("date_diff", "from" to date("2026-01-01"), "to" to date("2026-01-01"), "unit" to "year") shouldBe 0
    }

    @Test
    fun `add_days and add_months, including the month-end clamp`() {
        evaluate("add_days", "date" to date("2026-08-14"), "days" to 0) shouldBe date("2026-08-14")
        evaluate("add_days", "date" to date("2026-08-14"), "days" to -30) shouldBe date("2026-07-15")
        evaluate("add_days", "date" to date("2026-12-31"), "days" to 1) shouldBe date("2027-01-01")

        evaluate("add_months", "date" to date("2026-01-31"), "months" to 1) shouldBe date("2026-02-28")
        evaluate("add_months", "date" to date("2028-01-31"), "months" to 1) shouldBe date("2028-02-29")
        evaluate("add_months", "date" to date("2026-03-31"), "months" to -1) shouldBe date("2026-02-28")
    }

    @Test
    fun `add_business_days skips the weekend and the listed holidays`() {
        val friday = date("2026-08-14")

        // One business day from a Friday is the Monday — the weekend is skipped, not counted.
        evaluate("add_business_days", "date" to friday, "days" to 1) shouldBe date("2026-08-17")
        // …unless that Monday is a holiday, in which case it is the Tuesday.
        evaluate(
            "add_business_days",
            "date" to friday,
            "days" to 1,
            "holidays" to listOf(date("2026-08-17")),
        ) shouldBe date("2026-08-18")
        // A holiday that falls on a weekend day changes nothing: it was already not a working day.
        evaluate(
            "add_business_days",
            "date" to friday,
            "days" to 1,
            "holidays" to listOf(date("2026-08-15")),
        ) shouldBe date("2026-08-17")
        // Backwards, over the same weekend.
        evaluate("add_business_days", "date" to date("2026-08-17"), "days" to -1) shouldBe friday
        // Zero is the identity even when the date itself is a weekend day.
        evaluate("add_business_days", "date" to date("2026-08-15"), "days" to 0) shouldBe date("2026-08-15")
        // A non-standard weekend: Friday/Saturday, as in much of the Gulf.
        evaluate(
            "add_business_days",
            "date" to date("2026-08-13"),
            "days" to 1,
            "weekend_days" to listOf("friday", "saturday"),
        ) shouldBe date("2026-08-16")
    }

    // ---- parsing, formatting, zones ----

    @Test
    fun `date_parse reads its pattern and refuses text that does not match`() {
        evaluate("date_parse", "text" to "14/08/2026", "format" to "dd/MM/yyyy") shouldBe date("2026-08-14")
        evaluate("date_parse", "text" to "20260814", "format" to "yyyyMMdd") shouldBe date("2026-08-14")

        val mismatch =
            shouldThrow<CalculatorEvaluationException> {
                evaluate("date_parse", "text" to "2026-08-14", "format" to "dd/MM/yyyy")
            }
        mismatch.input shouldBe "text"
        mismatch.message!!.shouldContain("does not match the pattern")
    }

    @Test
    fun `a bad pattern is refused against the format input, not the text`() {
        // The distinction matters to whoever has to fix it: one is the author's pattern, the other
        // is the data. `#` is a RESERVED character in a DateTimeFormatter pattern, so the pattern
        // does not compile at all — note that `!` would NOT do: an unreserved punctuation mark is
        // appended as a literal, the pattern compiles, and the failure lands on `text` instead.
        val bad =
            shouldThrow<CalculatorEvaluationException> {
                evaluate("date_parse", "text" to "14/08/2026", "format" to "dd/MM/uuuu#")
            }
        bad.input shouldBe "format"
        bad.message!!.shouldContain("not a valid date pattern")

        shouldThrow<CalculatorEvaluationException> {
            evaluate("date_format", "date" to date("2026-08-14"), "format" to "yyyy/MM/dd#")
        }.input shouldBe "format"
    }

    @Test
    fun `date_format renders through the same grammar`() {
        evaluate("date_format", "date" to date("2026-08-14"), "format" to "yyyyMMdd") shouldBe "20260814"
        evaluate("date_format", "date" to date("2026-08-14"), "format" to "'FY'yyyy-'Q'Q") shouldBe "FY2026-Q3"
    }

    @Test
    fun `tz_shift keeps the clock face and moves the instant - across a DST boundary too`() {
        // Plain case: 12:00 read as UTC, re-read as Berlin summer time (+02:00) → 10:00Z.
        evaluate(
            "tz_shift",
            "timestamp" to Instant.parse("2026-06-01T12:00:00Z"),
            "from_zone" to "UTC",
            "to_zone" to "Europe/Berlin",
        ) shouldBe Instant.parse("2026-06-01T10:00:00Z")

        // Winter, same zone, one hour less offset — the whole reason the zone is an input and
        // not a fixed number.
        evaluate(
            "tz_shift",
            "timestamp" to Instant.parse("2026-01-15T12:00:00Z"),
            "from_zone" to "UTC",
            "to_zone" to "Europe/Berlin",
        ) shouldBe Instant.parse("2026-01-15T11:00:00Z")

        // THE GAP. Berlin springs forward 2026-03-29 at 02:00 local, so 02:30 does not exist;
        // java.time moves it forward by the gap to 03:30+02:00 = 01:30Z. Documented, and pinned
        // here so a "simplification" to an offset arithmetic cannot silently change it.
        evaluate(
            "tz_shift",
            "timestamp" to Instant.parse("2026-03-29T02:30:00Z"),
            "from_zone" to "UTC",
            "to_zone" to "Europe/Berlin",
        ) shouldBe Instant.parse("2026-03-29T01:30:00Z")

        // THE OVERLAP. Berlin falls back 2026-10-25 at 03:00 local; 02:30 happens twice and the
        // EARLIER offset (+02:00) wins → 00:30Z.
        evaluate(
            "tz_shift",
            "timestamp" to Instant.parse("2026-10-25T02:30:00Z"),
            "from_zone" to "UTC",
            "to_zone" to "Europe/Berlin",
        ) shouldBe Instant.parse("2026-10-25T00:30:00Z")
    }

    @Test
    fun `an unknown unit, mode, day name or zone is refused naming the input`() {
        shouldThrow<CalculatorEvaluationException> {
            evaluate("period_start", "date" to date("2026-08-14"), "unit" to "fortnight")
        }.input shouldBe "unit"

        shouldThrow<CalculatorEvaluationException> {
            evaluate("period_start", "date" to date("2026-08-14"), "unit" to "month", "mode" to "lunar")
        }.input shouldBe "mode"

        shouldThrow<CalculatorEvaluationException> {
            evaluate("day_of_week", "date" to date("2026-08-14"), "week_start" to "caturday")
        }.input shouldBe "week_start"

        // A fixed offset is deliberately not an IANA zone id.
        shouldThrow<CalculatorEvaluationException> {
            evaluate(
                "tz_shift",
                "timestamp" to Instant.EPOCH,
                "from_zone" to "+02:00",
                "to_zone" to "UTC",
            )
        }.input shouldBe "from_zone"

        shouldThrow<CalculatorEvaluationException> {
            evaluate("fiscal_year", "date" to date("2026-08-14"), "fiscal_start" to "SEP-15")
        }.input shouldBe "fiscal_start"
    }

    @Test
    fun `a missing required input is refused by name, not by ClassCastException`() {
        val missing = shouldThrow<CalculatorEvaluationException> { evaluate("add_days", "date" to date("2026-08-14")) }
        missing.input shouldBe "days"
        missing.message!!.shouldContain("required")
    }
}
