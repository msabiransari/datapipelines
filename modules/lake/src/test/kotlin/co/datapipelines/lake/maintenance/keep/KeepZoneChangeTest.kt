package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.maintenance.keep.KeepFixtures.NEW_YORK
import co.datapipelines.lake.maintenance.keep.KeepFixtures.UTC
import co.datapipelines.lake.maintenance.keep.KeepFixtures.after
import co.datapipelines.lake.maintenance.keep.KeepFixtures.at
import co.datapipelines.lake.maintenance.keep.KeepFixtures.dayTag
import co.datapipelines.lake.maintenance.keep.KeepFixtures.days
import co.datapipelines.lake.maintenance.keep.KeepFixtures.history
import co.datapipelines.lake.maintenance.keep.KeepFixtures.keepTags
import co.datapipelines.lake.maintenance.keep.KeepFixtures.tag
import co.datapipelines.lake.profiles.KeepPeriod
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * #960 T6 (record call 42 item 2 "The time zone changed", owner ruling 2026-11-13T23:05:53Z; brief D5a): one zone, the
 * profile's current one; existing tags keep their snapshots, every end is computed in the current zone, no label is
 * kept twice or skipped, and a tag whose period the new zone has not closed yet stands. `America/New_York` is UTC−5 on
 * these dates; the commits at 02:00Z make each zone's boundary pick a different snapshot.
 */
class KeepZoneChangeTest {
    private val created = at("2026-11-09T10:00:00Z")
    private val s0 = 100L to "2026-11-09T12:00:00Z"
    private val s1 = 101L to "2026-11-10T02:00:00Z"
    private val s2 = 102L to "2026-11-11T02:00:00Z"
    private val rule = days(KeepPeriod.DAY, 10)
    private val nov09 = dayTag("2026-11-09")
    private val nov10 = dayTag("2026-11-10")

    private fun commit(snapshot: Long): Instant = at(listOf(s0, s1, s2).single { it.first == snapshot }.second)

    private fun age(
        snapshot: Long,
        end: String,
    ): Long = Duration.between(commit(snapshot), at(end)).toMillis()

    @Test
    fun `each zone's boundary picks a different snapshot for both labels`() {
        val log = history(s0, s1, s2).log
        KeepReconcile.currentAt(log, at("2026-11-10T00:00:00Z")) shouldBe s0.first
        KeepReconcile.currentAt(log, at("2026-11-10T05:00:00Z")) shouldBe s1.first
        KeepReconcile.currentAt(log, at("2026-11-11T00:00:00Z")) shouldBe s1.first
        KeepReconcile.currentAt(log, at("2026-11-11T05:00:00Z")) shouldBe s2.first
        s0.first shouldNotBe s1.first
        s1.first shouldNotBe s2.first
    }

    @Test
    fun `UTC to New York - the tag keeps its snapshot, its end moves to New York's, the next label uses New York's boundary`() {
        val underUtc = history(s0, s1, s2)
        val utcPlan = KeepReconcile.plan(listOf(rule), UTC, created, underUtc, at("2026-11-10T06:00:00Z"))
        utcPlan.creates.map { it.name to it.snapshotId } shouldContainExactly listOf(nov09 to s0.first)
        utcPlan.creates.single().end shouldBe at("2026-11-20T00:00:00Z")

        val plan = KeepReconcile.plan(listOf(rule), NEW_YORK, created, underUtc.after(utcPlan), at("2026-11-11T06:00:00Z"))
        plan.ageChanges.map { Triple(it.name, it.snapshotId, it.end) } shouldContainExactly
            listOf(Triple(nov09, s0.first, at("2026-11-20T05:00:00Z")))
        plan.ageChanges.single().toMs shouldBe age(s0.first, "2026-11-20T05:00:00Z")
        plan.creates.map { Triple(it.name, it.snapshotId, it.end) } shouldContainExactly
            listOf(Triple(nov10, s2.first, at("2026-11-21T05:00:00Z")))
        plan.removals.shouldBeEmpty()
        plan.notKept.shouldBeEmpty()
        plan.liveNames shouldBe setOf(nov09, nov10)
    }

    @Test
    fun `New York to UTC - the tag keeps its snapshot, its end moves to UTC's, no label is skipped`() {
        val underNy = history(s0, s1, s2)
        val nyPlan = KeepReconcile.plan(listOf(rule), NEW_YORK, created, underNy, at("2026-11-10T06:00:00Z"))
        nyPlan.creates.map { it.name to it.snapshotId } shouldContainExactly listOf(nov09 to s1.first)

        val plan = KeepReconcile.plan(listOf(rule), UTC, created, underNy.after(nyPlan), at("2026-11-11T02:30:00Z"))
        plan.ageChanges.map { Triple(it.name, it.snapshotId, it.end) } shouldContainExactly
            listOf(Triple(nov09, s1.first, at("2026-11-20T00:00:00Z")))
        plan.creates.map { Triple(it.name, it.snapshotId, it.end) } shouldContainExactly
            listOf(Triple(nov10, s1.first, at("2026-11-21T00:00:00Z")))
        plan.removals.shouldBeEmpty()
        plan.liveNames shouldBe setOf(nov09, nov10)
    }

    @Test
    fun `a tag whose period the new zone has not closed yet stands, and is not made again when it closes`() {
        val underUtc = history(s0, s1, s2)
        val utcPlan = KeepReconcile.plan(listOf(rule), UTC, created, underUtc, at("2026-11-11T01:00:00Z"))
        utcPlan.creates.map { it.name to it.snapshotId } shouldContainExactly listOf(nov09 to s0.first, nov10 to s1.first)
        val tagged = underUtc.after(utcPlan)

        val beforeNyClose = KeepReconcile.plan(listOf(rule), NEW_YORK, created, tagged, at("2026-11-11T03:00:00Z"))
        beforeNyClose.removals.shouldBeEmpty()
        beforeNyClose.creates.shouldBeEmpty()
        beforeNyClose.ageChanges.associate { it.name to it.end } shouldBe
            mapOf(nov09 to at("2026-11-20T05:00:00Z"), nov10 to at("2026-11-21T05:00:00Z"))
        beforeNyClose.ageChanges.associate { it.name to it.snapshotId } shouldBe mapOf(nov09 to s0.first, nov10 to s1.first)
        val reAged = tagged.after(beforeNyClose)

        val afterNyClose = KeepReconcile.plan(listOf(rule), NEW_YORK, created, reAged, at("2026-11-11T06:00:00Z"))
        afterNyClose.hasChanges shouldBe false
        afterNyClose.notKept.shouldBeEmpty()
        reAged.after(afterNyClose).keepTags().mapValues { it.value.snapshotId } shouldBe mapOf(nov09 to s0.first, nov10 to s1.first)
    }

    @Test
    fun `a later duration edit under New York sets the ends again and moves no tag`() {
        val tagged =
            history(
                s0,
                s1,
                s2,
                refs =
                    listOf(
                        tag(nov09, s0.first, age(s0.first, "2026-11-20T05:00:00Z")),
                        tag(nov10, s2.first, age(s2.first, "2026-11-21T05:00:00Z")),
                    ),
            )
        KeepReconcile.plan(listOf(rule), NEW_YORK, created, tagged, at("2026-11-11T06:00:00Z")).hasChanges shouldBe false

        val plan = KeepReconcile.plan(listOf(days(KeepPeriod.DAY, 20)), NEW_YORK, created, tagged, at("2026-11-11T06:00:00Z"))
        plan.creates.shouldBeEmpty()
        plan.removals.shouldBeEmpty()
        plan.ageChanges.map { Triple(it.name, it.snapshotId, it.end) } shouldContainExactly
            listOf(Triple(nov09, s0.first, at("2026-11-30T05:00:00Z")), Triple(nov10, s2.first, at("2026-12-01T05:00:00Z")))
    }

    @Test
    fun `a load at 21h00 New York time on the 31st belongs to the 31st in New York and to the 1st in UTC`() {
        val before = 1L to "2026-10-31T12:00:00Z"
        val load = 2L to "2026-11-01T01:00:00Z"
        val log = history(before, load)
        val now = at("2026-11-03T12:00:00Z")
        val bornOct = at("2026-10-01T00:00:00Z")

        val ny = KeepReconcile.plan(listOf(rule), NEW_YORK, bornOct, log, now).creates.associate { it.name to it.snapshotId }
        val utc = KeepReconcile.plan(listOf(rule), UTC, bornOct, log, now).creates.associate { it.name to it.snapshotId }
        ny[dayTag("2026-10-31")] shouldBe load.first
        utc[dayTag("2026-10-31")] shouldBe before.first
        utc[dayTag("2026-11-01")] shouldBe load.first
    }
}
