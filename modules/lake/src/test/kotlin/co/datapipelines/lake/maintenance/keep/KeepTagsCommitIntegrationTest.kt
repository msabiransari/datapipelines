package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.maintenance.Guarded
import co.datapipelines.lake.maintenance.MaintenanceHarness
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceRunContext
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.keep.KeepFixtures.UTC
import co.datapipelines.lake.maintenance.keep.KeepFixtures.dayTag
import co.datapipelines.lake.maintenance.keep.KeepFixtures.days
import co.datapipelines.lake.profiles.KeepPeriod
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.iceberg.SnapshotRef
import org.apache.iceberg.TableMetadata
import org.apache.iceberg.TableMetadataParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * #960 T10: [KeepTags] commits through [MaintenanceRun.commit] (the table lease, a fresh load, the fence and the
 * revision trigger), over the module's Postgres and MinIO with a real managed table. A plan with a create, an age
 * change and a removal is ONE Iceberg commit and ONE `lake_table_revisions` row; a plan with no change (standing tags
 * only, or reported periods only) commits nothing; a refused lease changes no tag. Branches and holds are untouched.
 */
class KeepTagsCommitIntegrationTest {
    private val h = MaintenanceHarness()
    private val keep = KeepTags()
    private val rule = days(KeepPeriod.DAY, 10)

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    private fun run(table: MaintenanceTable): MaintenanceRun =
        MaintenanceRunContext(
            h.leases,
            h.defs.catalogs,
            h.heartbeats,
        ).open(UUID.randomUUID(), table, h.dbNow() + Duration.ofHours(1), emptyMap())

    private fun metadata(table: MaintenanceTable): TableMetadata {
        val location = location(table)
        return TableMetadataParser.read(h.defs.catalogs.metadataFileIo(), location)
    }

    private fun location(table: MaintenanceTable): String =
        requireNotNull(
            LakeTestDb.jdbc.queryForObject(
                "SELECT metadata_location FROM iceberg_tables WHERE table_namespace = ? AND table_name = ?",
                String::class.java,
                table.namespace,
                table.name,
            ),
        )

    private fun refs(table: MaintenanceTable): Map<String, SnapshotRef> = metadata(table).refs()

    @Test
    fun `a create, an age change and a removal land as one commit, and a plan with no change lands none`() {
        val table = h.table("keep10")
        val setup = run(table)
        setup.commit { it.newFastAppend() }.shouldBeInstanceOf<Guarded.Done<Unit>>()
        val snapshot = metadata(table).currentSnapshot()
        val day = Instant.ofEpochMilli(snapshot.timestampMillis()).atZone(ZoneOffset.UTC).toLocalDate()
        val yesterday = dayTag(day.minusDays(1).toString())
        val stale = dayTag("2020-01-01")
        setup
            .commit {
                it
                    .manageSnapshots()
                    .createTag(stale, snapshot.snapshotId())
                    .createTag(yesterday, snapshot.snapshotId())
                    .setMaxRefAgeMs(yesterday, 1_234L)
                    .createTag("dp-hold-audit", snapshot.snapshotId())
                    .createBranch(dayTag("2001-01-01"), snapshot.snapshotId())
                    .setMaxRefAgeMs(dayTag("2001-01-01"), 5_678L)
                    .createBranch("staging", snapshot.snapshotId())
            }.shouldBeInstanceOf<Guarded.Done<Unit>>()
        val untouched = refs(table).filterKeys { it in setOf("main", "staging", "dp-hold-audit", dayTag("2001-01-01")) }
        untouched.size shouldBe 4

        val now = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() + Duration.ofHours(1)
        val born = day.atStartOfDay(ZoneOffset.UTC).toInstant()
        val plan = KeepReconcile.plan(listOf(rule), UTC, born, KeepHistories.of(metadata(table)), now)
        plan.creates.map { it.name to it.snapshotId } shouldContainExactly listOf(dayTag(day.toString()) to snapshot.snapshotId())
        plan.ageChanges.map { it.name } shouldContainExactly listOf(yesterday)
        plan.removals.map { it.name } shouldContainExactly listOf(stale)
        plan.notKept.shouldBeEmpty()

        val revisions = h.revisions(table)
        val versions = metadata(table).previousFiles().size
        val applied = keep.apply(run(table), plan)

        (applied as Guarded.Done).value shouldBe KeepApplied(plan, committed = true)
        h.revisions(table) shouldBe revisions + 1
        metadata(table).previousFiles().size shouldBe versions + 1
        val after = refs(table)
        requireNotNull(after[dayTag(day.toString())]).let {
            it.isTag shouldBe true
            it.snapshotId() shouldBe snapshot.snapshotId()
            it.maxRefAgeMs() shouldBe plan.creates.single().maxRefAgeMs
        }
        requireNotNull(after[yesterday]).maxRefAgeMs() shouldBe plan.ageChanges.single().toMs
        after.containsKey(stale) shouldBe false
        after.filterKeys { it in untouched.keys } shouldBe untouched

        val again = KeepReconcile.plan(listOf(rule), UTC, born, KeepHistories.of(metadata(table)), now)
        again.hasChanges shouldBe false
        assertNoCommit(table, again)

        val gap = KeepReconcile.plan(listOf(rule), UTC, born - Duration.ofDays(3), KeepHistories.of(metadata(table)), now)
        gap.hasChanges shouldBe false
        gap.notKept.map { it.reason }.toSet() shouldBe setOf(KeepNotKeptReason.HISTORY_NOT_AVAILABLE)
        gap.notKept shouldHaveSize 3
        assertNoCommit(table, gap)
    }

    private fun assertNoCommit(
        table: MaintenanceTable,
        plan: KeepPlan,
    ) {
        val revisions = h.revisions(table)
        val location = location(table)
        (keep.apply(run(table), plan) as Guarded.Done).value shouldBe KeepApplied(plan, committed = false)
        h.revisions(table) shouldBe revisions
        location(table) shouldBe location
    }

    @Test
    fun `a refused lease leaves every tag as it was`() {
        val table = h.table("keep10busy")
        val setup = run(table)
        setup.commit { it.newFastAppend() }.shouldBeInstanceOf<Guarded.Done<Unit>>()
        val snapshot = metadata(table).currentSnapshot().snapshotId()
        setup.commit { it.manageSnapshots().createTag(dayTag("2020-01-01"), snapshot) }.shouldBeInstanceOf<Guarded.Done<Unit>>()
        val plan = KeepReconcile.plan(emptyList(), UTC, Instant.EPOCH, KeepHistories.of(metadata(table)), Instant.now())
        plan.removals shouldHaveSize 1
        val before = refs(table)
        val revisions = h.revisions(table)

        val other = UUID.randomUUID()
        h.leases
            .acquire(LeaseKind.TABLE, table.leaseName, other, Duration.ofMinutes(5), h.dbNow() + Duration.ofMinutes(10), "test")
            .shouldBeInstanceOf<LeaseOutcome.Acquired>()
        try {
            keep.apply(run(table), plan).shouldBeInstanceOf<Guarded.Refused>().outcome shouldBe MaintenanceOutcome.BUSY
        } finally {
            h.leases.release(other)
        }
        refs(table) shouldBe before
        h.revisions(table) shouldBe revisions
    }

    @Test
    fun `a plan that no longer matches the table is refused before anything is written`() {
        val table = h.table("keep10stale")
        val setup = run(table)
        setup.commit { it.newFastAppend() }.shouldBeInstanceOf<Guarded.Done<Unit>>()
        val snapshot = metadata(table).currentSnapshot().snapshotId()
        val ghost =
            KeepPlan(emptyList(), emptyList(), listOf(TagRemoval(dayTag(LocalDate.EPOCH.toString()), snapshot)), emptyList(), emptyList())
        val revisions = h.revisions(table)
        shouldThrow<KeepPlanStale> { keep.apply(run(table), ghost) }
        h.revisions(table) shouldBe revisions
    }
}
