package co.datapipelines.lake.maintenance.remove

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.iceberg.GenericStatisticsFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Brief 958 V15 (D10): the result is 964's input — the inventory is the location as the pass leaves it, and the visits
 * say how every reached key was reached. Nothing is derived from a file name: kinds come from how the test wrote each file.
 */
class RemoverResultIntegrationTest {
    private val h = RemoverHarness(BUCKET, NAMESPACE)

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `V15 inventory equals the test's own listing, every reached key has its kind, a shared file has one visit per snapshot`() {
        val f = h.table("orders")
        val shared = f.append()
        val sharedSnapshot = f.table.currentSnapshot().snapshotId()
        val second = f.append() // a second snapshot that still lists `shared` as an EXISTING entry
        val secondSnapshot = f.table.currentSnapshot().snapshotId()
        val stats = "${f.table.location()}/metadata/stats-${UUID.randomUUID()}.puffin"
        f.table
            .io()
            .newOutputFile(stats)
            .create()
            .use { it.write(ByteArray(STATS_BYTES)) }
        f.table
            .updateStatistics()
            .setStatistics(GenericStatisticsFile(secondSnapshot, stats, STATS_BYTES.toLong(), 0, emptyList()))
            .commit()
        val recipe = f.key("dp/recipes/feed_a/${UUID.randomUUID()}/${UUID.randomUUID()}.json").also { h.put(it) }
        val orphan = f.keyOf(f.writeData().location())
        h.clock.shift(RemoverHarness.PAST_GRACE)
        f.table.refresh()
        val current = f.currentMetadata()
        val revisions = h.revisions(f).toSet()
        val manifestLists = f.table.snapshots().associate { f.keyOf(it.manifestListLocation()) to it.snapshotId() }
        val manifests =
            f.table
                .snapshots()
                .flatMap { s -> s.allManifests(f.table.io()).map { f.keyOf(it.path()) } }
                .toSet()

        val result = h.remover().removeOrphans(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()

        val listing = h.listing(f.target.keyPrefix)
        result.inventory.map { it.key to it.size } shouldBe listing.map { it.key to it.size }
        result.inventory.any { it.key == orphan } shouldBe false
        result.inventory.any { it.key == recipe } shouldBe true

        val byKey = result.visits.groupBy { f.keyOf(it.location) }
        val reachedKeys = listing.map { it.key } - recipe
        reachedKeys.forEach { (it in byKey) shouldBe true }
        revisions.forEach { revision ->
            byKey.getValue(f.keyOf(revision)).all { it.kind == FileKind.METADATA && it.snapshotId == null } shouldBe true
        }
        manifestLists.forEach { (key, snapshot) ->
            byKey.getValue(key).map { it.kind to it.snapshotId }.toSet() shouldBe setOf(FileKind.MANIFEST_LIST to snapshot)
        }
        manifests.forEach { key -> byKey.getValue(key).all { it.kind == FileKind.MANIFEST } shouldBe true }
        byKey.getValue(f.keyOf(stats)).map { it.kind to it.snapshotId }.toSet() shouldBe setOf(FileKind.STATISTICS to secondSnapshot)
        byKey.getValue(f.keyOf(second.location())).map { it.kind to it.snapshotId }.toSet() shouldBe setOf(FileKind.DATA to secondSnapshot)

        // `shared` is live in both snapshots: one visit per (revision, snapshot) — from the current revision, exactly two.
        val sharedVisits = byKey.getValue(f.keyOf(shared.location()))
        sharedVisits.all { it.kind == FileKind.DATA } shouldBe true
        sharedVisits.filter { it.revision == current }.map { it.snapshotId } shouldBe listOf(sharedSnapshot, secondSnapshot)
        sharedVisits.size shouldBe sharedVisits.map { it.revision to it.snapshotId }.toSet().size
        result.visits.filter { it.kind == FileKind.METADATA }.all { it.snapshotId == null } shouldBe true
        println("(958 V15) inventory=${result.inventory.size} visits=${result.visits.size} sharedVisits=${sharedVisits.size}")
    }

    @Test
    fun `a refused pass returns no inventory and no visits`() {
        val f = h.table("orders")
        h.setMode("restoring")
        h.remover().removeOrphans(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Refused>()
    }

    private companion object {
        const val BUCKET = "dp-lake-result958"
        const val NAMESPACE = "result958"
        const val STATS_BYTES = 8
    }
}
