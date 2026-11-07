package co.datapipelines.lake

import co.datapipelines.persistence.FailureShape
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The writer table/namespace guards and the managed reader pin kind (§8a). */
enum class LeaseKind(
    val sql: String,
) {
    TABLE("table"),
    NAMESPACE("namespace"),
    READER("reader"),
}

/**
 * A held lease: the attempt's [operationId] and the [token] the fence compares (§8a). The token is a fencing
 * credential of the catalog, so [toString] shows only its first eight characters.
 */
data class LeaseHandle(
    val kind: LeaseKind,
    val name: String,
    val operationId: UUID,
    val token: UUID,
) {
    override fun toString(): String =
        "LeaseHandle(kind=${kind.sql}, name=$name, operationId=$operationId, token=${token.toString().take(TOKEN_SHOWN)}…)"

    private companion object {
        const val TOKEN_SHOWN = 8
    }
}

/** The outcome of [LakeLeases.acquire]: exactly one of the three. */
sealed interface LeaseOutcome {
    data class Acquired(
        val lease: LeaseHandle,
    ) : LeaseOutcome

    /** Another operation holds a live, undeadlined lease on the name. */
    data object Busy : LeaseOutcome

    /** The caller's own deadline had already passed by the catalog clock — no token is issued (E11 F2). */
    data object DeadlinePassed : LeaseOutcome
}

/**
 * The §8a lease protocol over `lake_leases` — the statements E1 (#774) and E11 (#777) falsified, verbatim:
 *
 * - **acquire** is ONE `INSERT … SELECT … WHERE deadline > now() ON CONFLICT (kind, name) DO UPDATE … WHERE (the
 *   row is expired OR deadlined) AND EXCLUDED.deadline_at > now() RETURNING token`. The `SELECT … WHERE` refuses a
 *   past deadline on the insert path too: §8a's earlier `INSERT … VALUES` form issued a dead-on-arrival token
 *   there (E11 F2). A takeover draws a NEW random token, so the dead holder's token stops matching.
 * - **heartbeat** extends `expires_at` (never past `deadline_at`) only on a row that is still live and undeadlined.
 * - **release** deletes by operation id: every lease of the operation at once.
 *
 * Every comparison is PostgreSQL's `now()`, never this JVM's clock. Each statement commits on its own pooled
 * connection before the call returns, so the lease row is visible to the fence trigger of another connection at once;
 * calling inside a Spring transaction would bind the statements to it and hide the lease until that transaction
 * commits, so it is refused ([requireNoTransaction]). The namespace lease is taken before the table lease (§8a fixed
 * order); that order is the caller's (P3/P4).
 *
 * **A takeover waits until [lockTimeout] or the five-second acquire transaction expires (#812, #799).**
 * `ON CONFLICT DO UPDATE` locks the conflicting row before its
 * `WHERE` is evaluated, so a takeover waits on any transaction that holds the row: the fence takes it `FOR SHARE` for
 * the life of a catalog transaction, including a dead attempt's that is still open. The acquire therefore runs as its
 * own short transaction whose first statement sets `lock_timeout` for that transaction only (`set_config(…, true)`,
 * the value bound, never a session setting on the shared pool), then the acquire statement, then the commit. A lock
 * wait that reaches either timeout (SQLSTATE `55P03` or `57014`) answers [LeaseOutcome.Busy], with the row unchanged.
 * Spring rounds remaining transaction time up to whole seconds, allowing almost one additional second.
 */
class LakeLeases(
    private val jdbc: JdbcTemplate,
    /** `lake_leases.holder_instance`: which process holds it; diagnostics for writers and a bulk-heartbeat fence for readers. */
    val holderInstance: String = defaultHolderInstance(),
    /** Lease-row lock timeout (`datapipelines.lake.catalog.lock-timeout-seconds`); the acquire transaction also caps it. */
    val lockTimeout: Duration = Duration.ofSeconds(LakeProperties.DEFAULT_LOCK_TIMEOUT_SECONDS),
) {
    init {
        require(!lockTimeout.isNegative && !lockTimeout.isZero) { "lease lock timeout must be positive: $lockTimeout" }
        require(lockTimeout.toMillis() <= Int.MAX_VALUE) { "lease lock timeout must fit PostgreSQL's lock_timeout: $lockTimeout" }
    }

    /**
     * The acquire's own transaction, on the same DataSource as [jdbc] so its statements share one connection. Bounded as
     * a whole (record §8b, D5): whichever timer ends a lock wait, [lockTimeout] or this one, the acquire answers Busy with
     * the row unchanged, so a lock wait longer than this bound is cut to it. `deadline_at` stays the only authority over a
     * lease's life.
     */
    private val acquireTransaction =
        TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource) { "the lease JdbcTemplate has no DataSource" }))
            .apply { timeout = ACQUIRE_TRANSACTION_SECONDS }

    /** `lock_timeout` as PostgreSQL reads it: whole milliseconds. */
    private val lockTimeoutSetting = "${lockTimeout.toMillis()}ms"

    fun acquire(
        kind: LeaseKind,
        name: String,
        operationId: UUID,
        ttl: Duration,
        deadline: Instant,
        operation: String,
    ): LeaseOutcome {
        requireNoTransaction()
        require(!ttl.isNegative && !ttl.isZero) { "lease ttl must be positive: $ttl" }
        require(operation.isNotBlank()) { "lease operation must be named" }
        val deadlineAt = OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC)
        val seconds = ttl.toMillis() / MILLIS_PER_SECOND
        val token =
            try {
                // Committed right after the acquire statement: a refused takeover still locked the row, and holds
                // it until this transaction ends.
                acquireTransaction.execute {
                    jdbc.queryForObject(LOCAL_LOCK_TIMEOUT_SQL, String::class.java, lockTimeoutSetting)
                    jdbc
                        .query(
                            ACQUIRE_SQL,
                            { rs, _ -> rs.getObject(1, UUID::class.java) },
                            kind.sql,
                            name,
                            operationId,
                            seconds,
                            deadlineAt,
                            deadlineAt,
                            operation,
                            holderInstance,
                            deadlineAt,
                            seconds,
                        ).singleOrNull()
                }
            } catch (e: DataAccessException) {
                if (FailureShape.sqlState(e) in ACQUIRE_WAIT_ENDED) return LeaseOutcome.Busy
                throw e
            }
        if (token != null) return LeaseOutcome.Acquired(LeaseHandle(kind, name, operationId, token))
        val past = jdbc.queryForObject(DEADLINE_PASSED_SQL, Boolean::class.java, deadlineAt) == true
        return if (past) LeaseOutcome.DeadlinePassed else LeaseOutcome.Busy
    }

    /** Renews [lease] for [ttl] (capped at its deadline). False = the lease is expired, deadlined or taken over. */
    fun heartbeat(
        lease: LeaseHandle,
        ttl: Duration,
    ): Boolean {
        requireNoTransaction()
        require(!ttl.isNegative && !ttl.isZero) { "lease ttl must be positive: $ttl" }
        return jdbc.update(HEARTBEAT_SQL, ttl.toMillis() / MILLIS_PER_SECOND, lease.kind.sql, lease.name, lease.operationId) == 1
    }

    private val readerJdbc = JdbcTemplate(requireNotNull(jdbc.dataSource)).apply { queryTimeout = READER_STATEMENT_TIMEOUT_SECONDS }

    /** Reader names are generation-unique: one autocommit INSERT, never a writer takeover transaction. */
    fun acquireReader(
        name: String,
        operationId: UUID,
        ttl: Duration,
        deadline: Instant,
        pinnedLocation: String,
    ): LeaseOutcome {
        requireNoTransaction()
        require(ttl > Duration.ZERO) { "reader ttl must be positive" }
        require(pinnedLocation.isNotBlank()) { "reader metadata location must be named" }
        val at = OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC)
        val token =
            readerJdbc
                .query(
                    "INSERT INTO lake_leases (kind,name,operation_id,token,acquired_at,expires_at,deadline_at," +
                        "operation,holder_instance,pinned_metadata_location)" +
                        " SELECT 'reader',?,?,gen_random_uuid(),now(),least(now()+make_interval(secs => ?),?::timestamptz)," +
                        " ?::timestamptz,'read',?,? WHERE ?::timestamptz > now() RETURNING token",
                    { rs, _ -> rs.getObject(1, UUID::class.java) },
                    name,
                    operationId,
                    ttl.toMillis() / MILLIS_PER_SECOND,
                    at,
                    at,
                    holderInstance,
                    pinnedLocation,
                    at,
                ).singleOrNull()
        return if (token ==
            null
        ) {
            LeaseOutcome.DeadlinePassed
        } else {
            LeaseOutcome.Acquired(LeaseHandle(LeaseKind.READER, name, operationId, token))
        }
    }

    /** One statement renews live readers and releases closed generations, returning per-generation row counts. */
    fun heartbeatReaders(
        operationIds: Set<UUID>,
        ttl: Duration,
        releasedIds: Set<UUID> = emptySet(),
    ): Map<UUID, Int> {
        requireNoTransaction()
        require(ttl > Duration.ZERO) { "reader ttl must be positive" }
        require(operationIds.intersect(releasedIds).isEmpty()) { "a generation cannot be live and released" }
        if (operationIds.isEmpty() && releasedIds.isEmpty()) return emptyMap()
        return jdbc
            .execute(
                org.springframework.jdbc.core.ConnectionCallback { connection ->
                    connection
                        .prepareStatement(
                            "WITH released AS (DELETE FROM lake_leases WHERE kind='reader' AND holder_instance=?" +
                                " AND operation_id=ANY(?) RETURNING operation_id)" +
                                " UPDATE lake_leases SET expires_at=least(now()+make_interval(secs => ?),deadline_at)" +
                                " WHERE kind='reader' AND holder_instance=? AND operation_id=ANY(?)" +
                                " AND expires_at>now() AND deadline_at>now() RETURNING operation_id",
                        ).use { statement ->
                            statement.queryTimeout = READER_STATEMENT_TIMEOUT_SECONDS
                            val live = connection.createArrayOf("uuid", operationIds.toTypedArray())
                            val released = connection.createArrayOf("uuid", releasedIds.toTypedArray())
                            try {
                                statement.setString(1, holderInstance)
                                statement.setArray(2, released)
                                statement.setDouble(READER_TTL_PARAMETER, ttl.toMillis() / MILLIS_PER_SECOND)
                                statement.setString(READER_HOLDER_PARAMETER, holderInstance)
                                statement.setArray(READER_IDS_PARAMETER, live)
                                statement.executeQuery().use { rows ->
                                    buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) }.groupingBy { it }.eachCount()
                                }
                            } finally {
                                live.free()
                                released.free()
                            }
                        }
                },
            ).orEmpty()
    }

    /** Releases every lease of [operationId]; answers how many rows it removed. */
    fun release(operationId: UUID): Int {
        requireNoTransaction()
        return jdbc.update(RELEASE_SQL, operationId)
    }

    /** The catalog clock plus [offset] — how a caller derives a deadline without trusting its own clock. */
    fun catalogNowPlus(offset: Duration): Instant =
        requireNotNull(
            jdbc.queryForObject(NOW_PLUS_SQL, OffsetDateTime::class.java, offset.toMillis() / MILLIS_PER_SECOND),
        ) { "now() answered null" }.toInstant()

    private fun requireNoTransaction() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "lake leases autocommit on their own connection; calling inside a transaction would hide the lease from the fence"
        }
    }

    companion object {
        private const val READER_TTL_PARAMETER = 3
        private const val READER_HOLDER_PARAMETER = 4
        private const val READER_IDS_PARAMETER = 5

        /** Reader statement timeout in seconds; the web adapter also bounds socket reads. */
        const val READER_STATEMENT_TIMEOUT_SECONDS = 5

        private const val MILLIS_PER_SECOND = 1000.0

        /** `lock_not_available`: a lock wait reached `lock_timeout`. */
        const val SQLSTATE_LOCK_NOT_AVAILABLE = "55P03"

        /** `query_canceled`: the acquire transaction's timer ended the statement; the transaction rolled back. */
        private const val SQLSTATE_QUERY_CANCELED = "57014"

        private val ACQUIRE_WAIT_ENDED = setOf(SQLSTATE_LOCK_NOT_AVAILABLE, SQLSTATE_QUERY_CANCELED)

        /** One acquire's transaction bound: the lease statements' own five seconds (record §8b, D5). */
        const val ACQUIRE_TRANSACTION_SECONDS = 5

        /** `lock_timeout` for the current transaction only (`is_local = true`); the value is bound. */
        private const val LOCAL_LOCK_TIMEOUT_SQL = "SELECT set_config('lock_timeout', ?, true)"

        private const val ON_CONFLICT =
            " ON CONFLICT (kind, name) DO UPDATE SET operation_id = EXCLUDED.operation_id, token = gen_random_uuid()," +
                " acquired_at = now(), expires_at = least(now() + make_interval(secs => ?), EXCLUDED.deadline_at)," +
                " deadline_at = EXCLUDED.deadline_at, operation = EXCLUDED.operation, holder_instance = EXCLUDED.holder_instance" +
                " WHERE (lake_leases.expires_at < now() OR lake_leases.deadline_at < now()) AND EXCLUDED.deadline_at > now()" +
                " RETURNING token"

        /** §8a acquire — E1's `BOTH_PATHS_SQL`, the past-deadline refusal on the insert AND the takeover path. */
        internal const val ACQUIRE_SQL =
            "INSERT INTO lake_leases (kind, name, operation_id, token, acquired_at, expires_at, deadline_at, operation," +
                " holder_instance) SELECT ?, ?, ?, gen_random_uuid(), now()," +
                " least(now() + make_interval(secs => ?), ?::timestamptz), ?::timestamptz, ?, ? WHERE ?::timestamptz > now()" +
                ON_CONFLICT

        private const val DEADLINE_PASSED_SQL = "SELECT ?::timestamptz <= now()"

        private const val HEARTBEAT_SQL =
            "UPDATE lake_leases SET expires_at = least(now() + make_interval(secs => ?), deadline_at)" +
                " WHERE kind = ? AND name = ? AND operation_id = ? AND expires_at > now() AND deadline_at > now()"

        private const val RELEASE_SQL = "DELETE FROM lake_leases WHERE operation_id = ?"

        private const val NOW_PLUS_SQL = "SELECT now() + make_interval(secs => ?)"

        /** `<host>/<pid>` — `RuntimeMXBean.getName()`'s `pid@host`, reordered; no configuration reaches it. */
        fun defaultHolderInstance(): String =
            ManagementFactory.getRuntimeMXBean().name.let { raw ->
                val pid = raw.substringBefore('@')
                val host = raw.substringAfter('@', missingDelimiterValue = "unknown")
                "$host/$pid"
            }
    }
}
