package co.datapipelines.web.parameters.stream

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.RequestBodies
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/** `POST /api/v1/parameter-sets/{id}/evaluations` — one observed evaluation (the parameter-set workspace spec §4.1). */
internal data class ObservedEvaluationRequest(
    /** REQUIRED here (the page is version-explicit); the ordinary route keeps its served default. */
    val version: Int,
    val selections: Map<String, JsonNode?>,
    /** Client-minted, fresh per attempt (the owner's §11.3 ruling) — every frame of the stream carries it. */
    val evaluationId: UUID,
    /** The page instance, for diagnostics only — the server keeps no per-instance state. */
    val instanceId: UUID,
)

/**
 * Reads the observed evaluation's body — [co.datapipelines.web.dashboards.runtime.RuntimeRequests]' twin. The body is
 * judged WHOLE before anything is looked up (the #300 rule: a malformed body is the family's 400 whatever the set id
 * says, and reveals nothing about which sets exist); every refusal is `parameter.validation.body_invalid` naming the
 * offending field's `details.path` and a `details.reason` — never its value.
 *
 * Bounds: the platform's 2 MiB pre-read filter ran first; the body is then refused over [MAX_EVALUATE_REQUEST_BYTES]
 * BEFORE its JSON is parsed — the same pre-parse bound as the ordinary evaluate (a parity test pins the two equal; one
 * number, never a third). The ids are v4 UUIDs in their canonical spelling: the client mints them, so a caller cannot
 * make the server accept another shape.
 */
internal object ParameterEvaluationRequests {
    /** The ordinary evaluate's pre-parse bound (`ParameterSetsController`'s; `ParameterEvaluationRequestsTest` pins parity). */
    const val MAX_EVALUATE_REQUEST_BYTES = 1_048_576

    /** The version nibble of a client-minted id: a random (v4) UUID, the only kind the ids may be. */
    private const val UUID_RANDOM_VERSION = 4

    /** The module's mapper under the request limits (`RequestLimits.requestMapper`, #291) — the ordinary evaluate's reader. */
    private val REQUEST_MAPPER = RequestLimits.requestMapper(ParameterSetJson.mapper)

    @Suppress("ThrowsCount") // one refusal per field, each naming the field and the reason
    fun read(body: String): ObservedEvaluationRequest {
        refuseOversized(body)
        val tree =
            RequestBodies.readTree(REQUEST_MAPPER, body, ApiErrors::malformedParameterSetBody) as? ObjectNode
                ?: throw bad("$", ApiErrors.REASON_WRONG_TYPE)
        val versionNode = tree.get("version")
        if (versionNode == null || versionNode.isNull) throw bad("version", ApiErrors.REASON_MISSING)
        if (!versionNode.isInt) throw bad("version", ApiErrors.REASON_WRONG_TYPE)
        val selectionsNode = tree.get("selections")
        val selections: Map<String, JsonNode?> =
            when {
                selectionsNode == null || selectionsNode.isNull -> emptyMap()
                selectionsNode.isObject -> selectionsNode.properties().associate { it.key to it.value }
                else -> throw bad("selections", ApiErrors.REASON_WRONG_TYPE)
            }
        return ObservedEvaluationRequest(
            version = versionNode.asInt(),
            selections = selections,
            evaluationId = uuidV4(tree, "evaluation_id"),
            instanceId = uuidV4(tree, "instance_id"),
        )
    }

    private fun refuseOversized(body: String) {
        if (body.toByteArray(Charsets.UTF_8).size <= MAX_EVALUATE_REQUEST_BYTES) return
        throw ApiException(
            PipelineErrorCodes.Request.BODY_TOO_LARGE,
            "The evaluate request body exceeds $MAX_EVALUATE_REQUEST_BYTES bytes; refused before its JSON is parsed. " +
                "The largest legal selections document for any set is far smaller.",
            mapOf("limit_bytes" to MAX_EVALUATE_REQUEST_BYTES),
        )
    }

    private fun uuidV4(
        tree: ObjectNode,
        field: String,
    ): UUID {
        val node = tree.get(field)
        val reason =
            when {
                node == null || node.isNull -> ApiErrors.REASON_MISSING
                !node.isTextual -> ApiErrors.REASON_WRONG_TYPE
                else -> null
            }
        if (reason != null) throw bad(field, reason)
        val text = node.asText()
        val parsed = runCatching { UUID.fromString(text) }.getOrNull()
        if (parsed == null || parsed.version() != UUID_RANDOM_VERSION || parsed.toString() != text) throw bad(field, MALFORMED)
        return parsed
    }

    /** The family's `body_invalid` for [path] with [reason] — the field's name, never its value. */
    fun bad(
        path: String,
        reason: String,
    ): ApiException =
        when (reason) {
            ApiErrors.REASON_MISSING, ApiErrors.REASON_WRONG_TYPE -> {
                ApiErrors.parameterSetBodyInvalid(path, reason)
            }

            else -> {
                ApiException(
                    ParameterErrorCodes.BODY_INVALID,
                    "The request's '$path' is not acceptable.",
                    mapOf(
                        "path" to path,
                        "reason" to reason,
                    ),
                )
            }
        }

    /** Not a v4 UUID in its canonical spelling (the dashboards runtime's word). */
    const val MALFORMED = "malformed"

    /** An `evaluation_id` already open on this instance (#375 D5; the dashboards runtime's word). */
    const val REUSED = "reused"
}
