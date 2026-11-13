package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real migrated rows, database-clock liveness and V53-triggered revisions; P6 has no remover yet. */
class ReaderPinsIntegrationTest {
    private val jdbc = LakeTestDb.jdbc
    private val leases = LakeTestDb.leases
    private val pins = ReaderPins(jdbc)

    @BeforeEach
    fun reset() {
        LakeTestDb.reset()
    }

    @Test
    fun `a live pin protects a superseded revision, and a lapsed pin cannot be resurrected`() {
        val writer = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        LakeTestDb.connect().use { connection ->
            connection.prepareStatement("SELECT set_config('dp.operation_id',?,false),set_config('dp.lease_token',?,false)").use {
                it.setString(1, writer.operationId.toString())
                it.setString(2, writer.token.toString())
                it.execute()
            }
            connection.createStatement().use {
                it.execute(
                    "INSERT INTO iceberg_tables (catalog_name,table_namespace,table_name,metadata_location) " +
                        "VALUES ('lake','sales','orders','s3://b/old.json')",
                )
                it.execute("UPDATE iceberg_tables SET metadata_location='s3://b/new.json' WHERE table_name='orders'")
            }
        }
        jdbc.update("UPDATE lake_table_revisions SET protected_until=now()-interval '1 day' WHERE metadata_location=?", OLD)
        val id = UUID.randomUUID()
        val handle = acquire(id, "sales.orders", OLD)
        pins.pinnedLocations("sales", "orders") shouldBe setOf(OLD)
        pins.pinnedLocations("sales", "other") shouldBe emptySet()
        val now = leases.catalogNowPlus(Duration.ZERO)
        val revisions =
            jdbc.query("SELECT metadata_location,protected_until FROM lake_table_revisions", { rs, _ ->
                SnapshotRevision(
                    rs.getString(1),
                    rs.getTimestamp(2)?.toInstant(),
                    now.minus(
                        Duration.ofDays(
                            if (rs.getString(1) ==
                                OLD
                            ) {
                                20
                            } else {
                                2
                            },
                        ),
                    ),
                )
            })
        val protected = SnapshotExpiryFloor.compute(now, Duration.ofDays(7), revisions, pins.pinnedLocations("sales", "orders"))
        protected.expireOlderThan shouldBe now.minus(Duration.ofDays(20))
        protected.protectedRevisions.size shouldBe 2
        leases.heartbeatReaders(setOf(id), MINUTE) shouldBe mapOf(id to 1)
        jdbc.update("UPDATE lake_leases SET expires_at=now()-interval '1 millisecond' WHERE kind='reader'")
        leases.heartbeat(handle, MINUTE) shouldBe false
        leases.heartbeatReaders(setOf(id), MINUTE) shouldBe emptyMap()
        pins.pinnedLocations("sales", "orders") shouldBe emptySet()
        val expired = SnapshotExpiryFloor.compute(now, Duration.ofDays(7), revisions, pins.pinnedLocations("sales", "orders"))
        expired.expireOlderThan shouldBe now.minus(Duration.ofDays(7))
        expired.protectedRevisions.map { it.metadataLocation } shouldBe listOf("s3://b/new.json")
    }

    @Test
    fun `bulk heartbeat scopes the instance and reports table counts for partial loss`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        acquire(a, "sales.orders", OLD)
        acquire(a, "sales.items", "s3://b/items.json")
        acquire(b, "sales.orders", OLD)
        val peer = LakeLeases(jdbc, holderInstance = "another-instance")
        val peerId = UUID.randomUUID()
        peer.acquireReader("sales.orders@peer/$peerId", peerId, MINUTE, leases.catalogNowPlus(MINUTE), OLD)
        leases.heartbeatReaders(setOf(a, b, peerId), MINUTE) shouldBe mapOf(a to 2, b to 1)
        jdbc.update("UPDATE lake_leases SET deadline_at=now()-interval '1 second' WHERE operation_id=?", b)
        leases.heartbeatReaders(setOf(a, b), MINUTE) shouldBe mapOf(a to 2)
        leases.release(a) shouldBe 2
        pins.pinnedLocations("sales", "orders") shouldBe setOf(OLD)
    }

    @Test
    fun `a reader deadline is capped, past deadlines refuse, and transaction use is refused`() {
        val id = UUID.randomUUID()
        leases.acquireReader("sales.orders@past/$id", id, MINUTE, leases.catalogNowPlus(Duration.ofSeconds(-1)), OLD) shouldBe
            LeaseOutcome.DeadlinePassed
        acquire(id, "sales.orders", OLD)
        jdbc.queryForObject("SELECT expires_at<=deadline_at FROM lake_leases", Boolean::class.java) shouldBe true
        val tx = TransactionTemplate(DataSourceTransactionManager(LakeTestDb.dataSource))
        shouldThrow<IllegalStateException> { tx.executeWithoutResult { acquire(UUID.randomUUID(), "sales.items", OLD) } }
        shouldThrow<IllegalStateException> { tx.executeWithoutResult { leases.heartbeatReaders(setOf(id), MINUTE) } }
        shouldThrow<IllegalStateException> { tx.executeWithoutResult { pins.pinnedLocations("sales", "orders") } }
    }

    @Test
    fun `cleanup is in the heartbeat statement and cannot touch another holder`() {
        val live = UUID.randomUUID()
        val closed = UUID.randomUUID()
        val peer = UUID.randomUUID()
        acquire(live, "sales.orders", OLD)
        acquire(closed, "sales.orders", OLD)
        LakeLeases(jdbc, holderInstance = "peer").acquireReader("sales.orders@peer/$peer", peer, MINUTE, leases.catalogNowPlus(MINUTE), OLD)
        leases.heartbeatReaders(setOf(live), MINUTE, setOf(closed, peer)) shouldBe mapOf(live to 1)
        jdbc.queryForObject("SELECT count(*) FROM lake_leases WHERE operation_id=?", Long::class.java, closed) shouldBe 0L
        jdbc.queryForObject("SELECT count(*) FROM lake_leases WHERE operation_id=?", Long::class.java, peer) shouldBe 1L
        leases.heartbeatReaders(emptySet(), MINUTE, setOf(live)) shouldBe emptyMap()
        jdbc.queryForObject("SELECT count(*) FROM lake_leases", Long::class.java) shouldBe 1L
    }

    @Test
    fun `expiry boundaries protect future and unfinalized revisions but not equality`() {
        val now = Instant.EPOCH.plus(Duration.ofDays(30))
        val window = Duration.ofDays(7)
        val current = SnapshotRevision("current", null, now.minus(Duration.ofDays(2)))
        val future = SnapshotRevision("future", now.plusSeconds(1), now.minus(Duration.ofDays(20)))
        val equal = SnapshotRevision("equal", now, now.minus(Duration.ofDays(25)))
        val protection = SnapshotExpiryFloor.compute(now, window, listOf(current, future, equal), emptySet())
        protection.protectedRevisions shouldBe setOf(current, future)
        protection.expireOlderThan shouldBe future.snapshotTimestamp
        SnapshotExpiryFloor.compute(now, window, emptyList(), emptySet()).expireOlderThan shouldBe now.minus(window)
        shouldThrow<IllegalArgumentException> { SnapshotExpiryFloor.compute(now, Duration.ZERO, emptyList(), emptySet()) }
    }

    @Test
    fun `invalid reader inputs and overlapping live and released ids fail before SQL`() {
        val id = UUID.randomUUID()
        val deadline = leases.catalogNowPlus(MINUTE)
        shouldThrow<IllegalArgumentException> { leases.acquireReader("sales.orders@invalid", id, Duration.ZERO, deadline, OLD) }
        shouldThrow<IllegalArgumentException> { leases.acquireReader("sales.orders@invalid", id, MINUTE, deadline, "") }
        shouldThrow<IllegalArgumentException> { leases.heartbeatReaders(setOf(id), MINUTE, setOf(id)) }
        shouldThrow<IllegalArgumentException> { leases.heartbeatReaders(emptySet(), Duration.ZERO) }
        leases.heartbeatReaders(emptySet(), MINUTE) shouldBe emptyMap()
        jdbc.queryForObject("SELECT count(*) FROM lake_leases", Long::class.java) shouldBe 0L
    }

    private fun acquire(
        id: UUID,
        table: String,
        location: String,
    ): LeaseHandle =
        leases
            .acquireReader("$table@${leases.holderInstance}/$id", id, MINUTE, leases.catalogNowPlus(MINUTE), location)
            .shouldBeInstanceOf<LeaseOutcome.Acquired>()
            .lease

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
        const val OLD = "s3://b/old.json"
    }
}
