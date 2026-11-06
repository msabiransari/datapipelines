package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** §8b C1/C2/C3 against the real fence: takeover before a fresh negative revision-log observation. */
class DeliverySettlerIntegrationTest {
    private val h = DeliverySettlementHarness()

    @BeforeEach
    fun clean() {
        h.reset()
    }

    @Test
    fun `a dead attempt without commit evidence fails only after takeover and cannot revive its old token`() {
        val row = h.unknown()
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "settle.orders", operationId = row.operationId)
        h.jdbc.update("UPDATE lake_leases SET expires_at = now() - interval '1 second' WHERE operation_id = ?", row.operationId)
        h.settler().settlePage(h.leases(), h.tableId, deadline()) shouldBe 1
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.FAILED
        h.attemptOutcome(row) shouldBe "FAILED"
        h.fencedConnection(lease).use { connection -> shouldThrow<SQLException> { h.insertPointer(connection) }.sqlState shouldBe "DP001" }
        h.repository.committedEvidence(row.id) shouldBe null
    }

    @Test
    fun `a live attempt stays unknown and is not taken over`() {
        val row = h.unknown()
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "settle.orders", operationId = row.operationId)
        try {
            h.settler().settlePage(h.leases(), h.tableId, deadline()) shouldBe 0
            h.repository.find(row.id)?.state shouldBe LakeDeliveryState.UNKNOWN
            h.attemptOutcome(row) shouldBe "UNKNOWN"
            h.jdbc.queryForObject("SELECT operation_id FROM lake_leases WHERE name = 'settle.orders'", java.util.UUID::class.java) shouldBe
                row.operationId
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    @Test
    fun `C3 a timed out takeover leaves an in-flight expired attempt unknown`() {
        val row = h.unknown()
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "settle.orders", ttl = Duration.ofSeconds(1), operationId = row.operationId)
        h.fencedConnection(lease).use { holder ->
            holder.autoCommit = false
            h.insertPointer(holder) shouldBe 1
            await("lease expiry by the catalog clock") { !h.observations.attemptLeaseLive(target(), row.operationId) }
            h.settler().settlePage(h.leases(), h.tableId, deadline()) shouldBe 0
            h.repository.find(row.id)?.state shouldBe LakeDeliveryState.UNKNOWN
            h.attemptOutcome(row) shouldBe "UNKNOWN"
            h.repository.committedEvidence(row.id) shouldBe null
            holder.commit()
        }
        h.settler().settlePage(h.leases(), h.tableId, deadline()) shouldBe 1
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.PUBLISHED
        h.attemptOutcome(row) shouldBe "PUBLISHED"
    }

    @Test
    fun `C1 takeover waits for the in-flight commit before reading a fresh revision snapshot`() {
        val row = h.unknown()
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "settle.orders", ttl = Duration.ofSeconds(1), operationId = row.operationId)
        val settler = h.settler(h.leases(Duration.ofSeconds(5)))
        h.fencedConnection(lease).use { holder ->
            holder.autoCommit = false
            h.insertPointer(holder) shouldBe 1
            await("lease expiry by the catalog clock") { !h.observations.attemptLeaseLive(target(), row.operationId) }
            val pending = CompletableFuture.supplyAsync { settler.settlePage(h.leases(), h.tableId, deadline()) }
            try {
                await("the takeover waiting on the fence's FOR SHARE") {
                    h.jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                            "AND query LIKE 'INSERT INTO lake_leases%')",
                        Boolean::class.java,
                    ) == true || pending.isDone
                }
                pending.isDone shouldBe false
                h.repository.find(row.id)?.state shouldBe LakeDeliveryState.UNKNOWN
                h.repository.committedEvidence(row.id) shouldBe null
                holder.commit()
                pending.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe 1
            } finally {
                holder.rollback()
                pending.get(WAIT_SECONDS, TimeUnit.SECONDS)
            }
        }
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.PUBLISHED
        h.repository.find(row.id)?.metadataLocationAfter shouldBe "s3://settle/metadata/one.json"
        h.attemptOutcome(row) shouldBe "PUBLISHED"
    }

    @Test
    fun `received rows for ended or retained-away executions fail before publication`() {
        val row = h.receive()
        h.settler().settlePage(h.leases(), h.tableId, deadline()) shouldBe 1
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.FAILED
        h.attemptOutcome(row) shouldBe "FAILED"
        h.jdbc.queryForObject("SELECT failure_phase FROM lake_deliveries WHERE id = ?", String::class.java, row.id) shouldBe "validation"
    }

    @Test
    fun `settlement refuses a surrounding transaction with a potentially stale snapshot`() {
        h.receive()
        shouldThrow<IllegalStateException> {
            h.transaction.executeWithoutResult { h.settler().settlePage(h.leases(), h.tableId, deadline()) }
        }
    }

    private fun target() = requireNotNull(h.observations.target(h.tableId))

    private fun deadline() = h.leases().catalogNowPlus(Duration.ofMinutes(1))

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < limit) { "Timed out: $description" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val POLL_MILLIS = 20L
    }
}
