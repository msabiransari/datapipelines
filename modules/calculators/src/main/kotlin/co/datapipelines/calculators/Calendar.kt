package co.datapipelines.calculators

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The calendar arithmetic every date kind shares — the fiscal year's shape, week starts, period
 * boundaries, and the two grammars that are contract (C10): `DateTimeFormatter` patterns and
 * IANA zone ids.
 *
 * ## The two conventions this file fixes, because somebody has to
 *
 * 1. **A fiscal year is labelled by the calendar year it STARTS in.** With
 *    `org_fiscal_start_date = "04-06"`, 2026-01-15 is in fiscal year **2025** — the one that
 *    began 2025-04-06. Deployments that label by the ENDING year (US federal FY) declare a
 *    parameter or add 1 in SQL; the rule is stated in `calculators.md` and never inferred.
 * 2. **`02-29` is a legal fiscal start** and resolves to 02-28 in a non-leap year, because
 *    `MonthDay.atYear` says so. `ConfigValidator` accepts it for exactly that reason.
 */
internal object Calendar {
    /** `period_start` / `period_end` / `prior_period` / `date_trunc` units. */
    const val WEEK = "week"
    const val MONTH = "month"
    const val QUARTER = "quarter"
    const val YEAR = "year"
    const val DAY = "day"

    /** `period_*` modes. Prefixed because a bare `CALENDAR` reads as this object's own name. */
    const val MODE_CALENDAR = "calendar"
    const val MODE_FISCAL = "fiscal"

    /** Named because `% 7` and `/ 3` in date arithmetic are the two easiest typos to miss. */
    private const val DAYS_IN_WEEK = 7
    private const val MONTHS_IN_QUARTER = 3

    val PERIOD_UNITS = listOf(WEEK, MONTH, QUARTER, YEAR)
    val TRUNC_UNITS = listOf(DAY, WEEK, MONTH, QUARTER, YEAR)
    val DIFF_UNITS = listOf(DAY, WEEK, MONTH, QUARTER, YEAR)
    val MODES = listOf(MODE_CALENDAR, MODE_FISCAL)

    private val DAY_NAMES: Map<String, DayOfWeek> = DayOfWeek.entries.associateBy { it.name.lowercase() }

    /** The `MM-DD` fiscal start, refused with the input's name when it is not one. */
    fun monthDay(
        value: String,
        input: String,
    ): MonthDay =
        runCatching { MonthDay.parse("--$value") }
            .getOrElse {
                throw CalculatorEvaluationException(
                    input,
                    "Input '$input' must be an MM-DD calendar day (e.g. 01-01 or 09-15), but was '$value'.",
                )
            }

    /** `monday` … `sunday`, refused with the input's name. */
    fun dayOfWeek(
        value: String,
        input: String,
    ): DayOfWeek =
        DAY_NAMES[value.lowercase()]
            ?: throw CalculatorEvaluationException(
                input,
                "Input '$input' must be a day name (${DAY_NAMES.keys.joinToString(", ")}), but was '$value'.",
            )

    /** An IANA zone id, refused with the input's name. A fixed offset is deliberately not one. */
    fun zone(
        value: String,
        input: String,
    ): ZoneId =
        if (value in ZoneId.getAvailableZoneIds()) {
            ZoneId.of(value)
        } else {
            throw CalculatorEvaluationException(
                input,
                "Input '$input' must be an IANA zone id (e.g. UTC or Europe/London), but was '$value'.",
            )
        }

    /** A `DateTimeFormatter` pattern, refused with the input's name when it does not compile. */
    fun formatter(
        pattern: String,
        input: String,
    ): DateTimeFormatter =
        runCatching { DateTimeFormatter.ofPattern(pattern) }
            .getOrElse { e ->
                throw CalculatorEvaluationException(
                    input,
                    "Input '$input' is not a valid date pattern: ${e.message}",
                    e,
                )
            }

    fun requireOneOf(
        value: String,
        allowed: List<String>,
        input: String,
    ): String =
        value.lowercase().takeIf { it in allowed }
            ?: throw CalculatorEvaluationException(
                input,
                "Input '$input' must be one of ${allowed.joinToString(" | ")}, but was '$value'.",
            )

    /** The start of the fiscal year [date] falls in — the latest `fiscalStart` on or before it. */
    fun fiscalYearStart(
        date: LocalDate,
        fiscalStart: MonthDay,
    ): LocalDate {
        val thisYear = fiscalStart.atYear(date.year)
        return if (date < thisYear) fiscalStart.atYear(date.year - 1) else thisYear
    }

    /** 1–4: which quarter of its fiscal year [date] falls in. */
    fun fiscalQuarter(
        date: LocalDate,
        fiscalStart: MonthDay,
    ): Int = (ChronoUnit.MONTHS.between(fiscalYearStart(date, fiscalStart), date) / MONTHS_IN_QUARTER).toInt() + 1

    /** The first day of the period of [unit] containing [date]. */
    @Suppress("ReturnCount")
    fun periodStart(
        date: LocalDate,
        unit: String,
        mode: String,
        fiscalStart: MonthDay,
        weekStart: DayOfWeek,
    ): LocalDate =
        when (unit) {
            WEEK -> {
                date.minusDays(((date.dayOfWeek.value - weekStart.value + DAYS_IN_WEEK) % DAYS_IN_WEEK).toLong())
            }

            // A month is a month in both modes: no fiscal calendar in use redefines its boundary,
            // and pretending `mode` matters here would invite an author to expect that it does.
            MONTH -> {
                date.withDayOfMonth(1)
            }

            QUARTER -> {
                if (mode == MODE_FISCAL) {
                    fiscalYearStart(date, fiscalStart)
                        .plusMonths(MONTHS_IN_QUARTER.toLong() * (fiscalQuarter(date, fiscalStart) - 1))
                } else {
                    date.withDayOfMonth(1).withMonth((date.monthValue - 1) / MONTHS_IN_QUARTER * MONTHS_IN_QUARTER + 1)
                }
            }

            YEAR -> {
                if (mode == MODE_FISCAL) fiscalYearStart(date, fiscalStart) else date.withDayOfYear(1)
            }

            else -> {
                date
            }
        }

    /** The last day of the period of [unit] containing [date] — the day before the next starts. */
    fun periodEnd(
        date: LocalDate,
        unit: String,
        mode: String,
        fiscalStart: MonthDay,
        weekStart: DayOfWeek,
    ): LocalDate = nextPeriodStart(periodStart(date, unit, mode, fiscalStart, weekStart), unit).minusDays(1)

    /** The start of the period [offset] periods before the one containing [date]. */
    fun priorPeriodStart(
        date: LocalDate,
        unit: String,
        offset: Int,
        mode: String,
        fiscalStart: MonthDay,
        weekStart: DayOfWeek,
    ): LocalDate = shift(periodStart(date, unit, mode, fiscalStart, weekStart), unit, -offset.toLong())

    private fun nextPeriodStart(
        start: LocalDate,
        unit: String,
    ): LocalDate = shift(start, unit, 1)

    private fun shift(
        start: LocalDate,
        unit: String,
        periods: Long,
    ): LocalDate =
        when (unit) {
            WEEK -> start.plusWeeks(periods)
            MONTH -> start.plusMonths(periods)
            QUARTER -> start.plusMonths(MONTHS_IN_QUARTER * periods)
            YEAR -> start.plusYears(periods)
            else -> start.plusDays(periods)
        }

    /** Complete [unit]s from [from] to [to]; negative when [to] precedes [from]. */
    fun diff(
        from: LocalDate,
        to: LocalDate,
        unit: String,
    ): Long =
        when (unit) {
            DAY -> ChronoUnit.DAYS.between(from, to)
            WEEK -> ChronoUnit.WEEKS.between(from, to)
            MONTH -> ChronoUnit.MONTHS.between(from, to)
            QUARTER -> ChronoUnit.MONTHS.between(from, to) / MONTHS_IN_QUARTER
            else -> ChronoUnit.YEARS.between(from, to)
        }
}
