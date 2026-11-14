package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.CLEANUP
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.COMPACTION
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.MANIFEST_REWRITE
import co.datapipelines.lake.maintenance.plan.PlanHarness.FileSpec
import co.datapipelines.lake.profiles.LakeProfileFixtures
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * N3a (brief 959 D3a): stale figures, with the affected table OUTSIDE the pass's page. Each change (a threshold, a
 * cap, a reassignment) is decided BEFORE remeasuring: compaction not due (`not measured (profile changed)`), no
 * profile-dependent score term, the read method says stale, manifest rewrite and cleanup decided as before; the table
 * is first in the next page; after remeasuring, the due work and the score follow the new profile. And a profile saved
 * while a measurement is blocked mid-read leaves figures that are stale at the next decision.
 */
class PlanStaleIntegrationTest {
    private val h = PlanHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    private fun answer(table: MaintenanceTable): TableDue = h.decide().getValue(table.id)

    private fun work(answer: TableDue): Set<String> = answer.due + answer.held.keys

    /**
     * Two tables with the same files, each measured once by a page of one. The one measured SECOND is the subject: after
     * a change that makes both stale, the next page of one takes the other (its attempt is older), so the subject stays
     * OUTSIDE that pass's page and is decided from its stored, stale row.
     */
    private fun subjectAndFiller(vararg files: FileSpec): Pair<MaintenanceTable, MaintenanceTable> {
        val tables = listOf(h.table("first"), h.table("second"))
        tables.forEach { h.append(it, *files) }
        repeat(2) { h.pass(planner) }
        val (fillerId, subjectId) = h.control.metadataReads.toList()
        return tables.single { it.id == subjectId } to tables.single { it.id == fillerId }
    }

    private val planner by lazy { h.planner(pageSize = 1) }

    /** One pass of one table, which must be [filler]: the subject is left out of it. */
    private fun passWithout(
        subject: MaintenanceTable,
        filler: MaintenanceTable,
    ) {
        val before = h.control.metadataReads.size
        h.pass(planner)
        h.control.metadataReads.drop(before) shouldBe listOf(filler.id)
        h.repository.lastPlan()!!.second.tablesStale shouldBe 1
        answer(filler).stale shouldBe false
        answer(subject).stale shouldBe true
    }

    /** Every D3a consequence while the subject is stale; then the next page starts with it. */
    private fun assertStale(subject: MaintenanceTable) {
        val stale = answer(subject)
        stale.stale shouldBe true
        stale.notDue[COMPACTION] shouldBe NotDueReason.PROFILE_CHANGED
        (COMPACTION in work(stale)) shouldBe false
        stale.score shouldBe 0 // the manifest term is zero here; the profile-dependent terms add nothing
        (MANIFEST_REWRITE in work(stale)) shouldBe false // one manifest, threshold 100: decided as before
        (CLEANUP in work(stale)) shouldBe true // never cleaned: due by time, as before
        h.figures
            .figures(subject.id)
            .shouldNotBeNull()
            .figuresState shouldBe FiguresState.STALE
        h.repository
            .page(1, emptySet())
            .single()
            .tableId shouldBe subject.id
    }

    @Test
    fun `N3a - a threshold changed - five files that were small are not, after remeasuring`() {
        // 20 MiB files are small under the 64 MiB target (below 48 MiB), not under a 16 MiB one (below 12 MiB)
        val (subject, filler) = subjectAndFiller(*Array(5) { FileSpec("2026-11-01", 20 * MIB) })
        answer(subject).score shouldBe 5
        (COMPACTION in work(answer(subject))) shouldBe true
        h.figures.figures(subject.id)!!.figuresState shouldBe FiguresState.CURRENT

        h.editProfile("production", LakeProfileFixtures.document("maintenance.target_file_bytes" to "${16 * MIB}"))
        passWithout(subject, filler)
        assertStale(subject)

        h.pass(planner)
        val after = answer(subject)
        after.stale shouldBe false
        after.score shouldBe 0
        (COMPACTION in work(after)) shouldBe false
        after.notDue.containsKey(COMPACTION) shouldBe false
        h.figures.figures(subject.id)!!.figuresState shouldBe FiguresState.CURRENT
    }

    @Test
    fun `N3a - a cap changed - an unsorted file that fitted no longer fits, after remeasuring`() {
        val (subject, filler) = subjectAndFiller(FileSpec("2026-11-01", 200 * MIB, sorted = false))
        answer(subject).score shouldBe 1
        (COMPACTION in work(answer(subject))) shouldBe true

        h.editProfile("production", LakeProfileFixtures.document("maintenance.max_bytes_rewritten_per_run" to "${128 * MIB}"))
        passWithout(subject, filler)
        assertStale(subject)

        h.pass(planner)
        val after = answer(subject)
        after.stale shouldBe false
        after.score shouldBe 0
        after.notDue[COMPACTION] shouldBe NotDueReason.CAPACITY
    }

    @Test
    fun `N3a - the table reassigned to another profile - stale until remeasured, then it follows the new profile`() {
        val (subject, filler) = subjectAndFiller(*Array(5) { FileSpec("2026-11-01", 10 * MIB) })
        answer(subject).score shouldBe 5
        h.editProfile(
            "development",
            LakeProfileFixtures.document("maintenance.window" to "\"00:00-00:00\"", "maintenance.compact_min_small_files" to "6"),
        )

        // both reassigned (the filler first, so its attempt stays the older one): the subject waits outside the page
        h.assign(filler, "development")
        h.assign(subject, "development")
        passWithout(subject, filler)
        assertStale(subject)

        h.pass(planner)
        val after = answer(subject)
        after.stale shouldBe false
        after.score shouldBe 0 // five small files, fewer than development's six: no candidate partition
        (COMPACTION in work(after)) shouldBe false
        h.measurement(subject)!!.figuresProfileId shouldBe h.profileId("development")
    }

    @Test
    fun `N3a - a profile saved while a measurement is blocked mid-read leaves figures stale at the next decision`() {
        val table = h.table("midread")
        h.append(table, *Array(5) { FileSpec("2026-11-01", 10 * MIB) })
        val before = h.profileUpdatedAt("production")
        val stall = h.control.stall(table.id)
        val pass = Executors.newSingleThreadExecutor()
        try {
            val running = pass.submit { h.pass(h.planner(pageSize = 1, tableTimeout = Duration.ofSeconds(30))) }
            stall.entered.await(PlanHarness.WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            h.editProfile("production", LakeProfileFixtures.document("maintenance.compact_min_small_files" to "3"))
            h.control.release(table.id)
            running.get(PlanHarness.PASS_BOUND_SECONDS, TimeUnit.SECONDS)
        } finally {
            pass.shutdownNow()
        }

        val row = h.measurement(table).shouldNotBeNull()
        row.errorCode shouldBe null
        row.figuresProfileUpdatedAt shouldBe before // computed under the copy read before the manifests
        answer(table).stale shouldBe true
        h.figures.figures(table.id)!!.figuresState shouldBe FiguresState.STALE
    }

    private companion object {
        const val MIB = 1_048_576L
    }
}
