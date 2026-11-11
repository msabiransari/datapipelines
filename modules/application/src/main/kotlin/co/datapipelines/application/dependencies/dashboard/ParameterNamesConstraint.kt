package co.datapipelines.application.dependencies.dashboard

import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.Edge
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Resolution
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.visualization.ArtifactValidationException
import co.datapipelines.visualization.DashboardErrorCodes

/**
 * A34 in the release planning path (#873 R3; record §6 dashboard → parameter set, F8 "G2/A34"): every edge of a walked
 * graph that carries [Constraint.ParameterNames] is evaluated against the EXACT parameter-set version its target node
 * resolved to, read through the registry's parameter-set provider in the walk's own [ResolveContext] — the same snapshot,
 * the same deadline and statement bound, ⌈n / `resolveBatchSize`⌉ batch reads, never one per edge.
 *
 * A bound name the set version does not declare is the dashboard family's NATIVE binding refusal,
 * `dashboard.validation.parameter_unbound` (400, reason `set_parameter_unknown` — the code `DashboardValidator` uses for
 * the same fact at save), with one failure per missing name located AT THE EDGE: `path` is the edge's source path and
 * `details.edge_id` its occurrence. A satisfied edge, a literal binding (no name) and a repeated valid binding (one name
 * once) add nothing (A34's three cases). An unresolved target is left to the blocker rules; the neutral walker and the
 * six diagnostic codes are untouched (A28). At most [maxFailures] failures are listed (the plan's `maxBlockers`).
 *
 * Messages are generic: the missing names are the dashboard author's own and travel only in `details`.
 */
class ParameterNamesConstraint(
    private val registry: FamilyRegistry,
    private val batchSize: Int,
    private val maxFailures: Int,
) {
    /**
     * Throws the native refusal naming every violated edge of [graph]; otherwise answers whether it read anything (false
     * for a graph with no constrained edge — the walk's deadline needs no second look then).
     */
    fun check(
        graph: GraphSnapshot,
        context: ResolveContext,
    ): Boolean {
        val constrained =
            graph.edges.mapNotNull { edge ->
                val names = edge.constraints.filterIsInstance<Constraint.ParameterNames>().flatMap { it.names }
                val target = graph.node(edge.toNodeId)
                if (names.isEmpty() || target.family != FamilyKey.PARAMETER_SET || target.resolution != Resolution.RESOLVED) {
                    null
                } else {
                    Constrained(edge, names, ObjectRequest(target.requestedAddress, edge.requestedSelector))
                }
            }
        if (constrained.isEmpty()) return false
        val sets = read(constrained.map { it.request }.distinct(), context)
        val failures =
            constrained.flatMap { (edge, names, request) ->
                val set = sets[request] ?: return@flatMap emptyList()
                DashboardReleaseProvider.violations(names, set).map { missing -> failure(edge, missing, request) }
            }
        if (failures.isNotEmpty()) {
            throw ArtifactValidationException(ValidationResult(failures.take(maxFailures)), DashboardErrorCodes.BODY_INVALID)
        }
        return true
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
            .flatMap { batch -> provider.resolveMany(batch, context).entries }
            .mapNotNull { (request, outcome) ->
                val stored = (outcome as? Outcome.Resolved<*>)?.body as? StoredVersion<*>
                (stored?.body as? ParameterSetBody)?.let { request to it }
            }.toMap()
    }

    private fun failure(
        edge: Edge,
        missing: String,
        request: ObjectRequest,
    ): ValidationFailure =
        ValidationFailure(
            DashboardErrorCodes.PARAMETER_UNBOUND,
            edge.sourcePath.orEmpty(),
            "A {parameter} binding names a parameter the pinned set version does not declare.",
            linkedMapOf(
                "reason" to SET_PARAMETER_UNKNOWN,
                "parameter" to missing,
                "edge_id" to edge.edgeId,
                "parameter_set" to linkedMapOf("name" to request.address.lookupKey, "version" to edge.resolvedVersion),
            ),
        )

    private data class Constrained(
        val edge: Edge,
        val names: List<String>,
        val request: ObjectRequest,
    )

    companion object {
        /** `DashboardValidator`'s reason for the same fact at save. */
        const val SET_PARAMETER_UNKNOWN = "set_parameter_unknown"
    }
}
