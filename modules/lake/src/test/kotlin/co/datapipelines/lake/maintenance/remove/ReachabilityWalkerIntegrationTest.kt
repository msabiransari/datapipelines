package co.datapipelines.lake.maintenance.remove

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.apache.iceberg.ExpireSnapshots
import org.apache.iceberg.GenericStatisticsFile
import org.apache.iceberg.ImmutableGenericPartitionStatisticsFile
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.TableProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Brief 958 V1–V3: the walker reaches exactly record §9's set, from real metadata on MinIO. */
class ReachabilityWalkerIntegrationTest {
    private val h = RemoverHarness(BUCKET, NAMESPACE)

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `V1 three appends, a manifest merge and statistics files - the walker reaches exactly the expected keys`() {
        val f = h.table("orders")
        val data = List(APPENDS) { f.append() }
        f.table
            .rewriteManifests()
            .clusterBy { "all" }
            .commit()
        f.table.refresh()
        f.table
            .currentSnapshot()
            .allManifests(f.table.io())
            .size shouldBe 1
        f.table.currentSnapshot().operation() shouldBe "replace"
        val snapshotId = f.table.currentSnapshot().snapshotId()
        val (stats, partitionStats) = addStatistics(f, snapshotId)

        // The expectation, from the test's own writes and the catalog's revision record — never from the walker.
        val revisions = h.revisions(f)
        val current = revisions.last()
        val io = f.table.io()
        val expected =
            buildSet {
                revisions.forEach { add(f.keyOf(it)) } // the current file plus every earlier one, all in its log (< 100)
                f.table.snapshots().forEach { snapshot ->
                    add(f.keyOf(snapshot.manifestListLocation()))
                    snapshot.allManifests(io).forEach { add(f.keyOf(it.path())) }
                }
                data.forEach { add(f.keyOf(it.location())) }
                add(f.keyOf(stats))
                add(f.keyOf(partitionStats))
            }
        val visits = ArrayList<ReachVisit>()
        val declared = h.walker().walk(listOf(current)) { visits += it }

        visits.map { f.keyOf(it.location) }.toSet() shouldBe expected
        declared shouldBe mapOf(current to f.table.location())
        visits.filter { it.kind == FileKind.DATA }.map { it.location }.toSet() shouldBe data.map { it.location() }.toSet()
        visits.single { it.kind == FileKind.STATISTICS }.let { it.location to it.snapshotId } shouldBe (stats to snapshotId)
        visits.single { it.kind == FileKind.PARTITION_STATISTICS }.let { it.location to it.snapshotId } shouldBe
            (partitionStats to snapshotId)
        val snapshots = f.table.snapshots().count()
        println("(958 V1) revisions=${revisions.size} snapshots=$snapshots expected=${expected.size} visits=${visits.size}")
    }

    @Test
    fun `V2 the metadata log is followed one level - a file named only by an older file's log is not reached`() {
        val f = h.table("orders", mapOf(TableProperties.METADATA_PREVIOUS_VERSIONS_MAX to "1"))
        repeat(APPENDS) { f.append() }
        val revisions = h.revisions(f)
        val current = revisions[revisions.size - 1]
        val parent = revisions[revisions.size - 2]
        val grandparent = revisions[revisions.size - 3]
        val io = f.table.io()
        // The premise: two generations of log, the current file names only its parent, the parent names the grandparent.
        TableMetadataParser.read(io, current).previousFiles().map { it.file() } shouldBe listOf(parent)
        TableMetadataParser.read(io, parent).previousFiles().map { it.file() } shouldBe listOf(grandparent)

        val visits = ArrayList<ReachVisit>()
        h.walker().walk(listOf(current)) { visits += it }
        val metadata = visits.filter { it.kind == FileKind.METADATA }
        metadata.map { it.location }.toSet() shouldBe setOf(current, parent)
        visits.map { it.location } shouldNotContain grandparent
        metadata.all { it.snapshotId == null && it.revision == current } shouldBe true
    }

    @Test
    fun `V3 a tombstone is not reached, and a file is reached from a pinned revision or a null protected_until`() {
        val f = h.table("orders")
        val gone = f.append()
        val appendSnapshot = f.table.currentSnapshot().snapshotId()
        val pinnedRevision = h.revisions(f).last() // the one revision that still lists `gone` as live, after the expiry below
        f.table
            .newDelete()
            .deleteFile(gone)
            .commit()
        val deleteSnapshot = f.table.currentSnapshot().snapshotId()
        val kept = f.append()
        f.table
            .expireSnapshots()
            .expireSnapshotId(appendSnapshot)
            .cleanupLevel(ExpireSnapshots.CleanupLevel.NONE)
            .commit()
        f.table.refresh()
        val io = f.table.io()
        // The tombstone is really there: the delete snapshot, still in the current metadata, carries `gone` as DELETED.
        f.table
            .snapshot(deleteSnapshot)
            .allManifests(io)
            .sumOf { it.deletedFilesCount() ?: 0 } shouldBe 1
        f.table.snapshot(appendSnapshot) shouldBe null
        val current = h.revisions(f).last()

        fun reachedFromProtectedSet(): Set<String> {
            val reached = HashSet<String>()
            h.walker().walk(h.checks().protectedRevisions(f.target)) { reached += f.keyOf(it.location) }
            return reached
        }
        val currentOnly = HashSet<String>().also { r -> h.walker().walk(listOf(current)) { r += f.keyOf(it.location) } }
        currentOnly shouldNotContain f.keyOf(gone.location())
        currentOnly shouldContain f.keyOf(kept.location())

        h.unprotectSuperseded(f)
        reachedFromProtectedSet() shouldNotContain f.keyOf(gone.location())

        val pin = h.pin(f, pinnedRevision)
        h.checks().protectedRevisions(f.target) shouldContain pinnedRevision
        reachedFromProtectedSet() shouldContain f.keyOf(gone.location())
        h.unpin(pin)
        reachedFromProtectedSet() shouldNotContain f.keyOf(gone.location())

        h.setProtectedUntilNull(pinnedRevision)
        reachedFromProtectedSet() shouldContain f.keyOf(gone.location())
    }

    @Test
    fun `an unreadable metadata file fails the walk`() {
        val f = h.table("orders")
        f.append()
        val missing = "${f.table.location()}/metadata/99999-${UUID.randomUUID()}.metadata.json"
        val failure = shouldThrow<ReachabilityReadException> { h.walker().walk(listOf(h.revisions(f).last(), missing)) {} }
        failure.revision shouldBe missing
    }

    /** Writes and commits one statistics file and one partition-statistics file for [snapshotId]; returns their paths. */
    private fun addStatistics(
        f: RemoverHarness.Fixture,
        snapshotId: Long,
    ): Pair<String, String> {
        val stats = "${f.table.location()}/metadata/stats-${UUID.randomUUID()}.puffin"
        val partitionStats = "${f.table.location()}/metadata/partition-stats-${UUID.randomUUID()}.parquet"
        listOf(stats, partitionStats).forEach { path ->
            f.table
                .io()
                .newOutputFile(path)
                .create()
                .use { it.write(ByteArray(STATS_BYTES)) }
        }
        f.table
            .updateStatistics()
            .setStatistics(GenericStatisticsFile(snapshotId, stats, STATS_BYTES.toLong(), 0, emptyList()))
            .commit()
        f.table
            .updatePartitionStatistics()
            .setPartitionStatistics(
                ImmutableGenericPartitionStatisticsFile
                    .builder()
                    .snapshotId(snapshotId)
                    .path(partitionStats)
                    .fileSizeInBytes(STATS_BYTES.toLong())
                    .build(),
            ).commit()
        f.table.refresh()
        return stats to partitionStats
    }

    private companion object {
        const val BUCKET = "dp-lake-walker958"
        const val NAMESPACE = "walker958"
        const val APPENDS = 3
        const val STATS_BYTES = 8
    }
}
