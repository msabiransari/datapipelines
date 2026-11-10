package co.datapipelines.datasources

import co.datapipelines.typesystem.guard.MetadataWriterGuard
import co.datapipelines.typesystem.guard.PrerequisiteKey
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * The datasource writers' side of the metadata writer protocol (dependency-graph record §15.1, #841 G0). A datasource
 * is shared across workspaces, so ownership never picks one workspace lock: every definition, credential, delete and
 * grant write takes, inside ONE metadata transaction (joined when the caller already has one),
 *
 * 1. the datasource's canonical prerequisite key — its primary-key name, so no alias evades it and a name that does
 *    not exist yet is protected too (a create races nothing it cannot see);
 * 2. then every AFFECTED workspace row, sorted — read UNDER the key, so the set cannot change before it is locked
 *    (every grant writer takes the same key first);
 *
 * then writes. An exhausted cumulative wait refuses [DatasourceErrorCodes.WRITE_BUSY] (409) with nothing written; a
 * boot or repair caller fails closed on the same exception at its own boundary. Last-test observations
 * (`recordTestOutcome`) deliberately stay outside: they are not consent facts.
 *
 * [UNGUARDED] (no transaction, no lock) is the directly constructed unit-test shape; production wires the JDBC guard
 * and the metadata transaction manager (`DomainConfiguration`).
 */
class DatasourceWriteGuarding(
    private val jdbc: NamedParameterJdbcTemplate?,
    private val transactions: TransactionOperations?,
    val guard: MetadataWriterGuard,
) {
    /** Whether this instance actually locks — false only for [UNGUARDED]. */
    val isGuarded: Boolean get() = transactions != null

    /**
     * A definition write of [name] (create, update, credential, delete): the key, then the workspaces it is granted to,
     * its stored owner and any [extraWorkspaces] (a create's or a rebinding update's new owner), then [block].
     */
    fun <T> definition(
        name: String,
        extraWorkspaces: Collection<UUID?> = emptyList(),
        block: () -> T,
    ): T =
        guarded(name, { affectedBy(name) + extraWorkspaces.filterNotNull() }, block)

    /** A grant write of [name]: the key, then the workspaces [workspaces] answers (read under the key), then [block]. */
    fun <T> grant(
        name: String,
        workspaces: () -> Collection<UUID>,
        block: () -> T,
    ): T = guarded(name, workspaces, block)

    /** Several keys at once, sorted by the guard; the caller re-resolves under them ([co.datapipelines.typesystem.guard.Replanning]). */
    fun <T> inTransaction(block: () -> T): T {
        val tx = transactions ?: return block()
        var result: Result<T>? = null
        tx.executeWithoutResult { result = Result.success(block()) }
        return requireNotNull(result) { "the transaction callback did not run" }.getOrThrow()
    }

    /**
     * Runs [action] once the surrounding transaction COMMITS (at once when there is none): pool retirement, cache
     * eviction and the peer broadcast must never announce a row a peer could still read in its old state.
     */
    fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private fun <T> guarded(
        name: String,
        workspaces: () -> Collection<UUID>,
        block: () -> T,
    ): T =
        inTransaction {
            guard.prerequisites(listOf(PrerequisiteKey.datasource(name)), DatasourceErrorCodes.WRITE_BUSY)
            guard.workspaces(if (isGuarded) workspaces() else emptyList(), DatasourceErrorCodes.WRITE_BUSY)
            block()
        }

    /** The workspaces a definition change of [name] is visible in: its grants and its stored owner. */
    private fun affectedBy(name: String): Set<UUID> {
        val template = jdbc ?: return emptySet()
        return template
            .query(
                """
                SELECT workspace_id FROM datasource_workspaces WHERE datasource_name = :name
                UNION
                SELECT owner_workspace_id FROM datasources WHERE name = :name AND owner_workspace_id IS NOT NULL
                """.trimIndent(),
                MapSqlParameterSource("name", name),
            ) { rs, _ -> rs.getObject(1, UUID::class.java) }
            .toSet()
    }

    companion object {
        /** No transaction, no lock — the unit-test construction default. Production never wires it. */
        val UNGUARDED = DatasourceWriteGuarding(null, null, MetadataWriterGuard.NONE)
    }
}
