package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.profiles.KeepDuration
import co.datapipelines.lake.profiles.KeepPeriod
import co.datapipelines.lake.profiles.KeepUnit
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * One period of a keep rule: its [period] and the local date it starts on, in the profile's zone (record call 42,
 * brief 960 D2). A day starts at its date, an ISO week on its Monday, a month, quarter or year on its first day.
 */
data class KeepLabel(
    val period: KeepPeriod,
    val start: LocalDate,
) {
    /** The tag name: `dp-keep-day-2026-10-31`, `dp-keep-week-2026-W44`, `dp-keep-month-2026-10`, `-quarter-2026-Q4`, `-year-2026`. */
    val tagName: String
        get() = KeepCalendar.PREFIX + period.wire + "-" + KeepCalendar.labelText(period, start)
}

/**
 * The calendar of keep periods (brief 960 D2, D4): labels, period starts and ends, and a keep's end, every one computed
 * from an enum and integers with `java.time` (never from user text). An end E is the first instant of the next period
 * in the zone given; a keep's end is `E + keep_for` by calendar arithmetic on E's date, at the start of that day.
 */
object KeepCalendar {
    /** Every computed keep's tag name starts with this; nothing else in DP creates, re-ages or removes such a tag. */
    const val PREFIX = "dp-keep-"

    private val WEEK = Regex("""(\d{4})-W(\d{2})""")
    private val QUARTER = Regex("""(\d{4})-Q(\d)""")
    private val YEAR = Regex("""\d{4}""")

    /** The start of the [period] that holds [date]. */
    fun startOf(
        period: KeepPeriod,
        date: LocalDate,
    ): LocalDate =
        when (period) {
            KeepPeriod.DAY -> date
            KeepPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            KeepPeriod.MONTH -> date.withDayOfMonth(1)
            KeepPeriod.QUARTER -> date.with(IsoFields.DAY_OF_QUARTER, 1L)
            KeepPeriod.YEAR -> date.withDayOfYear(1)
        }

    /** The start of the period after the one starting at [start]. */
    fun next(
        period: KeepPeriod,
        start: LocalDate,
    ): LocalDate =
        when (period) {
            KeepPeriod.DAY -> start.plusDays(1)
            KeepPeriod.WEEK -> start.plusWeeks(1)
            KeepPeriod.MONTH -> start.plusMonths(1)
            KeepPeriod.QUARTER -> start.plus(1, IsoFields.QUARTER_YEARS)
            KeepPeriod.YEAR -> start.plusYears(1)
        }

    /** The start of the period before the one starting at [start]. */
    fun previous(
        period: KeepPeriod,
        start: LocalDate,
    ): LocalDate =
        when (period) {
            KeepPeriod.DAY -> start.minusDays(1)
            KeepPeriod.WEEK -> start.minusWeeks(1)
            KeepPeriod.MONTH -> start.minusMonths(1)
            KeepPeriod.QUARTER -> start.minus(1, IsoFields.QUARTER_YEARS)
            KeepPeriod.YEAR -> start.minusYears(1)
        }

    /** E: the first instant of the next period in [zone] (a midnight that does not exist is the first instant after it). */
    fun periodEnd(
        label: KeepLabel,
        zone: ZoneId,
    ): Instant = next(label.period, label.start).atStartOfDay(zone).toInstant()

    /** The keep's end: `E + keep_for` on E's date in [zone] (31 January + 1 month = the last day of February). */
    fun keepEnd(
        label: KeepLabel,
        keepFor: KeepDuration,
        zone: ZoneId,
    ): Instant {
        val endDate = next(label.period, label.start)
        val n = keepFor.n.toLong()
        val keptUntil =
            when (keepFor.unit) {
                KeepUnit.DAYS -> endDate.plusDays(n)
                KeepUnit.MONTHS -> endDate.plusMonths(n)
                KeepUnit.YEARS -> endDate.plusYears(n)
            }
        return keptUntil.atStartOfDay(zone).toInstant()
    }

    /** The label text of the period starting at [start]; a week is `<ISO week-year>-W<ISO week, two digits>`. */
    fun labelText(
        period: KeepPeriod,
        start: LocalDate,
    ): String =
        when (period) {
            KeepPeriod.DAY -> {
                String.format(Locale.ROOT, "%04d-%02d-%02d", start.year, start.monthValue, start.dayOfMonth)
            }

            KeepPeriod.WEEK -> {
                String.format(
                    Locale.ROOT,
                    "%04d-W%02d",
                    start.get(IsoFields.WEEK_BASED_YEAR),
                    start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                )
            }

            KeepPeriod.MONTH -> {
                String.format(Locale.ROOT, "%04d-%02d", start.year, start.monthValue)
            }

            KeepPeriod.QUARTER -> {
                String.format(Locale.ROOT, "%04d-Q%d", start.year, start.get(IsoFields.QUARTER_OF_YEAR))
            }

            KeepPeriod.YEAR -> {
                String.format(Locale.ROOT, "%04d", start.year)
            }
        }

    /**
     * The label a tag name spells, or null when it is not exactly the name [KeepLabel.tagName] would build (another
     * prefix, an unknown period, an impossible date, or a non-canonical spelling such as `W1` for `W01`).
     */
    fun parse(name: String): KeepLabel? {
        if (!name.startsWith(PREFIX)) return null
        val rest = name.removePrefix(PREFIX)
        return KeepPeriod.entries.firstNotNullOfOrNull { period ->
            val text = rest.removePrefix(period.wire + "-").takeIf { rest.startsWith(period.wire + "-") }
            text
                ?.let { startFromText(period, it) }
                ?.let { KeepLabel(period, it) }
                ?.takeIf { it.tagName == name }
        }
    }

    private fun startFromText(
        period: KeepPeriod,
        text: String,
    ): LocalDate? =
        try {
            when (period) {
                KeepPeriod.DAY -> {
                    LocalDate.parse(text)
                }

                KeepPeriod.WEEK -> {
                    WEEK.matchEntire(text)?.destructured?.let { (year, week) ->
                        // Midsummer of a year always lies in the ISO week-year of the same number.
                        LocalDate
                            .of(year.toInt(), Month.JUNE, 1)
                            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
                            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    }
                }

                KeepPeriod.MONTH -> {
                    YearMonth.parse(text).atDay(1)
                }

                KeepPeriod.QUARTER -> {
                    QUARTER.matchEntire(text)?.destructured?.let { (year, quarter) ->
                        Year.of(year.toInt()).atDay(1).with(IsoFields.QUARTER_OF_YEAR, quarter.toLong())
                    }
                }

                KeepPeriod.YEAR -> {
                    YEAR.matchEntire(text)?.let { Year.of(text.toInt()).atDay(1) }
                }
            }
        } catch (_: DateTimeException) {
            null
        }
}
