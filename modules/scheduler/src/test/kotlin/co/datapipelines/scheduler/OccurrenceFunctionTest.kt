package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule
import com.github.kagkarlsson.scheduler.task.schedule.CronStyle
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeStrictlyIncreasing
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The ONE occurrence function (scheduler design revision §3.2, R1) against the record's own
 * numbers: A9's measured case (§9.3), our DST rule, month/year boundaries, the public five-field
 * contract, and the B18 spacing guard (L4).
 *
 * The DST cases print the library's answer beside ours (`event=dst.measurement …`) — the handback's
 * DST measurement is this output, re-derived on every run rather than recalled.
 */
class OccurrenceFunctionTest {
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    // ------------------------------------------------------------------ A9: the measured case

    @Test
    fun `spring forward - a 02-30 daily runs at the transition instant, where the library skips the day`() {
        val pattern = OccurrenceFunction.parse("30 2 * * *")
        val ours = OccurrenceFunction.between(pattern, newYork, instant("2026-03-07T00:00:00Z"), instant("2026-03-10T00:00:00Z"), CAP)
        // 03-07 02:30 EST = 07:30Z; 03-08 has no 02:30 → 03:00 EDT = 07:00Z; 03-09 02:30 EDT = 06:30Z.
        ours.instants shouldContainExactly
            listOf(instant("2026-03-07T07:30:00Z"), instant("2026-03-08T07:00:00Z"), instant("2026-03-09T06:30:00Z"))
        val library = library("30 2 * * *", instant("2026-03-07T00:00:00Z"), instant("2026-03-10T00:00:00Z"))
        println(
            "event=dst.measurement case=spring_forward pattern='30 2 * * *' zone=America/New_York library=$library ours=${ours.instants}",
        )
        // The library's measured behaviour (A9): no occurrence on 2026-03-08 at all.
        library.none { it.atZone(newYork).toLocalDate() == LocalDate.parse("2026-03-08") } shouldBe true
    }

    @Test
    fun `fall back - a 01-30 daily runs once, at its first pass`() {
        val pattern = OccurrenceFunction.parse("30 1 * * *")
        val ours = OccurrenceFunction.between(pattern, newYork, instant("2026-11-01T00:00:00Z"), instant("2026-11-02T00:00:00Z"), CAP)
        ours.instants shouldContainExactly listOf(instant("2026-11-01T05:30:00Z")) // 01:30 EDT, the first pass
        val library = library("30 1 * * *", instant("2026-11-01T00:00:00Z"), instant("2026-11-02T00:00:00Z"))
        println(
            "event=dst.measurement case=fall_back_daily pattern='30 1 * * *' zone=America/New_York library=$library ours=${ours.instants}",
        )
    }

    @Test
    fun `fall back - an every-30-minutes pattern runs twice in the repeated hour, where the library runs four times`() {
        val pattern = OccurrenceFunction.parse("0,30 1 * * *")
        val ours = OccurrenceFunction.between(pattern, newYork, instant("2026-11-01T04:00:00Z"), instant("2026-11-01T08:00:00Z"), CAP)
        ours.instants shouldContainExactly listOf(instant("2026-11-01T05:00:00Z"), instant("2026-11-01T05:30:00Z"))
        val library = library("*/30 1 * * *", instant("2026-11-01T04:00:00Z"), instant("2026-11-01T08:00:00Z"))
        println(
            "event=dst.measurement case=fall_back_every_30 pattern='*/30 1 * * *' zone=America/New_York " +
                "library=$library ours=${ours.instants}",
        )
        library.size shouldBe 4 // A9: both passes of both minutes
    }

    @Test
    fun `several local times inside one gap collapse to one occurrence at the transition`() {
        val pattern = OccurrenceFunction.parse("0,15,30,45 2 * * *")
        val ours = OccurrenceFunction.between(pattern, newYork, instant("2026-03-08T05:00:00Z"), instant("2026-03-08T09:00:00Z"), CAP)
        ours.instants shouldContainExactly listOf(instant("2026-03-08T07:00:00Z"))
    }

    @Test
    fun `a gap time and a regular time that share the transition instant are one occurrence`() {
        // 02:00 is in the gap (→ 03:00 EDT = 07:00Z) and 03:00 is 07:00Z itself.
        val pattern = OccurrenceFunction.parse("0 2,3 * * *")
        val ours = OccurrenceFunction.between(pattern, newYork, instant("2026-03-08T05:00:00Z"), instant("2026-03-08T09:00:00Z"), CAP)
        ours.instants shouldContainExactly listOf(instant("2026-03-08T07:00:00Z"))
    }

    @Test
    fun `next is strictly after its argument and never goes backwards across a DST year`() {
        val patterns = listOf("30 2 * * *", "0,30 1 * * *", "15 * * * *", "0 0 1 * *", "45 23 * * 0")
        val zones = listOf("America/New_York", "Europe/London", "Australia/Lord_Howe", "UTC", "Asia/Kolkata")
        patterns.forEach { cron ->
            zones.forEach { zone ->
                val z = ZoneId.of(zone)
                val p = OccurrenceFunction.parse(cron)
                var cursor = instant("2026-01-01T00:00:00Z")
                val seen = ArrayList<Instant>()
                while (cursor.isBefore(instant("2027-01-01T00:00:00Z"))) {
                    val next = OccurrenceFunction.next(p, z, cursor)
                    (next > cursor) shouldBe true
                    seen += next
                    cursor = next
                }
                seen.shouldBeStrictlyIncreasing()
            }
        }
    }

    // ------------------------------------------------------------------ month / year boundaries

    @Test
    fun `day 31 skips the short months and a leap day fires only in a leap year`() {
        val thirtyFirst =
            OccurrenceFunction.between(
                parse("0 0 31 * *"),
                utc(),
                instant("2026-01-01T00:00:00Z"),
                instant("2026-12-31T23:59:00Z"),
                CAP,
            )
        thirtyFirst.instants.map { it.atZone(utc()).monthValue } shouldContainExactly listOf(1, 3, 5, 7, 8, 10, 12)
        val leap =
            OccurrenceFunction.between(
                parse("0 0 29 2 *"),
                utc(),
                instant("2026-01-01T00:00:00Z"),
                instant("2029-01-01T00:00:00Z"),
                CAP,
            )
        leap.instants shouldContainExactly listOf(instant("2028-02-29T00:00:00Z"))
    }

    @Test
    fun `a year boundary and a delayed start across midnight`() {
        OccurrenceFunction.next(parse("0 0 1 1 *"), utc(), instant("2026-12-31T23:59:59Z")) shouldBe instant("2027-01-01T00:00:00Z")
        // 23:55 New York on 09-22 is 03:55Z on 09-23: the occurrence keeps its own date whatever the start.
        OccurrenceFunction.next(parse("55 23 * * *"), newYork, instant("2026-09-22T12:00:00Z")) shouldBe instant("2026-09-23T03:55:00Z")
    }

    @Test
    fun `upcoming carries the offset in force at each occurrence`() {
        val shown = OccurrenceFunction.upcoming(parse("0 12 * * *"), newYork, instant("2026-10-30T00:00:00Z"), 4)
        shown.map { it.offset.id } shouldContainExactly listOf("-04:00", "-04:00", "-05:00", "-05:00")
        shown.map { it.local.hour }.toSet() shouldBe setOf(12)
    }

    @Test
    fun `between reports a lower bound when the cap truncates`() {
        val range =
            OccurrenceFunction.between(
                parse("* * * * *"),
                utc(),
                instant("2026-09-25T00:00:00Z"),
                instant("2026-09-25T02:00:00Z"),
                10,
            )
        range.instants.size shouldBe 10
        range.truncated shouldBe true
    }

    // ------------------------------------------------------------------ the public contract

    @ParameterizedTest
    @ValueSource(strings = ["0 30 2 * * *", "-", "@daily", "", "* * * *", "61 * * * *", "* 25 * * *", "banana * * * *"])
    fun `anything but a five-field Unix cron is refused as cron_invalid`(cron: String) {
        shouldThrow<ScheduleException> { OccurrenceFunction.parse(cron) }.code shouldBe ScheduleErrorCodes.CRON_INVALID
    }

    @ParameterizedTest
    @ValueSource(strings = ["+02:00", "UTC+2", "GMT+5", "Not/AZone", "", "EST5EDTX"])
    fun `a fixed offset or an unknown zone is refused as timezone_invalid`(zone: String) {
        shouldThrow<ScheduleException> { OccurrenceFunction.zone(zone) }.code shouldBe ScheduleErrorCodes.TIMEZONE_INVALID
    }

    @Test
    fun `IANA region ids are accepted, UTC included`() {
        OccurrenceFunction.zone("UTC").id shouldBe "UTC"
        OccurrenceFunction.zone(" America/New_York ").id shouldBe "America/New_York"
    }

    @Test
    fun `whitespace inside a pattern is normalized`() {
        OccurrenceFunction.parse("  0   9 *  * 1-5 ").pattern shouldBe "0 9 * * 1-5"
    }

    // ------------------------------------------------------------------ B18 spacing guard (L4)

    @Test
    fun `the spacing guard measures consecutive local matches - within a day and across midnight`() {
        val floor = Duration.ofMinutes(5)
        OccurrenceFunction.minSpacing(parse("* * * * *"), utc(), NOW, floor) shouldBe Duration.ofMinutes(1)
        OccurrenceFunction.minSpacing(parse("*/5 * * * *"), utc(), NOW, floor) shouldBe Duration.ofMinutes(5)
        OccurrenceFunction.minSpacing(parse("0 9 * * 1-5"), utc(), NOW, floor) shouldBe Duration.ofDays(1)
        // 23:59 then 00:00 the next day: one minute apart across midnight.
        OccurrenceFunction.minSpacing(parse("0,59 0,23 * * *"), utc(), NOW, floor) shouldBe Duration.ofMinutes(1)
    }

    @Test
    fun `a pattern matching at most once in the horizon has no spacing`() {
        OccurrenceFunction.minSpacing(parse("0 0 29 2 *"), utc(), NOW, Duration.ofMinutes(5)).shouldBeNull()
    }

    @Test
    fun `the spacing guard is about the written pattern, not the DST-shifted instants`() {
        // Around the fall-back fold our rule SKIPS the second pass, which would read as a 90-minute
        // gap between instants; the local matches stay 30 minutes apart.
        val spacing = OccurrenceFunction.minSpacing(parse("0,30 * * * *"), newYork, instant("2026-10-31T00:00:00Z"), Duration.ofMinutes(5))
        spacing shouldBe Duration.ofMinutes(30)
        spacing shouldNotBe null
    }

    private fun parse(cron: String) = OccurrenceFunction.parse(cron)

    private fun utc(): ZoneId = ZoneId.of("UTC")

    private fun instant(text: String): Instant = Instant.parse(text)

    /** The library's own answers over a window — `getNextExecutionTime` chained, the §9.3 method. */
    private fun library(
        cron: String,
        from: Instant,
        to: Instant,
    ): List<Instant> {
        val schedule = CronSchedule(cron, newYork, CronStyle.UNIX)
        val out = ArrayList<Instant>()
        var cursor = from
        while (true) {
            cursor = schedule.getNextExecutionTime(ExecutionComplete.simulatedSuccess(cursor))
            if (cursor.isAfter(to)) return out
            out += cursor
        }
    }

    private companion object {
        const val CAP = 10_000
        val NOW: Instant = Instant.parse("2026-09-25T12:00:00Z")
    }
}
