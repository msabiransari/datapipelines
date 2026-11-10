package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DependencyWalker
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * The deadline of one release planning walk (record §25.4): ONE injected supplier, so #786 swaps one bean. G1 pins it
 * to [Deadline.none] — today's plan path has no timeout and G1 adds no refusal code.
 */
fun interface ReleasePlanningDeadline {
    fun open(): Deadline

    companion object {
        /** The unbounded deadline the G1 wiring answers. */
        val NONE = ReleasePlanningDeadline { Deadline.none() }
    }
}

/** The release bounds as the walk's [GraphLimits] (brief B.5): `maxVersions` is the node bound, `maxBlockers` the diagnostic one. */
object ReleaseGraphLimits {
    fun of(bounds: ReleaseBounds): GraphLimits =
        GraphLimits(
            depth = bounds.maxDepth,
            nodes = bounds.maxVersions,
            edges = bounds.maxEdges,
            diagnostics = bounds.maxBlockers,
            wireBytes = bounds.maxWireBytes,
            lockWaitSeconds = bounds.lockWaitSeconds,
            resolveBatchSize = GraphLimits.DEFAULT_RESOLVE_BATCH_SIZE,
            metadataTimeout = GraphLimits.DEFAULT_METADATA_TIMEOUT,
            statementTimeout = GraphLimits.DEFAULT_STATEMENT_TIMEOUT,
        )
}

/**
 * The release closure read through the neutral walk (#868 G1 section D.3): the registry's release families, the
 * [ReleaseClosureAdapter], purpose `RELEASE_PLANNING`, the caller as a member authority and the [deadline] supplier.
 *
 * - [plan] — the plan path's read: ONE explicitly read-only REPEATABLE READ transaction of its own ([planningTransaction]:
 *   `REQUIRES_NEW`, so it never joins a weaker caller), ended before the caller builds anything from the closure. Every
 *   provider batch reads the one snapshot (record §7.2 step 2, §7.3).
 * - [walkInCurrentTransaction] — the compatibility path's walk and the plan path's guarded re-walk, inside the release
 *   write transaction the coordinator already holds (unchanged: its isolation, its guard, its re-compare).
 */
class ReleasePlanningWalk(
    registry: FamilyRegistry,
    bounds: ReleaseBounds,
    private val planningTransactions: TransactionOperations,
    private val deadline: ReleasePlanningDeadline,
    clock: Clock = Clock.systemUTC(),
) {
    private val walker = DependencyWalker(registry, ReleaseGraphLimits.of(bounds), clock)

    /** The closure of [root] in the planning transaction; null when the root does not resolve. */
    fun plan(
        root: ReleaseNodeIdentity,
        actor: UUID,
    ): ReleaseClosure? = planningTransactions.execute { walk(root, actor, MetadataSnapshot.SINGLE_REPEATABLE_READ) }

    /** The closure of [root] in the caller's transaction; null when the root does not resolve. */
    fun walkInCurrentTransaction(
        root: ReleaseNodeIdentity,
        actor: UUID,
    ): ReleaseClosure? = walk(root, actor, MetadataSnapshot.READ_COMMITTED)

    private fun walk(
        root: ReleaseNodeIdentity,
        actor: UUID,
        snapshot: MetadataSnapshot,
    ): ReleaseClosure? {
        val context = ResolveContext(root.workspaceId, snapshot, Purpose.RELEASE_PLANNING, Authority.Member(actor), deadline.open())
        return ReleaseClosureAdapter.closure(root, walker.walk(listOf(ReleaseDependencyFamilies.request(root)), context))
    }

    companion object {
        /**
         * The planning transaction on [metadataTransactionManager]: REPEATABLE READ, read-only, REQUIRES_NEW. A template
         * of its own, never a bean — a `TransactionOperations` bean would switch off Boot's auto-configured template.
         */
        fun planningTransaction(metadataTransactionManager: PlatformTransactionManager): TransactionTemplate =
            TransactionTemplate(metadataTransactionManager).apply {
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                isReadOnly = true
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                setName(PLANNING_TRANSACTION_NAME)
            }

        /** The planning transaction's name, for a reader of the transaction log. */
        const val PLANNING_TRANSACTION_NAME = "release-planning-snapshot"
    }
}
