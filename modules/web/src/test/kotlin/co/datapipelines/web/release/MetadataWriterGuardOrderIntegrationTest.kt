package co.datapipelines.web.release

import co.datapipelines.typesystem.guard.PrerequisiteKey
import co.datapipelines.web.SharedPostgres
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * #841 (G0) — acquisition ORDER on a real PostgreSQL (brief §C, the out-of-order falsification's target): the guard
 * sorts every class's keys before issuing the first statement, whatever order the caller passed. Observed, not
 * inferred: while a writer waits on the sorted-FIRST key, the later key is provably not held by it; and two writers
 * passing opposite orders, queued FIFO behind one holder, both complete — the input-order mutant deadlocks (`40P01`).
 */
class MetadataWriterGuardOrderIntegrationTest {
    private val rows = GuardRows()
    private val dataSource = SharedPostgres.dataSource()
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val guard = JdbcReleaseMutationGuard(JdbcTemplate(dataSource), BOUND_SECONDS)
    private val pool = Executors.newCachedThreadPool()
    private val holder = LockHolder()

    private fun writer(block: () -> Unit): Future<*> = pool.submit { transactions.executeWithoutResult { block() } }

    @AfterEach
    fun tearDown() {
        holder.close()
        pool.shutdownNow()
        pool.awaitTermination(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
        rows.close()
    }

    @Test
    fun `prerequisite keys passed in reverse are acquired sorted - waiting on the first, the second is not held`() {
        val (first, second) = sortedKeys()
        holder.prerequisite(first)
        val write = writer { guard.prerequisites(listOf(second, first), CODE) }

        LockObserver.awaitWaiter(holder, write).shouldNotBeNull().blockers shouldContain holder.pid
        LockObserver.holdersOf(second) shouldBe 0
        holder.commit()
        write.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `workspace rows passed in reverse are locked sorted - waiting on the first, the second row is free`() {
        val (low, high) = listOf(rows.workspace(), rows.workspace()).sorted()
        holder.workspace(low)
        val write = writer { guard.workspaces(listOf(high, low), CODE) }

        LockObserver.awaitWaiter(holder, write).shouldNotBeNull().blockers shouldContain holder.pid
        rowIsFree(high) shouldBe true
        holder.commit()
        write.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `two writers passing opposite key and workspace orders both complete - no deadlock`() {
        val (first, second) = sortedKeys()
        val (low, high) = listOf(rows.workspace(), rows.workspace()).sorted()
        holder.prerequisite(first)
        // FIFO behind the holder: the in-order writer queues first, so an input-order mutant's reverse writer would hold
        // `second` while waiting for `first` — and the in-order writer, granted `first`, would then wait for `second`.
        val inOrder =
            writer {
                guard.prerequisites(listOf(first, second), CODE)
                guard.workspaces(listOf(low, high), CODE)
            }
        awaitWaiters(1, inOrder)
        val reverse =
            writer {
                guard.prerequisites(listOf(second, first), CODE)
                guard.workspaces(listOf(high, low), CODE)
            }
        awaitWaiters(2, reverse)
        holder.commit()

        inOrder.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
        reverse.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
    }

    private fun sortedKeys(): Pair<PrerequisiteKey, PrerequisiteKey> {
        val keys = listOf(rows.datasourceName("b"), rows.datasourceName("a")).map(PrerequisiteKey::datasource).sorted()
        return keys[0] to keys[1]
    }

    private fun awaitWaiters(
        count: Int,
        latest: Future<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LockObserver.WAIT_PROBE_SECONDS)
        while (LockObserver.waitersOn(holder.pid).size < count) {
            check(!latest.isDone) { "the writer finished without waiting on the holder" }
            check(System.nanoTime() < deadline) { "no $count waiters on the holder within the probe window" }
            Thread.sleep(POLL_MS)
        }
    }

    /** True when nobody holds [id]'s row: a NOWAIT row lock on a fresh connection, rolled back at once. */
    private fun rowIsFree(id: UUID): Boolean =
        LockHolder.open().use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("SELECT id FROM workspaces WHERE id = ? FOR NO KEY UPDATE NOWAIT").use { ps ->
                    ps.setObject(1, id)
                    ps.executeQuery().close()
                }
                true
            } catch (e: SQLException) {
                if (e.sqlState != "55P03") throw e
                false
            } finally {
                c.rollback()
            }
        }

    private companion object {
        const val CODE = "datasource.write.busy"
        const val BOUND_SECONDS = 20
        const val POLL_MS = 20L
    }
}
