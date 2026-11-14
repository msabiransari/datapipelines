package co.datapipelines.lake.reporting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The lateness rule (brief 961 A, record K6): F1 the median decides, F2 the silence rule, F3 the factor's boundary,
 * F4 only the sample counts. Times are built from gaps, newest last, so a case reads as the feed's history.
 */
class FeedLatenessTest {
    @Test
    fun `F1 - one long gap among short ones does not make an hourly feed on time`() {
        // Gaps 1 h × 5 and one 30 h: the mean is 6 h (on time at 3 h since), the median is 1 h (late).
        val times = history(hours(1), hours(1), hours(30), hours(1), hours(1), hours(1))
        val verdict = judge(times, since = hours(3))
        verdict shouldBe FeedVerdict.Late(usualInterval = hours(1), sinceLast = hours(3))
    }

    @Test
    fun `F1 - an even number of gaps takes the mean of the two middle ones`() {
        // Gaps 1 h, 1 h, 3 h, 9 h → median (1 h + 3 h) / 2 = 2 h; the mean would be 3.5 h.
        val times = history(hours(9), hours(1), hours(3), hours(1))
        judge(times, since = hours(4)) shouldBe FeedVerdict.OnTime(usualInterval = hours(2), sinceLast = hours(4))
        judge(times, since = hours(5)) shouldBe FeedVerdict.Late(usualInterval = hours(2), sinceLast = hours(5))
    }

    @Test
    fun `F2 - one delivery only is not enough to say`() {
        judge(listOf(T0), since = hours(1000), minDeliveries = 2) shouldBe FeedVerdict.NotEnoughDeliveries(1)
        judge(emptyList(), since = hours(1), minDeliveries = 2) shouldBe FeedVerdict.NotEnoughDeliveries(0)
    }

    @Test
    fun `F2 - four deliveries under a minimum of five are not enough to say, however long ago the last one was`() {
        val times = history(hours(1), hours(1), hours(1))
        judge(times, since = hours(1000), minDeliveries = 5) shouldBe FeedVerdict.NotEnoughDeliveries(4)
        judge(times, since = Duration.ZERO, minDeliveries = 5) shouldBe FeedVerdict.NotEnoughDeliveries(4)
        // The fifth delivery makes the same rhythm judgeable.
        judge(history(hours(1), hours(1), hours(1), hours(1)), since = hours(1000), minDeliveries = 5)
            .shouldBe(FeedVerdict.Late(hours(1), hours(1000)))
    }

    @Test
    fun `F2 - a zero median from identical times is not enough to say`() {
        val times = List(6) { T0 }
        judge(times, since = hours(5)) shouldBe FeedVerdict.NotEnoughDeliveries(6)
    }

    @Test
    fun `F2 - a zero median from gaps 0, 0, 0, 10 min is not enough to say`() {
        val times = history(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ofMinutes(10))
        judge(times, since = hours(5)) shouldBe FeedVerdict.NotEnoughDeliveries(5)
    }

    @Test
    fun `F3 - just under and exactly at the factor are on time, just over is late`() {
        val times = history(hours(1), hours(1), hours(1), hours(1), hours(1))
        val at = hours(2)
        judge(times, since = at.minusNanos(1)) shouldBe FeedVerdict.OnTime(hours(1), at.minusNanos(1))
        judge(times, since = at) shouldBe FeedVerdict.OnTime(hours(1), at)
        judge(times, since = at.plusNanos(1)) shouldBe FeedVerdict.Late(hours(1), at.plusNanos(1))
    }

    @Test
    fun `F3 - a fractional factor is compared exactly`() {
        // 1.5 × 1 h = 90 min: the boundary is exact for a factor a double holds exactly.
        val times = history(hours(1), hours(1), hours(1), hours(1), hours(1))
        val at = Duration.ofMinutes(90)
        judge(times, since = at, factor = 1.5) shouldBe FeedVerdict.OnTime(hours(1), at)
        judge(times, since = at.plusNanos(1), factor = 1.5) shouldBe FeedVerdict.Late(hours(1), at.plusNanos(1))
    }

    @Test
    fun `F4 - only the last sample deliveries count and an older rhythm is ignored`() {
        // Ten weekly gaps, then five hourly ones: with a sample of 6 deliveries (5 gaps) the feed is hourly.
        val weekly = List(10) { Duration.ofDays(7) }
        val hourly = List(5) { hours(1) }
        val times = history(*(weekly + hourly).toTypedArray())
        judge(times, since = hours(3), sample = 6) shouldBe FeedVerdict.Late(hours(1), hours(3))
        // With the whole history as the sample the weekly rhythm is the median, and 3 h is on time.
        judge(times, since = hours(3), sample = times.size)
            .shouldBe(FeedVerdict.OnTime(Duration.ofDays(7), hours(3)))
    }

    @Test
    fun `F4 - the order the times arrive in does not matter`() {
        val times = history(hours(1), hours(1), hours(1), hours(1), hours(1))
        judge(times.reversed(), since = hours(3)) shouldBe judge(times, since = hours(3))
        judge(times.shuffled(java.util.Random(SEED)), since = hours(3)) shouldBe FeedVerdict.Late(hours(1), hours(3))
    }

    @Test
    fun `the inputs the profile never gives are refused`() {
        shouldThrow<IllegalArgumentException> { judge(listOf(T0), since = hours(1), sample = 0) }
        shouldThrow<IllegalArgumentException> { judge(listOf(T0), since = hours(1), factor = 0.0) }
        shouldThrow<IllegalArgumentException> { judge(listOf(T0), since = hours(1), factor = Double.NaN) }
    }

    /** The feed's published times from the first delivery at [T0], each next one [gaps] later, oldest first. */
    private fun history(vararg gaps: Duration): List<Instant> = gaps.runningFold(T0) { at, gap -> at.plus(gap) }

    private fun judge(
        times: List<Instant>,
        since: Duration,
        sample: Int = SAMPLE,
        factor: Double = FACTOR,
        minDeliveries: Int = MIN_DELIVERIES,
    ): FeedVerdict =
        FeedLateness.judge(
            publishedTimes = times,
            now = (times.maxOrNull() ?: T0).plus(since),
            sample = sample,
            overdueFactor = factor,
            minDeliveries = minDeliveries,
        )

    private fun hours(n: Long): Duration = Duration.ofHours(n)

    private companion object {
        val T0: Instant = Instant.parse("2026-11-01T00:00:00Z")
        const val SAMPLE = 20
        const val FACTOR = 2.0
        const val MIN_DELIVERIES = 5
        const val SEED = 961L
    }
}
