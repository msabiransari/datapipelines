package co.datapipelines.web.release

import co.datapipelines.typesystem.guard.EndpointRegistryLock
import co.datapipelines.typesystem.guard.PrerequisiteKey
import co.datapipelines.web.SharedPostgres
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * #841 (G0) — the two-connection apparatus of the writer-guard proofs (brief §C): a [LockHolder] is a raw connection
 * holding exactly one of the guard's lock classes in an open transaction (the stand-in for "a conflicting writer in
 * flight" — the property under test is the lock, not that writer's other work), and [LockObserver] reads
 * `pg_stat_activity` / `pg_locks` / `pg_blocking_pids` on a FRESH connection per read (inside a transaction the view is a
 * frozen snapshot). Synchronisation is the observed wait, never a sleep: the poll interval only spaces the reads.
 */
internal class LockHolder : AutoCloseable {
    val connection: Connection = open().apply { autoCommit = false }
    val pid: Int =
        connection.createStatement().use { s ->
            s.executeQuery("SELECT pg_backend_pid()").use { it.next().let { _ -> it.getInt(1) } }
        }

    /** The prerequisite key's two-int advisory lock, exactly as the guard issues it. */
    fun prerequisite(key: PrerequisiteKey): LockHolder =
        apply {
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)").use { ps ->
                ps.setInt(1, key.namespace.lockSpace)
                ps.setInt(2, JdbcReleaseMutationGuard.nameHash(key.name))
                ps.executeQuery().close()
            }
        }

    /** The workspace row, exactly as the guard (and a release in flight) holds it. */
    fun workspace(id: UUID): LockHolder =
        apply {
            connection.prepareStatement("SELECT id FROM workspaces WHERE id = ? FOR NO KEY UPDATE").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { check(it.next()) { "the workspace row to hold exists" } }
            }
        }

    /** The endpoint registry's one deployment-wide key. */
    fun registry(): LockHolder =
        apply {
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { ps ->
                ps.setLong(1, EndpointRegistryLock.KEY)
                ps.executeQuery().close()
            }
        }

    /** One statement inside the holder's transaction (a write the holder commits with its locks). */
    fun execute(
        sql: String,
        vararg args: Any?,
    ): LockHolder =
        apply {
            connection.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.execute()
            }
        }

    fun commit() = connection.commit()

    override fun close() {
        if (!connection.isClosed) {
            connection.rollback()
            connection.close()
        }
    }

    companion object {
        fun open(): Connection {
            val pg = SharedPostgres.postgres
            return DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        }
    }
}

/** One backend waiting on a lock another backend holds, as `pg_locks` names it. */
internal data class Waiter(
    val pid: Int,
    val blockers: List<Int>,
    val lockType: String,
    val classId: Long?,
    val objId: Long?,
    val objSubId: Int?,
    val query: String,
)

internal object LockObserver {
    private const val POLL_INTERVAL_MS = 20L

    /**
     * How long a probe waits for a writer to show up blocked. Generous and NAMED (brief §C (b)): it bounds only a red
     * run's duration — a writer that never waits fails the probe — and asserts nothing about when a refusal arrives.
     */
    const val WAIT_PROBE_SECONDS = 20L

    /** The completion allowance for a writer released by its holder: generous, named, independent of the poll cadence. */
    const val COMPLETION_ALLOWANCE_SECONDS = 30L

    /** Every backend waiting on a lock [holderPid] owns, read on a fresh connection. */
    fun waitersOn(holderPid: Int): List<Waiter> =
        LockHolder.open().use { c ->
            c
                .prepareStatement(
                    """
                    SELECT a.pid, pg_blocking_pids(a.pid) AS blockers, l.locktype, l.classid::bigint, l.objid::bigint,
                           l.objsubid, a.query
                      FROM pg_stat_activity a
                      JOIN pg_locks l ON l.pid = a.pid AND NOT l.granted
                     WHERE ? = ANY(pg_blocking_pids(a.pid))
                    """.trimIndent(),
                ).use { ps ->
                    ps.setInt(1, holderPid)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    Waiter(
                                        pid = rs.getInt(1),
                                        blockers = (rs.getArray(2).array as Array<*>).map { (it as Number).toInt() },
                                        lockType = rs.getString(3),
                                        classId = rs.getObject(4)?.let { (it as Number).toLong() },
                                        objId = rs.getObject(5)?.let { (it as Number).toLong() },
                                        objSubId = rs.getObject(6)?.let { (it as Number).toInt() },
                                        query = rs.getString(7),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    /** Polls until a backend waits on [holder], or [writer] answered first (it never waited — the guard is absent). */
    fun awaitWaiter(
        holder: LockHolder,
        writer: Future<*>,
    ): Waiter? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PROBE_SECONDS)
        while (!writer.isDone && System.nanoTime() < deadline) {
            waitersOn(holder.pid).firstOrNull()?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * How many backends hold [key]'s advisory lock (granted), read on a fresh connection — after a writer's transaction
     * ended this must be 0: a transaction-scoped lock is gone with it, a leaked session lock would not be.
     */
    fun holdersOf(key: PrerequisiteKey): Int =
        LockHolder.open().use { c ->
            c
                .prepareStatement(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted AND classid::bigint = ?" +
                        " AND objid::bigint = ? AND objsubid = 2",
                ).use { ps ->
                    ps.setLong(1, key.namespace.lockSpace.toLong())
                    ps.setLong(2, objIdOf(JdbcReleaseMutationGuard.nameHash(key.name)))
                    ps.executeQuery().use {
                        it.next()
                        it.getInt(1)
                    }
                }
        }

    /** The unsigned `pg_locks.objid` a signed advisory sub-key appears as. */
    fun objIdOf(subKey: Int): Long = subKey.toLong() and 0xFFFF_FFFFL
}

/**
 * The rows a writer-guard proof needs on the module's shared database, created with UNIQUE names and removed again
 * afterwards (the shared container's suites clean only what they touch; nothing here truncates).
 */
internal class GuardRows : AutoCloseable {
    val jdbc = JdbcTemplate(SharedPostgres.dataSource())
    val user: UUID = UUID.randomUUID()
    private val tag = user.toString().take(8)
    private val workspaceIds = mutableListOf<UUID>()
    private val datasourceNames = mutableListOf<String>()

    init {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (?, ?, 'G0', 'google', ?)",
            user,
            "g0-$user@example.com",
            "g0-$user",
        )
    }

    /** A new live workspace; ids are random, so a sorted pair is the test's to order. */
    fun workspace(): UUID =
        UUID.randomUUID().also { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (?, ?, 'G0', ?)",
                id,
                "g0-$tag-${workspaceIds.size}",
                user,
            )
            workspaceIds += id
        }

    /** A unique, valid canonical datasource name (`[a-z0-9_-]`) — registered only if the test inserts it. */
    fun datasourceName(label: String): String = "g0-$tag-$label".also { datasourceNames += it }

    /** An inserted instance (or [owner]-owned) datasource row, the way the registry stores one. */
    fun datasource(
        label: String,
        owner: UUID? = null,
    ): String =
        datasourceName(label).also { name ->
            jdbc.update(
                "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by, owner_workspace_id)" +
                    " VALUES (?, 'G0', 'POSTGRES', 'jdbc:postgresql://g0.invalid/db', 'none', ?, ?)",
                name,
                user,
                owner,
            )
        }

    fun grant(
        datasource: String,
        workspace: UUID,
    ) {
        jdbc.update(
            "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES (?, ?, ?)",
            datasource,
            workspace,
            user,
        )
    }

    fun grantees(datasource: String): Set<UUID> =
        jdbc.queryForList("SELECT workspace_id FROM datasource_workspaces WHERE datasource_name = ?", UUID::class.java, datasource).toSet()

    override fun close() {
        datasourceNames.forEach { name ->
            jdbc.update("DELETE FROM datasource_workspaces WHERE datasource_name = ?", name)
            jdbc.update("DELETE FROM datasources WHERE name = ?", name)
        }
        workspaceIds.forEach { id ->
            jdbc.update("DELETE FROM datasource_workspaces WHERE workspace_id = ?", id)
            jdbc.update("DELETE FROM workspaces WHERE id = ?", id)
        }
        jdbc.update("DELETE FROM users WHERE id = ?", user)
    }
}
