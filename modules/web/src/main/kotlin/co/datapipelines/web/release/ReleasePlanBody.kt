package co.datapipelines.web.release

import co.datapipelines.pipeline.release.CheckOverride
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseLimit
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.pipeline.release.ReleaseRefusals
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The release route's optional JSON body (record §6 "build choice"): a bounded, typed read of exactly the three plan
 * fields — `tree_hash`, `include_dependencies` (node ids) and `check_overrides` (`{node_id, reason}`). No body, an empty
 * body or `{}` is no plan, so a legacy request is unchanged. Anything else the client sends — another key, a status, a
 * family, an order — is refused, never ignored. Refusals name a field locator, never the submitted value.
 */
object ReleasePlanBody {
    private val MAPPER = ObjectMapper()
    private val FIELDS = setOf(TREE_HASH, INCLUDE, OVERRIDES)

    /** The plan [body] carries for a root of [family], or null when it carries none. */
    @Suppress("ThrowsCount") // a boundary maps each distinct malformation to its own field locator
    fun parse(
        body: String?,
        family: ReleaseFamily,
        bounds: ReleaseBounds,
    ): ReleasePlanRequest? {
        if (body.isNullOrBlank()) return null
        if (body.toByteArray(Charsets.UTF_8).size > bounds.maxWireBytes) {
            throw ReleaseRefusals.limit(family, ReleaseLimit.WIRE_BYTES, bounds.maxWireBytes)
        }
        val tree =
            runCatching { MAPPER.readTree(body) }.getOrNull() as? ObjectNode
                ?: throw ReleaseRefusals.planInvalid(family, "body", "the body is not a JSON object")
        if (!FIELDS.containsAll(tree.fieldNames().asSequence().toList())) {
            throw ReleaseRefusals.planInvalid(family, "body", "the body carries a field the plan does not have")
        }
        return ReleasePlanRequest(
            treeHash = tree.get(TREE_HASH)?.let { text(it, family, TREE_HASH) },
            includeDependencies =
                tree.get(INCLUDE)?.let { node ->
                    array(node, family, INCLUDE).mapIndexed { i, entry -> text(entry, family, "$INCLUDE[$i]") }
                },
            checkOverrides =
                tree.get(OVERRIDES)?.let { node ->
                    array(node, family, OVERRIDES).mapIndexed { i, entry -> override(entry, family, "$OVERRIDES[$i]") }
                },
        )
    }

    private fun text(
        node: JsonNode,
        family: ReleaseFamily,
        field: String,
    ): String = node.takeIf { it.isTextual }?.asText() ?: throw ReleaseRefusals.planInvalid(family, field, "expected a string")

    private fun array(
        node: JsonNode,
        family: ReleaseFamily,
        field: String,
    ): List<JsonNode> = node.takeIf { it.isArray }?.toList() ?: throw ReleaseRefusals.planInvalid(family, field, "expected an array")

    @Suppress("ThrowsCount") // a boundary maps each distinct malformation to its own field locator
    private fun override(
        node: JsonNode,
        family: ReleaseFamily,
        field: String,
    ): CheckOverride {
        val entry = node as? ObjectNode ?: throw ReleaseRefusals.planInvalid(family, field, "expected an object")
        if (!OVERRIDE_FIELDS.containsAll(entry.fieldNames().asSequence().toList())) {
            throw ReleaseRefusals.planInvalid(family, field, "an override carries a field it does not have")
        }
        val nodeId = entry.get("node_id") ?: throw ReleaseRefusals.planInvalid(family, "$field.node_id", "required")
        val reason = entry.get("reason") ?: throw ReleaseRefusals.planInvalid(family, "$field.reason", "required")
        return CheckOverride(text(nodeId, family, "$field.node_id"), text(reason, family, "$field.reason"))
    }

    private const val TREE_HASH = "tree_hash"
    private const val INCLUDE = "include_dependencies"
    private const val OVERRIDES = "check_overrides"
    private val OVERRIDE_FIELDS = setOf("node_id", "reason")
}
