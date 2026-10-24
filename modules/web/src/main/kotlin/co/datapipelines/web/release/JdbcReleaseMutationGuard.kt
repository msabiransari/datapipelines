package co.datapipelines.web.release

import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseMutationGuard
import co.datapipelines.pipeline.release.ReleaseRefusals
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.util.UUID

/**
 * The workspace metadata writer guard (release-dependencies record §2, §4.3; brief choice 5): a row lock on the EXISTING
 * `workspaces` row, taken inside the caller's metadata transaction and held to its end — no migration, no lock table,
 * cross-process because it is the database's. `FOR NO KEY UPDATE` conflicts with itself and with `FOR UPDATE`, never
 * with the `FOR KEY SHARE` a foreign-key check on a child row takes, so ordinary inserts that merely reference the
 * workspace are not serialised by it; only guarded writers are.
 *
 * The wait is bounded by `SET LOCAL lock_timeout` for this statement alone: the previous value is read first and
 * restored after the lock is held, so the transaction's later row-lock waits keep the server's setting. The bound is
 * [lockWaitSeconds], a server-validated integer (`ReleaseDependencyProperties`), never request input; exhausting it
 * refuses `<family>.release.busy` with nothing written — the caller's transaction rolls back with the throw.
 *
 * The exhausted bound is recognised by its SQLSTATE, `55P03` (lock_not_available), read off the cause chain — never by
 * the Spring exception type: the shipped translator answers it as `UncategorizedSQLException` (measured on the shared
 * test Postgres, `WorkspaceMutationGuardIntegrationTest`), so a type match turned the refusal into a 500.
 *
 * A call outside a transaction is a wiring defect (the lock would be released at once) and throws; so does a workspace
 * row that does not exist, which every authenticated caller's workspace does.
 */
class JdbcReleaseMutationGuard(
    private val jdbc: JdbcTemplate,
    private val lockWaitSeconds: Int,
) : ReleaseMutationGuard {
    init {
        require(lockWaitSeconds > 0) { "lockWaitSeconds must be positive, was $lockWaitSeconds" }
    }

    override fun acquire(
        workspaceId: UUID,
        family: ReleaseFamily,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "The workspace writer guard must be acquired inside a metadata transaction."
        }
        val previous = jdbc.queryForObject(SHOW_LOCK_TIMEOUT, String::class.java)
        // The interpolated value is this class's validated Int, never a caller's string; SET takes no bind parameter.
        jdbc.execute("SET LOCAL lock_timeout = '${lockWaitSeconds}s'")
        val locked =
            try {
                jdbc.queryForList(LOCK_WORKSPACE, UUID::class.java, workspaceId)
            } catch (e: DataAccessException) {
                if (sqlStates(e).none { it == LOCK_NOT_AVAILABLE }) throw e
                throw ReleaseRefusals.busy(family, lockWaitSeconds, e)
            }
        check(locked.size == 1) { "Workspace $workspaceId has no row to guard." }
        jdbc.queryForObject(RESTORE_LOCK_TIMEOUT, String::class.java, previous)
    }

    private fun sqlStates(e: Throwable): Sequence<String> =
        generateSequence(e) { it.cause.takeUnless { cause -> cause === it } }.filterIsInstance<SQLException>().mapNotNull { it.sqlState }

    companion object {
        /** Postgres `lock_not_available`: the statement waited `lock_timeout` for the row and gave up. */
        private const val LOCK_NOT_AVAILABLE = "55P03"
        private const val SHOW_LOCK_TIMEOUT = "SELECT current_setting('lock_timeout')"
        private const val LOCK_WORKSPACE = "SELECT id FROM workspaces WHERE id = ? FOR NO KEY UPDATE"

        /** `set_config(..., is_local => true)` is `SET LOCAL` with a bind parameter. */
        private const val RESTORE_LOCK_TIMEOUT = "SELECT set_config('lock_timeout', ?, true)"
    }
}
