package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.maintenance.keep.KeepFixtures.UTC
import co.datapipelines.lake.maintenance.keep.KeepFixtures.after
import co.datapipelines.lake.maintenance.keep.KeepFixtures.at
import co.datapipelines.lake.maintenance.keep.KeepFixtures.dayTag
import co.datapipelines.lake.maintenance.keep.KeepFixtures.days
import co.datapipelines.lake.maintenance.keep.KeepFixtures.history
import co.datapipelines.lake.maintenance.keep.KeepFixtures.keepTags
import co.datapipelines.lake.profiles.KeepPeriod
import co.datapipelines.lake.profiles.KeepRuleLimits
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * #960 T7a (brief D2, D7): the accepted rule "day, kept 400 days" on a table older than that with complete history.
 * In one zone it holds exactly 400 live keeps at every instant, the 400 of 956's validator; after a zone change it can
 * hold the periods the new zone has not closed yet, at most two day periods, so never more than 402.
 */
class KeepBoundaryTest {
    private val rule = days(KeepPeriod.DAY, 400)
    private val created = at("2023-12-31T00:00:00Z")
    private val complete = history(1L to "2024-01-01T00:00:00Z")
    private val midnight = at("2026-11-11T00:00:00Z")
    private val ms = Duration.ofMillis(1)

    private fun fresh(
        now: Instant,
        zone: ZoneId = UTC,
    ) = KeepReconcile.plan(listOf(rule), zone, created, complete, now)

    @Test
    fun `956's validator admits the rule with a bound of 400`() {
        KeepRuleLimits.breach(rule) shouldBe null
        KeepRuleLimits.liveKeeps(rule) shouldBe 400L
        fresh(midnight).creates.size.toLong() shouldBe KeepRuleLimits.liveKeeps(rule)
    }

    @Test
    fun `one millisecond before, exactly at and one millisecond after midnight there are 400 live keeps`() {
        val before = fresh(midnight - ms).creates.map { it.name }
        before.size shouldBe 400
        before.first() shouldBe dayTag("2025-10-06")
        before.last() shouldBe dayTag("2026-11-09")

        val exactly = fresh(midnight).creates
        exactly.size shouldBe 400
        exactly.first().name shouldBe dayTag("2025-10-06")
        exactly.first().end shouldBe midnight
        exactly.last().name shouldBe dayTag("2026-11-09")
        exactly.map { it.name } shouldNotContain dayTag("2026-11-10")

        val after = fresh(midnight + ms).creates.map { it.name }
        after.size shouldBe 400
        after.first() shouldBe dayTag("2025-10-07")
        after.last() shouldBe dayTag("2026-11-10")
    }

    @Test
    fun `a keep whose end is exactly now is still asked for, and one millisecond later it is removed`() {
        val atMidnight = fresh(midnight)
        val tagged = complete.after(atMidnight)
        val next = KeepReconcile.plan(listOf(rule), UTC, created, tagged, midnight + ms)
        next.removals.map { it.name } shouldContainExactly listOf(dayTag("2025-10-06"))
        next.creates.map { it.name } shouldContainExactly listOf(dayTag("2026-11-10"))
        next.liveNames.size shouldBe 400
    }

    @Test
    fun `hour by hour over three days in one zone the live keeps are 400`() {
        var state = complete.after(fresh(midnight))
        (1..72).forEach { hour ->
            val plan = KeepReconcile.plan(listOf(rule), UTC, created, state, midnight + Duration.ofHours(hour.toLong()))
            plan.liveNames.size shouldBe 400
            state = state.after(plan)
        }
    }

    @Test
    fun `UTC to UTC-5 - one keep created, one reopened period retained, every age set again, then 400`() {
        val utcPlan = fresh(at("2026-11-11T01:00:00Z"))
        utcPlan.creates.size shouldBe 400
        utcPlan.creates.first().name shouldBe dayTag("2025-10-07")
        utcPlan.creates.last().name shouldBe dayTag("2026-11-10")
        val tagged = complete.after(utcPlan)

        val gmtMinus5 = ZoneId.of("Etc/GMT+5")
        val three = KeepReconcile.plan(listOf(rule), gmtMinus5, created, tagged, at("2026-11-11T03:00:00Z"))
        three.creates.map { it.name } shouldContainExactly listOf(dayTag("2025-10-06"))
        three.creates.single().end shouldBe at("2026-11-11T05:00:00Z")
        three.removals.shouldBeEmpty()
        three.ageChanges.size shouldBe 400
        three.ageChanges.map { it.name } shouldBe utcPlan.creates.map { it.name }
        three.liveNames.size shouldBe 401
        val reopened = tagged.keepTags().getValue(dayTag("2026-11-10"))
        three.ageChanges.single { it.name == reopened.name }.snapshotId shouldBe reopened.snapshotId

        val six = KeepReconcile.plan(listOf(rule), gmtMinus5, created, tagged.after(three), at("2026-11-11T06:00:00Z"))
        six.removals.map { it.name } shouldContainExactly listOf(dayTag("2025-10-06"))
        six.creates.shouldBeEmpty()
        six.liveNames shouldBe (tagged.keepTags().keys)
        six.liveNames.size shouldBe 400
    }

    @Test
    fun `the largest shift, UTC+14 to UTC-12, reopens two day periods and never holds more than 402`() {
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val gmtMinus12 = ZoneId.of("Etc/GMT+12")
        var state = complete.after(KeepReconcile.plan(listOf(rule), kiritimati, created, complete, at("2026-11-11T10:30:00Z")))
        var mostReopened = 0
        var mostLive = 0
        (0..60).forEach { hour ->
            val now = at("2026-11-11T11:00:00Z") + Duration.ofHours(hour.toLong())
            val plan = KeepReconcile.plan(listOf(rule), gmtMinus12, created, state, now)
            state = state.after(plan)
            val reopened =
                state.keepTags().keys.count { name ->
                    KeepCalendar.periodEnd(requireNotNull(KeepCalendar.parse(name)), gmtMinus12) >= now
                }
            reopened shouldBeLessThanOrEqual 2
            plan.liveNames.size shouldBeLessThanOrEqual 402
            mostReopened = maxOf(mostReopened, reopened)
            mostLive = maxOf(mostLive, plan.liveNames.size)
        }
        mostReopened shouldBe 2
        mostLive shouldBeLessThanOrEqual 402
    }
}
