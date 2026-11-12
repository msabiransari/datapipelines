package co.datapipelines.web.dependencies

import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.application.dependencies.dashboard.DashboardReleaseProvider
import co.datapipelines.application.dependencies.parameterset.ParameterSetReleaseBody
import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Resolution
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.parameters.ParameterSetBody

/**
 * The tree's `unmet_names` (#892; record §11.1 `parameter_names` row and §12, Draft 10 erratum (11); acceptance A34's
 * read-side display): for every `parameter_names` constraint of a walked [GraphSnapshot], the names the reached set version
 * does not declare. The tree routes refuse nothing for it — an unmet constraint is a fact about a fully read edge and never
 * changes `complete`, `acyclic` or `diagnostics`.
 *
 * One stage, run by [DependencyTreeService] inside the tree's read transaction, after the walk and before the projection:
 * 1. only an edge whose TARGET the walk resolved, in the parameter-set family, is evaluated — a DISCARDED set is resolved and
 *    is evaluated; a MISSING or UNSUPPORTED one is not read again (§12: nothing about an object the caller cannot read);
 * 2. the targets are deduplicated and read through the registry's parameter-set provider exactly as the release path's
 *    `ParameterNamesConstraint.read` reads them — at most `resolveBatchSize` requests per `resolveMany`, under the walk's
 *    OWN [ResolveContext] (same workspace authority, deadline and per-statement bound);
 * 3. each constraint is judged by [DashboardReleaseProvider.violations] — the one name comparison — and only a non-empty
 *    answer is kept.
 *
 * The answer describes the set as THIS read saw it: under READ COMMITTED an open draft can change between the walk and
 * this read, and the next request converges; a target that no longer resolves here yields no property. A provider failure
 * propagates, and an expired deadline is [DeadlineExceeded] before a batch — the service maps both to the same
 * `dependencies.metadata.unavailable` as the walk's own. No set body leaves this class.
 */
class UnmetParameterNames(
    private val registry: FamilyRegistry,
    private val batchSize: Int,
) {
    init {
        require(batchSize >= 1) { "a resolve batch holds at least one request, got $batchSize" }
    }

    /** The unmet names of every evaluated constraint of [graph]; [Unmet.NONE] when no constraint is unmet. */
    fun evaluate(
        graph: GraphSnapshot,
        context: ResolveContext,
    ): Unmet {
        val constrained =
            graph.edges.flatMap { edge ->
                val target = graph.node(edge.toNodeId)
                if (target.family != FamilyKey.PARAMETER_SET || target.resolution != Resolution.RESOLVED) {
                    emptyList()
                } else {
                    val request = ObjectRequest(target.requestedAddress, edge.requestedSelector)
                    edge.constraints.filterIsInstance<Constraint.ParameterNames>().map { Constrained(edge.edgeId, it, request) }
                }
            }
        if (constrained.isEmpty()) return Unmet.NONE
        val sets = read(constrained.map { it.request }.distinct(), context)
        val unmet =
            constrained.mapNotNull { (edgeId, constraint, request) ->
                val set = sets[request] ?: return@mapNotNull null
                DashboardReleaseProvider
                    .violations(constraint.names, set)
                    .takeIf { it.isNotEmpty() }
                    ?.let { (edgeId to constraint) to it }
            }
        return if (unmet.isEmpty()) Unmet.NONE else Unmet(unmet.toMap())
    }

    private fun read(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, ParameterSetBody> {
        val descriptor = registry[FamilyKey.PARAMETER_SET] ?: return emptyMap()

        @Suppress("UNCHECKED_CAST") // a registry descriptor's provider reads its own body type; the body is checked below
        val provider = descriptor.provider as DependencyProvider<Any>
        return requests
            .chunked(batchSize)
            .flatMap { batch ->
                // The walker's rule: no batch starts once the walk's cumulative deadline has passed (§25.4).
                if (context.deadline.expired()) throw DeadlineExceeded(FamilyKey.PARAMETER_SET)
                provider.resolveMany(batch, context).entries
            }.mapNotNull { (request, outcome) ->
                val stored = (outcome as? Outcome.Resolved<*>)?.body as? StoredVersion<*>
                (stored?.body as? ParameterSetReleaseBody)?.let { request to it.set }
            }.toMap()
    }

    private data class Constrained(
        val edgeId: String,
        val constraint: Constraint.ParameterNames,
        val request: ObjectRequest,
    )

    /**
     * The request-local marks the projection writes as `unmet_names`, by edge id and constraint — the snapshot and the
     * domain [Constraint] are never changed (the internal fingerprint and the release `tree_hash` read them).
     */
    class Unmet internal constructor(
        private val byConstraint: Map<Pair<String, Constraint.ParameterNames>, List<String>>,
    ) {
        /** The unmet names of [constraint] on edge [edgeId] — non-empty, in `names`' order — or null: write no property. */
        fun of(
            edgeId: String,
            constraint: Constraint.ParameterNames,
        ): List<String>? = byConstraint[edgeId to constraint]

        companion object {
            /** No constraint is unmet: the projection writes the payload #875 wrote. */
            val NONE = Unmet(emptyMap())
        }
    }
}
