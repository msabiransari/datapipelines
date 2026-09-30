package co.datapipelines.application.dashboards

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ActionScope
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardErrorCodes
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * What a refresh will run (the implementation spec's §9 steps 3–5), decided from the pinned dashboard body, the
 * requested scope and the evaluated parameter values — a PURE function, so the sharing rule is provable without a
 * database, a stream or an executor.
 *
 * ## Sharing (the record's §5.2)
 * Two sources whose pinned pipeline release AND resolved parameters — after the outgoing overrides (D23) — are
 * identical are ONE invocation: one execution, one collected table, fed to every consumer. The identity is the
 * canonical text of (pipeline name, version, parameters with object keys sorted), so `{a:1,b:2}` and `{b:2,a:1}`
 * share and `1` and `"1"` do not. Sharing is within ONE refresh: a plan never aliases anything across refreshes.
 *
 * ## The closure
 * Scope `all` targets every visualization occurrence; `targets` exactly the named ones. A source runs only if a
 * target reads it — the closure is over `inputs[*].source`. A visualization with no inputs needs no source.
 */
class RefreshPlanner {
    /**
     * Plans a refresh.
     *
     * @param values every set parameter's evaluated value as the pipeline binder will read it (wire-encoded); a
     *   parameter with no value is absent or a JSON null, and a binding to it is OMITTED (the pipeline's default, or
     *   its own `required` refusal, applies).
     * @throws DatapipelinesException `dashboard.validation.empty_targets` / `target_not_visualization` for a
     *   request naming no target or a name that is no visualization occurrence.
     */
    fun plan(
        body: DashboardBody,
        scope: ActionScope,
        requestedTargets: List<String>,
        values: Map<String, JsonNode?>,
    ): RefreshPlan {
        val occurrences = body.visualizations.associateBy { it.name }
        val targets = targetsOf(body, scope, requestedTargets, occurrences.keys)
        val sourcesOfTarget =
            targets.associateWith { target ->
                occurrences
                    .getValue(target)
                    .inputs.values
                    .map { it.source }
                    .distinct()
            }
        val neededSources = body.sources.filter { source -> sourcesOfTarget.values.any { source.name in it } }
        val bySource = neededSources.associate { it.name to resolvedParameters(body, it.name, values) }
        val invocations = LinkedHashMap<String, MutableList<String>>()
        neededSources.forEach { source ->
            invocations.getOrPut(identity(source.pipeline, bySource.getValue(source.name))) { mutableListOf() }.add(source.name)
        }
        val planned =
            invocations.map { (id, sources) ->
                val first = neededSources.first { it.name == sources.first() }
                Invocation(
                    id = id,
                    pipeline = first.pipeline,
                    parameters = bySource.getValue(first.name),
                    sources = sources,
                    consumers = targets.filter { target -> sourcesOfTarget.getValue(target).any { it in sources } },
                )
            }
        return RefreshPlan(
            targets = targets,
            invocations = planned,
            invocationOfSource = planned.flatMap { inv -> inv.sources.map { it to inv.id } }.toMap(),
            sourcesOfTarget = sourcesOfTarget,
        )
    }

    private fun targetsOf(
        body: DashboardBody,
        scope: ActionScope,
        requested: List<String>,
        known: Set<String>,
    ): List<String> {
        if (scope == ActionScope.ALL) return body.visualizations.map { it.name }
        if (requested.isEmpty()) {
            throw DatapipelinesException(
                code = DashboardErrorCodes.EMPTY_TARGETS,
                message = "A refresh with scope 'targets' must name at least one visualization.",
                details = mapOf("field" to "targets"),
            )
        }
        val unknown = requested.firstOrNull { it !in known }
        if (unknown != null) {
            throw DatapipelinesException(
                code = DashboardErrorCodes.TARGET_NOT_VISUALIZATION,
                message = "A refresh target must be a visualization occurrence of this dashboard.",
                details = mapOf("field" to "targets", "name" to unknown.take(MAX_ECHO)),
            )
        }
        val wanted = requested.toSet()
        return body.visualizations.map { it.name }.filter { it in wanted }
    }

    /** The parameters [sourceName] launches with: its bindings, then the outgoing overrides (a literal wins, D23). */
    private fun resolvedParameters(
        body: DashboardBody,
        sourceName: String,
        values: Map<String, JsonNode?>,
    ): Map<String, JsonNode> {
        val source = body.sources.first { it.name == sourceName }
        val resolved = LinkedHashMap<String, JsonNode>()
        source.parameters.forEach { (pipelineParameter, binding) ->
            val value = binding.parameter?.let { values[it] } ?: binding.value
            if (value != null && !value.isNull && !value.isMissingNode) resolved[pipelineParameter] = value
        }
        body.outgoingOverrides[sourceName]?.forEach { (pipelineParameter, literal) -> resolved[pipelineParameter] = literal.value }
        return resolved
    }

    private fun identity(
        pipeline: ArtifactRef,
        parameters: Map<String, JsonNode>,
    ): String {
        val canonical = sortedObject(parameters)
        return "${pipeline.name}@${pipeline.version}#" + CANONICAL.writeValueAsString(canonical)
    }

    private fun sortedObject(parameters: Map<String, JsonNode>): ObjectNode =
        CANONICAL.createObjectNode().also { out ->
            parameters.toSortedMap().forEach { (key, value) -> out.set<JsonNode>(key, sorted(value)) }
        }

    private fun sorted(node: JsonNode): JsonNode =
        when {
            node.isObject -> {
                CANONICAL.createObjectNode().also { out ->
                    node.properties().sortedBy { it.key }.forEach { out.set<JsonNode>(it.key, sorted(it.value)) }
                }
            }

            node.isArray -> {
                CANONICAL.createArrayNode().also { out -> node.forEach { out.add(sorted(it)) } }
            }

            else -> {
                node
            }
        }

    private companion object {
        const val MAX_ECHO = 64
        val CANONICAL = ObjectMapper()
    }
}

/** One distinct execution of a refresh: a pinned pipeline release with its resolved parameters, and who reads it. */
data class Invocation(
    /** The canonical identity — pipeline name, version and the sorted parameters. */
    val id: String,
    val pipeline: ArtifactRef,
    val parameters: Map<String, JsonNode>,
    /** The source names this one execution serves, in dashboard order. */
    val sources: List<String>,
    /** The targets that read any of [sources], in dashboard order. */
    val consumers: List<String>,
) {
    /** `dashboard_refresh_executions.shared`: the execution served more than one target. */
    val shared: Boolean get() = consumers.size > 1
}

/** A refresh's plan: the targets, the distinct executions, and which source each target reads through which. */
data class RefreshPlan(
    val targets: List<String>,
    val invocations: List<Invocation>,
    val invocationOfSource: Map<String, String>,
    val sourcesOfTarget: Map<String, List<String>>,
) {
    /** The invocation serving [source]. */
    fun invocationFor(source: String): Invocation = invocations.first { it.id == invocationOfSource.getValue(source) }
}
