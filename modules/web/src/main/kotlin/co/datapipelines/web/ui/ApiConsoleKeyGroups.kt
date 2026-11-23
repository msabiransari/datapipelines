package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBinding
import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.endpoints.EndpointPath.Segment
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.web.ui.ApiConsoleController.KeyGroup

/** Groups the workspace's named grants by their applicability to a published route. */
internal class ApiConsoleKeyGroups(
    bindings: List<EndpointKeyBinding>,
    private val keyNames: Map<String, String>,
) {
    private val getBindings = bindings.filter { it.method == EndpointMethod.GET }.groupBy { it.pathPrefix }
    private val mutationGrants = bindings.filter { it.method.isMutation }.groupBy { it.endpointId to it.method }
    private val concretePathsByVersion =
        getBindings.keys
            .mapNotNull { path -> EndpointPath.parseTreeNode(path).getOrNull()?.takeIf { it.pattern == path } }
            .filter { it.segments.size > VERSION_DEPTH && it.variableNames.isEmpty() }
            .groupBy { it.segments.take(VERSION_DEPTH) }

    fun forEndpoint(endpoint: PublishedEndpoint): List<KeyGroup> =
        if (endpoint.method == EndpointMethod.GET) {
            forGet(endpoint.parsed)
        } else {
            listOfNotNull(group(mutationGrants[endpoint.id to endpoint.method].orEmpty(), label = null))
        }

    private fun forGet(route: EndpointPath.Parsed): List<KeyGroup> {
        // The literal stem includes the route itself when it has no parameters.
        val commonPath = EndpointPath.ancestors(route.pattern.substringBefore("/{")).firstOrNull(getBindings::containsKey)
        val scoped = if (route.variableNames.isEmpty()) emptyList() else scopedBindings(route)
        val groups =
            scoped.mapNotNull { (values, path) ->
                val condition = values.entries.joinToString(", ") { (name, value) -> "$name = $value" }
                group(getBindings.getValue(path.pattern), "$condition · bound at ${EndpointPath.API_ROOT}${path.pattern}")
            }
        val fallback =
            commonPath?.let { path ->
                val source = "${EndpointPath.API_ROOT}$path"
                val label =
                    when {
                        path == route.pattern -> null
                        scoped.isNotEmpty() -> "Other values · inherited from $source"
                        else -> "Inherited from $source"
                    }
                group(getBindings.getValue(path), label)
            }
        return groups + listOfNotNull(fallback)
    }

    /**
     * A concrete prefix matches a route subset when its literals agree at every fixed position.
     * Prefixes that fix the same parameter values cover the same subset, so keep its deepest
     * binding. More-specific subsets come first to show which binding wins when conditions overlap.
     */
    private fun scopedBindings(route: EndpointPath.Parsed): List<Pair<Map<String, String>, EndpointPath.Parsed>> =
        concretePathsByVersion[route.segments.take(VERSION_DEPTH)]
            .orEmpty()
            .mapNotNull { path ->
                parameterValues(route, path)?.takeIf { it.isNotEmpty() }?.let { it to path }
            }.groupBy({ it.first }, { it.second })
            .map { (values, paths) -> values to paths.maxBy { it.segments.size } }
            .sortedWith(
                compareByDescending<Pair<Map<String, String>, EndpointPath.Parsed>> { it.second.segments.size }
                    .thenBy { it.second.pattern },
            )

    private fun parameterValues(
        route: EndpointPath.Parsed,
        concrete: EndpointPath.Parsed,
    ): Map<String, String>? {
        if (concrete.segments.size > route.segments.size) return null
        val values = linkedMapOf<String, String>()
        concrete.segments.forEachIndexed { index, segment ->
            val value = (segment as Segment.Literal).value
            when (val part = route.segments[index]) {
                is Segment.Literal -> if (part.value != value) return null
                is Segment.Variable -> values[part.name] = value
            }
        }
        return values
    }

    private fun group(
        bindings: List<EndpointKeyBinding>,
        label: String?,
    ): KeyGroup? {
        val names = bindings.mapNotNull { keyNames[it.apiKeyId] }.sorted()
        return names.takeIf { it.isNotEmpty() }?.let { KeyGroup(it, label) }
    }

    private companion object {
        const val VERSION_DEPTH = EndpointPath.VERSION_INDEX + 1
    }
}
