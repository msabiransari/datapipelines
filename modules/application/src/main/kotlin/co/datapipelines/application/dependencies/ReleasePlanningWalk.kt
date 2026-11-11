package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.CycleHandling
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.DependencyWalker
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseRefusal
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The deadline of one release planning walk (record §25.4): ONE injected supplier, opened once per walk. G1 pinned it to
 * [Deadline.none]; #786 §E.3 wires a finite one of `metadata-timeout-seconds` (owner ruling 2026-11-08, 868 R1-4
 * option 1), and an expired walk is refused `<family>.release.metadata_unavailable` by [ReleasePlanningWalk].
 */
fun interface ReleasePlanningDeadline {
    fun open(): Deadline

    companion object {
        /** The unbounded deadline — the directly constructed test default; production wires [after]. */
        val NONE = ReleasePlanningDeadline { Deadline.none() }

        /** A fresh deadline [budget] after [clock]'s now, for every walk. */
        fun after(
            budget: Duration,
            clock: Clock = Clock.systemUTC(),
        ): ReleasePlanningDeadline = ReleasePlanningDeadline { Deadline.after(budget, clock) }
    }
}

/**
 * The planning walk's batch and time bounds (#786 §E.3, record §25.4; configuration.md §3.35): the provider batch size,
 * the plan's cumulative metadata deadline and one statement's ceiling inside it. [DEFAULT] equals the documented
 * defaults and the contract's own.
 */
data class ReleasePlanningLimits(
    val resolveBatchSize: Int = GraphLimits.DEFAULT_RESOLVE_BATCH_SIZE,
    val metadataTimeout: Duration = GraphLimits.DEFAULT_METADATA_TIMEOUT,
    val statementTimeout: Duration = GraphLimits.DEFAULT_STATEMENT_TIMEOUT,
) {
    companion object {
        val DEFAULT = ReleasePlanningLimits()
    }
}

/** The release bounds as the walk's [GraphLimits] (brief B.5): `maxVersions` is the node bound, `maxBlockers` the diagnostic one. */
object ReleaseGraphLimits {
    fun of(
        bounds: ReleaseBounds,
        planning: ReleasePlanningLimits = ReleasePlanningLimits.DEFAULT,
    ): GraphLimits =
        GraphLimits(
            depth = bounds.maxDepth,
            nodes = bounds.maxVersions,
            edges = bounds.maxEdges,
            diagnostics = bounds.maxBlockers,
            wireBytes = bounds.maxWireBytes,
            lockWaitSeconds = bounds.lockWaitSeconds,
            resolveBatchSize = planning.resolveBatchSize,
            metadataTimeout = planning.metadataTimeout,
            statementTimeout = planning.statementTimeout,
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
 *
 * Both walks fence their speculative reads with `speculativeReads` — in production a [SavepointReadIsolation] on the
 * same `metadataTransactionManager`, built by the companion's factory of that name — so a speculative read that fails
 * cannot leave either transaction aborted and turn into the walk's outcome (#868 R1, round 3).
 */
class ReleasePlanningWalk(
    registry: FamilyRegistry,
    bounds: ReleaseBounds,
    private val planningTransactions: TransactionOperations,
    private val speculativeReads: ReadIsolation,
    private val deadline: ReleasePlanningDeadline,
    clock: Clock = Clock.systemUTC(),
    planning: ReleasePlanningLimits = ReleasePlanningLimits.DEFAULT,
) {
    /** Stops at the first cycle, as `ReleaseGraph` refused there: no read after the refusal can overtake it (#868 R1). */
    private val walker = DependencyWalker(registry, ReleaseGraphLimits.of(bounds, planning), clock, CycleHandling.STOP)

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
        val context =
            ResolveContext(
                root.workspaceId,
                snapshot,
                Purpose.RELEASE_PLANNING,
                Authority.Member(actor),
                deadline.open(),
                speculativeReads,
            )
        val graph =
            try {
                walker.walk(listOf(ReleaseDependencyFamilies.request(root)), context)
            } catch (expired: DeadlineExceeded) {
                throw metadataUnavailable(root, expired)
            }
        // #786 R2: the walker checks the deadline BEFORE each provider read, so a read that runs past it — the final
        // needed batch, one answering Missing, or a traversal finished from the memo after it — would otherwise return a
        // closure read outside its budget. The cumulative deadline covers the whole walk: expired at its end, refused.
        if (context.deadline.expired()) throw metadataUnavailable(root, DeadlineExceeded(ReleaseDependencyFamilies.keyOf(root.family)))
        return ReleaseClosureAdapter.closure(root, graph)
    }

    companion object {
        /**
         * The plan's deadline expired — before a provider batch, or during one (#786 R2: checked again when the walk
         * ends) (record §25.4 erratum 6): `<root family>.release.
         * metadata_unavailable` — 503 with `Retry-After`, nothing released, no partial plan. The details name no fact
         * of the closure; the blocking family's key is not a visible identity, so it travels only in the log's cause.
         */
        fun metadataUnavailable(
            root: ReleaseNodeIdentity,
            cause: DeadlineExceeded,
        ): DatapipelinesException =
            DatapipelinesException(
                code = root.family.code(ReleaseRefusal.METADATA_UNAVAILABLE),
                message = "The release plan couldn't be read in time. Nothing was released; try again in a moment.",
                details = emptyMap(),
                cause = cause,
            )

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

        /**
         * The speculative-read fence on [metadataTransactionManager]: a savepoint in whichever transaction the walk reads
         * in — the planning transaction, or the guarded caller's write transaction.
         */
        fun speculativeReads(metadataTransactionManager: PlatformTransactionManager): ReadIsolation =
            SavepointReadIsolation(metadataTransactionManager)
    }
}
