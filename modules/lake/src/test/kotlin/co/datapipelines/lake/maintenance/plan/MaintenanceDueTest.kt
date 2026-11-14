package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.CLEANUP
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.COMPACTION
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.MANIFEST_REWRITE
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.PURGE_DELETION
import co.datapipelines.lake.maintenance.plan.PlanFixtures.GRACE
import co.datapipelines.lake.maintenance.plan.PlanFixtures.NOW
import co.datapipelines.lake.maintenance.plan.PlanFixtures.attempt
import co.datapipelines.lake.maintenance.plan.PlanFixtures.hoursAgo
import co.datapipelines.lake.maintenance.plan.PlanFixtures.id
import co.datapipelines.lake.maintenance.plan.PlanFixtures.input
import co.datapipelines.lake.maintenance.plan.PlanFixtures.measured
import co.datapipelines.lake.profiles.LakeProfileDocument
import co.datapipelines.lake.profiles.LakeProfileFixtures
import co.datapipelines.lake.profiles.LakeTablePause
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * N5 (M3), N7, N8: the pure due function (brief 959 D4, D6, D7's ranking; record §11a call 34). The built-in profile:
 * window `02:00-05:00` (NOW is 03:00, inside), cleanup every 24 h, manifest threshold 100; the orphan grace is 72 h.
 */
class MaintenanceDueTest {
    private val due = MaintenanceDue(GRACE)
    private val registeredButManifests = setOf(CLEANUP, COMPACTION, PURGE_DELETION)

    private val lateWindow = LakeProfileDocument.parse(LakeProfileFixtures.document("maintenance.window" to "\"05:00-06:00\""))

    /** N5's one fixture: every case the brief lists, numbered by the table it builds. */
    private val fixture =
        listOf(
            // 1 cleanup only: last cleanup 30 h ago → overdue since 6 h ago; nothing to rewrite
            input(1, lastCleanup = hoursAgo(30)),
            // 2 purged, due: protected until 100 h ago + 72 h grace → due since 28 h ago
            input(2, status = PlanStatus.PURGED, measurement = null, finalProtectedUntil = hoursAgo(100)),
            // 3 purged, not yet due: protected until 10 h ago, the grace runs another 62 h
            input(3, status = PlanStatus.PURGED, measurement = null, finalProtectedUntil = hoursAgo(10)),
            // 4 purged, finished: 962 stamped purge_deleted_at
            input(4, status = PlanStatus.PURGED, measurement = null, finalProtectedUntil = hoursAgo(100), purgeDeletedAt = hoursAgo(5)),
            // 5 never-published active, never cleaned: overdue since its row was created 50 h ago; no rewrite
            input(5, measurement = measured(snapshot = null), lastCleanup = null, createdAt = hoursAgo(50)),
            // 6 never-published deprecated: nothing to rewrite, cleaned recently
            input(6, status = PlanStatus.DEPRECATED, measurement = measured(snapshot = null)),
            // 7 archived: cleanup only although its figures would call for both rewrites
            input(7, status = PlanStatus.ARCHIVED, measurement = measured(small = 10, manifests = 200), lastCleanup = hoursAgo(25)),
            // 8 compaction paused: its compaction is held, no task
            input(8, measurement = measured(small = 10), pause = LakeTablePause(NOW.plusSeconds(3600), null)),
            // 9 cleanup paused: never cleaned, held, no task
            input(9, lastCleanup = null, createdAt = hoursAgo(40), pause = LakeTablePause(null, NOW.plusSeconds(3600))),
            // 10 outside its window: compaction held, no task
            input(10, measurement = measured(small = 10), profile = lateWindow),
            // 11 capacity-blocked: two unsorted files, both larger than the cap, nothing else
            input(11, measurement = measured(unsorted = 2, overCap = 2)),
            // 12 after a capacity attempt 5 h ago that committed nothing and left nothing: waits
            input(12, measurement = measured(small = 6), attempt = attempt(5, MaintenanceOutcome.CAPACITY)),
            // 13 after the same attempt 30 h ago: the wait has passed
            input(13, measurement = measured(small = 6), attempt = attempt(30, MaintenanceOutcome.CAPACITY)),
            // 14 after the same attempt 5 h ago, the profile edited 2 h ago (and remeasured under it)
            input(
                14,
                measurement = measured(small = 6, profileUpdatedAt = hoursAgo(2)),
                attempt = attempt(5, MaintenanceOutcome.CAPACITY),
                profileUpdatedAt = hoursAgo(2),
            ),
            // 15 after a deadline attempt an hour ago: due at once
            input(15, measurement = measured(small = 7), attempt = attempt(1, MaintenanceOutcome.DEADLINE)),
            // 16 after a capacity attempt that committed nothing and left one file for a fresh run (L18): due at once
            input(16, measurement = measured(small = 8), attempt = attempt(1, MaintenanceOutcome.CAPACITY, committed = false, left = 1)),
            // 17 never measured
            input(17, measurement = null),
            // 18 last measurement failed (its old figures kept): no rewrite work
            input(18, measurement = measured(small = 10, errorCode = PlanErrors.TIMEOUT)),
            // 19 manifest rewrite due, its part not registered
            input(19, measurement = measured(manifests = 150)),
            // 20, 21 a tie on score 9: the older last finished rewrite first
            input(20, measurement = measured(small = 9), lastFinishedRewrite = hoursAgo(5)),
            input(21, measurement = measured(small = 9), lastFinishedRewrite = hoursAgo(50)),
        )

    private val plan = due.plan(fixture, registeredButManifests, NOW)
    private val byTable = plan.tables.associateBy { it.tableId }

    private fun answer(n: Int): TableDue = byTable.getValue(id(n))

    @Test
    fun `N5 - one expected ordered list`() {
        // light group by overdue time: 5 (-50 h), 2 (-28 h), 1 (-6 h), 7 (-1 h); then score: 21, 20 (9; tie by rewrite),
        // 16 (8), 15 (7), 13, 14 (6; tie by table id, neither has a finished rewrite)
        plan.order shouldContainExactly listOf(5, 2, 1, 7, 21, 20, 16, 15, 13, 14).map(::id)
    }

    @Test
    fun `N5 - each case answers its own parts, holds and reasons`() {
        answer(1).due shouldBe setOf(CLEANUP)
        answer(1).overdueSince shouldBe hoursAgo(6)
        answer(2).due shouldBe setOf(PURGE_DELETION)
        answer(2).overdueSince shouldBe hoursAgo(28)
        answer(3).due.shouldBeEmpty()
        answer(4).due.shouldBeEmpty()
        answer(5).due shouldBe setOf(CLEANUP)
        answer(5).overdueSince shouldBe hoursAgo(50)
        answer(6).due.shouldBeEmpty()
        answer(6).notDue.shouldBeEmpty()
        answer(7).due shouldBe setOf(CLEANUP)
        answer(8).due.shouldBeEmpty()
        answer(8).held.keys shouldBe setOf(COMPACTION)
        answer(8).held.getValue(COMPACTION).outcome shouldBe MaintenanceOutcome.PAUSED_UNTIL
        answer(9).held.getValue(CLEANUP).reason shouldBe "paused until ${NOW.plusSeconds(3600)}"
        answer(10).held.getValue(COMPACTION).reason shouldBe "outside window"
        answer(11).notDue shouldBe mapOf(COMPACTION to NotDueReason.CAPACITY)
        answer(11).score shouldBe 0
        answer(12).notDue shouldBe mapOf(COMPACTION to NotDueReason.CAPACITY_WAIT)
        answer(13).due shouldBe setOf(COMPACTION)
        answer(14).due shouldBe setOf(COMPACTION)
        answer(15).due shouldBe setOf(COMPACTION)
        answer(16).due shouldBe setOf(COMPACTION)
        answer(17).notDue shouldBe mapOf(MANIFEST_REWRITE to NotDueReason.NOT_MEASURED, COMPACTION to NotDueReason.NOT_MEASURED)
        answer(18).notDue shouldBe mapOf(MANIFEST_REWRITE to NotDueReason.NOT_MEASURED, COMPACTION to NotDueReason.NOT_MEASURED)
        answer(19).notDue shouldBe mapOf(MANIFEST_REWRITE to NotDueReason.NOT_REGISTERED)
        plan.tables.filter { it.silent }.shouldBeEmpty()
        plan.tables.filter { it.stale }.shouldBeEmpty()
    }

    @Test
    fun `the silence rule - a purged table with no finalised revision and a capacity entry without 969's fields`() {
        val purged = input(1, status = PlanStatus.PURGED, measurement = null)
        val unshaped = input(2, measurement = measured(small = 5), attempt = attempt(1, MaintenanceOutcome.CAPACITY, null, null))
        val answers = due.plan(listOf(purged, unshaped), PlanFixtures.ALL_PARTS, NOW)
        answers.tables[0].silent shouldBe true
        answers.tables[0].notDue shouldBe mapOf(PURGE_DELETION to NotDueReason.RULE_SILENT)
        answers.tables[0].hasWork shouldBe false
        // toward doing the work: no wait, still due, and reported
        answers.tables[1].silent shouldBe true
        answers.tables[1].due shouldBe setOf(COMPACTION)
        answers.order shouldContainExactly listOf(id(2))
    }

    @Test
    fun `D3a - stale figures - compaction not due, no profile-dependent score, manifest rewrite from the current threshold`() {
        val stale =
            input(1, measurement = measured(small = 10, unsorted = 4, manifests = 130), profileUpdatedAt = hoursAgo(1))
        val answer = due.decide(stale, PlanFixtures.ALL_PARTS, NOW)
        answer.stale shouldBe true
        answer.notDue shouldBe mapOf(COMPACTION to NotDueReason.PROFILE_CHANGED)
        answer.due shouldBe setOf(MANIFEST_REWRITE)
        answer.score shouldBe 30
    }

    @Test
    fun `N7 - the score is the record's sum, one term at a time`() {
        fun score(m: StoredMeasurement) = due.decide(input(1, measurement = m), PlanFixtures.ALL_PARTS, NOW).score
        score(measured(small = 7)) shouldBe 7
        score(measured(unsorted = 5, overCap = 2)) shouldBe 3
        score(measured(manifests = 130)) shouldBe 30
        score(measured(manifests = 99)) shouldBe 0
        score(measured(small = 7, unsorted = 5, overCap = 2, manifests = 130)) shouldBe 40
    }

    @Test
    fun `N8 - nothing registered - no table has work`() {
        val nothing = due.plan(fixture, emptySet(), NOW)
        nothing.order.shouldBeEmpty()
        nothing.tables.filter { it.hasWork }.shouldBeEmpty()
        nothing.tables.first { it.tableId == id(1) }.notDue shouldBe mapOf(CLEANUP to NotDueReason.NOT_REGISTERED)
    }

    @Test
    fun `N11 unit - a table never cleaned is due, overdue since its creation`() {
        val answer = due.decide(input(1, lastCleanup = null, createdAt = hoursAgo(3)), PlanFixtures.ALL_PARTS, NOW)
        answer.due shouldBe setOf(CLEANUP)
        answer.overdueSince shouldBe hoursAgo(3)
    }
}
