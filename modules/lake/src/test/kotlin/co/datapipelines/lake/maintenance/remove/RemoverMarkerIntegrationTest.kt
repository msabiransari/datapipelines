package co.datapipelines.lake.maintenance.remove

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Brief 958 V5–V7: the pass's own marker, a crashed pass's marker, and the `dp/` rule (D5). */
class RemoverMarkerIntegrationTest {
    private val h = RemoverHarness(BUCKET, NAMESPACE)

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `V5 after a completed pass its own marker is gone though it is younger than grace`() {
        val f = h.table("orders")
        val run = UUID.randomUUID()
        val client = FaultingS3Client(h.s3)

        val result = h.remover(client).removeOrphans(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Completed>()

        result.marker shouldBe MarkerFate.Removed
        h.exists(f.target.markerKey(run)) shouldBe false
        client.deleteCalls.last() shouldBe listOf(f.target.markerKey(run))
        result.inventory.map { it.key }.contains(f.target.markerKey(run)) shouldBe false
    }

    @Test
    fun `V5 the lease lost before the final cleanup leaves the marker, and a later run clears it once this run is terminal`() {
        val f = h.table("orders")
        h.put(f.key("data/orphan.parquet"))
        val run = UUID.randomUUID()
        h.clock.shift(RemoverHarness.PAST_GRACE)
        val client = FaultingS3Client(h.s3).apply { afterDelete = { if (it == 0) h.loseLease(f.lease) } }

        val result = h.remover(client).removeOrphans(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Completed>()

        result.marker shouldBe MarkerFate.Left(MarkerLeftReason.LEASE_LOST)
        result.deleted shouldBe 1
        client.deleteCalls.size shouldBe 1
        h.exists(f.target.markerKey(run)) shouldBe true
        result.inventory.map { it.key }.contains(f.target.markerKey(run)) shouldBe true

        h.runStates.terminal(run)
        val again = LakeTestDb.lease(LeaseKind.TABLE, f.target.leaseName)
        val later = UUID.randomUUID()
        val second = h.remover().removeOrphans(f.id, again, later).shouldBeInstanceOf<RemovalResult.Completed>()
        second.marker shouldBe MarkerFate.Removed
        second.deleted shouldBe 2 // the first run's marker, and this run's own
        h.exists(f.target.markerKey(run)) shouldBe false
        h.exists(f.target.markerKey(later)) shouldBe false
    }

    @Test
    fun `V5 the mode changed before the final cleanup leaves the marker with the reason`() {
        val f = h.table("orders")
        h.put(f.key("data/orphan.parquet"))
        val run = UUID.randomUUID()
        h.clock.shift(RemoverHarness.PAST_GRACE)
        val client = FaultingS3Client(h.s3).apply { afterDelete = { if (it == 0) h.setMode("restoring") } }

        val result = h.remover(client).removeOrphans(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Completed>()

        result.marker shouldBe MarkerFate.Left(MarkerLeftReason.MODE_CHANGED)
        h.exists(f.target.markerKey(run)) shouldBe true
        h.setMode("normal")
        h.runStates.terminal(run)
        h.remover().removeOrphans(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
        h.exists(f.target.markerKey(run)) shouldBe false
    }

    @Test
    fun `V6 a crashed pass's marker goes only when it is older than grace and its run is terminal`() {
        val f = h.table("orders")
        val terminalOld = UUID.randomUUID().also { h.runStates.terminal(it) }
        val liveOld = UUID.randomUUID().also { h.runStates.live(it) }
        val absentOld = UUID.randomUUID()
        val terminalYoung = UUID.randomUUID().also { h.runStates.terminal(it) }
        listOf(terminalOld, liveOld, absentOld).forEach { h.put(f.target.markerKey(it), 0) }
        h.separate()
        h.put(f.target.markerKey(terminalYoung), 0)
        h.youngAt(f.target.markerKey(terminalYoung))
        val client = FaultingS3Client(h.s3)
        val run = UUID.randomUUID()

        val result = h.remover(client).removeOrphans(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Completed>()

        h.exists(f.target.markerKey(terminalOld)) shouldBe false
        h.exists(f.target.markerKey(liveOld)) shouldBe true
        h.exists(f.target.markerKey(absentOld)) shouldBe true
        h.exists(f.target.markerKey(terminalYoung)) shouldBe true
        result.left(LeftReason.MARKER_RUN_LIVE) shouldBe 1
        result.left(LeftReason.MARKER_RUN_UNKNOWN) shouldBe 1
        result.left(LeftReason.YOUNG) shouldBe 1
        result.deleted shouldBe 2 // the terminal old marker, and this pass's own
        client.deleteCalls.first() shouldBe listOf(f.target.markerKey(terminalOld))
    }

    @Test
    fun `V7 a recipe and another dp key older than grace survive both passes`() {
        val f = h.table("orders")
        val recipe = f.key("dp/recipes/feed_a/${UUID.randomUUID()}/${UUID.randomUUID()}.json")
        val other = f.key("dp/other-record")
        val orphan = f.key("data/orphan.parquet")
        listOf(recipe, other, orphan).forEach { h.put(it) }
        h.clock.shift(RemoverHarness.PAST_GRACE)

        val orphans = h.remover().removeOrphans(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
        orphans.left(LeftReason.RECIPE) shouldBe 1
        orphans.left(LeftReason.OTHER_DP_KEY) shouldBe 1
        h.exists(orphan) shouldBe false

        f.drop()
        h.unprotectSuperseded(f)
        h.markPurged(f)
        val purge = h.remover().purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
        purge.left(LeftReason.RECIPE) shouldBe 1
        purge.left(LeftReason.OTHER_DP_KEY) shouldBe 1
        purge.purge shouldBe PurgeAnswer.MORE_TO_DO
        h.exists(recipe) shouldBe true
        h.exists(other) shouldBe true
        h.listing(f.target.keyPrefix).map { it.key }.toSet() shouldBe setOf(recipe, other)
    }

    private companion object {
        const val BUCKET = "dp-lake-marker958"
        const val NAMESPACE = "marker958"
    }
}
