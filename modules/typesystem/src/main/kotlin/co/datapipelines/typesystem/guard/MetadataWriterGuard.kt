package co.datapipelines.typesystem.guard

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * The metadata writer guard's family-neutral face (dependency-graph record §15/§15.1, D10; #841 G0). Every writer
 * whose facts a release resolves acquires, INSIDE its metadata transaction and in this order only:
 *
 * 1. [prerequisites] — canonical prerequisite keys (today: shared datasource names), the protection for facts no single
 *    workspace owns and for rows that do not exist yet — acquired in the order of their PHYSICAL advisory identities
 *    ([PrerequisiteLock]), never of their names: two names may share one physical lock (a hash collision), and only an
 *    order over what the database actually locks is one total order every writer shares;
 * 2. [workspaces] — sorted `workspaces` rows, the same `FOR NO KEY UPDATE` row the release guard takes;
 * 3. [endpointRegistry] — the single deployment-wide endpoint registry key, when path absence/overlap matters;
 *
 * then its own entity/version (4) and evidence (5) row locks. Acquiring an earlier class after a later one in the same
 * transaction is a wiring defect and throws [IllegalStateException]: deadlock inversion is a defect, not a retry policy.
 * Re-acquiring something the transaction already holds is a no-op — for a prerequisite that includes a DIFFERENT name
 * whose physical lock is already held (a collision is the same lock, so it is the same exclusion).
 *
 * Every acquisition in one transaction (or one [withinOneDeadline] scope across replanning retries) shares ONE
 * cumulative deadline `D = t0 + bound`, fixed at the first acquisition; each statement is granted at most `D − now`.
 * An exhausted wait refuses with the caller's [busyCode] (409, nothing written): a real Postgres lock timeout keeps
 * its `55P03` `SQLException` on the cause chain; a deadline already passed before the next statement refuses with
 * no cause and issues no statement (the two shapes of the G0 refusal contract).
 *
 * Production wires the JDBC implementation in `web` (`JdbcReleaseMutationGuard`); [NONE] is the directly constructed
 * unit-test default and is never production wiring (the configuration wiring tests pin that).
 */
interface MetadataWriterGuard {
    /**
     * Class (1): the canonical prerequisite keys, deduplicated and acquired in [PrerequisiteLock] order (namespace lock
     * space, then the name's advisory sub-key) — the physical identities, so colliding names never invert the order.
     */
    fun prerequisites(
        keys: Collection<PrerequisiteKey>,
        busyCode: String,
    )

    /** Class (2): the workspace rows, acquired in sorted id order. */
    fun workspaces(
        workspaceIds: Collection<UUID>,
        busyCode: String,
    )

    /** Class (3): the single deployment-wide endpoint registry key. */
    fun endpointRegistry(busyCode: String)

    /**
     * Runs [block] under ONE cumulative deadline shared by every acquisition inside it, across the transactions a
     * replanning retry opens ([Replanning]). Nested scopes reuse the outer deadline.
     */
    fun <T> withinOneDeadline(block: () -> T): T

    /**
     * [withinOneDeadline] under a caller's CEILING (#841 R4): the scope's deadline is no later than [ceiling] after the
     * scope OPENS — `D = min(t0 + bound, opened + ceiling)` — so a caller whose own wall-clock budget is shorter than
     * the configured lock wait never waits longer than that budget, whatever the bound is set to. Its first user is the
     * managed lake's freshness repair, which shares the jobs' one `dp-scheduled` thread: its lock waits, its discovery
     * read and its INSERT together fit one statement bound. Nested in an open scope, a ceiling only ever TIGHTENS the
     * open scope's deadline, never loosens it.
     */
    fun <T> withinOneDeadline(
        ceiling: Duration,
        block: () -> T,
    ): T

    companion object {
        /** No database, no lock — the unit-test construction default. Production never wires it. */
        val NONE: MetadataWriterGuard =
            object : MetadataWriterGuard {
                override fun prerequisites(
                    keys: Collection<PrerequisiteKey>,
                    busyCode: String,
                ) = Unit

                override fun workspaces(
                    workspaceIds: Collection<UUID>,
                    busyCode: String,
                ) = Unit

                override fun endpointRegistry(busyCode: String) = Unit

                override fun <T> withinOneDeadline(block: () -> T): T = block()

                override fun <T> withinOneDeadline(
                    ceiling: Duration,
                    block: () -> T,
                ): T = block()
            }
    }
}

/** The prerequisite namespaces; each owns a distinct advisory-key space, isolated from every other native lock. */
enum class PrerequisiteNamespace(
    /** The first `int4` of the two-key advisory form; never reused by another lock of this application. */
    val lockSpace: Int,
) {
    /** A shared datasource, keyed by its canonical (primary-key) name. */
    DATASOURCE(LOCK_SPACE_DATASOURCE),
}

private const val LOCK_SPACE_DATASOURCE = 841_001

/**
 * One canonical prerequisite identity. [name] is the canonical identity itself (a datasource's primary-key name, which
 * the validator already restricts to `[a-z0-9_-]`), never a display alias, so no alias can evade the key. Discovery and
 * revalidation compare names; the LOCK is [lock], and acquisition is ordered by it alone.
 */
data class PrerequisiteKey(
    val namespace: PrerequisiteNamespace,
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "a prerequisite key needs a canonical name" }
    }

    /** The physical advisory lock this key takes. Distinct names may map to one lock: that over-serialises, never weakens. */
    val lock: PrerequisiteLock get() = PrerequisiteLock(namespace.lockSpace, PrerequisiteLock.subKeyOf(name))

    companion object {
        fun datasource(name: String) = PrerequisiteKey(PrerequisiteNamespace.DATASOURCE, name)
    }
}

/**
 * The PHYSICAL identity of a prerequisite advisory lock — exactly the two `int4` arguments of
 * `pg_advisory_xact_lock(lockSpace, subKey)` — and the one total order (by [lockSpace], then [subKey], both signed)
 * every writer acquires prerequisites in (#841 G0 R1). Ordering by canonical NAME is not a total order over locks:
 * with `a < b < c` lexically and `subKeyOf(a) == subKeyOf(c)`, one writer's sorted `{a, b}` and another's sorted
 * `{b, c}` request the same two locks in opposite orders, and PostgreSQL answers one of them `40P01`.
 */
data class PrerequisiteLock(
    val lockSpace: Int,
    val subKey: Int,
) : Comparable<PrerequisiteLock> {
    override fun compareTo(other: PrerequisiteLock): Int = compareValuesBy(this, other, { it.lockSpace }, { it.subKey })

    companion object {
        /** A canonical name's advisory sub-key: the first four bytes (big-endian) of its UTF-8 SHA-256 — stable across JVMs and servers. */
        fun subKeyOf(canonicalName: String): Int =
            ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(canonicalName.toByteArray(Charsets.UTF_8))).int
    }
}
