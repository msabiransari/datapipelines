package co.datapipelines.lake.maintenance.remove

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.S3Error
import java.util.UUID

/**
 * Brief 958 V14 (D9a): faults injected by a client that wraps the real one, MinIO staying the store. In purge mode none
 * answers *finished*; after each, a fresh pass without the fault completes the work.
 */
class RemoverFailureIntegrationTest {
    private val h = RemoverHarness(BUCKET, NAMESPACE)

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `V14a a response confirming some keys and refusing others - confirmed ones gone, the rest delete_failed, next batch runs`() {
        val f = purgedTable(ORPHANS)
        val before = h.listing(f.target.keyPrefix)
        val refused = orphanKeys(f).take(REFUSED_KEYS).toSet()
        val client =
            FaultingS3Client(h.s3).apply {
                onDelete = { index, request, real ->
                    if (index != 0) {
                        null
                    } else {
                        val requested = request.delete().objects().map { it.key() }
                        val confirmed = real.deleteObjects(requestFor(requested - refused)).deleted()
                        val errors =
                            (requested intersect refused).map {
                                S3Error
                                    .builder()
                                    .key(it)
                                    .code("AccessDenied")
                                    .message("injected")
                                    .build()
                            }
                        DeleteObjectsResponse
                            .builder()
                            .deleted(confirmed)
                            .errors(errors)
                            .build()
                    }
                }
            }

        val result = h.remover(client).purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()

        client.deleteCalls.size shouldBe 3 // batch one (refusals injected), batch two, the own marker
        result.left(LeftReason.DELETE_FAILED) shouldBe REFUSED_KEYS
        result.deleted shouldBe before.size - REFUSED_KEYS + 1 // every listed key but the refused ones, and the own marker
        result.stopped shouldBe null
        result.purge shouldBe PurgeAnswer.MORE_TO_DO
        refused.forEach { h.exists(it) shouldBe true }
        result.inventory.map { it.key }.toSet() shouldBe refused
        afterFaultAFreshPassFinishes(f)
    }

    @Test
    fun `V14b the second of three batches throws - batch one counted, batch two outcome_unknown, batch three not_attempted`() {
        val f = purgedTable(THREE_BATCHES)
        val before = h.listing(f.target.keyPrefix)
        val client =
            FaultingS3Client(h.s3).apply {
                onDelete = { index, _, _ -> if (index == 1) throw SdkClientException.create("injected failure") else null }
            }

        val result = h.remover(client).purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()

        result.stopped shouldBe StopReason.DELETE_FAILED
        result.left(LeftReason.OUTCOME_UNKNOWN) shouldBe RemoverHarness.BATCH
        result.left(LeftReason.NOT_ATTEMPTED) shouldBe before.size - 2 * RemoverHarness.BATCH
        result.deleted shouldBe RemoverHarness.BATCH + 1 // batch one, and the own marker (still removed under D5)
        result.marker shouldBe MarkerFate.Removed
        client.deleteCalls.map { it.size } shouldBe listOf(RemoverHarness.BATCH, RemoverHarness.BATCH, 1) // the third: the marker
        result.purge shouldBe PurgeAnswer.MORE_TO_DO
        h.listing(f.key("data/")).size shouldBe THREE_BATCHES - RemoverHarness.BATCH
        afterFaultAFreshPassFinishes(f)
    }

    @Test
    fun `V14c a batch applied by the store whose response is lost - its keys outcome_unknown and still in the inventory though gone`() {
        val f = purgedTable(ORPHANS)
        val before = h.listing(f.target.keyPrefix)
        val lostBatch = before.take(RemoverHarness.BATCH).map { it.key }
        val client =
            FaultingS3Client(h.s3).apply {
                onDelete = { index, request, real ->
                    if (index == 0) {
                        real.deleteObjects(request)
                        throw SdkClientException.create("injected: response lost")
                    }
                    null
                }
            }

        val result = h.remover(client).purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()

        result.left(LeftReason.OUTCOME_UNKNOWN) shouldBe RemoverHarness.BATCH
        result.left(LeftReason.NOT_ATTEMPTED) shouldBe before.size - RemoverHarness.BATCH
        result.stopped shouldBe StopReason.DELETE_FAILED
        client.deleteCalls.first() shouldBe lostBatch
        lostBatch.forEach { h.exists(it) shouldBe false }
        result.inventory.map { it.key } shouldBe before.map { it.key } // the own marker removed; the lost batch still listed
        result.purge shouldBe PurgeAnswer.MORE_TO_DO
        afterFaultAFreshPassFinishes(f)
    }

    @Test
    fun `V14d the own marker's delete unconfirmed - marker left`() {
        val f = purgedTable(ORPHANS)
        val run = UUID.randomUUID()
        val marker = f.target.markerKey(run)
        val client =
            FaultingS3Client(h.s3).apply {
                onDelete = { _, request, _ ->
                    if (request.delete().objects().map { it.key() } == listOf(marker)) DeleteObjectsResponse.builder().build() else null
                }
            }

        val result = h.remover(client).purge(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Completed>()

        result.marker shouldBe MarkerFate.Left(MarkerLeftReason.DELETE_UNCONFIRMED)
        result.purge shouldBe PurgeAnswer.MORE_TO_DO
        result.stopped shouldBe null
        h.exists(marker) shouldBe true
        result.inventory.map { it.key } shouldBe listOf(marker)
        h.runStates.terminal(run)
        afterFaultAFreshPassFinishes(f)
    }

    /** A purged table (dropped, unprotected, status `purged`) holding [orphans] old files; the clock past grace. */
    private fun purgedTable(orphans: Int): RemoverHarness.Fixture {
        val f = h.table("orders")
        f.drop()
        h.unprotectSuperseded(f)
        h.markPurged(f)
        repeat(orphans) { h.put(f.key("data/orphan-%05d.parquet".format(it))) }
        h.clock.shift(RemoverHarness.PAST_GRACE)
        return f
    }

    private fun orphanKeys(f: RemoverHarness.Fixture): List<String> = h.listing(f.key("data/")).map { it.key }

    private fun afterFaultAFreshPassFinishes(f: RemoverHarness.Fixture) {
        val fresh = h.remover().purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
        fresh.purge shouldBe PurgeAnswer.FINISHED
        h.listing(f.target.keyPrefix) shouldBe emptyList()
    }

    private fun requestFor(keys: List<String>): DeleteObjectsRequest =
        DeleteObjectsRequest
            .builder()
            .bucket(h.bucket)
            .delete(Delete.builder().objects(keys.map { ObjectIdentifier.builder().key(it).build() }).build())
            .build()

    private companion object {
        const val BUCKET = "dp-lake-failure958"
        const val NAMESPACE = "failure958"

        /** Two batches: 1000, then 3. */
        const val ORPHANS = 1_003
        const val REFUSED_KEYS = 4
        const val THREE_BATCHES = 2_005
    }
}
