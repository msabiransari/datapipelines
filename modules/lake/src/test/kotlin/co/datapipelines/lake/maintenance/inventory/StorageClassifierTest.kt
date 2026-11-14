package co.datapipelines.lake.maintenance.inventory

import co.datapipelines.lake.maintenance.cleanup.ExpiryFloor
import co.datapipelines.lake.maintenance.cleanup.ObservedRef
import co.datapipelines.lake.maintenance.cleanup.ObservedRefType
import co.datapipelines.lake.maintenance.cleanup.ObservedSnapshot
import co.datapipelines.lake.maintenance.remove.FileKind
import co.datapipelines.lake.maintenance.remove.ListedObject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Random

/**
 * Brief 964 A (B1–B4, B4a): the six rows built by hand over the classifier's pure input. Snapshot ids: [OLD_KEPT] and
 * [OLD_HELD] are older than the floor and tagged (keep-held); [OLD_PLAIN] is older and untagged, [IN_WINDOW_TAGGED] is
 * tagged and younger than the floor, [CURRENT] is the current snapshot; [EARLIER] is listed only by an earlier protected
 * revision (not by the current metadata).
 */
class StorageClassifierTest {
    @Test
    fun `B1 first fit - a recipe and a clock marker are dp_records, never not_referenced, also when the walk reached them`() {
        val recipe = key("dp/recipes/2026/11/01/recipe.json")
        val marker = key("dp/clock/0b8e7c5a-1d2e-4f30-9a4b-5c6d7e8f9a0b")
        val other = key("dp/other/thing")
        val placed =
            rows(
                listOf(recipe, marker, other),
                reached = mapOf(recipe to reach(FileKind.METADATA), marker to reach(FileKind.DATA, CURRENT)),
            )
        placed shouldBe mapOf(recipe to StorageRow.DP_RECORDS, marker to StorageRow.DP_RECORDS, other to StorageRow.DP_RECORDS)
    }

    @Test
    fun `B1 first fit - a key reached as metadata and as data is table_metadata, current before history before keep_held`() {
        val both = key("metadata/odd.avro")
        val shared = key("data/shared.parquet")
        placedRow(both, reach(setOf(FileKind.MANIFEST, FileKind.DATA), setOf(CURRENT))) shouldBe StorageRow.TABLE_METADATA
        placedRow(shared, reach(FileKind.DATA, CURRENT, OLD_PLAIN, OLD_KEPT)) shouldBe StorageRow.CURRENT
        placedRow(shared, reach(FileKind.DELETE, OLD_PLAIN, OLD_KEPT)) shouldBe StorageRow.HISTORY
        placedRow(shared, reach(FileKind.DATA, OLD_KEPT)) shouldBe StorageRow.KEEP_HELD
    }

    @Test
    fun `B1 every metadata kind is table_metadata and an unreached key outside dp is not_referenced`() {
        listOf(FileKind.METADATA, FileKind.MANIFEST_LIST, FileKind.MANIFEST, FileKind.STATISTICS, FileKind.PARTITION_STATISTICS)
            .forEach { kind -> placedRow(key("metadata/$kind"), reach(kind)) shouldBe StorageRow.TABLE_METADATA }
        rows(listOf(key("data/orphan.parquet"))).values.single() shouldBe StorageRow.NOT_REFERENCED
        // A key outside the table's location (never listed by the cleanup, but the classifier is total): not referenced.
        rows(listOf("lake/other/table/dp/recipes/r.json")).values.single() shouldBe StorageRow.NOT_REFERENCED
    }

    @Test
    fun `B2 a file shared by a keep-held and a history snapshot is history, shared with the current snapshot it is current`() {
        placedRow(key("data/a.parquet"), reach(FileKind.DATA, OLD_KEPT, OLD_PLAIN)) shouldBe StorageRow.HISTORY
        placedRow(key("data/b.parquet"), reach(FileKind.DATA, OLD_KEPT, CURRENT)) shouldBe StorageRow.CURRENT
        placedRow(key("data/c.parquet"), reach(FileKind.DATA, OLD_HELD, IN_WINDOW_TAGGED)) shouldBe StorageRow.HISTORY
    }

    @Test
    fun `B3 a file only keep-held snapshots reach is keep_held, also when an earlier protected revision lists that snapshot`() {
        // The walk reaches the file through OLD_KEPT from two revisions: the same snapshot id, so still keep-held.
        placedRow(key("data/k.parquet"), reach(FileKind.DATA, OLD_KEPT)) shouldBe StorageRow.KEEP_HELD
        placedRow(key("data/kh.parquet"), reach(FileKind.DATA, OLD_KEPT, OLD_HELD)) shouldBe StorageRow.KEEP_HELD
        StorageClassifier.keepHeld(input(emptyList())) shouldBe setOf(OLD_KEPT, OLD_HELD)
    }

    @Test
    fun `B3 a tagged snapshot still inside the window makes its files history`() {
        placedRow(key("data/w.parquet"), reach(FileKind.DATA, IN_WINDOW_TAGGED)) shouldBe StorageRow.HISTORY
    }

    @Test
    fun `B3 a dp-hold tag counts like a dp-keep tag, and only tags with those prefixes count`() {
        placedRow(key("data/h.parquet"), reach(FileKind.DATA, OLD_HELD)) shouldBe StorageRow.KEEP_HELD
        placedRow(key("data/p.parquet"), reach(FileKind.DATA, OLD_PLAIN)) shouldBe StorageRow.HISTORY // tag "release-1"
        placedRow(key("data/b.parquet"), reach(FileKind.DATA, OLD_BRANCH)) shouldBe StorageRow.HISTORY // a dp-keep- BRANCH
    }

    @Test
    fun `B3 a snapshot only an earlier protected revision lists is history, even with a keep tag name on it`() {
        placedRow(key("data/e.parquet"), reach(FileKind.DATA, EARLIER)) shouldBe StorageRow.HISTORY
    }

    @Test
    fun `B4a never published - no current snapshot, floor not applicable, one metadata file, one unreached data file, one recipe`() {
        val metadata = key("metadata/00000-a.metadata.json")
        val data = key("data/abandoned.parquet")
        val recipe = key("dp/recipes/r.json")
        val listing = listOf(ListedObject(metadata, 100, T0), ListedObject(data, 20, T0), ListedObject(recipe, 3, T0))
        val input =
            ClassifierInput(LOCATION, listing, mapOf(metadata to reach(FileKind.METADATA)), null, emptyList(), emptyList(), ExpiryFloor.NotApplicable)

        val breakdown = StorageBreakdown.of(listing, StorageClassifier.place(input))

        breakdown.rows shouldBe
            mapOf(
                StorageRow.DP_RECORDS to RowFigure(1, 3),
                StorageRow.TABLE_METADATA to RowFigure(1, 100),
                StorageRow.CURRENT to RowFigure.ZERO,
                StorageRow.HISTORY to RowFigure.ZERO,
                StorageRow.KEEP_HELD to RowFigure.ZERO,
                StorageRow.NOT_REFERENCED to RowFigure(1, 20),
            )
        (breakdown.objects to breakdown.bytes) shouldBe (3L to 123L)
        breakdown.rows.keys.toList() shouldBe StorageRow.entries // all six, in order
    }

    @Test
    fun `B4a no floor means no keep-held snapshot, whatever the tags say`() {
        val i = input(emptyList()).copy(floor = ExpiryFloor.NotApplicable)
        StorageClassifier.keepHeld(i) shouldBe emptySet()
    }

    @Test
    fun `B4 property - over random inputs every listed key is placed exactly once and the rows add up to the listing`() {
        val random = Random(SEED)
        var nonEmptyRows = 0
        repeat(ROUNDS) {
            val listing = (0 until random.nextInt(MAX_KEYS)).map { i -> ListedObject(key(randomPath(random, i)), random.nextInt(MAX_SIZE).toLong(), T0) }
            val reached =
                listing
                    .filter { random.nextBoolean() }
                    .associate { o ->
                        val kinds = (1..1 + random.nextInt(2)).map { FileKind.entries[random.nextInt(FileKind.entries.size)] }.toSet()
                        val ids = (0 until random.nextInt(3)).map { ALL_IDS[random.nextInt(ALL_IDS.size)] }.toSet()
                        o.key to ReachedKey(kinds, ids)
                    }
            val i = input(listing, reached).copy(currentSnapshotId = if (random.nextInt(4) == 0) null else CURRENT)

            val placements = StorageClassifier.place(i)

            placements.map { it.listed.key } shouldBe listing.map { it.key }
            val breakdown = StorageBreakdown.of(listing, placements)
            (breakdown.objects to breakdown.bytes) shouldBe (listing.size.toLong() to listing.sumOf { it.size })
            nonEmptyRows += breakdown.rows.values.count { it.objects > 0 }
        }
        // Non-vacuity: the random inputs exercised the rows, not only empty listings.
        (nonEmptyRows > ROUNDS) shouldBe true
    }

    @Test
    fun `B4 an object counted twice or dropped is refused with the two totals`() {
        val listing = listOf(ListedObject(key("data/a"), 5, T0), ListedObject(key("data/b"), 7, T0))
        val twice = listing.map { Placement(it, StorageRow.NOT_REFERENCED) } + Placement(listing[0], StorageRow.CURRENT)
        val e = shouldThrow<InventoryTotalsMismatch> { StorageBreakdown.of(listing, twice) }
        listOf(e.listedObjects, e.listedBytes, e.rowObjects, e.rowBytes) shouldBe listOf(2L, 12L, 3L, 17L)
        val dropped = listOf(Placement(listing[0], StorageRow.NOT_REFERENCED))
        shouldThrow<InventoryTotalsMismatch> { StorageBreakdown.of(listing, dropped) }.rowObjects shouldBe 1L
    }

    @Test
    fun `TableLocation splits the location the cleanup hands over into bucket and key prefix`() {
        TableLocation.parse("s3://lake-bucket/lake/sales/2b1c") shouldBe TableLocation("lake-bucket", "lake/sales/2b1c/")
        TableLocation.parse("s3://lake-bucket/sales/2b1c") shouldBe TableLocation("lake-bucket", "sales/2b1c/")
        shouldThrow<IllegalArgumentException> { TableLocation.parse("file:///tmp/x") }
        shouldThrow<IllegalArgumentException> { TableLocation.parse("s3://bucket-only") }
    }

    private fun placedRow(
        key: String,
        reach: ReachedKey,
    ): StorageRow = rows(listOf(key), mapOf(key to reach)).getValue(key)

    private fun rows(
        keys: List<String>,
        reached: Map<String, ReachedKey> = emptyMap(),
    ): Map<String, StorageRow> =
        StorageClassifier
            .place(input(keys.map { ListedObject(it, 1, T0) }, reached))
            .associate { it.listed.key to it.row }

    private fun input(
        listing: List<ListedObject>,
        reached: Map<String, ReachedKey> = emptyMap(),
    ) = ClassifierInput(
        location = LOCATION,
        listing = listing,
        reached = reached,
        currentSnapshotId = CURRENT,
        snapshots =
            listOf(
                ObservedSnapshot(OLD_KEPT, FLOOR - Duration.ofDays(3)),
                ObservedSnapshot(OLD_HELD, FLOOR - Duration.ofDays(2)),
                ObservedSnapshot(OLD_PLAIN, FLOOR - Duration.ofDays(1)),
                ObservedSnapshot(OLD_BRANCH, FLOOR - Duration.ofHours(1)),
                ObservedSnapshot(IN_WINDOW_TAGGED, FLOOR + Duration.ofDays(1)),
                ObservedSnapshot(CURRENT, FLOOR + Duration.ofDays(2)),
            ),
        refs =
            listOf(
                ObservedRef("main", ObservedRefType.BRANCH, CURRENT),
                ObservedRef("dp-keep-day-2026-10-20", ObservedRefType.TAG, OLD_KEPT),
                ObservedRef("dp-hold-legal-1", ObservedRefType.TAG, OLD_HELD),
                ObservedRef("release-1", ObservedRefType.TAG, OLD_PLAIN),
                ObservedRef("dp-keep-day-2026-10-21", ObservedRefType.BRANCH, OLD_BRANCH),
                ObservedRef("dp-keep-day-2026-11-12", ObservedRefType.TAG, IN_WINDOW_TAGGED),
                // A tag on a snapshot the current metadata does not list (it never makes it keep-held).
                ObservedRef("dp-keep-day-2026-10-01", ObservedRefType.TAG, EARLIER),
            ),
        floor = ExpiryFloor.Applied(FLOOR),
    )

    private fun reach(
        kind: FileKind,
        vararg ids: Long,
    ) = ReachedKey(setOf(kind), ids.toSet())

    private fun reach(
        kinds: Set<FileKind>,
        ids: Set<Long>,
    ) = ReachedKey(kinds, ids)

    private fun randomPath(
        random: Random,
        i: Int,
    ): String = listOf("dp/recipes/", "dp/clock/", "metadata/", "data/", "data/p=1/")[random.nextInt(5)] + "obj-$i"

    private companion object {
        const val LOCATION = "s3://lake-bucket/lake/sales/2b1c9d"
        const val PREFIX = "lake/sales/2b1c9d/"
        val T0: Instant = Instant.parse("2026-11-14T00:00:00Z")
        val FLOOR: Instant = Instant.parse("2026-11-07T00:00:00Z")
        const val OLD_KEPT = 1L
        const val OLD_HELD = 2L
        const val OLD_PLAIN = 3L
        const val OLD_BRANCH = 4L
        const val IN_WINDOW_TAGGED = 5L
        const val CURRENT = 6L
        const val EARLIER = 7L
        val ALL_IDS = listOf(OLD_KEPT, OLD_HELD, OLD_PLAIN, OLD_BRANCH, IN_WINDOW_TAGGED, CURRENT, EARLIER)
        const val SEED = 964L
        const val ROUNDS = 500
        const val MAX_KEYS = 40
        const val MAX_SIZE = 10_000

        fun key(relative: String) = PREFIX + relative
    }
}
