package co.datapipelines.lake.profiles

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * The two limits of a `retention.keep` rule (record call 42: `keep_for` at least one period long, at most 400 live
 * keeps), decided the same on any save date and in any zone (brief 956 D2 gives the arithmetic): the verdict uses
 * fixed tables of calendar days ([KeepPeriod]) and [maxDays], never today's date or the profile's zone.
 */
object KeepRuleLimits {
    /** The most live keeps one rule may ask for. */
    const val MAX_LIVE_KEEPS = 400

    /** The first and last day of one 400-year Gregorian cycle: every calendar pattern occurs between them. */
    private val CYCLE_START: LocalDate = LocalDate.of(2000, 1, 1)
    private val CYCLE_END: LocalDate = LocalDate.of(2399, 12, 31)

    private val maxDaysCache = ConcurrentHashMap<KeepDuration, Long>()

    /** Why a rule is refused, or null when it passes. */
    enum class Breach(
        val reason: String,
    ) {
        SHORTER_THAN_PERIOD("keep_for_shorter_than_period"),
        TOO_MANY_KEEPS("too_many_keeps"),
    }

    fun breach(rule: KeepRule): Breach? =
        when {
            !coversOnePeriod(rule) -> Breach.SHORTER_THAN_PERIOD
            liveKeeps(rule) > MAX_LIVE_KEEPS -> Breach.TOO_MANY_KEEPS
            else -> null
        }

    /** `keep_for` is at least the period's longest length, for every period there will ever be. */
    fun coversOnePeriod(rule: KeepRule): Boolean {
        val n = rule.keepFor.n
        return when (rule.keepFor.unit) {
            KeepUnit.DAYS -> n >= rule.period.longestDays
            KeepUnit.MONTHS -> n >= (rule.period.months ?: 1)
            KeepUnit.YEARS -> n >= 1
        }
    }

    /**
     * The upper bound of live keeps: for a calendar period and a calendar `keep_for`, months ÷ the period's months
     * rounded up; otherwise [maxDays] ÷ the period's shortest length rounded up.
     */
    fun liveKeeps(rule: KeepRule): Long {
        val periodMonths = rule.period.months
        val keepMonths =
            when (rule.keepFor.unit) {
                KeepUnit.MONTHS -> rule.keepFor.n.toLong()
                KeepUnit.YEARS -> rule.keepFor.n.toLong() * MONTHS_PER_YEAR
                KeepUnit.DAYS -> null
            }
        return if (periodMonths != null && keepMonths != null) {
            ceilDiv(keepMonths, periodMonths.toLong())
        } else {
            ceilDiv(maxDays(rule.keepFor), rule.period.shortestDays.toLong())
        }
    }

    /**
     * The largest number of days `d + keepFor` is after `d`: `n` for days; for months and years, the maximum over
     * every start date of one 400-year Gregorian cycle, computed with `java.time` (1 month → 31, 12 months and 1 year
     * → 366, 13 months → 397). Memoised per duration.
     */
    fun maxDays(keepFor: KeepDuration): Long =
        when (keepFor.unit) {
            KeepUnit.DAYS -> keepFor.n.toLong()
            KeepUnit.MONTHS, KeepUnit.YEARS -> maxDaysCache.computeIfAbsent(keepFor, ::scanCycle)
        }

    private fun scanCycle(keepFor: KeepDuration): Long {
        var longest = 0L
        var day = CYCLE_START
        while (!day.isAfter(CYCLE_END)) {
            val end =
                when (keepFor.unit) {
                    KeepUnit.MONTHS -> day.plusMonths(keepFor.n.toLong())
                    KeepUnit.YEARS -> day.plusYears(keepFor.n.toLong())
                    KeepUnit.DAYS -> error("days need no scan")
                }
            longest = maxOf(longest, ChronoUnit.DAYS.between(day, end))
            day = day.plusDays(1)
        }
        return longest
    }

    private fun ceilDiv(
        a: Long,
        b: Long,
    ): Long = Math.floorDiv(a + b - 1, b)

    private const val MONTHS_PER_YEAR = 12L
}
