package co.datapipelines.lake.maintenance.remove

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Brief 958 V12 (D8, record §11a "When a purge deletion is finished"): a purged table, dropped from the catalog and past
 * its protection window, is emptied down to its recipes — and only then is the answer *finished*.
 */
class RemoverPurgeIntegrationTest {
    private val h = RemoverHarness(BUCKET, NAMESPACE)

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `V12 a young file keeps the purge at more to do, and once it has aged the purge finishes with the recipe kept`() {
        val f = h.table("orders")
        repeat(2) { f.append() }
        val recipe = f.key("dp/recipes/feed_a/${UUID.randomUUID()}/${UUID.randomUUID()}.json").also { h.put(it) }
        purged(f)
        h.separate()
        val young = f.key("data/late.parquet").also { h.put(it) }
        h.youngAt(young)

        val first = h.remover().purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
        first.purge shouldBe PurgeAnswer.MORE_TO_DO
        first.left(LeftReason.YOUNG) shouldBe 1
        first.left.keys shouldBe setOf(LeftReason.YOUNG, LeftReason.RECIPE)
        h.listing(f.target.keyPrefix).map { it.key }.toSet() shouldBe setOf(recipe, young)

        h.oldAt(young)
        val second = h.remover().purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
        second.purge shouldBe PurgeAnswer.FINISHED
        second.marker shouldBe MarkerFate.Removed
        second.left shouldBe mapOf(LeftReason.RECIPE to 1)
        second.inventory.map { it.key } shouldBe listOf(recipe)
        h.listing(f.target.keyPrefix).map { it.key } shouldBe listOf(recipe)
    }

    @Test
    fun `V12 a file reached from a reader-pinned revision is left as reached until the pin goes, then the purge finishes`() {
        val f = h.table("orders")
        val data = f.append()
        val pinnedRevision = f.currentMetadata()
        purged(f)
        val pin = h.pin(f, pinnedRevision)
        h.clock.shift(RemoverHarness.PAST_GRACE)

        repeat(2) {
            val pinned = h.remover().purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
            pinned.purge shouldBe PurgeAnswer.MORE_TO_DO
            (pinned.left(LeftReason.REACHED) > 0) shouldBe true
            h.exists(f.keyOf(data.location())) shouldBe true
            h.exists(f.keyOf(pinnedRevision)) shouldBe true
        }

        h.unpin(pin)
        val unpinned = h.remover().purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
        unpinned.purge shouldBe PurgeAnswer.FINISHED
        h.exists(f.keyOf(data.location())) shouldBe false
        h.listing(f.target.keyPrefix) shouldBe emptyList()
    }

    @Test
    fun `V12 another dp key keeps the purge at more to do for good`() {
        val f = h.table("orders")
        f.append()
        val other = f.key("dp/other-record").also { h.put(it) }
        purged(f)
        h.clock.shift(RemoverHarness.PAST_GRACE)

        repeat(2) {
            val result = h.remover().purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Completed>()
            result.purge shouldBe PurgeAnswer.MORE_TO_DO
            result.left shouldBe mapOf(LeftReason.OTHER_DP_KEY to 1)
            h.listing(f.target.keyPrefix).map { it.key } shouldBe listOf(other)
        }
    }

    @Test
    fun `a purge of a table that is not purged is refused before anything is written`() {
        val f = h.table("orders")
        val client = FaultingS3Client(h.s3)
        val result = h.remover(client).purge(f.id, f.lease, UUID.randomUUID()).shouldBeInstanceOf<RemovalResult.Refused>()
        result.reason shouldBe RefusalReason.NOT_PURGED
        client.calls shouldBe emptyList()
    }

    /** The purge sequence up to the remover: dropped from the catalog, past the protection window, status `purged`. */
    private fun purged(f: RemoverHarness.Fixture) {
        f.drop()
        h.unprotectSuperseded(f)
        h.markPurged(f)
    }

    private companion object {
        const val BUCKET = "dp-lake-purge958"
        const val NAMESPACE = "purge958"
    }
}
