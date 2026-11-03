package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeNames
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * How an apply takes its leases (dp-lake design record §8a): the NAMESPACE lease first, and only when the Iceberg
 * namespace does not exist yet; then the TABLE lease. Each acquire waits up to `lake.admission.lease-wait-seconds`
 * (bounded by the caller's deadline), polling; the wait and the deadline are read from the catalog clock, never this
 * JVM's. Every lease is taken under the attempt's one operation id, so `LakeLeases.release(operationId)` frees both.
 */
class LakeApplyLeases(
    private val leases: LakeLeases,
    private val leaseWait: Duration,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
) {
    /** The leases one attempt holds: [namespace] is null when the namespace already existed. */
    data class Held(
        val namespace: LeaseHandle?,
        val table: LeaseHandle,
    )

    /** The answer of [take]: the leases, or the reason the wait ended without them. */
    sealed interface Taken {
        data class Ok(
            val held: Held,
        ) : Taken

        data class Busy(
            val reason: String,
        ) : Taken
    }

    /** Takes the namespace lease (when [namespaceExists] is false) and then the table lease, in that fixed order. */
    fun take(
        namespace: String,
        table: String,
        namespaceExists: Boolean,
        operationId: UUID,
        deadline: Instant,
        operation: String,
    ): Taken {
        val namespaceLease =
            if (namespaceExists) {
                null
            } else {
                when (val o = acquire(LeaseKind.NAMESPACE, namespace, operationId, deadline, operation)) {
                    is LeaseOutcome.Acquired -> o.lease
                    LeaseOutcome.Busy -> return Taken.Busy(REASON_NAMESPACE)
                    LeaseOutcome.DeadlinePassed -> return Taken.Busy(REASON_DEADLINE)
                }
            }
        return when (val o = acquire(LeaseKind.TABLE, LakeNames.tableLeaseName(namespace, table), operationId, deadline, operation)) {
            is LeaseOutcome.Acquired -> Taken.Ok(Held(namespaceLease, o.lease))
            LeaseOutcome.Busy -> Taken.Busy(REASON_TABLE)
            LeaseOutcome.DeadlinePassed -> Taken.Busy(REASON_DEADLINE)
        }
    }

    /** One attempt at the TABLE lease, no wait (verify's takeover: a live holder means a live attempt; leave it). */
    fun tryTable(
        namespace: String,
        table: String,
        operationId: UUID,
        deadline: Instant,
        operation: String,
    ): LeaseOutcome = leases.acquire(LeaseKind.TABLE, LakeNames.tableLeaseName(namespace, table), operationId, ttl(), deadline, operation)

    /** Renews [lease] before a commit; false = it was lost (expired, deadlined or taken over). */
    fun renew(lease: LeaseHandle): Boolean = leases.heartbeat(lease, ttl())

    fun release(operationId: UUID): Int = leases.release(operationId)

    fun catalogNow(): Instant = leases.catalogNowPlus(Duration.ZERO)

    private fun acquire(
        kind: LeaseKind,
        name: String,
        operationId: UUID,
        deadline: Instant,
        operation: String,
    ): LeaseOutcome {
        val waitUntil = minOf(deadline, leases.catalogNowPlus(leaseWait))
        while (true) {
            val outcome = leases.acquire(kind, name, operationId, ttl(), deadline, operation)
            if (outcome != LeaseOutcome.Busy) return outcome
            if (!leases.catalogNowPlus(pollInterval).isBefore(waitUntil)) return LeaseOutcome.Busy
            Thread.sleep(pollInterval.toMillis())
        }
    }

    private fun ttl(): Duration = APPLY_LEASE_TTL

    companion object {
        /**
         * An apply's lease time-to-live. An apply is a metadata commit (seconds); the lease is renewed right before
         * the commit, and capped at the caller's deadline by `LakeLeases.acquire` itself. Not a configuration key:
         * the brief adds none, and P4 owns lease tuning for appends.
         */
        val APPLY_LEASE_TTL: Duration = Duration.ofMinutes(2)

        val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMillis(250)

        const val REASON_NAMESPACE = "namespace"
        const val REASON_TABLE = "table"
        const val REASON_DEADLINE = "deadline"
    }
}
