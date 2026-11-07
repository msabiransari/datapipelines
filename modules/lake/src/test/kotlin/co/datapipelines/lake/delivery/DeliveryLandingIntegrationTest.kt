package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real admission, trusted listings and permanent ledger rows across execution-owned cleanup. */
class DeliveryLandingIntegrationTest {
    private val h = DeliverySettlementHarness()
    private val targets = listOf(DeliveryTarget("settle", "orders"))
    private val storage = LakeMinio.storage(BUCKET)

    @BeforeEach
    fun reset() = h.reset()

    @Test
    fun `admission retains scratch until cleanup then conditionally fails the received attempt`() {
        withLanding { landing, _, key ->
            val scratch = Reservation()
            val landed = land(landing, key, scratch)
            landed.receipts.single().shouldBeInstanceOf<DeliveryReceipt.Accepted>()
            scratch.closes shouldBe 0
            rowState(landed) shouldBe "RECEIVED"
            landed.close()
            landed.close()
            scratch.closes shouldBe 1
            rowState(landed) shouldBe "FAILED"
            phase(landed) shouldBe "staging"
            h.attemptOutcome(landed.receipts.single().delivery) shouldBe "FAILED"
        }
    }

    @Test
    fun `exhausted slots refuse before scratch reservation and trusted listing`() {
        val admission = admission()
        val held = requireNotNull(admission.acquire { Reservation() })
        val scratch = Reservation()
        try {
            LakeMinio.client().use { client ->
                val landing = landing(client, admission)
                // No done marker exists: listing first would produce incomplete, not capacity.busy.
                val key = DeliveryKey("land799", UUID.randomUUID().toString())
                val failure = shouldThrow<DatapipelinesException> { land(landing, key, scratch) }
                failure.code shouldBe Lake.CAPACITY_BUSY
                scratch.closes shouldBe 0
            }
        } finally {
            held.close()
        }
    }

    @Test
    fun `a fatal listing unwind still releases unopened scratch and the ingestion slot`() {
        val admission = admission()
        val scratch = Reservation()
        val client = mockk<S3Client>()
        val fatal = AssertionError("Controlled fatal listing unwind")
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } throws fatal
        val failure = shouldThrow<AssertionError> { land(landing(client, admission), DeliveryKey("fatal799", "batch"), scratch) }
        (failure === fatal) shouldBe true
        scratch.closes shouldBe 1
        requireNotNull(admission.acquire { Reservation() }).close()
    }

    @Test
    fun `same published fingerprint skips without opening another attempt`() {
        withLanding { landing, _, key ->
            val first = land(landing, key)
            val row = first.receipts.single().delivery
            h.ledger.beginPublishing(row) shouldBe true
            val publishing = requireNotNull(h.repository.find(row.id))
            h.ledger.conclude(publishing, DeliveryConclusion.Published("s3://fixture/metadata/one.json")) shouldBe true
            first.close()
            land(landing, key).use { repeated ->
                repeated.receipts.single().shouldBeInstanceOf<DeliveryReceipt.AlreadyPublished>()
                attempts(row.id) shouldBe 1
                rowState(repeated) shouldBe "PUBLISHED"
            }
        }
    }

    @Test
    fun `changed immutable input refuses without replacing the prior operation`() {
        withLanding(slots = 2) { landing, client, key ->
            land(landing, key).use { first ->
                val original = first.receipts.single().delivery
                put(client, key, "id\n2\n")
                val scratch = Reservation()
                shouldThrow<DatapipelinesException> { land(landing, key, scratch) }.code shouldBe Lake.DELIVERY_CHANGED
                h.repository.find(original.id) shouldBe original
                attempts(original.id) shouldBe 1
                scratch.closes shouldBe 1
            }
        }
    }

    @Test
    fun `same size overwrite during the read fails staging and never deletes landing files`() {
        withLanding { landing, client, key ->
            val landed = land(landing, key)
            put(client, key, "id\n2\n")
            shouldThrow<DatapipelinesException> { landed.readCompleted() }.code shouldBe Lake.DELIVERY_CHANGED_DURING_READ
            landed.close()
            phase(landed) shouldBe "staging"
            h.jdbc.queryForObject(
                "SELECT failure_code FROM lake_deliveries WHERE id = ?",
                String::class.java,
                landed.receipts
                    .single()
                    .delivery.id,
            ) shouldBe Lake.DELIVERY_CHANGED_DURING_READ
            client.getObjectAsBytes { it.bucket(BUCKET).key(dataKey(key)) }.asUtf8String() shouldBe "id\n2\n"
        }
    }

    @Test
    fun `a completed immutable read makes later unpublished failure a validation failure`() {
        withLanding { landing, _, key ->
            val landed = land(landing, key)
            landed.readCompleted()
            landed.close()
            phase(landed) shouldBe "validation"
        }
    }

    @Test
    fun `a later target refusal fails already received targets and releases admission`() {
        withLanding { landing, _, key ->
            val scratch = Reservation()
            shouldThrow<DatapipelinesException> {
                land(landing, key, scratch, targets + DeliveryTarget("settle", "missing"))
            }.code shouldBe Lake.TABLE_UNAVAILABLE
            scratch.closes shouldBe 1
            h.jdbc.queryForObject("SELECT state FROM lake_deliveries", String::class.java) shouldBe "FAILED"
            h.jdbc.queryForObject("SELECT failure_phase FROM lake_deliveries", String::class.java) shouldBe "listing"
            land(landing, key).use { retry -> rowState(retry) shouldBe "RECEIVED" }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["deprecated", "archived", "purged"])
    fun `inactive targets refuse before ledger admission`(status: String) {
        h.jdbc.update(
            "UPDATE lake_tables_managed SET status = ?, purged_at = CASE WHEN ? = 'purged' THEN clock_timestamp() END WHERE id = ?",
            status,
            status,
            h.tableId,
        )
        withLanding { landing, _, key ->
            val scratch = Reservation()
            val expected = if (status == "deprecated") Lake.TABLE_DEPRECATED else Lake.TABLE_UNAVAILABLE
            shouldThrow<DatapipelinesException> { land(landing, key, scratch) }.code shouldBe expected
            scratch.closes shouldBe 1
            h.jdbc.queryForObject("SELECT count(*) FROM lake_deliveries", Int::class.java) shouldBe 0
        }
    }

    @Test
    fun `an expired unconfirmed attempt settles before a fresh operation is received`() {
        withLanding { landing, _, key ->
            val old = land(landing, key)
            val row = old.receipts.single().delivery
            h.ledger.beginPublishing(row) shouldBe true
            h.ledger.conclude(requireNotNull(h.repository.find(row.id)), DeliveryConclusion.Unknown(null)) shouldBe true
            // The execution slot must end before the retry; its stale RECEIVED conclusion cannot change UNKNOWN.
            old.close()
            land(landing, key).use { retry ->
                val fresh = retry.receipts.single().delivery
                (fresh.operationId != row.operationId) shouldBe true
                attempts(row.id) shouldBe 2
                h.attemptOutcome(row) shouldBe "FAILED"
                rowState(retry) shouldBe "RECEIVED"
            }
        }
    }

    @Test
    fun `a live unconfirmed attempt stays busy and cannot be restarted`() {
        withLanding { landing, _, key ->
            val old = land(landing, key)
            val row = old.receipts.single().delivery
            h.ledger.beginPublishing(row) shouldBe true
            val leases = h.leases()
            leases
                .acquire(LeaseKind.TABLE, "settle.orders", row.operationId, Duration.ofMinutes(1), deadline(), "append")
                .shouldBeInstanceOf<LeaseOutcome.Acquired>()
            old.close()
            try {
                shouldThrow<DatapipelinesException> { land(landing, key) }.code shouldBe Lake.TABLE_BUSY
                attempts(row.id) shouldBe 1
                h.repository.find(row.id)?.state shouldBe LakeDeliveryState.PUBLISHING
            } finally {
                leases.release(row.operationId)
            }
        }
    }

    private fun land(
        landing: DeliveryLanding,
        key: DeliveryKey,
        scratch: Reservation = Reservation(),
        requested: List<DeliveryTarget> = targets,
    ): LandedDelivery<Reservation> =
        landing.land(key, requested, UUID.randomUUID(), UUID.randomUUID()) { bytes ->
            bytes shouldBe SCRATCH_BYTES
            scratch
        }

    private fun withLanding(
        slots: Int = 1,
        block: (DeliveryLanding, S3Client, DeliveryKey) -> Unit,
    ) {
        LakeMinio.client().use { client ->
            val key = DeliveryKey("land799", UUID.randomUUID().toString())
            put(client, key, "id\n1\n")
            client.putObject({ it.bucket(BUCKET).key(key.prefix(storage.landingPrefix) + "_done") }, RequestBody.empty())
            block(landing(client, admission(slots)), client, key)
        }
    }

    private fun put(
        client: S3Client,
        key: DeliveryKey,
        text: String,
    ) {
        client.putObject({ it.bucket(BUCKET).key(dataKey(key)) }, RequestBody.fromString(text))
    }

    private fun dataKey(key: DeliveryKey) = key.prefix(storage.landingPrefix) + "input.csv"

    private fun admission(slots: Int = 1) =
        DeliveryAdmission(slots, SCRATCH_BYTES, Duration.ZERO, Duration.ofMinutes(2), h.leases()::catalogNowPlus)

    private fun landing(
        client: S3Client,
        admission: DeliveryAdmission = admission(),
    ) = DeliveryLanding(admission, TrustedDeliveryListing(client, storage, 1024), LakeDefinitionRepository(h.named), h.ledger, h.settler())

    private fun rowState(landed: LandedDelivery<*>) =
        h.repository
            .find(
                landed.receipts
                    .single()
                    .delivery.id,
            )?.state
            ?.name

    private fun phase(landed: LandedDelivery<*>) =
        h.jdbc.queryForObject(
            "SELECT failure_phase FROM lake_deliveries WHERE id = ?",
            String::class.java,
            landed.receipts
                .single()
                .delivery.id,
        )

    private fun attempts(id: UUID) =
        h.jdbc.queryForObject("SELECT count(*) FROM lake_delivery_attempts WHERE delivery_id = ?", Int::class.java, id)

    private fun deadline(): Instant = Instant.now().plusSeconds(120)

    private class Reservation : AutoCloseable {
        var closes = 0

        override fun close() {
            closes++
        }
    }

    private companion object {
        const val BUCKET = "dp-lake-landing799"
        const val SCRATCH_BYTES = 3L
    }
}
