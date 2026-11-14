package co.datapipelines.lake.maintenance.manifests

import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.manifests.ManifestRewriteHarness.Companion.DAYS
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.apache.iceberg.ManifestContent
import org.apache.iceberg.Table
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * **W1–W4 of #963** (brief D2, D2a, D3; record §11, §11a call 34, §8a, §8b): the part folds small manifests in one
 * commit and changes nothing else; it commits nothing when nothing folds or the table is under the threshold; the
 * replaced manifests stay in the bucket and history reads as before. Real managed tables on Postgres and MinIO, run by
 * 957's worker.
 */
class ManifestRewriteIntegrationTest {
    private val h = ManifestRewriteHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    /** The full picture a rewrite must leave unchanged: live file lists, the current totals, every snapshot's totals. */
    private data class Picture(
        val data: Set<String>,
        val deletes: Set<String>,
        val currentTotals: Map<String, String?>,
        val snapshotTotals: Map<Long, Map<String, String?>>,
    )

    private fun picture(t: Table): Picture {
        val (data, deletes) = h.liveFiles(t)
        return Picture(data, deletes, h.totals(t.currentSnapshot()), t.snapshots().associate { it.snapshotId() to h.totals(it) })
    }

    private fun assertUnchanged(
        before: Picture,
        after: Table,
    ) {
        val now = picture(after)
        now.data shouldBe before.data
        now.deletes shouldBe before.deletes
        now.currentTotals shouldBe before.currentTotals
        before.snapshotTotals.forEach { (id, totals) -> withClue("snapshot $id") { now.snapshotTotals[id] shouldBe totals } }
    }

    /** W1's fixture: 12 small data manifests interleaving three days, and one delete manifest; threshold 10. */
    private fun foldFixture(): MaintenanceTable {
        val table = h.table()
        h.threshold(table, THRESHOLD)
        h.manifests(table, count = 12, perManifest = 3, days = DAYS)
        h.deleteEntry(table)
        return table
    }

    @Test
    fun `W1 - many small manifests fold into one per spec, and the files, rows and every snapshot's totals are unchanged`() {
        val table = foldFixture()
        val before = picture(h.loaded(table))
        val deleteManifests = h.manifestList(table).filter { it.content() == ManifestContent.DELETES }.map { it.path() }
        val revisions = h.revisions(table)

        val row = h.run(table)

        val entry = h.entry(row)
        entry["outcome"].asText() shouldBe "done"
        entry["attempted"].asBoolean() shouldBe true
        entry["folded"].asBoolean() shouldBe true
        entry["manifests_before"].asInt() shouldBe 13
        entry["manifests_after"].asInt() shouldBe 2
        entry["files"].asLong() shouldBe 12
        entry["lease_held_millis"].asLong() shouldBeGreaterThanOrEqual 0
        row.state shouldBe "finished"
        h.revisions(table) shouldBe revisions + 1
        val after = h.manifestList(table)
        after.filter { it.content() == ManifestContent.DATA } shouldHaveSize 1
        after.filter { it.content() == ManifestContent.DELETES }.map { it.path() } shouldBe deleteManifests
        assertUnchanged(before, h.loaded(table))
    }

    @Test
    fun `W1a - manifests interleaving partitions under two specs fold into one manifest per spec listing exactly its entries`() {
        val table = h.table()
        h.threshold(table, 4)
        h.manifests(table, count = 3, perManifest = 3, days = DAYS)
        h.addCustomerPartition(table)
        h.manifests(table, count = 3, perManifest = 3, days = DAYS)
        val loaded = h.loaded(table)
        val bySpec = h.entriesByManifest(loaded).groupBy({ it.first }, { it.second }).mapValues { (_, sets) -> sets.flatten().toSet() }
        withClue("the fixture has two specs, three manifests each, every manifest over three partitions") {
            bySpec.keys shouldHaveSize 2
            h.entriesByManifest(loaded) shouldHaveSize 6
        }
        val before = picture(loaded)

        val entry = h.entry(h.run(table))

        entry["outcome"].asText() shouldBe "done"
        entry["folded"].asBoolean() shouldBe true
        entry["manifests_after"].asInt() shouldBe 2
        val after = h.entriesByManifest(h.loaded(table))
        withClue("one manifest per spec, each listing exactly the entries of its spec: $after") {
            after.map { it.first }.sorted() shouldBe bySpec.keys.sorted()
            after.forEach { (spec, paths) -> paths shouldBe bySpec.getValue(spec) }
        }
        assertUnchanged(before, h.loaded(table))
    }

    @Test
    fun `W1b(i) - above the threshold with every manifest at or over half the target is nothing to fold, no commit`() {
        val table = h.table()
        h.threshold(table, 3)
        h.manifests(table, count = 4)
        val shortest = h.manifestList(table).minOf { it.length() }
        h.target(table, shortest * 2)
        val location = h.currentLocation(table)
        val revisions = h.revisions(table)

        val entry = h.entry(h.run(table))

        entry["outcome"].asText() shouldBe "nothing_to_do"
        entry["attempted"].asBoolean() shouldBe true
        entry["reason"].asText() shouldBe "nothing to fold"
        entry["manifests_before"].asInt() shouldBe 4
        h.currentLocation(table) shouldBe location
        h.revisions(table) shouldBe revisions
    }

    @Test
    fun `W1b(ii) - above the threshold with exactly one candidate per spec is nothing to fold, no commit`() {
        val table = h.table()
        h.threshold(table, 3)
        h.entries(table, ManifestRewriteHarness.DAY_1)
        h.manifests(table, count = 1, perManifest = BIG_MANIFEST_ENTRIES)
        h.addCustomerPartition(table)
        h.entries(table, ManifestRewriteHarness.DAY_1)
        val lengths = h.manifestList(table).map { it.length() }.sorted()
        // the two one-entry manifests are under half the target; the big one is not.
        h.target(table, (lengths[1] + 1) * 2)
        withClue("the big manifest is at or over half the target: $lengths") { lengths[2] shouldBeGreaterThanOrEqual lengths[1] + 1 }
        val location = h.currentLocation(table)

        val entry = h.entry(h.run(table))

        entry["outcome"].asText() shouldBe "nothing_to_do"
        entry["reason"].asText() shouldBe "nothing to fold"
        h.currentLocation(table) shouldBe location
    }

    @Test
    fun `W1c - right after a fold a second run is nothing to do, with no commit and the count unchanged`() {
        val table = foldFixture()
        h.entry(h.run(table))["folded"].asBoolean() shouldBe true
        val location = h.currentLocation(table)
        val count = h.manifestList(table).size

        // Under the threshold now (2 manifests < 10): not due.
        h.entry(h.run(table))["outcome"].asText() shouldBe "nothing_to_do"
        // With the threshold at the count, it is due and the rule finds nothing to fold.
        h.threshold(table, count)
        val due = h.entry(h.run(table))

        due["outcome"].asText() shouldBe "nothing_to_do"
        due["attempted"].asBoolean() shouldBe true
        due["reason"].asText() shouldBe "nothing to fold"
        h.currentLocation(table) shouldBe location
        h.manifestList(table) shouldHaveSize count
    }

    @Test
    fun `W1c - after a fold large enough to roll at the target, a second run is nothing to do`() {
        val table = h.table()
        h.threshold(table, THRESHOLD)
        h.bulk(table, count = 60, perManifest = 50)
        val longest = h.manifestList(table).maxOf { it.length() }
        val target = longest * 2 + 2
        h.target(table, target)

        val first = h.entry(h.run(table))

        first["outcome"].asText() shouldBe "done"
        first["folded"].asBoolean() shouldBe true
        val rolled = h.manifestList(table)
        println("(963 W1c roll) target=$target before=60 after=${rolled.size} lengths=${rolled.map { it.length() }}")
        withClue("several manifests rolled at the target, at most one tail under half of it: ${rolled.map { it.length() }}") {
            rolled.size shouldBeGreaterThan 2
            (rolled.count { it.length() < target / 2 } <= 1) shouldBe true
        }
        val location = h.currentLocation(table)
        h.threshold(table, 2)

        val second = h.entry(h.run(table))

        second["outcome"].asText() shouldBe "nothing_to_do"
        second["reason"].asText() shouldBe "nothing to fold"
        h.currentLocation(table) shouldBe location
        h.manifestList(table) shouldHaveSize rolled.size
    }

    @Test
    fun `W1d - after the schema grew a rewrite can keep the count - done, folded false, the next run nothing to do`() {
        val table = h.table()
        h.threshold(table, 2)
        h.manifests(table, count = 2)
        val longest = h.manifestList(table).maxOf { it.length() }
        val target = longest * 2 + TARGET_SLACK
        h.target(table, target)
        // Four columns of documentation, each as long as the target: the new header alone is larger than the target.
        h.growSchema(table, columns = 4, docChars = target.toInt())
        val before = picture(h.loaded(table))

        val entry = h.entry(h.run(table))

        println("(963 W1d) target=$target old_lengths=$longest after=${h.manifestList(table).map { it.length() }} entry=$entry")
        entry["outcome"].asText() shouldBe "done"
        entry["folded"].asBoolean() shouldBe false
        entry["manifests_before"].asInt() shouldBe 2
        entry["manifests_after"].asInt() shouldBeGreaterThanOrEqual 2
        assertUnchanged(before, h.loaded(table))
        val location = h.currentLocation(table)

        val next = h.entry(h.run(table))

        next["outcome"].asText() shouldBe "nothing_to_do"
        next["reason"].asText() shouldBe "nothing to fold"
        h.currentLocation(table) shouldBe location
    }

    @Test
    fun `W2 - under the threshold the part is nothing to do, with no commit and no revision row`() {
        val table = h.table()
        h.threshold(table, THRESHOLD)
        h.manifests(table, count = THRESHOLD - 1)
        val location = h.currentLocation(table)
        val revisions = h.revisions(table)

        val row = h.run(table)

        val entry = h.entry(row)
        entry["outcome"].asText() shouldBe "nothing_to_do"
        entry["attempted"].asBoolean() shouldBe false
        h.currentLocation(table) shouldBe location
        h.revisions(table) shouldBe revisions
        h.manifestList(table) shouldHaveSize THRESHOLD - 1
    }

    /** W3/W4's fixture: real rows in six appends (one manifest each), a tag after the third; threshold 5. */
    private fun rowsFixture(): MaintenanceTable {
        val table = h.table()
        h.threshold(table, 5)
        (0 until 6).forEach { i ->
            h.rows(table, (i * 3L + 1)..(i * 3L + 3), DAYS[i % DAYS.size])
            if (i == 2) h.tag(table, TAG)
        }
        return table
    }

    @Test
    fun `W3 - every replaced manifest stays in the bucket and a reader pinned to the previous revision still reads`() {
        val table = rowsFixture()
        val previous = h.currentLocation(table)
        val replaced = h.manifestList(table).map { it.path() }
        val pinned = h.at(previous)
        val rowsBefore = h.rowsAt(pinned, pinned.currentSnapshot().snapshotId())

        h.entry(h.run(table))["folded"].asBoolean() shouldBe true

        h.currentLocation(table) shouldBe h.revisionLocations(table).last()
        replaced.forEach { path -> withClue(path) { h.exists(path) shouldBe true } }
        val again = h.at(previous)
        h.rowsAt(again, again.currentSnapshot().snapshotId()) shouldBe rowsBefore
        h.liveFiles(again) shouldBe h.liveFiles(pinned)
    }

    @Test
    fun `W4 - time travel to each earlier snapshot returns the rows it returned before, and tags are unchanged`() {
        val table = rowsFixture()
        val loaded = h.loaded(table)
        val rowsBySnapshot = loaded.snapshots().associate { it.snapshotId() to h.rowsAt(loaded, it.snapshotId()) }
        val tagsBefore = loaded.refs().filterValues { it.isTag }.mapValues { it.value.snapshotId() }
        withClue("the fixture has six snapshots and the tag") {
            rowsBySnapshot.keys shouldHaveSize 6
            tagsBefore.keys shouldBe setOf(TAG)
        }

        h.entry(h.run(table))["folded"].asBoolean() shouldBe true

        val after = h.loaded(table)
        rowsBySnapshot.forEach { (id, rows) -> withClue("snapshot $id") { h.rowsAt(after, id) shouldBe rows } }
        after.refs().filterValues { it.isTag }.mapValues { it.value.snapshotId() } shouldBe tagsBefore
        h.rowsAt(after, after.currentSnapshot().snapshotId()) shouldBe rowsBySnapshot.getValue(loaded.currentSnapshot().snapshotId())
    }

    private companion object {
        const val THRESHOLD = 10
        const val BIG_MANIFEST_ENTRIES = 400
        const val TARGET_SLACK = 1_024L
        const val TAG = "dp-test-tag"
    }
}
