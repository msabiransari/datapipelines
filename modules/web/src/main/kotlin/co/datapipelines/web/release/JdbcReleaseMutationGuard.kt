package co.datapipelines.web.release

import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseMutationGuard
import co.datapipelines.pipeline.release.ReleaseRefusals
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.guard.EndpointRegistryLock
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import co.datapipelines.typesystem.guard.PrerequisiteKey
import co.datapipelines.typesystem.guard.WriterBusy
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.SQLException
import java.util.SortedSet
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The metadata writer guard (release-dependencies record §2, §4.3; dependency-graph record §15.1, #841 G0): ONE
 * implementation of both faces — the release families' [ReleaseMutationGuard] and the family-neutral
 * [MetadataWriterGuard] — so every participating writer shares one lock order, one per-transaction bookkeeping and one
 * cumulative deadline.
 *
 * **Lock classes, in order.** (1) prerequisite advisory keys: `pg_advisory_xact_lock(int4, int4)`, the namespace's own
 * lock space plus a 32-bit SHA-256 prefix of the canonical name — a collision over-serialises, never weakens exclusion,
 * and the two-key form never meets the endpoint registry's one-bigint key; (2) the EXISTING `workspaces` row
 * `FOR NO KEY UPDATE` — no migration, cross-process because it is the database's, and it conflicts only with itself
 * and `FOR UPDATE`, never with the `FOR KEY SHARE` a child row's foreign-key check takes; (3) the endpoint registry's
 * one deployment-wide key ([EndpointRegistryLock.KEY]). Each class is acquired sorted; an earlier class (or a smaller
 * key of the same class) after a later one in the same transaction throws [IllegalStateException]. Everything is
 * transaction-scoped: commit, rollback or a cancelled connection releases it; nothing is ever held on the pooled session.
 *
 * **One cumulative deadline.** The first acquisition of a transaction (or of a [withinOneDeadline] scope spanning
 * replanning retries) fixes `D = t0 + lockWaitSeconds` on the injected monotonic [nanoTime]. Every later statement reads
 * the clock and is granted `D − now` as its statement-local `lock_timeout` (bound as a parameter through `set_config`,
 * then restored, so the transaction's other waits keep the server's setting); at `now ≥ D` it refuses without issuing
 * the statement. The invariant is elapsed time against `D`, never a sum of the limits granted.
 *
 * **Refusal.** A statement that timed out is recognised by SQLSTATE `55P03` on the cause chain — never by Spring's
 * exception type, which the shipped translator answers as `UncategorizedSQLException` — and refuses with that cause
 * kept; a passed deadline refuses with no cause. Both carry the caller's code, the release message and exactly
 * `{lock_wait_seconds}` ([WriterBusy]); the caller's transaction rolls back with the throw.
 *
 * A call outside a transaction is a wiring defect (the lock would be released at once) and throws; so does a workspace
 * row that does not exist.
 */
class JdbcReleaseMutationGuard(
    private val jdbc: JdbcTemplate,
    private val lockWaitSeconds: Int,
    /** The monotonic clock the deadline is measured on; injected so the deadline arithmetic is testable exactly. */
    private val nanoTime: () -> Long = System::nanoTime,
) : ReleaseMutationGuard,
    MetadataWriterGuard {
    init {
        require(lockWaitSeconds > 0) { "lockWaitSeconds must be positive, was $lockWaitSeconds" }
    }

    private val boundNanos = TimeUnit.SECONDS.toNanos(lockWaitSeconds.toLong())

    /** The open [withinOneDeadline] scope of this thread, if any: its deadline outlives each attempt's transaction. */
    private val scope = ThreadLocal<DeadlineScope?>()

    override fun acquire(
        workspaceId: UUID,
        family: ReleaseFamily,
    ) = lockWorkspaces(listOf(workspaceId)) { cause -> ReleaseRefusals.busy(family, lockWaitSeconds, cause) }

    override fun workspaces(
        workspaceIds: Collection<UUID>,
        busyCode: String,
    ) = lockWorkspaces(workspaceIds) { cause -> WriterBusy.refusal(busyCode, lockWaitSeconds, cause) }

    override fun prerequisites(
        keys: Collection<PrerequisiteKey>,
        busyCode: String,
    ) {
        val state = state()
        val wanted = keys.toSortedSet() - state.prerequisites
        if (wanted.isEmpty()) return
        state.enter(CLASS_PREREQUISITE, "prerequisite keys")
        state.requireAfter(state.prerequisites, wanted.first(), "prerequisite key")
        for (key in wanted) {
            bounded(state, { WriterBusy.refusal(busyCode, lockWaitSeconds, it) }) {
                jdbc.queryForList(LOCK_PREREQUISITE, key.namespace.lockSpace, nameHash(key.name))
            }
            state.prerequisites += key
        }
    }

    override fun endpointRegistry(busyCode: String) {
        val state = state()
        if (state.registry) return
        state.enter(CLASS_REGISTRY, "the endpoint registry key")
        bounded(state, { WriterBusy.refusal(busyCode, lockWaitSeconds, it) }) {
            jdbc.queryForList(LOCK_REGISTRY, EndpointRegistryLock.KEY)
        }
        state.registry = true
    }

    override fun <T> withinOneDeadline(block: () -> T): T {
        if (scope.get() != null) return block()
        scope.set(DeadlineScope())
        try {
            return block()
        } finally {
            scope.remove()
        }
    }

    private fun lockWorkspaces(
        workspaceIds: Collection<UUID>,
        refusal: (Throwable?) -> DatapipelinesException,
    ) {
        val state = state()
        val wanted = workspaceIds.toSortedSet() - state.workspaces
        if (wanted.isEmpty()) return
        state.enter(CLASS_WORKSPACE, "workspace rows")
        state.requireAfter(state.workspaces, wanted.first(), "workspace row")
        for (workspaceId in wanted) {
            val locked = bounded(state, refusal) { jdbc.queryForList(LOCK_WORKSPACE, workspaceId) }
            check(locked.size == 1) { "Workspace $workspaceId has no row to guard." }
            state.workspaces += workspaceId
        }
    }

    /**
     * One acquisition statement under the cumulative deadline: refuse without a statement when it has passed, else
     * grant the remainder as the statement's `lock_timeout`, map `55P03` to [refusal], and restore the setting.
     */
    private fun <R> bounded(
        state: TxState,
        refusal: (Throwable?) -> DatapipelinesException,
        statement: () -> R,
    ): R {
        val deadline = deadlineFor(state)
        val remaining = deadline - nanoTime()
        if (remaining <= 0) throw refusal(null)
        val previous = jdbc.queryForObject(SHOW_LOCK_TIMEOUT, String::class.java)
        // At least 1 ms: Postgres reads `0` as "wait forever", the opposite of a nearly spent budget.
        val grantMillis = maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining + NANOS_PER_MILLI - 1))
        jdbc.queryForObject(SET_LOCK_TIMEOUT, String::class.java, "${grantMillis}ms")
        val result =
            try {
                statement()
            } catch (e: DataAccessException) {
                throw lockTimeoutRefusal(e, refusal) ?: e
            }
        jdbc.queryForObject(SET_LOCK_TIMEOUT, String::class.java, previous)
        return result
    }

    /** [refusal] carrying [e] when [e] is a lock timeout (`55P03` on its cause chain), else null: any other failure propagates. */
    private fun lockTimeoutRefusal(
        e: DataAccessException,
        refusal: (Throwable?) -> DatapipelinesException,
    ): DatapipelinesException? = if (sqlStates(e).any { it == LOCK_NOT_AVAILABLE }) refusal(e) else null

    /** `D`, fixed once: by the open scope when there is one, else by this transaction's first acquisition. */
    private fun deadlineFor(state: TxState): Long {
        val open = scope.get()
        val deadline = state.deadline ?: open?.deadline ?: (nanoTime() + boundNanos)
        state.deadline = deadline
        open?.deadline = deadline
        return deadline
    }

    private fun state(): TxState {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "The workspace writer guard must be acquired inside a metadata transaction."
        }
        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "The workspace writer guard needs transaction synchronization to track its lock order."
        }
        // A synchronization, not a bound resource: synchronizations are suspended with their transaction, so a
        // REQUIRES_NEW inner transaction starts clean and the outer one's bookkeeping resumes with it.
        return TransactionSynchronizationManager
            .getSynchronizations()
            .filterIsInstance<TxState>()
            .firstOrNull { it.owner === this }
            ?: TxState(this).also(TransactionSynchronizationManager::registerSynchronization)
    }

    private fun sqlStates(e: Throwable): Sequence<String> =
        generateSequence(e) { it.cause.takeUnless { cause -> cause === it } }.filterIsInstance<SQLException>().mapNotNull { it.sqlState }

    /** A replanning scope's deadline: unset until the scope's first acquisition. */
    private class DeadlineScope {
        var deadline: Long? = null
    }

    /** What one transaction holds, and since when its deadline runs. Dies with the transaction. */
    private class TxState(
        val owner: JdbcReleaseMutationGuard,
    ) : TransactionSynchronization {
        var highestClass = 0
        val prerequisites: SortedSet<PrerequisiteKey> = sortedSetOf()
        val workspaces: SortedSet<UUID> = sortedSetOf()
        var registry = false
        var deadline: Long? = null

        fun enter(
            lockClass: Int,
            what: String,
        ) {
            check(lockClass >= highestClass) {
                "Lock order violated: $what requested after a later lock class was acquired in this transaction."
            }
            highestClass = lockClass
        }

        fun <K : Comparable<K>> requireAfter(
            held: SortedSet<K>,
            first: K,
            what: String,
        ) {
            check(held.isEmpty() || first > held.last()) {
                "Lock order violated: a $what sorting before one already held was requested in this transaction."
            }
        }
    }

    companion object {
        /** Postgres `lock_not_available`: the statement waited `lock_timeout` for the lock and gave up. */
        private const val LOCK_NOT_AVAILABLE = "55P03"
        private const val CLASS_PREREQUISITE = 1
        private const val CLASS_WORKSPACE = 2
        private const val CLASS_REGISTRY = 3
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val SHOW_LOCK_TIMEOUT = "SELECT current_setting('lock_timeout')"
        private const val LOCK_WORKSPACE = "SELECT id FROM workspaces WHERE id = ? FOR NO KEY UPDATE"
        private const val LOCK_PREREQUISITE = "SELECT pg_advisory_xact_lock(?, ?)::text AS held"
        private const val LOCK_REGISTRY = "SELECT pg_advisory_xact_lock(?)::text AS held"

        /** `set_config(..., is_local => true)` is `SET LOCAL` with a bind parameter. */
        private const val SET_LOCK_TIMEOUT = "SELECT set_config('lock_timeout', ?, true)"

        /** The canonical name's advisory sub-key: the first four bytes of its SHA-256, stable across JVMs and servers. */
        fun nameHash(canonicalName: String): Int =
            ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(canonicalName.toByteArray(Charsets.UTF_8))).int
    }
}
