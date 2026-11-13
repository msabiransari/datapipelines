package co.datapipelines.lake.append

import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineNodeRef
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Recipe-safe copies; a redacted body needs operator-supplied inputs before replay. */
data class RedactedRecipeInputs(
    val body: JsonNode,
    val parameters: JsonNode,
    val redactedPaths: List<String>,
) {
    val exactReplayPossible: Boolean get() = redactedPaths.isEmpty()
}

/**
 * Applies the owner's recipe-only opt-in. Does not mutate the execution body or binding values.
 * [childParameters] resolves the exact pinned child in the execution's workspace, never ambient context.
 * Keep that function last so trailing-lambda callers retain their binding.
 */
class RecipeRedactor(
    private val mapper: ObjectMapper = PipelineJson.objectMapper(),
    private val childParameters: (PipelineNodeRef) -> Map<String, Parameter>,
) {
    /** Resolved values are already in their canonical wire encoding, including explicit JSON nulls. */
    fun redact(
        pipeline: Pipeline,
        executedBody: JsonNode,
        resolvedParameters: Map<String, JsonNode>,
    ): RedactedRecipeInputs {
        require(mapper.valueToTree<JsonNode>(pipeline) == executedBody) {
            "Recipe body must be the executed pipeline's canonical body."
        }
        require(resolvedParameters.keys.containsAll(pipeline.parameters.keys)) {
            "Recipe inputs must include every resolved pipeline parameter."
        }
        val body = executedBody.deepCopy<ObjectNode>()
        val parameters = mapper.createObjectNode()
        val paths = mutableListOf<String>()
        pipeline.parameters.forEach { (name, declaration) ->
            if (declaration.redactInRecipe) {
                parameters.put(name, REDACTED)
                paths += "parameters.$name"
                val descriptor = body.path("parameters").path(name) as ObjectNode
                redactDeclaration(descriptor, name, paths)
            } else {
                parameters.set<JsonNode>(name, resolvedParameters.getValue(name).deepCopy())
            }
        }
        redactChildOverrides(pipeline, body, paths)
        return RedactedRecipeInputs(body, parameters, paths.sorted())
    }

    private fun redactDeclaration(
        descriptor: ObjectNode,
        name: String,
        paths: MutableList<String>,
    ) {
        // Constraints can repeat a sensitive default (for example a literal regex or bound).
        listOf("default", "constraints").forEach { field ->
            if (descriptor.has(field)) {
                descriptor.put(field, REDACTED)
                paths += "pipeline_body.parameters.$name.$field"
            }
        }
    }

    private fun redactChildOverrides(
        pipeline: Pipeline,
        body: ObjectNode,
        paths: MutableList<String>,
    ) {
        pipeline.nodes.forEachIndexed { index, node ->
            val supplied = node.parameters
            if (node.type == NodeType.PIPELINE && !supplied.isNullOrEmpty()) {
                val reference = requireNotNull(node.pipeline) { "A recipe child must have a pinned reference." }
                val declarations = childParameters(reference)
                val overrides = body.path("nodes").path(index).path("parameters") as ObjectNode
                supplied.keys.forEach { name ->
                    val declaration = requireNotNull(declarations[name]) { "Recipe child parameter metadata is missing." }
                    redactOverride(declaration, overrides, name, index, paths)
                }
            }
        }
    }

    private fun redactOverride(
        declaration: Parameter,
        overrides: ObjectNode,
        name: String,
        index: Int,
        paths: MutableList<String>,
    ) {
        if (declaration.redactInRecipe) {
            overrides.put(name, REDACTED)
            paths += "pipeline_body.nodes[$index].parameters.$name"
        }
    }

    private companion object {
        const val REDACTED = "<redacted>"
    }
}
