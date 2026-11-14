package co.datapipelines.lake.reporting

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * One feed's lateness, as [FeedLateness] or [LakeFeedLateness] judged it (lake.md "Profiles"; design record §11b call
 * 46). Nothing is stored: a verdict is computed when asked and holds for the instant it was computed at.
 */
sealed interface FeedVerdict {
    /**
     * The data cannot say (the silence rule): fewer than the profile's minimum of published deliveries among the
     * sample, or a usual interval of zero. [published] is how many published deliveries the sample held.
     */
    data class NotEnoughDeliveries(
        val published: Int,
    ) : FeedVerdict

    /** The time [sinceLast] since the last published delivery is at most the factor times [usualInterval]. */
    data class OnTime(
        val usualInterval: Duration,
        val sinceLast: Duration,
    ) : FeedVerdict

    /** The time [sinceLast] since the last published delivery exceeds the factor times [usualInterval]. */
    data class Late(
        val usualInterval: Duration,
        val sinceLast: Duration,
    ) : FeedVerdict

    /** The table is `deprecated`, `archived` or `purged` (its [status]): none of its feeds is judged, none is ever late. */
    data class NotJudged(
        val status: String,
    ) : FeedVerdict
}

/**
 * The lateness rule, a pure function (brief 961 D1; record §11b call 46). The usual interval is the MEDIAN gap between
 * consecutive published times of the feed's last `sample` published deliveries (an even number of gaps: the mean of
 * the two middle ones); the feed is late when the time since its last published delivery exceeds `overdueFactor`
 * times that interval, and exactly that much is on time. Fewer than `minDeliveries` published deliveries, or a median
 * gap of zero, is [FeedVerdict.NotEnoughDeliveries]: never late and never on time when the data cannot say.
 *
 * The comparison is exact: the interval is held in nanoseconds as a [BigDecimal] (a mean of two gaps can end in half a
 * nanosecond) and multiplied by the factor's exact binary value, so "exactly at the factor" is never decided by
 * floating-point rounding.
 */
object FeedLateness {
    /**
     * Judges one feed. [publishedTimes] are its published deliveries' `published_at`, in any order and possibly more
     * than [sample] of them (only the newest [sample] count); [now] is the catalog's clock.
     */
    fun judge(
        publishedTimes: List<Instant>,
        now: Instant,
        sample: Int,
        overdueFactor: Double,
        minDeliveries: Int,
    ): FeedVerdict {
        require(sample >= 1) { "sample must be at least 1, was $sample" }
        require(overdueFactor.isFinite() && overdueFactor > 0.0) { "overdueFactor must be a positive number, was $overdueFactor" }
        val newestFirst = publishedTimes.sortedDescending().take(sample)
        // A single delivery has no gap at all, whatever the minimum says.
        if (newestFirst.size < minDeliveries || newestFirst.size < 2) return FeedVerdict.NotEnoughDeliveries(newestFirst.size)
        val interval = medianGapNanos(newestFirst)
        if (interval.signum() == 0) return FeedVerdict.NotEnoughDeliveries(newestFirst.size)
        val sinceLast = Duration.between(newestFirst.first(), now)
        val usual = Duration.ofNanos(interval.setScale(0, RoundingMode.DOWN).longValueExact())
        val overdueAfter = interval.multiply(BigDecimal(overdueFactor))
        return if (BigDecimal.valueOf(sinceLast.toNanos()) > overdueAfter) {
            FeedVerdict.Late(usual, sinceLast)
        } else {
            FeedVerdict.OnTime(usual, sinceLast)
        }
    }

    /** The median of the gaps between consecutive times of [newestFirst] (at least two), in nanoseconds. */
    private fun medianGapNanos(newestFirst: List<Instant>): BigDecimal {
        val gaps = newestFirst.zipWithNext { newer, older -> Duration.between(older, newer).toNanos() }.sorted()
        val middle = gaps.size / 2
        return if (gaps.size % 2 == 1) {
            BigDecimal.valueOf(gaps[middle])
        } else {
            BigDecimal.valueOf(gaps[middle - 1]).add(BigDecimal.valueOf(gaps[middle])).divide(TWO)
        }
    }

    private val TWO = BigDecimal.valueOf(2)
}
