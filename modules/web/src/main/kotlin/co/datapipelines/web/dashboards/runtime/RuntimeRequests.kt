package co.datapipelines.web.dashboards.runtime

import co.datapipelines.visualization.ActionScope
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.visualizations.ArtifactFamily
import co.datapipelines.web.visualizations.ArtifactHttp
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/** `POST /runtime/parameters` — the parameter evaluation for a client instance (spec §8.2). */
internal data class ParametersRequest(
    val configurationId: String,
    val instanceId: UUID,
    val selections: Map<String, JsonNode?>,
    val intent: Intent,
) {
    enum class Intent(
        val wire: String,
    ) {
        BOOTSTRAP("bootstrap"),
        PARENT_CHANGE("parent_change"),
        RETRY("retry"),
    }
}

/** `POST /runtime/visualizations` — one refresh (spec §8.3). [selectionsJson] is the text stored on the row. */
internal data class RefreshRequest(
    val configurationId: String,
    val instanceId: UUID,
    val refreshId: UUID,
    val parameterRevision: Int,
    val selections: Map<String, JsonNode?>,
    val selectionsJson: String,
    val scope: ActionScope,
    val targets: List<String>,
)

/** `POST /runtime/refreshes/{refresh_id}/abort` — `{ instance_id }` (spec §8.4). */
internal data class AbortRequest(
    val instanceId: UUID,
)

/**
 * Reads the three runtime bodies. A body is judged WHOLE before anything is looked up (the #300 rule: a malformed
 * body is the family's 400 whatever the dashboard id says, and reveals nothing about which dashboards exist) and every
 * refusal is `dashboard.validation.body_invalid` naming the offending field's PATH and a REASON — never its value.
 *
 * Bounds: the platform's request cap ran first; `selections` is additionally held to the 64 KiB the
 * `dashboard_refreshes.selections_json` CHECK stores, and `targets` to one entry per possible occurrence. A refresh id is
 * a v4 UUID in its canonical spelling — the client mints it, so a caller cannot make the server accept another's shape.
 */
internal object RuntimeRequests {
    /** The stored selections cap (V43's CHECK on `selections_json`). */
    const val MAX_SELECTIONS_BYTES = 65_536

    /** More targets than any dashboard has occurrences (`max-visualizations-per-dashboard` tops out at 500). */
    const val MAX_TARGETS = 500

    /** The version nibble of a client-minted id: a random (v4) UUID, the only kind the refresh id may be. */
    private const val UUID_RANDOM_VERSION = 4

    fun parameters(body: String): ParametersRequest {
        val tree = tree(body)
        val intent =
            ParametersRequest.Intent.entries.firstOrNull { it.wire == text(tree, "intent") }
                ?: throw bad("intent", UNKNOWN_VALUE)
        return ParametersRequest(text(tree, "configuration_id"), uuid(tree, "instance_id"), selections(tree).first, intent)
    }

    @Suppress("ThrowsCount") // one refusal per field, each naming the field and the reason
    fun refresh(body: String): RefreshRequest {
        val tree = tree(body)
        val revisionNode = tree.get("parameter_revision")
        if (revisionNode == null || revisionNode.isNull) throw bad("parameter_revision", MISSING)
        if (!revisionNode.isInt || revisionNode.asInt() < 0) throw bad("parameter_revision", WRONG_TYPE)
        val scope = ActionScope.entries.firstOrNull { it.wire == text(tree, "scope") } ?: throw bad("scope", UNKNOWN_VALUE)
        val (selections, selectionsJson) = selections(tree)
        return RefreshRequest(
            configurationId = text(tree, "configuration_id"),
            instanceId = uuid(tree, "instance_id"),
            refreshId = uuidV4(tree, "refresh_id"),
            parameterRevision = revisionNode.asInt(),
            selections = selections,
            selectionsJson = selectionsJson,
            scope = scope,
            targets = targets(tree, scope),
        )
    }

    fun abort(body: String): AbortRequest = AbortRequest(uuid(tree(body), "instance_id"))

    private fun tree(body: String): ObjectNode =
        ArtifactHttp.readTree(ArtifactFamily.DASHBOARD, body) as? ObjectNode ?: throw bad("$", WRONG_TYPE)

    private fun text(
        tree: ObjectNode,
        field: String,
    ): String {
        val node = tree.get(field)
        if (node == null || node.isNull) throw bad(field, MISSING)
        if (!node.isTextual) throw bad(field, WRONG_TYPE)
        return node.asText()
    }

    private fun uuid(
        tree: ObjectNode,
        field: String,
    ): UUID = parseUuid(text(tree, field), field)

    private fun uuidV4(
        tree: ObjectNode,
        field: String,
    ): UUID {
        val text = text(tree, field)
        val parsed = parseUuid(text, field)
        if (parsed.version() != UUID_RANDOM_VERSION || parsed.toString() != text) throw bad(field, MALFORMED)
        return parsed
    }

    private fun parseUuid(
        text: String,
        field: String,
    ): UUID = runCatching { UUID.fromString(text) }.getOrElse { throw bad(field, MALFORMED) }

    /** `selections`: an object (absent or null is `{}`), at most [MAX_SELECTIONS_BYTES] as stored. */
    private fun selections(tree: ObjectNode): Pair<Map<String, JsonNode?>, String> {
        val node = tree.get("selections")
        if (node == null || node.isNull) return emptyMap<String, JsonNode?>() to "{}"
        if (!node.isObject) throw bad("selections", WRONG_TYPE)
        val json = node.toString()
        if (json.toByteArray(Charsets.UTF_8).size > MAX_SELECTIONS_BYTES) throw bad("selections", TOO_LARGE)
        return node.properties().associate { it.key to it.value } to json
    }

    @Suppress("ThrowsCount") // one refusal per reason
    private fun targets(
        tree: ObjectNode,
        scope: ActionScope,
    ): List<String> {
        val node = tree.get("targets")
        if (node == null || node.isNull) return emptyList()
        if (!node.isArray) throw bad("targets", WRONG_TYPE)
        if (node.size() > MAX_TARGETS) throw bad("targets", TOO_LARGE)
        if (scope == ActionScope.ALL && node.size() > 0) throw bad("targets", UNEXPECTED)
        return node.map { if (it.isTextual) it.asText() else throw bad("targets", WRONG_TYPE) }
    }

    /** The family's `body_invalid` for [path] with [reason] — the field's name, never its value. */
    fun bad(
        path: String,
        reason: String,
    ): ApiException =
        ApiException(
            DashboardErrorCodes.BODY_INVALID,
            "The request's '$path' is not acceptable.",
            mapOf("path" to path, "reason" to reason),
        )

    const val MISSING = "missing"
    const val WRONG_TYPE = "wrong_type"
    const val MALFORMED = "malformed"
    const val TOO_LARGE = "too_large"
    const val UNKNOWN_VALUE = "unknown_value"
    const val UNEXPECTED = "unexpected"
    const val REUSED = "reused"
}
