package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.maintenance.keep.KeepFixtures.UTC
import co.datapipelines.lake.maintenance.keep.KeepFixtures.after
import co.datapipelines.lake.maintenance.keep.KeepFixtures.allNames
import co.datapipelines.lake.maintenance.keep.KeepFixtures.at
import co.datapipelines.lake.maintenance.keep.KeepFixtures.branch
import co.datapipelines.lake.maintenance.keep.KeepFixtures.dayTag
import co.datapipelines.lake.maintenance.keep.KeepFixtures.days
import co.datapipelines.lake.maintenance.keep.KeepFixtures.history
import co.datapipelines.lake.maintenance.keep.KeepFixtures.keepTags
import co.datapipelines.lake.maintenance.keep.KeepFixtures.rule
import co.datapipelines.lake.maintenance.keep.KeepFixtures.tag
import co.datapipelines.lake.profiles.KeepPeriod
import co.datapipelines.lake.profiles.KeepUnit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** #960 T1–T5 and T7 (record row K2): the reconcile alone, over rules, a snapshot log and the refs a table has. */
class KeepReconcileTest {
    private val created = at("2026-11-01T00:30:00Z")
    private val now = at("2026-11-11T12:00:00Z")
    private val daily = (1..10).map { it.toLong() to "2026-11-%02dT01:00:00Z".format(it) }.toTypedArray()
    private val dayRule = days(KeepPeriod.DAY, 10)

    private fun firstPass(): Pair<KeepPlan, KeepHistory> {
        val start = history(*daily)
        val plan = KeepReconcile.plan(listOf(dayRule), UTC, created, start, now)
        return plan to start.after(plan)
    }

    @Test
    fun `T1 rules and tags that agree give an empty plan`() {
        val (first, tagged) = firstPass()
        first.creates.map { it.name } shouldContainExactly (1..10).map { dayTag("2026-11-%02d".format(it)) }
        first.creates.forEach { it.snapshotId shouldBe it.name.takeLast(2).toLong() }
        first.notKept.shouldBeEmpty()

        val again = KeepReconcile.plan(listOf(dayRule), UTC, created, tagged, now)
        again.hasChanges shouldBe false
        again.notKept.shouldBeEmpty()
        again.standing shouldHaveSize 10
    }

    @Test
    fun `T2 a removed rule removes its tags, a shorter or longer keep_for sets the ages again and moves no tag`() {
        val (_, tagged) = firstPass()
        val snapshots = tagged.keepTags().mapValues { it.value.snapshotId }

        val removed = KeepReconcile.plan(emptyList(), UTC, created, tagged, now)
        removed.removals.map { it.name }.toSet() shouldBe snapshots.keys
        removed.creates.shouldBeEmpty()
        removed.ageChanges.shouldBeEmpty()

        val shorter = KeepReconcile.plan(listOf(days(KeepPeriod.DAY, 5)), UTC, created, tagged, now)
        shorter.creates.shouldBeEmpty()
        shorter.ageChanges.map { it.name } shouldContainExactly (6..10).map { dayTag("2026-11-%02d".format(it)) }
        shorter.removals.map { it.name } shouldContainExactly (1..5).map { dayTag("2026-11-%02d".format(it)) }
        shorter.ageChanges.forEach { change ->
            change.snapshotId shouldBe snapshots.getValue(change.name)
            (requireNotNull(change.fromMs) - change.toMs) shouldBe Duration.ofDays(5).toMillis()
        }

        val longer = KeepReconcile.plan(listOf(days(KeepPeriod.DAY, 20)), UTC, created, tagged, now)
        longer.creates.shouldBeEmpty()
        longer.removals.shouldBeEmpty()
        longer.ageChanges shouldHaveSize 10
        longer.ageChanges.forEach { change ->
            change.snapshotId shouldBe snapshots.getValue(change.name)
            (change.toMs - requireNotNull(change.fromMs)) shouldBe Duration.ofDays(10).toMillis()
        }
    }

    @Test
    fun `T3 a period with no commit keeps the snapshot the log shows current at its end`() {
        val start = history(1L to "2026-11-01T01:00:00Z", 2L to "2026-11-04T01:00:00Z")
        val plan = KeepReconcile.plan(listOf(dayRule), UTC, created, start, at("2026-11-06T12:00:00Z"))
        plan.creates.associate { it.name to it.snapshotId } shouldBe
            mapOf(
                dayTag("2026-11-01") to 1L,
                dayTag("2026-11-02") to 1L,
                dayTag("2026-11-03") to 1L,
                dayTag("2026-11-04") to 2L,
                dayTag("2026-11-05") to 2L,
            )
        plan.creates.count { it.snapshotId == 1L } shouldBe 3
    }

    private val monthRule = rule(KeepPeriod.MONTH, 13, KeepUnit.MONTHS)
    private val tableBorn = at("2026-01-01T09:00:00Z")
    private val march = at("2026-03-15T12:00:00Z")
    private val a = 1L to "2026-01-01T10:00:00Z"
    private val b = 2L to "2026-01-31T10:00:00Z"
    private val c = 3L to "2026-02-10T10:00:00Z"

    /** A survives (a named keep holds it), B expired, so Iceberg cut the log behind it: the log starts at C. */
    private fun abcWithBExpired(vararg refs: ExistingRef) =
        KeepHistory(
            log = listOf(SnapshotLogEntry(at(c.second), c.first)),
            commitTimes = mapOf(a.first to at(a.second), c.first to at(c.second)),
            refs = refs.toList() + tag("dp-hold-before-migration", a.first),
        )

    @Test
    fun `T4 the A-B-C history gives January no keep, and without B January keeps A`() {
        val switchedOn = KeepReconcile.plan(listOf(monthRule), UTC, tableBorn, abcWithBExpired(), march)
        switchedOn.notKept shouldContainExactly listOf(NotKept("dp-keep-month-2026-01", KeepNotKeptReason.HISTORY_NOT_AVAILABLE))
        switchedOn.creates.map { it.name to it.snapshotId } shouldContainExactly listOf("dp-keep-month-2026-02" to c.first)

        val profileChange =
            KeepReconcile.plan(
                listOf(monthRule),
                UTC,
                tableBorn,
                abcWithBExpired(tag("dp-keep-week-2026-W06", c.first)),
                march,
            )
        profileChange.removals.map { it.name } shouldContainExactly listOf("dp-keep-week-2026-W06")
        profileChange.notKept shouldBe switchedOn.notKept
        profileChange.creates shouldBe switchedOn.creates

        val withoutB = KeepReconcile.plan(listOf(monthRule), UTC, tableBorn, history(a, c), march)
        withoutB.creates.associate { it.name to it.snapshotId } shouldBe
            mapOf("dp-keep-month-2026-01" to a.first, "dp-keep-month-2026-02" to c.first)
        withoutB.notKept.shouldBeEmpty()

        val bAlive = KeepReconcile.plan(listOf(monthRule), UTC, tableBorn, history(a, b, c), march)
        bAlive.creates.associate { it.name to it.snapshotId }["dp-keep-month-2026-01"] shouldBe b.first
    }

    @Test
    fun `T5 an existing tag is neither moved nor removed when the log no longer reaches its period`() {
        val plan = KeepReconcile.plan(listOf(monthRule), UTC, tableBorn, abcWithBExpired(tag("dp-keep-month-2026-01", a.first)), march)
        plan.removals.shouldBeEmpty()
        plan.notKept.shouldBeEmpty()
        plan.creates.map { it.name } shouldContainExactly listOf("dp-keep-month-2026-02")
        val january = plan.ageChanges.single { it.name == "dp-keep-month-2026-01" }
        january.snapshotId shouldBe a.first
        january.toMs shouldBe Duration.between(at(a.second), at("2027-03-01T00:00:00Z")).toMillis()
    }

    @Test
    fun `T7 a period that ended before the table's row was created is not listed`() {
        val bornLater = at("2026-11-05T12:00:00Z")
        val plan = KeepReconcile.plan(listOf(dayRule), UTC, bornLater, history(*daily), now)
        plan.allNames() shouldNotContain dayTag("2026-11-04")
        plan.creates.map { it.name } shouldContainExactly (5..10).map { dayTag("2026-11-%02d".format(it)) }
    }

    @Test
    fun `T7 ISO week 53 and week 1 labels at a year's edge`() {
        val weeks = days(KeepPeriod.WEEK, 70)
        val plan =
            KeepReconcile.plan(
                listOf(weeks),
                UTC,
                at("2026-12-01T00:00:00Z"),
                history(1L to "2026-12-01T12:00:00Z"),
                at("2027-01-13T12:00:00Z"),
            )
        plan.creates.map { it.name } shouldContainAll listOf("dp-keep-week-2026-W53", "dp-keep-week-2027-W01")
    }

    @Test
    fun `T7 a hold tag, main and any branch are never in a plan, and a branch holding an asked name is reported`() {
        val asked = dayTag("2026-11-10")
        val untouched =
            listOf(
                branch("main", 10L),
                branch("staging", 7L, maxRefAgeMs = 86_400_000L),
                branch(asked, 3L, maxRefAgeMs = 5_000L),
                branch(dayTag("2001-01-01"), 2L, maxRefAgeMs = 6_000L),
                tag("dp-hold-audit", 4L),
                tag("dp-hold-q4", 5L, maxRefAgeMs = 7_000L),
            )
        val start = history(*daily, refs = untouched)
        val plan = KeepReconcile.plan(listOf(dayRule), UTC, created, start, now)

        plan.notKept shouldContainExactly listOf(NotKept(asked, KeepNotKeptReason.NAME_TAKEN_BY_BRANCH))
        plan.creates.map { it.name } shouldNotContain asked
        val entries =
            plan.creates.map { it.name } + plan.ageChanges.map { it.name } + plan.removals.map { it.name } + plan.standing.map { it.name }
        untouched.forEach { entries shouldNotContain it.name }
        start.after(plan).refs.filter { ref -> untouched.any { it.name == ref.name } } shouldContainExactly untouched

        val withoutRules = KeepReconcile.plan(emptyList(), UTC, created, start.after(plan), now)
        withoutRules.removals.map { it.name }.toSet() shouldBe plan.creates.map { it.name }.toSet()
        untouched.forEach { withoutRules.allNames() shouldNotContain it.name }
    }
}
