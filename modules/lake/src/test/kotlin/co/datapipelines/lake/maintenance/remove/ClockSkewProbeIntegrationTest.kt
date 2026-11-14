package co.datapipelines.lake.maintenance.remove

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brief 958 V4 (record E1 (o), the probe's four clock cases, plus the lease-lost fifth and a wide-interval boundary case):
 * the catalog clock is shifted by wrapping its one function (D7); the store is the real MinIO with its own clock.
 */
class ClockSkewProbeIntegrationTest {
    private val h = RemoverHarness(BUCKET, NAMESPACE)

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @ParameterizedTest
    @ValueSource(longs = [ADMITTED, -ADMITTED])
    fun `V4 a catalog 3500 s ahead of or behind the store is admitted and the pass runs`(shiftSeconds: Long) {
        val f = h.table("orders")
        val client = FaultingS3Client(h.s3)
        val run = UUID.randomUUID()
        h.clock.shift(Duration.ofSeconds(shiftSeconds))

        val result = h.remover(client).removeOrphans(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Completed>()

        result.marker shouldBe MarkerFate.Removed
        client.listCalls shouldBe 1
        h.exists(f.target.markerKey(run)) shouldBe false
    }

    @ParameterizedTest
    @ValueSource(longs = [REFUSED, -REFUSED])
    fun `V4 a catalog 3700 s ahead of or behind the store is refused, nothing listed, one delete - the own marker`(shiftSeconds: Long) {
        val f = h.table("orders")
        h.put(f.key("data/orphan.parquet"))
        val client = FaultingS3Client(h.s3)
        val run = UUID.randomUUID()
        h.clock.shift(Duration.ofSeconds(shiftSeconds))

        val result = h.remover(client).removeOrphans(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Refused>()

        result.reason shouldBe RefusalReason.CLOCK_SKEW
        result.reason.code shouldBe PipelineErrorCodes.Lake.MAINTENANCE_CLOCK_SKEW
        result.reason.code shouldBe "lake.maintenance.clock_skew"
        result.marker shouldBe MarkerFate.Removed
        val skew = requireNotNull(result.skew)
        // Store minus catalog: a catalog AHEAD by 3700 s puts the whole interval near −3700 s, and the reverse.
        (if (shiftSeconds > 0) skew.upper < Duration.ofSeconds(-SKEW) else skew.lower > Duration.ofSeconds(SKEW)) shouldBe true
        client.listCalls shouldBe 0
        client.deleteCalls shouldBe listOf(listOf(f.target.markerKey(run)))
        h.exists(f.target.markerKey(run)) shouldBe false
        h.exists(f.key("data/orphan.parquet")) shouldBe true
    }

    @Test
    fun `V4 fifth - refused for skew with the lease lost before the cleanup - no delete call at all, the marker left`() {
        val f = h.table("orders")
        val client = FaultingS3Client(h.s3)
        val run = UUID.randomUUID()
        h.clock.shift(Duration.ofSeconds(REFUSED))
        val reads = AtomicInteger()
        // The probe's second reading (after its PUT) is where the caller's lease is lost: before the refusal's cleanup.
        val losing =
            CatalogClock {
                h.clock.now().also { if (reads.incrementAndGet() == 2) h.loseLease(f.lease) }
            }

        val result = h.remover(client, catalogClock = losing).removeOrphans(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Refused>()

        result.reason shouldBe RefusalReason.CLOCK_SKEW
        result.marker shouldBe MarkerFate.Left(MarkerLeftReason.LEASE_LOST)
        client.deleteCalls shouldBe emptyList()
        client.listCalls shouldBe 0
        h.exists(f.target.markerKey(run)) shouldBe true
    }

    @Test
    fun `V4 boundary - an interval whose midpoint is inside but one end is outside is refused`() {
        val f = h.table("orders")
        val client = FaultingS3Client(h.s3)
        val run = UUID.randomUUID()
        val reads = AtomicInteger()
        // before = real − 3000 s (upper ≈ +3001 s, inside); after = real + 3700 s (lower ≈ −3700 s, outside): midpoint ≈ −350 s.
        val wide =
            CatalogClock {
                val real = h.clock.now()
                if (reads.incrementAndGet() == 1) real.minusSeconds(WIDE_BEFORE) else real.plusSeconds(REFUSED)
            }

        val result = h.remover(client, catalogClock = wide).removeOrphans(f.id, f.lease, run).shouldBeInstanceOf<RemovalResult.Refused>()

        val skew = requireNotNull(result.skew)
        (skew.upper <= ClockSkewProbe.SKEW_ALLOWANCE) shouldBe true
        (skew.lower < ClockSkewProbe.SKEW_ALLOWANCE.negated()) shouldBe true
        (
            skew.lower
                .plus(skew.upper)
                .dividedBy(2)
                .abs() < ClockSkewProbe.SKEW_ALLOWANCE
        ) shouldBe true
        result.reason shouldBe RefusalReason.CLOCK_SKEW
        client.listCalls shouldBe 0
    }

    private companion object {
        const val BUCKET = "dp-lake-probe958"
        const val NAMESPACE = "probe958"
        const val ADMITTED = 3_500L
        const val REFUSED = 3_700L
        const val SKEW = 3_600L
        const val WIDE_BEFORE = 3_000L
    }
}
