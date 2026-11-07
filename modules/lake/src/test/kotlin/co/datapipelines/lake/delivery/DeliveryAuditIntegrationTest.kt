package co.datapipelines.lake.delivery

import co.datapipelines.auth.AuditEventSink
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** The strict audit write belongs to the delivery/attempt transaction, including its rollback. */
class DeliveryAuditIntegrationTest {
    @Test
    fun `an audit refusal rolls back both the delivery verdict and attempt outcome`() {
        val h = DeliverySettlementHarness()
        h.reset()
        val received = h.receive()
        h.ledger.beginPublishing(received) shouldBe true
        val row = requireNotNull(h.repository.find(received.id))
        val sink = Sink(refuse = true)
        val ledger = DeliveryLedger(h.repository, h.transaction, sink)
        shouldThrow<IllegalStateException> { ledger.conclude(row, DeliveryConclusion.Published("s3://fixture/metadata/committed.json")) }
        sink.inTransaction shouldBe true
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.PUBLISHING
        h.attemptOutcome(row) shouldBe null
    }

    @Test
    fun `settled audit and terminal outcome are emitted in the same transaction with identifier details only`() {
        val h = DeliverySettlementHarness()
        h.reset()
        val row = h.unknown()
        val sink = Sink()
        val ledger = DeliveryLedger(h.repository, h.transaction, sink)
        ledger.conclude(row, DeliveryConclusion.Published("s3://fixture/metadata/committed.json"), settled = true) shouldBe true
        sink.inTransaction shouldBe true
        sink.events shouldBe listOf(LakeDeliveryAuditEvents.PUBLISHED, LakeDeliveryAuditEvents.SETTLED)
        sink.keys shouldBe setOf("table_id", "delivery_id", "operation_id", "outcome")
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.PUBLISHED
        h.attemptOutcome(row) shouldBe "PUBLISHED"
    }

    private class Sink(
        private val refuse: Boolean = false,
    ) : AuditEventSink {
        var inTransaction = false
        var keys: Set<String> = emptySet()
        val events = mutableListOf<String>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            inTransaction = TransactionSynchronizationManager.isActualTransactionActive()
            keys = details.keys
            events += event
            check(!refuse) { "Audit refused." }
        }
    }
}
