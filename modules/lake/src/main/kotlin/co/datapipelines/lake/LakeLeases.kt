package co.datapipelines.lake

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What a lease guards (§8a): a managed table (`<ns>.<table>`) or a namespace (`<ns>`). The `lake_leases.kind` values. */
enum class LeaseKind(
    val sql: String,
) {
    TABLE("table"),
    NAMESPACE("namespace"),
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
 * Every comparison is PostgreSQL's `now()`, never this JVM's clock. Each statement autocommits on its own pooled
 * connection, so the lease row is visible to the fence trigger of another connection at once; calling inside a
 * Spring transaction would bind the statements to it and hide the lease until that transaction commits, so it is
 * refused ([requireNoTransaction]). The namespace lease is taken before the table lease (§8a fixed order); that
 * order is the caller's (P3/P4).
 */
class LakeLeases(
    private val jdbc: JdbcTemplate,
    /** `lake_leases.holder_instance`: which process holds the lease (diagnostic only, never compared). */
    val holderInstance: String = defaultHolderInstance(),
) {
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
        private const val MILLIS_PER_SECOND = 1000.0

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
