package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.settle.LakeSettlementSweep
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** §8a case F: protection starts from an observation after visibility, never the trigger's earlier timestamp. */
class ProtectionObservationIntegrationTest {
    @Test
    fun `an uncommitted supersession stays protected and a later observation grants the full backup window`() {
        val h = DeliverySettlementHarness()
        h.reset()
        val initial = LakeTestDb.lease(LeaseKind.TABLE, "settle.orders")
        try {
            h.fencedConnection(initial).use { connection -> h.insertPointer(connection) shouldBe 1 }
        } finally {
            LakeTestDb.leases.release(initial.operationId)
        }
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "settle.orders")
        val sweep =
            LakeSettlementSweep(h.named, h.leases(), h.settler(), mockk(), mockk(), mockk(), LakeProperties.DEFAULT_PROTECTION_DAYS)
        try {
            h.fencedConnection(lease).use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "UPDATE iceberg_tables SET metadata_location = 's3://settle/metadata/two.json' " +
                            "WHERE table_namespace = 'settle' AND table_name = 'orders'",
                    ) shouldBe 1
                }
                sweep.finalizeProtection() shouldBe 0
                val backupWindow =
                    requireNotNull(
                        h.jdbc.queryForObject(
                            "SELECT clock_timestamp() + make_interval(days => ?)",
                            OffsetDateTime::class.java,
                            sweep.protectionDays,
                        ),
                    )
                connection.commit()
                sweep.finalizeProtection() shouldBe 1
                val protection =
                    requireNotNull(
                        h.jdbc.queryForObject(
                            "SELECT protected_until FROM lake_table_revisions WHERE superseded_at IS NOT NULL",
                            OffsetDateTime::class.java,
                        ),
                    )
                protection.toInstant().isBefore(backupWindow.toInstant()) shouldBe false
                sweep.finalizeProtection() shouldBe 0
                println("case=protection_observation revisions=2 before_commit_finalized=0 after_commit_finalized=1")
            }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }
}
