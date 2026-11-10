package co.datapipelines.typesystem.guard

import java.util.UUID

/**
 * The metadata writer guard's family-neutral face (dependency-graph record §15/§15.1, D10; #841 G0). Every writer
 * whose facts a release resolves acquires, INSIDE its metadata transaction and in this order only:
 *
 * 1. [prerequisites] — sorted canonical prerequisite advisory keys (today: shared datasource names), the protection
 *    for facts no single workspace owns and for rows that do not exist yet;
 * 2. [workspaces] — sorted `workspaces` rows, the same `FOR NO KEY UPDATE` row the release guard takes;
 * 3. [endpointRegistry] — the single deployment-wide endpoint registry key, when path absence/overlap matters;
 *
 * then its own entity/version (4) and evidence (5) row locks. Acquiring an earlier class after a later one in the same
 * transaction is a wiring defect and throws [IllegalStateException]: deadlock inversion is a defect, not a retry policy.
 * Re-acquiring something the transaction already holds is a no-op.
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
    /** Class (1): the canonical prerequisite advisory keys, acquired in sorted order. */
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
 * the validator already restricts to `[a-z0-9_-]`), never a display alias, so no alias can evade the key. Sorted by
 * namespace then name: the acquisition order every writer shares.
 */
data class PrerequisiteKey(
    val namespace: PrerequisiteNamespace,
    val name: String,
) : Comparable<PrerequisiteKey> {
    init {
        require(name.isNotBlank()) { "a prerequisite key needs a canonical name" }
    }

    override fun compareTo(other: PrerequisiteKey): Int = compareValuesBy(this, other, { it.namespace.ordinal }, { it.name })

    companion object {
        fun datasource(name: String) = PrerequisiteKey(PrerequisiteNamespace.DATASOURCE, name)
    }
}
