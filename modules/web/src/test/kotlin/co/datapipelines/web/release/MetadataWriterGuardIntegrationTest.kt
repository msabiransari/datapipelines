package co.datapipelines.web.release

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.guard.EndpointRegistryLock
import co.datapipelines.typesystem.guard.PrerequisiteKey
import co.datapipelines.typesystem.guard.PrerequisiteNamespace
import co.datapipelines.web.SharedPostgres
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * #841 (G0) — the writer guard's mechanics on a REAL PostgreSQL (brief §C layer (b), A36 shape): a raw holder
 * connection takes one lock class, the guarded writer runs on its own connection, and a third, fresh connection
 * observes the wait (`pg_blocking_pids` names the holder). Ends by the holder's commit or by the writer's own refusal,
 * never by a sleep.
 *
 * The deadline cases use the guard's injectable monotonic clock to PRE-SPEND the budget, so a reset-deadline mutant
 * (a fresh `D` per statement) waits the full long bound and misses the completion allowance, while the correct guard
 * is refused with a real `55P03` within the few milliseconds left.
 */
class MetadataWriterGuardIntegrationTest {
    private val rows = GuardRows()
    private val dataSource = SharedPostgres.dataSource()
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val pool = Executors.newCachedThreadPool()
    private val holders = mutableListOf<LockHolder>()
    private val skew = AtomicLong()
    private val lockStatements = AtomicInteger()

    private val counting =
        object : JdbcTemplate(dataSource) {
            override fun queryForList(
                sql: String,
                vararg args: Any?,
            ): MutableList<MutableMap<String, Any>> {
                if (sql.contains("pg_advisory_xact_lock")) lockStatements.incrementAndGet()
                return super.queryForList(sql, *args)
            }
        }

    private fun guard(boundSeconds: Int = BOUND_SECONDS) = JdbcReleaseMutationGuard(counting, boundSeconds) { System.nanoTime() + skew.get() }

    private fun holder() = LockHolder().also { holders += it }

    private fun writer(block: () -> Unit): Future<*> = pool.submit { transactions.executeWithoutResult { block() } }

    @AfterEach
    fun tearDown() {
        holders.forEach(LockHolder::close)
        pool.shutdownNow()
        pool.awaitTermination(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
        rows.close()
    }

    @Test
    fun `a writer waits on a held prerequisite key - two-int advisory form in its own namespace - and proceeds when the holder commits`() {
        val ws = rows.workspace()
        val key = PrerequisiteKey.datasource(rows.datasourceName("a"))
        val holder = holder().prerequisite(key)
        val guard = guard()
        val write = writer { guard.prerequisites(listOf(key), CODE).also { guard.workspaces(listOf(ws), CODE) } }

        val waiter = LockObserver.awaitWaiter(holder, write).shouldNotBeNull()
        waiter.blockers shouldContain holder.pid
        waiter.lockType shouldBe "advisory"
        waiter.classId shouldBe PrerequisiteNamespace.DATASOURCE.lockSpace.toLong()
        waiter.objId shouldBe LockObserver.objIdOf(JdbcReleaseMutationGuard.nameHash(key.name))
        waiter.objSubId shouldBe TWO_INT_FORM
        holder.commit()
        write.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
        holders.forEach(LockHolder::close)
        LockObserver.holdersOf(key) shouldBe 0
    }

    @Test
    fun `a writer waits on a held workspace row and proceeds when the holder commits`() {
        val ws = rows.workspace()
        val holder = holder().workspace(ws)
        val guard = guard()
        val write = writer { guard.workspaces(listOf(ws), CODE) }

        val waiter = LockObserver.awaitWaiter(holder, write).shouldNotBeNull()
        waiter.blockers shouldContain holder.pid
        holder.commit()
        write.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `a writer waits on the held endpoint registry key - single bigint form, distinct from every prerequisite key`() {
        val holder = holder().registry()
        val guard = guard()
        val write = writer { guard.endpointRegistry(CODE) }

        val waiter = LockObserver.awaitWaiter(holder, write).shouldNotBeNull()
        waiter.blockers shouldContain holder.pid
        waiter.lockType shouldBe "advisory"
        waiter.classId shouldBe (EndpointRegistryLock.KEY ushr Int.SIZE_BITS)
        waiter.objId shouldBe (EndpointRegistryLock.KEY and 0xFFFF_FFFFL)
        waiter.objSubId shouldBe BIGINT_FORM
        holder.commit()
        write.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `an exhausted real wait refuses with the caller code, exactly lock_wait_seconds and the 55P03 cause - and leaves no lock`() {
        val ws = rows.workspace()
        val holder = holder().workspace(ws)
        val guard = guard(boundSeconds = 1)
        val write = writer { guard.workspaces(listOf(ws), CODE) }

        val refusal = refusalOf(write)
        refusal.code shouldBe CODE
        refusal.details shouldBe mapOf("lock_wait_seconds" to 1)
        sqlStateOnCauseChain(refusal) shouldBe LOCK_NOT_AVAILABLE
    }

    @Test
    fun `a pre-spent deadline is not reset by the next class - the later wait gets only the remainder and refuses 55P03`() {
        val ws = rows.workspace()
        val key = PrerequisiteKey.datasource(rows.datasourceName("spent"))
        val holder = holder().workspace(ws)
        val guard = guard(boundSeconds = LONG_BOUND_SECONDS)
        val write =
            writer {
                guard.prerequisites(listOf(key), CODE)
                // All but REMAINDER_MILLIS of the long bound has passed since D was fixed at the first acquisition.
                skew.set(TimeUnit.SECONDS.toNanos(LONG_BOUND_SECONDS.toLong()) - TimeUnit.MILLISECONDS.toNanos(REMAINDER_MILLIS))
                guard.workspaces(listOf(ws), CODE)
            }

        // A mutant that granted the next class a fresh bound would still be waiting when the allowance ends (red).
        val refusal = refusalOf(write)
        refusal.code shouldBe CODE
        sqlStateOnCauseChain(refusal) shouldBe LOCK_NOT_AVAILABLE
    }

    @Test
    fun `a deadline already passed refuses with no cause and issues no further lock statement - shape 2 on a real server`() {
        val keyA = PrerequisiteKey.datasource(rows.datasourceName("a"))
        val keyB = PrerequisiteKey.datasource(rows.datasourceName("b"))
        val guard = guard()
        val write =
            writer {
                guard.prerequisites(listOf(keyA), CODE)
                skew.set(TimeUnit.SECONDS.toNanos(BOUND_SECONDS.toLong()))
                guard.prerequisites(listOf(keyB), CODE)
            }

        val refusal = refusalOf(write)
        refusal.code shouldBe CODE
        refusal.cause.shouldBeNull()
        lockStatements.get() shouldBe 1
        LockObserver.holdersOf(keyA) shouldBe 0
        LockObserver.holdersOf(keyB) shouldBe 0
    }

    @Test
    fun `a writer on an unrelated key and workspace never waits on another writer's locks`() {
        val wsHeld = rows.workspace()
        val wsFree = rows.workspace()
        val held = PrerequisiteKey.datasource(rows.datasourceName("held"))
        val free = PrerequisiteKey.datasource(rows.datasourceName("free"))
        holder().prerequisite(held).workspace(wsHeld)
        val guard = guard()

        writer {
            guard.prerequisites(listOf(free), CODE)
            guard.workspaces(listOf(wsFree), CODE)
            guard.endpointRegistry(CODE)
        }.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS)
    }

    private fun refusalOf(write: Future<*>): DatapipelinesException {
        val failure = shouldThrow<ExecutionException> { write.get(LockObserver.COMPLETION_ALLOWANCE_SECONDS, TimeUnit.SECONDS) }
        return failure.cause as? DatapipelinesException ?: throw AssertionError("expected the busy refusal", failure.cause)
    }

    private fun sqlStateOnCauseChain(error: Throwable): String? =
        generateSequence(error.cause) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private companion object {
        const val CODE = "datasource.write.busy"
        const val BOUND_SECONDS = 5
        const val LONG_BOUND_SECONDS = 120
        const val REMAINDER_MILLIS = 300L
        const val LOCK_NOT_AVAILABLE = "55P03"
        const val TWO_INT_FORM = 2
        const val BIGINT_FORM = 1
    }
}
