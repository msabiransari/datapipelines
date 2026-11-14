package co.datapipelines.lake.maintenance.remove

import co.datapipelines.lake.DeferredDeleteHints
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/** Brief 958 V8–V11: orphans, refusals, the queue as a hint, and the fresh read. */
class RemoverOrphanIntegrationTest {
    private val h = RemoverHarness(BUCKET, NAMESPACE)

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `V8 an abandoned append's old files go, young ones stay, a reachable file stays, and the counts match the store`() {
        val f = h.table("orders")
        val reachable = f.append()
        val oldOrphans = List(2) { f.writeData() }.map { f.keyOf(it.location()) }
        h.separate()
        val youngOrphans = List(2) { f.writeData() }.map { f.keyOf(it.location()) }
        h.youngAt(youngOrphans.last())
        val before = h.listing(f.target.keyPrefix)
        val run = UUID.randomUUID()

        val result = h.remover().removeOrphans(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Completed>()

        val after = h.listing(f.target.keyPrefix)
        oldOrphans.forEach { h.exists(it) shouldBe false }
        youngOrphans.forEach { h.exists(it) shouldBe true }
        h.exists(f.keyOf(reachable.location())) shouldBe true
        result.listed shouldBe before.size + 1 // the store before the pass, plus the marker the pass PUT before listing
        result.deleted shouldBe oldOrphans.size + 1 // the old orphans, and the pass's own marker
        before.map { it.key }.toSet() - after.map { it.key }.toSet() shouldBe oldOrphans.toSet()
        result.bytesDeleted shouldBe before.filter { it.key in oldOrphans }.sumOf { it.size }
        result.left(LeftReason.YOUNG) shouldBe youngOrphans.size
        result.left(LeftReason.REACHED) shouldBe before.size - oldOrphans.size - youngOrphans.size
        result.inventory.map { it.key to it.size } shouldBe after.map { it.key to it.size }
        result.stopped shouldBe null
        println("(958 V8) listed=${result.listed} deleted=${result.deleted} expected=${oldOrphans.size + 1} left=${result.left}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["restoring", "replaying"])
    fun `V9 outside normal mode nothing is listed, written or deleted`(mode: String) {
        val f = h.table("orders")
        h.put(f.key("data/orphan.parquet"))
        h.clock.shift(RemoverHarness.PAST_GRACE)
        h.setMode(mode)
        val client = FaultingS3Client(h.s3)

        val orphans = h.remover(client).removeOrphans(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Refused>()
        orphans.reason shouldBe RefusalReason.MODE_NOT_NORMAL
        orphans.marker shouldBe MarkerFate.NotWritten
        h.markPurged(f)
        val purge = h.remover(client).purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Refused>()
        purge.reason shouldBe RefusalReason.MODE_NOT_NORMAL
        client.calls shouldBe emptyList()
        h.exists(f.key("data/orphan.parquet")) shouldBe true
    }

    @Test
    fun `V9 the lease lost between two batches stops the pass before the next batch`() {
        val (f, client, result) = twoBatchPass { f -> h.loseLease(f.lease) }
        result.stopped shouldBe StopReason.LEASE_LOST
        result.marker shouldBe MarkerFate.Left(MarkerLeftReason.LEASE_LOST)
        assertStoppedAfterOneBatch(f, client, result)
    }

    @Test
    fun `V9 the mode changed between two batches stops the pass before the next batch`() {
        val (f, client, result) = twoBatchPass { h.setMode("replaying") }
        result.stopped shouldBe StopReason.MODE_CHANGED
        result.marker shouldBe MarkerFate.Left(MarkerLeftReason.MODE_CHANGED)
        assertStoppedAfterOneBatch(f, client, result)
    }

    @Test
    fun `V10 a queued key that is reachable, young, under dp or outside the location is not deleted and is counted`() {
        val f = h.table("orders")
        val other = h.table("other")
        val reachable = f.append()
        val oldOrphan = f.writeData()
        val dpKey = f.key("dp/other-record").also { h.put(it) }
        val otherTableKey = other.key("data/theirs.parquet").also { h.put(it) }
        val siblingKey = "${f.target.keyPrefix.dropLast(1)}-evil/data/x.parquet".also { h.put(it) }
        h.separate()
        val youngOrphan = f.writeData()
        h.youngAt(f.keyOf(youngOrphan.location()))
        val hints =
            listOf(
                reachable.location(),
                youngOrphan.location(),
                "s3://${h.bucket}/$dpKey",
                "s3://${h.bucket}/$otherTableKey",
                "s3://${h.bucket}/$siblingKey",
                "s3://another-bucket/${f.key("data/x.parquet")}",
                oldOrphan.location(),
            )

        val result = h.remover().removeOrphans(f.id, f.lease, UUID.randomUUID(), hints).shouldBeInstanceOf<RemovalResult.Completed>()

        result.deferred(HintReason.REACHED) shouldBe 1
        result.deferred(HintReason.YOUNG) shouldBe 1
        result.deferred(HintReason.UNDER_DP) shouldBe 1
        result.deferred(HintReason.OUTSIDE_LOCATION) shouldBe 3
        result.deferredHints.values.sum() shouldBe 6 // the old orphan's hint is a candidate, not a deferral
        listOf(f.keyOf(reachable.location()), f.keyOf(youngOrphan.location()), dpKey, otherTableKey, siblingKey).forEach {
            h.exists(it) shouldBe true
        }
        h.exists(f.keyOf(oldOrphan.location())) shouldBe false
    }

    @Test
    fun `V10 the refusing FileIO queues a library delete on the collecting thread only`() {
        val f = h.table("orders")
        val data = f.append()
        val queue = ArrayList<String>()
        DeferredDeleteHints.collectInto(queue) {
            shouldThrow<IllegalStateException> { f.table.io().deleteFile(data.location()) }
        }
        shouldThrow<IllegalStateException> { f.table.io().deleteFile("${f.table.location()}/data/not-collected.parquet") }
        queue shouldBe listOf(data.location())
        h.exists(f.keyOf(data.location())) shouldBe true
    }

    @Test
    fun `V11 nothing from before the call is reused - a file referenced after pass 1 is kept as reached by pass 2`() {
        val f = h.table("orders")
        val file = f.writeData()
        val key = f.keyOf(file.location())
        val remover = h.remover()

        val first = remover.removeOrphans(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
        first.left(LeftReason.YOUNG) shouldBe 1
        f.table
            .newAppend()
            .appendFile(file)
            .commit() // the test builds the commit through Iceberg's API; production never does this
        LakeTestDb.leases.release(f.lease.operationId)
        val again = LakeTestDb.lease(LeaseKind.TABLE, f.target.leaseName)
        h.clock.shift(RemoverHarness.PAST_GRACE)

        val second = remover.removeOrphans(f.id, again, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()

        h.exists(key) shouldBe true
        second.visits.any { f.keyOf(it.location) == key && it.kind == FileKind.DATA } shouldBe true
        second.inventory.any { it.key == key } shouldBe true
        second.left(LeftReason.YOUNG) shouldBe 0
    }

    private data class TwoBatch(
        val f: RemoverHarness.Fixture,
        val client: FaultingS3Client,
        val result: RemovalResult.Completed,
    )

    /** More than 1000 old orphans, so the candidates take two batches; [between] runs after the first batch answered. */
    private fun twoBatchPass(between: (RemoverHarness.Fixture) -> Unit): TwoBatch {
        val f = h.table("orders")
        repeat(ORPHANS) { h.put(f.key("data/orphan-%05d.parquet".format(it))) }
        h.clock.shift(RemoverHarness.PAST_GRACE)
        val client = FaultingS3Client(h.s3).apply { afterDelete = { if (it == 0) between(f) } }
        val result = h.remover(client).removeOrphans(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
        return TwoBatch(f, client, result)
    }

    private fun assertStoppedAfterOneBatch(
        f: RemoverHarness.Fixture,
        client: FaultingS3Client,
        result: RemovalResult.Completed,
    ) {
        client.deleteCalls.size shouldBe 1
        client.deleteCalls.single().size shouldBe RemoverHarness.BATCH
        result.deleted shouldBe RemoverHarness.BATCH
        result.left(LeftReason.NOT_ATTEMPTED) shouldBe ORPHANS - RemoverHarness.BATCH
        h.listing(f.key("data/")).size shouldBe ORPHANS - RemoverHarness.BATCH
        h.listing(f.key("dp/clock/")).size shouldBe 1
        println("(958 V9) deleted=${result.deleted} expected=${RemoverHarness.BATCH} stopped=${result.stopped?.wire}")
    }

    private companion object {
        const val BUCKET = "dp-lake-orphans958"
        const val NAMESPACE = "orphans958"
        const val ORPHANS = 1_001
    }
}
