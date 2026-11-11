package co.datapipelines.application.dependencies.restore

import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleaseGraphLimits
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningLimits
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.CycleHandling
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.DependencyWalker
import co.datapipelines.dependencies.DiagnosticKind
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.LimitKind
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Node
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Resolution
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseLimit
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePin
import co.datapipelines.pipeline.release.ReleaseRefusals
import co.datapipelines.pipeline.release.RestoreDependencyGuard
import co.datapipelines.pipeline.release.RestoreReach
import java.time.Clock
import java.util.UUID

/**
 * The [RestoreDependencyGuard] over the family registry (#873 §D.0): the DISCARDED root's stored body is read through its
 * own family's provider and its `outgoing` references are taken from THAT body (the walk would treat the root as a
 * terminal leaf); those references are the roots of ONE `LIFECYCLE_GUARD` walk under [Authority.System] in the caller's
 * transaction (`READ_COMMITTED`, the restore's guarded write transaction), its speculative reads fenced by
 * [speculativeReads], bounded by the release bounds and one fresh [deadline]. Every node the walk reaches that is not
 * RELEASED is one [RestoreReach] per direct reference, in the walk's edge order. G1's rules are never relaxed: a
 * DISCARDED or MISSING descendant is terminal; the first cycle or traversal bound is the root family's refusal.
 */
class WalkingRestoreDependencyGuard(
    private val registry: FamilyRegistry,
    bounds: ReleaseBounds,
    private val speculativeReads: ReadIsolation,
    private val deadline: ReleasePlanningDeadline,
    clock: Clock = Clock.systemUTC(),
    private val planning: ReleasePlanningLimits = ReleasePlanningLimits.DEFAULT,
) : RestoreDependencyGuard {
    private val walker = DependencyWalker(registry, ReleaseGraphLimits.of(bounds, planning), clock, CycleHandling.STOP)

    override fun recheck(root: ReleaseNodeIdentity): List<RestoreReach> =
        guarded(root) { context ->
            val request = ReleaseDependencyFamilies.request(root)
            registry[request.family]?.provider?.let { referencesOf(it, request, context) }.orEmpty().map { reference ->
                Direct(reference.request, requireNotNull(reference.sourcePath) { "a release pin always has its field path" })
            }
        }

    override fun recheck(
        root: ReleaseNodeIdentity,
        pins: List<ReleasePin>,
    ): List<RestoreReach> = guarded(root) { pins.map { Direct(ReleaseDependencyFamilies.request(it.target), it.path) } }

    /** [direct] read in one guard-purpose context, then walked as the roots of one walk. */
    private fun guarded(
        root: ReleaseNodeIdentity,
        direct: (ResolveContext) -> List<Direct>,
    ): List<RestoreReach> {
        val context =
            ResolveContext(
                root.workspaceId,
                MetadataSnapshot.READ_COMMITTED,
                Purpose.LIFECYCLE_GUARD,
                Authority.System,
                deadline.open().withStatementLimit(planning.statementTimeout),
                speculativeReads,
            )
        try {
            val references = direct(context)
            if (references.isEmpty()) return emptyList()
            val requests = references.map { it.request }.distinct()
            val snapshot = refused(root, walker.walk(requests, context), context)
            return Reaches(snapshot).of(references, requests)
        } catch (expired: DeadlineExceeded) {
            throw ReleasePlanningWalk.metadataUnavailable(root, expired)
        }
    }

    /** [snapshot] unless the deadline expired during the walk or it carries a cycle or a release bound: then those throw. */
    private fun refused(
        root: ReleaseNodeIdentity,
        snapshot: GraphSnapshot,
        context: ResolveContext,
    ): GraphSnapshot {
        if (context.deadline.expired()) throw DeadlineExceeded(ReleaseDependencyFamilies.keyOf(root.family))
        refusal(root, snapshot)?.let { throw it }
        return snapshot
    }

    /** The outgoing references of the stored version [request] names, by its own family's provider; empty when absent. */
    private fun <B : Any> referencesOf(
        provider: DependencyProvider<B>,
        request: ObjectRequest,
        context: ResolveContext,
    ): List<Reference> {
        val outcome = provider.resolveMany(listOf(request), context)[request] as? Outcome.Resolved<B> ?: return emptyList()
        return provider.outgoing(outcome)
    }

    /** One pin of the restored body: what it requests, at which of the body's own fields. */
    private data class Direct(
        val request: ObjectRequest,
        val path: String,
    )

    /** The first cycle or release-bound diagnostic in event order, spelled with the restored root's family. */
    private fun refusal(
        root: ReleaseNodeIdentity,
        snapshot: GraphSnapshot,
    ): RuntimeException? {
        val first =
            snapshot.diagnostics.firstOrNull {
                it.kind == DiagnosticKind.CYCLE || (it.kind == DiagnosticKind.LIMIT && it.limit in RELEASE_LIMITS)
            } ?: return null
        if (first.kind == DiagnosticKind.CYCLE) {
            return ReleaseRefusals.cycle(root.family, first.trail.map { closureNode(snapshot.node(it)) })
        }
        return ReleaseRefusals.limit(root.family, RELEASE_LIMITS.getValue(requireNotNull(first.limit)), requireNotNull(first.maximum))
    }

    /** The not-RELEASED nodes below each direct reference: a depth-first pass over the snapshot's edges, in edge order. */
    private class Reaches(
        private val snapshot: GraphSnapshot,
    ) {
        private val out = snapshot.edges.groupBy { it.fromNodeId }

        fun of(
            references: List<Direct>,
            requests: List<ObjectRequest>,
        ): List<RestoreReach> =
            references.flatMap { reference ->
                val start = snapshot.roots[requests.indexOf(reference.request)]
                buildList { visit(start, reference.path, reference.path, emptyList(), HashSet(), this) }
            }

        private fun visit(
            nodeId: String,
            directPath: String,
            path: String,
            via: List<ReleaseNodeIdentity>,
            seen: MutableSet<String>,
            into: MutableList<RestoreReach>,
        ) {
            if (!seen.add(nodeId)) return
            val node = closureNode(snapshot.node(nodeId))
            if (node.status != ReleaseNodeStatus.RELEASED) {
                into += RestoreReach(node.identity, node.name, node.status, directPath, path, via)
            }
            out[nodeId].orEmpty().forEach { edge ->
                val step = requireNotNull(edge.sourcePath) { "a release pin always has its field path" }
                visit(edge.toNodeId, directPath, "$path.$step", via + node.identity, seen, into)
            }
        }
    }

    private companion object {
        /** The walk's bounds release refuses with (`ReleaseClosureAdapter`'s): `versions` is the node bound. */
        val RELEASE_LIMITS =
            mapOf(LimitKind.NODES to ReleaseLimit.VERSIONS, LimitKind.EDGES to ReleaseLimit.EDGES, LimitKind.DEPTH to ReleaseLimit.DEPTH)

        /** A walk node as the release closure names it: an unresolved node is MISSING with the name its pin spelled. */
        fun closureNode(node: Node): ClosureNode {
            val identity =
                checkNotNull(ReleaseDependencyFamilies.identityOf(ObjectRequest(node.requestedAddress, node.requestedSelector))) {
                    "a restore walks exact release-family pins only, got ${node.requestedAddress} ${node.requestedSelector}"
                }
            val resolved = node.resolution == Resolution.RESOLVED
            return ClosureNode(
                nodeId = node.nodeId,
                identity = identity,
                name = node.name ?: identity.entityId,
                status = if (resolved) statusOf(requireNotNull(node.lifecycle)) else ReleaseNodeStatus.MISSING,
                bodyHash = node.bodyHash.takeIf { resolved },
                currentVersion = node.currentVersion.takeIf { resolved },
                entityUuid = node.identity?.let { UUID.fromString(it.stableEntityId) },
            )
        }

        fun statusOf(lifecycle: Lifecycle): ReleaseNodeStatus =
            when (lifecycle) {
                Lifecycle.DRAFT -> ReleaseNodeStatus.DRAFT
                Lifecycle.RELEASED -> ReleaseNodeStatus.RELEASED
                Lifecycle.DISCARDED -> ReleaseNodeStatus.DISCARDED
            }
    }
}
