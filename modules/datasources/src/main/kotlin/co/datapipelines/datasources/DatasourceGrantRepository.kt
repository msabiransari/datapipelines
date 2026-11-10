package co.datapipelines.datasources

import co.datapipelines.typesystem.guard.KeySetChanged
import co.datapipelines.typesystem.guard.PrerequisiteKey
import co.datapipelines.typesystem.guard.Replanning
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.TransactionTimedOutException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One row of `datasource_workspaces` (metadata-db §4.16): a datasource is visible to a
 * workspace because somebody granted it (D-R7). Every grant records who and when — the
 * design says "every grant is audited", and the row itself is half of that record.
 */
data class DatasourceGrant(
    val datasourceName: String,
    val workspaceId: UUID,
    val workspaceName: String,
    val grantedBy: UUID,
    val grantedAt: java.time.Instant,
)

/**
 * `datasource_workspaces` persistence (metadata-db §4.16) — the table that REPLACED
 * `datasources.workspace_id` and with it the concept of a "global" datasource (D-R7).
 *
 * Visibility is the grant and nothing else. `DatasourceRepository`'s reads carry the
 * predicate in their SQL (`GRANTED_PREDICATE`), so this repository exists for the
 * MANAGEMENT half: listing a datasource's grants, adding one, removing one.
 *
 * An ordinary datasource is made visible only by a person's decision: an explicit grant, or the
 * grant a registration implies — a workspace admin who registers a datasource bound to their own
 * workspace gets its grant ([grantOnRegistration]), because registering a datasource you then
 * cannot see would be a bug wearing a rule's clothes. The `demo` seeder's
 * [grantAllInstanceDatasourcesTo] is D-R7's one narrow exception, for the workspace the product ships.
 *
 * The server-managed lake (#818, #823; dp-lake record D3: the lake is server-level) is the other
 * exception: the server, not a person, holds its grants, as its system actor. Boot and the freshness
 * tick grant it to every workspace ([grantToEveryWorkspace]); `WorkspaceService.create` grants it
 * to a new workspace inside the creation transaction (through [grant]); and the REST and UI revoke
 * handlers refuse to remove one with [DatasourceErrorCodes.MANAGED]. That refusal lives in those
 * handlers, not here: this repository does not know which datasource is managed, and [revoke]
 * deletes whatever grant it is asked to.
 */
class DatasourceGrantRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    /**
     * #841 (G0, dependency-graph record §15.1) — every grant write takes the datasource's prerequisite key, then the
     * workspace(s) it changes, in one metadata transaction (joined when the caller has one, as
     * `WorkspaceService.create`'s grant-at-creation does). [DatasourceWriteGuarding.UNGUARDED] is the unit-test default.
     */
    val writes: DatasourceWriteGuarding = DatasourceWriteGuarding.UNGUARDED,
    /**
     * #841 R4 — the freshness tick's repository only (null everywhere else): ONE wall-clock budget for a whole guarded
     * [grantToEveryWorkspace] — its lock waits, its discovery read and its INSERT together — so the repair holds the
     * jobs' shared `dp-scheduled` thread at most one statement bound, as it did before it took the writer guard.
     */
    private val repairBudget: Duration? = null,
    /** The monotonic clock [repairBudget] is measured on. */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Every workspace [datasourceName] is granted to, name order. */
    fun grantsOf(datasourceName: String): List<DatasourceGrant> =
        jdbc.query(
            """
            SELECT g.datasource_name, g.workspace_id, w.name AS workspace_name, g.granted_by, g.granted_at
              FROM datasource_workspaces g
              JOIN workspaces w ON w.id = g.workspace_id
             WHERE g.datasource_name = :name
             ORDER BY w.name
            """.trimIndent(),
            MapSqlParameterSource("name", datasourceName),
            MAPPER,
        )

    /** True when [datasourceName] is granted to [workspaceId] — the visibility question, asked directly. */
    fun isGranted(
        datasourceName: String,
        workspaceId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM datasource_workspaces WHERE datasource_name = :name AND workspace_id = :ws
            )
            """.trimIndent(),
            MapSqlParameterSource().addValue("name", datasourceName).addValue("ws", workspaceId),
            Boolean::class.java,
        ) == true

    /**
     * Grants [datasourceName] to [workspaceId]. Idempotent: re-granting is success and keeps
     * the ORIGINAL `granted_by`/`granted_at`, because the first grant is the decision and a
     * later no-op is not a new one — rewriting the actor would erase who actually decided.
     */
    fun grant(
        datasourceName: String,
        workspaceId: UUID,
        grantedBy: UUID,
    ): Boolean =
        writes.grant(datasourceName, { listOf(workspaceId) }) {
            jdbc.update(
                """
                INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by)
                VALUES (:name, :ws, :by)
                ON CONFLICT (datasource_name, workspace_id) DO NOTHING
                """.trimIndent(),
                MapSqlParameterSource().addValue("name", datasourceName).addValue("ws", workspaceId).addValue("by", grantedBy),
            ) > 0
        }

    /**
     * The grant a registration implies (RBAC design §4): a workspace admin who registers a
     * datasource bound to their own workspace gets the grant in the same breath.
     *
     * Tolerates the duplicate rather than pre-checking: registration and grant are two
     * statements, and a racing second registration of the same name has already failed on the
     * datasource's own primary key by the time this runs.
     */
    fun grantOnRegistration(
        datasourceName: String,
        workspaceId: UUID,
        grantedBy: UUID,
    ) {
        try {
            grant(datasourceName, workspaceId, grantedBy)
        } catch (_: DuplicateKeyException) {
            // Already granted — the outcome this method exists to produce.
        }
    }

    /**
     * Grants every INSTANCE datasource — one no workspace owns — to [workspaceId]. Returns how
     * many grants were created; existing ones are left with their original actor.
     *
     * Its only caller is the `demo` seeder (see `InstanceDatasourceGrants` for why that is the
     * one workspace this is right for).
     *
     * #841 (G0): the set of instance datasources is DISCOVERED, so it is the writer protocol's replanning case — read
     * the names, take their prerequisite keys (the guard orders them by physical lock), re-read them under the keys;
     * a different set (a datasource registered or deleted in between) rolls the transaction back and replans inside
     * the ORIGINAL cumulative deadline ([Replanning]); an unchanged set takes the workspace row and grants exactly the
     * locked names.
     */
    fun grantAllInstanceDatasourcesTo(
        workspaceId: UUID,
        grantedBy: UUID,
    ): Int =
        Replanning.run(writes.guard) {
            val planned = instanceDatasourceNames()
            if (planned.isEmpty()) return@run 0
            writes.inTransaction {
                writes.guard.prerequisites(planned.map(PrerequisiteKey::datasource), DatasourceErrorCodes.WRITE_BUSY)
                if (writes.isGuarded && instanceDatasourceNames() != planned) throw KeySetChanged()
                writes.guard.workspaces(listOf(workspaceId), DatasourceErrorCodes.WRITE_BUSY)
                jdbc.update(
                    """
                    INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by)
                    SELECT d.name, :ws, :by
                      FROM datasources d
                     WHERE d.owner_workspace_id IS NULL AND d.is_deleted = FALSE AND d.name IN (:names)
                    ON CONFLICT (datasource_name, workspace_id) DO NOTHING
                    """.trimIndent(),
                    MapSqlParameterSource().addValue("ws", workspaceId).addValue("by", grantedBy).addValue("names", planned),
                )
            }
        }

    /** The live instance (unowned) datasources' names, sorted — [grantAllInstanceDatasourcesTo]'s key discovery. */
    private fun instanceDatasourceNames(): List<String> =
        jdbc.queryForList(
            "SELECT name FROM datasources WHERE owner_workspace_id IS NULL AND is_deleted = FALSE ORDER BY name COLLATE \"C\"",
            MapSqlParameterSource(),
            String::class.java,
        )

    /**
     * #818 (dp-lake record D3: the lake is server-level) — grants [datasourceName] to every workspace
     * that is not deleted. Returns how many grants were created; existing ones keep their original
     * actor. ONE statement, so a workspace created between a read and a loop cannot be missed.
     *
     * Its only caller is the managed lake's registrar, at boot and on every freshness tick. It is not
     * the only path that grants the managed lake: since #823 `WorkspaceService.create` grants it to
     * the new workspace atomically, in the creation transaction (the registrar's `grantTo`, through
     * [grant]), so while the lake is enabled a workspace created there never exists without it. The
     * tick's pass through here is the REPAIR path, for a workspace that lacks the grant: one not
     * created through `WorkspaceService.create` (for example, one inserted by SQL), one that
     * predates the lake, or one whose grant row was deleted by hand. The `demo` seeder has its own
     * grant path: [grantAllInstanceDatasourcesTo] includes an already-registered managed lake,
     * because that datasource is active and has no owning workspace.
     *
     * #841 (G0): under the datasource's prerequisite key, the live workspaces that LACK the grant — exactly the rows
     * this insert changes, and a set no grant writer can alter while the key is held — are read and locked (sorted),
     * and only those are granted. A pass with nothing to repair therefore locks no workspace row; a workspace created
     * after the read receives the grant in its own creation transaction (which takes the same key) or on the next tick.
     *
     * #841 R4 — with a [repairBudget] (the freshness tick's repository), the guarded repair runs in phases that share
     * ONE deadline, `start + repairBudget`: (1) the transaction's first statement, on this repository's template, reads
     * the server's `statement_timeout` — so the connection carries the template's socket bound before the guard's own
     * statements run on it; (2) the guard takes the datasource's key, its `lock_timeout` capped at the same deadline
     * ([co.datapipelines.typesystem.guard.MetadataWriterGuard.withinOneDeadline] with a ceiling); (3) the discovery
     * read, its `statement_timeout` the time left; (4) the workspace rows it found, on the guard's capped
     * `lock_timeout`; (5) the INSERT, its `statement_timeout` the time left. A repair whose key wait spent part of
     * the budget therefore has only the rest for its INSERT: a peer waits at the key and refuses busy (`55P03`)
     * inside the budget, an INSERT queued behind a table lock is cancelled (`57014`) at the deadline, and either way
     * the transaction rolls back, nothing is written and the next tick retries. A budget spent before a statement
     * refuses with [TransactionTimedOutException] and issues none.
     */
    fun grantToEveryWorkspace(
        datasourceName: String,
        grantedBy: UUID,
    ): Int {
        val budget = repairBudget?.takeIf { writes.isGuarded } ?: return repair(datasourceName, grantedBy, deadline = null)
        val deadline = RepairDeadline(nanoTime() + budget.toNanos())
        return writes.guard.withinOneDeadline(Duration.ofNanos(maxOf(1L, deadline.at - nanoTime()))) {
            writes.inTransaction {
                // The transaction's first statement, on this repository's template: the connection now carries the
                // template's socket bound for every later statement of the transaction, the guard's included.
                deadline.serverSetting = currentStatementTimeout()
                repair(datasourceName, grantedBy, deadline)
            }
        }
    }

    /** [grantToEveryWorkspace]'s guarded write; with a [deadline], each of this repository's statements is capped at it. */
    private fun repair(
        datasourceName: String,
        grantedBy: UUID,
        deadline: RepairDeadline?,
    ): Int {
        var locked: List<UUID> = emptyList()
        val discover = { within(deadline) { ungrantedWorkspaceIds(datasourceName).also { locked = it } } }
        return writes.grant(datasourceName, discover) {
            val params = MapSqlParameterSource().addValue("name", datasourceName).addValue("by", grantedBy)
            within(deadline) {
                jdbc.update(
                    """
                    INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by)
                    SELECT :name, w.id, :by
                      FROM workspaces w
                     WHERE w.is_deleted = FALSE
                    ${if (writes.isGuarded) "AND w.id IN (:locked)" else ""}
                    ON CONFLICT (datasource_name, workspace_id) DO NOTHING
                    """.trimIndent(),
                    if (writes.isGuarded) params.addValue("locked", locked.ifEmpty { listOf(NO_WORKSPACE) }) else params,
                )
            }
        }
    }

    /**
     * Runs [block] — one of this repository's own statements — with the transaction's `statement_timeout` set to what is
     * left before [deadline] (whole milliseconds, rounded down, at least one: Postgres reads `0` as "no limit"), then
     * restores the server's setting, so the guard's lock statements between them keep theirs and end on their own
     * `lock_timeout` (`55P03`, the native busy refusal). Once nothing is left it refuses without a statement. Without a
     * deadline (boot, REST, the unguarded unit-test shape) it is just [block].
     */
    private fun <T> within(
        deadline: RepairDeadline?,
        block: () -> T,
    ): T {
        if (deadline == null) return block()
        val remaining = deadline.at - nanoTime()
        if (remaining <= 0) throw TransactionTimedOutException("the repair's budget was spent before its next statement")
        setStatementTimeout("${maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))}ms")
        val result = block()
        setStatementTimeout(deadline.serverSetting)
        return result
    }

    private fun currentStatementTimeout(): String =
        checkNotNull(
            jdbc.queryForObject("SELECT current_setting('statement_timeout')", MapSqlParameterSource(), String::class.java),
        ) { "current_setting answered null" }

    /** `set_config(..., is_local => true)`: `SET LOCAL` with a bind parameter; it ends with the transaction. */
    private fun setStatementTimeout(value: String) {
        jdbc.queryForObject(
            "SELECT set_config('statement_timeout', :timeout, true)",
            MapSqlParameterSource("timeout", value),
            String::class.java,
        )
    }

    /** One budgeted repair's deadline on [nanoTime], and the server's `statement_timeout` its statements restore. */
    private class RepairDeadline(
        val at: Long,
    ) {
        var serverSetting: String = "0"
    }

    private fun ungrantedWorkspaceIds(datasourceName: String): List<UUID> =
        jdbc.queryForList(
            """
            SELECT w.id FROM workspaces w
             WHERE w.is_deleted = FALSE
               AND NOT EXISTS (SELECT 1 FROM datasource_workspaces g WHERE g.datasource_name = :name AND g.workspace_id = w.id)
            """.trimIndent(),
            MapSqlParameterSource("name", datasourceName),
            UUID::class.java,
        )

    /**
     * Revokes the grant; false when there was none. Nothing about the datasource itself changes.
     * It refuses nothing: the managed lake's grant is protected by the REST and UI revoke handlers
     * ([DatasourceErrorCodes.MANAGED], #823), so a new caller that can reach a managed grant needs
     * the same check.
     */
    fun revoke(
        datasourceName: String,
        workspaceId: UUID,
    ): Boolean =
        writes.grant(datasourceName, { listOf(workspaceId) }) {
            jdbc.update(
                "DELETE FROM datasource_workspaces WHERE datasource_name = :name AND workspace_id = :ws",
                MapSqlParameterSource().addValue("name", datasourceName).addValue("ws", workspaceId),
            ) > 0
        }

    private companion object {
        /** A uuid no workspace holds: `IN ()` is not SQL, and an empty locked set must grant nothing. */
        val NO_WORKSPACE: UUID = UUID(0L, 0L)

        val MAPPER =
            RowMapper { rs, _ ->
                DatasourceGrant(
                    datasourceName = rs.getString("datasource_name"),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    workspaceName = rs.getString("workspace_name"),
                    grantedBy = rs.getObject("granted_by", UUID::class.java),
                    grantedAt = rs.getObject("granted_at", OffsetDateTime::class.java).toInstant(),
                )
            }
    }
}
