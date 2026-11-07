package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V2
import co.datapipelines.pipeline.WriteSurface
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #812: a lease takeover never waits unbounded on a lease row another transaction holds. The holder here is a
 * second connection that took the row `FOR SHARE` in an open transaction, as the fence trigger does for the life of
 * a catalog transaction (V53 `dp_fence`): a dead attempt's catalog transaction still open, its lease expired. Each
 * acquire waits at most the lease protocol's lock timeout, then answers `Busy`; the row stays as it was for the next
 * sweep. Every wait is bounded here by 3 x the lock timeout, so a regression fails the case instead of hanging it.
 */
class LakeLeaseTakeoverIntegrationTest {
    private val leases = LakeLeases(LakeTestDb.jdbc, holderInstance = "lake-test-812", lockTimeout = LOCK_TIMEOUT)
    private val h = LakeDefinitionsHarness(leases = leases)
    private val pool: ExecutorService = Executors.newSingleThreadExecutor()
    private var holder: Connection? = null

    @BeforeEach
    fun clean() {
        h.reset()
    }

    @AfterEach
    fun close() {
        holder?.let {
            it.rollback()
            it.close()
        }
        pool.shutdown()
        pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `an expired lease whose row another transaction holds is Busy after the lock timeout - the row is unchanged`() {
        val dead = expiredLease()
        hold(dead)
        val started = System.nanoTime()
        val outcome = bounded { leases.acquire(LeaseKind.TABLE, NAME, UUID.randomUUID(), MINUTE, leases.catalogNowPlus(MINUTE), "test") }
        val waited = millisSince(started)
        println("(812 lease) outcome = $outcome after ${waited}ms (lock timeout ${LOCK_TIMEOUT.toMillis()}ms)")
        outcome shouldBe LeaseOutcome.Busy
        (waited >= LOCK_TIMEOUT.toMillis() - SLACK_MILLIS) shouldBe true
        tokenOf() shouldBe dead.token

        release()
        val after = leases.acquire(LeaseKind.TABLE, NAME, UUID.randomUUID(), MINUTE, leases.catalogNowPlus(MINUTE), "test")
        after.shouldBeInstanceOf<LeaseOutcome.Acquired>().lease.token shouldBe tokenOf()
    }

    @Test
    fun `the lock timeout is local to the lease transaction - the pooled connection keeps its own setting`() {
        val connection = LakeTestDb.connect()
        val single = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val shared = LakeLeases(single, holderInstance = "lake-test-812", lockTimeout = LOCK_TIMEOUT)
        val before = single.queryForObject("SHOW lock_timeout", String::class.java)
        shared
            .acquire(LeaseKind.TABLE, NAME, UUID.randomUUID(), MINUTE, shared.catalogNowPlus(MINUTE), "test")
            .shouldBeInstanceOf<LeaseOutcome.Acquired>()
        val after = single.queryForObject("SHOW lock_timeout", String::class.java)
        println(
            "(812 local) lock_timeout on the borrowed connection: before = $before, after = $after; autocommit = ${connection.autoCommit}",
        )
        after shouldBe before
        connection.autoCommit shouldBe true
        connection.close()
    }

    @Test
    fun `verifyApplying over a held lease row is table busy within the lock timeout - the row stays applying`() {
        val id = appliedV1()
        writeV2(id)
        h.landWithoutApply(id, 2)
        val dead = expiredLease()
        h.applies.markApplying(id, 2, dead.operationId) shouldBe 1
        hold(dead)
        val started = System.nanoTime()
        val outcome = bounded { h.verifyApplying(id) }
        println("(812 verify) outcome = $outcome after ${millisSince(started)}ms")
        outcome shouldBe LakeApplyOutcome.Busy(LakeApplyLeases.REASON_TABLE)
        h.table(id).let { (it.applyState to it.applyOperationId) shouldBe (LakeApplyState.APPLYING to dead.operationId) }
        tokenOf() shouldBe dead.token

        release()
        h.verifyApplying(id) shouldBe LakeApplyOutcome.Pending(null)
    }

    @Test
    fun `a release whose table lease row is held returns table busy after the wait - the landing stands`() {
        val id = appliedV1()
        val v2 = writeV2(id)
        val dead = expiredLease()
        hold(dead)
        val started = System.nanoTime()
        val result = bounded { h.service.release(id, v2, h.actor, WriteSurface.SESSION) }
        println("(812 release) outcome = ${result.apply} after ${millisSince(started)}ms")
        result shouldBe LakeReleaseResult(2, LakeApplyOutcome.Busy(LakeApplyLeases.REASON_TABLE))
        h.table(id).let { (it.currentDefinitionVersion to it.applyState) shouldBe (2 to LakeApplyState.PENDING) }
        tokenOf() shouldBe dead.token
    }

    /** A lease on [NAME] whose holder is gone: taken, then expired by UPDATE (never by sleeping). */
    private fun expiredLease(): LeaseHandle {
        val dead = LakeTestDb.lease(LeaseKind.TABLE, NAME)
        h.jdbc.update("UPDATE lake_leases SET expires_at = now() - interval '1 second' WHERE operation_id = ?", dead.operationId)
        return dead
    }

    /** A second connection takes [lease]'s row FOR SHARE and keeps its transaction open, as a live fence would. */
    private fun hold(lease: LeaseHandle) {
        val c = LakeTestDb.connect().also { holder = it }
        c.autoCommit = false
        c.prepareStatement("SELECT token FROM lake_leases WHERE kind = 'table' AND name = ? FOR SHARE").use { s ->
            s.setString(1, lease.name)
            s.executeQuery().use { rs -> rs.next() shouldBe true }
        }
        val locks =
            h.jdbc.queryForList(
                "SELECT l.mode, l.granted FROM pg_locks l JOIN pg_class r ON r.oid = l.relation " +
                    "WHERE r.relname = 'lake_leases' AND l.pid <> pg_backend_pid()",
            )
        println("(812) the holder's locks on lake_leases: $locks")
    }

    private fun release() {
        holder?.let {
            it.rollback()
            it.close()
        }
        holder = null
    }

    /** Runs [call] off the test thread and fails the case when it outlives 3 x the lock timeout. */
    private fun <T> bounded(call: () -> T): T = pool.submit(Callable { call() }).get(BOUND.toMillis(), TimeUnit.MILLISECONDS)

    private fun appliedV1(): UUID {
        val v1 = h.service.createDraft(NS, T, ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION).apply shouldBe LakeApplyOutcome.Applied(1, committed = true)
        return v1.tableId
    }

    private fun writeV2(id: UUID): Int {
        val v1Hash = requireNotNull(h.definitions.findVersion(id, 1)).bodyHash
        return h.service.writeDraft(id, ORDERS_V2, v1Hash, h.actor, WriteSurface.SESSION).version
    }

    private fun tokenOf(): UUID? =
        h.jdbc.queryForObject("SELECT token FROM lake_leases WHERE kind = 'table' AND name = ?", UUID::class.java, NAME)

    private fun millisSince(started: Long): Long = Duration.ofNanos(System.nanoTime() - started).toMillis()

    private companion object {
        const val NS = "sales"
        const val T = "orders"
        const val NAME = "$NS.$T"
        const val SLACK_MILLIS = 100L
        const val WAIT_SECONDS = 30L
        val LOCK_TIMEOUT: Duration = Duration.ofSeconds(1)
        val BOUND: Duration = LOCK_TIMEOUT.multipliedBy(3)
        val MINUTE: Duration = Duration.ofMinutes(1)
    }
}
