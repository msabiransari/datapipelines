package co.datapipelines.web.release

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.guard.EndpointRegistryLock
import co.datapipelines.typesystem.guard.KeySetChanged
import co.datapipelines.typesystem.guard.PrerequisiteKey
import co.datapipelines.typesystem.guard.PrerequisiteNamespace
import co.datapipelines.typesystem.guard.Replanning
import co.datapipelines.typesystem.guard.WriterBusy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * #841 (G0) — the cumulative deadline's ARITHMETIC against an injected monotonic clock (brief §C layer (a), review R2-2):
 * `D = t0 + bound` is fixed once, at the first acquisition of the write (`t0`, the measurement origin); every later
 * acquisition — and every replanning retry inside [Replanning.run] — is granted `max(0, D − now)` as its statement-local
 * `lock_timeout`; at `now ≥ D` the guard refuses (shape 2: `cause == null`) without issuing another statement.
 *
 * No database: a recording [JdbcTemplate] answers the guard's statements and advances the clock when a lock statement
 * "waits". The real-PostgreSQL half (blocker identity, `55P03`, rollback) is `MetadataWriterGuardIntegrationTest`. A
 * reset-deadline mutant (a fresh `D` per key, per class or per retry) fails the `*refuses*` cases on any machine: the
 * clock is the test's, not the scheduler's.
 */
class JdbcReleaseMutationGuardDeadlineTest {
    private var now = T0
    private val jdbc = RecordingJdbc()
    private val guard = JdbcReleaseMutationGuard(jdbc, BOUND_SECONDS) { now }

    @AfterEach
    fun endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    /** One stand-in metadata transaction: synchronization active, as the guard requires, and torn down after [block]. */
    private fun <T> inTransaction(block: () -> T): T {
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            return block()
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun `the first statement is granted the full bound and a later one the remainder - limits may sum above the bound`() {
        inTransaction {
            guard.prerequisites(listOf(KEY_A), CODE)
            now += millis(500)
            guard.workspaces(listOf(WS_1), CODE)
        }
        // The first acquired at once with the full bound; the second, 500 ms later, got the 4500 ms left of D. The two
        // limits sum to 9500 ms > the 5000 ms bound — CORRECT: the invariant is elapsed time against D, not a sum.
        jdbc.grantedTimeouts() shouldContainExactly listOf("5000ms", "4500ms")
        jdbc.lockStatements() shouldBe 2
    }

    @Test
    fun `a lock that waited spends the deadline - the next acquisition is granted only what is left of D`() {
        jdbc.waitOnLock = millis(3_200)
        inTransaction {
            guard.prerequisites(listOf(KEY_A), CODE)
            jdbc.waitOnLock = 0
            guard.workspaces(listOf(WS_1, WS_2), CODE)
        }
        jdbc.grantedTimeouts() shouldContainExactly listOf("5000ms", "1800ms", "1800ms")
    }

    @Test
    fun `at now at or past D the guard refuses with no cause and issues no further statement - shape 2`() {
        val refusal =
            shouldThrow<DatapipelinesException> {
                inTransaction {
                    guard.prerequisites(listOf(KEY_A), CODE)
                    now += TimeUnit.SECONDS.toNanos(BOUND_SECONDS.toLong())
                    guard.workspaces(listOf(WS_1), CODE)
                }
            }
        refusal.code shouldBe CODE
        refusal.message shouldBe WriterBusy.MESSAGE
        refusal.details shouldBe mapOf("lock_wait_seconds" to BOUND_SECONDS)
        refusal.cause.shouldBeNull()
        // Only the first key's lock statement ran — and no SHOW/SET for a statement that was never issued.
        jdbc.lockStatements() shouldBe 1
        jdbc.statements.size shouldBe STATEMENTS_PER_ACQUISITION
    }

    @Test
    fun `a nearly spent deadline is granted at least one millisecond - never Postgres' zero, which waits forever`() {
        inTransaction {
            guard.prerequisites(listOf(KEY_A), CODE)
            now += TimeUnit.SECONDS.toNanos(BOUND_SECONDS.toLong()) - 1
            guard.workspaces(listOf(WS_1), CODE)
        }
        jdbc.grantedTimeouts() shouldContainExactly listOf("5000ms", "1ms")
    }

    @Test
    fun `a replanning retry keeps the ORIGINAL deadline - a fresh transaction is not a fresh budget`() {
        var attempts = 0
        Replanning.run(guard) {
            attempts++
            inTransaction {
                guard.prerequisites(listOf(KEY_A), CODE)
                if (attempts == 1) {
                    now += millis(3_000)
                    throw KeySetChanged()
                }
            }
        }
        attempts shouldBe 2
        jdbc.grantedTimeouts() shouldContainExactly listOf("5000ms", "2000ms")
    }

    @Test
    fun `a changed-key retry whose budget expired refuses before its next acquisition statement - cause null, nothing issued`() {
        var attempts = 0
        val refusal =
            shouldThrow<DatapipelinesException> {
                Replanning.run(guard) {
                    attempts++
                    inTransaction {
                        guard.prerequisites(listOf(KEY_A, KEY_B), CODE)
                        now += TimeUnit.SECONDS.toNanos(BOUND_SECONDS.toLong())
                        throw KeySetChanged()
                    }
                }
            }
        attempts shouldBe 2
        refusal.code shouldBe CODE
        refusal.cause.shouldBeNull()
        refusal.details shouldBe mapOf("lock_wait_seconds" to BOUND_SECONDS)
        // The first attempt's two keys; the second attempt issued nothing at all.
        jdbc.lockStatements() shouldBe 2
    }

    @Test
    fun `a new transaction outside any replanning scope starts its own deadline`() {
        inTransaction { guard.prerequisites(listOf(KEY_A), CODE) }
        now += millis(4_000)
        inTransaction { guard.prerequisites(listOf(KEY_A), CODE) }
        jdbc.grantedTimeouts() shouldContainExactly listOf("5000ms", "5000ms")
    }

    @Test
    fun `a Postgres lock timeout refuses with the 55P03 exception kept as the cause - shape 1`() {
        val timeout = UncategorizedSQLException("lock", "SELECT", SQLException("canceling statement due to lock timeout", "55P03"))
        jdbc.failLockWith = timeout
        val refusal = shouldThrow<DatapipelinesException> { inTransaction { guard.workspaces(listOf(WS_1), CODE) } }
        refusal.code shouldBe CODE
        refusal.details shouldBe mapOf("lock_wait_seconds" to BOUND_SECONDS)
        refusal.cause shouldBeSameInstanceAs timeout
    }

    @Test
    fun `a release family keeps its own busy code through the shared deadline`() {
        jdbc.failLockWith = UncategorizedSQLException("lock", "SELECT", SQLException("timeout", "55P03"))
        val refusal =
            shouldThrow<DatapipelinesException> { inTransaction { guard.acquire(WS_1, ReleaseFamily.TEMPLATE) } }
        refusal.code shouldBe PipelineErrorCodes.Template.RELEASE_BUSY
        refusal.message shouldBe WriterBusy.MESSAGE
    }

    @Test
    fun `any other database failure propagates untouched`() {
        val other = UncategorizedSQLException("lock", "SELECT", SQLException("connection reset", "08006"))
        jdbc.failLockWith = other
        val thrown = shouldThrow<UncategorizedSQLException> { inTransaction { guard.workspaces(listOf(WS_1), CODE) } }
        thrown shouldBeSameInstanceAs other
    }

    @Test
    fun `each statement's lock_timeout is restored to the previous value after it`() {
        inTransaction { guard.workspaces(listOf(WS_1), CODE) }
        jdbc.statements shouldContainExactly
            listOf(
                "SHOW",
                "SET 5000ms",
                "LOCK workspace $WS_1",
                "SET $SERVER_LOCK_TIMEOUT",
            )
    }

    @Test
    fun `the lock classes are taken in order - an earlier class after a later one throws`() {
        shouldThrow<IllegalStateException> {
            inTransaction {
                guard.workspaces(listOf(WS_1), CODE)
                guard.prerequisites(listOf(KEY_A), CODE)
            }
        }.message shouldContain "Lock order violated"
        shouldThrow<IllegalStateException> {
            inTransaction {
                guard.endpointRegistry(CODE)
                guard.workspaces(listOf(WS_1), CODE)
            }
        }.message shouldContain "Lock order violated"
    }

    @Test
    fun `within a class keys are sorted - a smaller key after a larger one throws, one batch is sorted for the caller`() {
        inTransaction { guard.prerequisites(listOf(KEY_B, KEY_A), CODE) }
        jdbc.lockedPrerequisites() shouldContainExactly
            listOf(JdbcReleaseMutationGuard.nameHash(KEY_A.name), JdbcReleaseMutationGuard.nameHash(KEY_B.name))
        shouldThrow<IllegalStateException> {
            inTransaction {
                guard.workspaces(listOf(WS_2), CODE)
                guard.workspaces(listOf(WS_1), CODE)
            }
        }.message shouldContain "Lock order violated"
    }

    @Test
    fun `re-acquiring what the transaction already holds issues no statement`() {
        inTransaction {
            guard.prerequisites(listOf(KEY_A), CODE)
            guard.workspaces(listOf(WS_1), CODE)
            guard.endpointRegistry(CODE)
            guard.prerequisites(listOf(KEY_A), CODE)
            guard.workspaces(listOf(WS_1), CODE)
            guard.acquire(WS_1, ReleaseFamily.PIPELINE)
            guard.endpointRegistry(CODE)
        }
        jdbc.lockStatements() shouldBe 3
    }

    @Test
    fun `the prerequisite key is the two-int advisory form in its own lock space - never the registry's one-bigint key`() {
        inTransaction {
            guard.prerequisites(listOf(KEY_A), CODE)
            guard.endpointRegistry(CODE)
        }
        jdbc.statements shouldContain
            "LOCK prerequisite ${PrerequisiteNamespace.DATASOURCE.lockSpace}:${JdbcReleaseMutationGuard.nameHash(KEY_A.name)}"
        jdbc.statements shouldContain "LOCK registry ${EndpointRegistryLock.KEY}"
    }

    @Test
    fun `a guard call outside a transaction is a wiring defect and throws`() {
        shouldThrow<IllegalStateException> { guard.workspaces(listOf(WS_1), CODE) }.message shouldContain "inside a metadata transaction"
        jdbc.statements.size shouldBe 0
    }

    /**
     * Answers the guard's statements without a database: `SHOW` returns [SERVER_LOCK_TIMEOUT], `set_config` records the
     * granted value, a lock statement records its target and advances the test clock by [waitOnLock] (the time it
     * "waited") or throws [failLockWith].
     */
    private inner class RecordingJdbc : JdbcTemplate() {
        val statements = mutableListOf<String>()
        var waitOnLock = 0L
        var failLockWith: RuntimeException? = null

        fun grantedTimeouts(): List<String> =
            statements.filter { it.startsWith("SET ") && it != "SET $SERVER_LOCK_TIMEOUT" }.map { it.removePrefix("SET ") }

        fun lockStatements(): Int = statements.count { it.startsWith("LOCK ") }

        fun lockedPrerequisites(): List<Int> =
            statements.filter { it.startsWith("LOCK prerequisite ") }.map { it.substringAfterLast(':').toInt() }

        override fun queryForList(
            sql: String,
            vararg args: Any?,
        ): MutableList<MutableMap<String, Any>> {
            statements +=
                when {
                    sql.contains("FROM workspaces") -> "LOCK workspace ${args[0]}"
                    sql.contains("pg_advisory_xact_lock(?, ?)") -> "LOCK prerequisite ${args[0]}:${args[1]}"
                    sql.contains("pg_advisory_xact_lock(?)") -> "LOCK registry ${args[0]}"
                    else -> error("unexpected statement: $sql")
                }
            failLockWith?.let { throw it }
            now += waitOnLock
            return mutableListOf(mutableMapOf("id" to (args[0] ?: "")))
        }

        override fun <T : Any?> queryForObject(
            sql: String,
            requiredType: Class<T>,
        ): T? {
            check(sql.contains("current_setting('lock_timeout')")) { "unexpected statement: $sql" }
            statements += "SHOW"
            return requiredType.cast(SERVER_LOCK_TIMEOUT)
        }

        override fun <T : Any?> queryForObject(
            sql: String,
            requiredType: Class<T>,
            vararg args: Any?,
        ): T? {
            check(sql.contains("set_config('lock_timeout', ?, true)")) { "unexpected statement: $sql" }
            statements += "SET ${args[0]}"
            return requiredType.cast(args[0])
        }
    }

    private companion object {
        const val BOUND_SECONDS = 5
        const val T0 = 1_000_000_000L
        const val CODE = "datasource.write.busy"
        const val SERVER_LOCK_TIMEOUT = "0"

        /** SHOW, SET, the lock statement and the restoring SET. */
        const val STATEMENTS_PER_ACQUISITION = 4
        val KEY_A = PrerequisiteKey.datasource("alpha-db")
        val KEY_B = PrerequisiteKey.datasource("beta-db")
        val WS_1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val WS_2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

        fun millis(value: Long): Long = TimeUnit.MILLISECONDS.toNanos(value)
    }
}
