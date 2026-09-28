package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The REST projection of a stored parameter set (rest-api.md §21): the §3 document **plus** the
 * server-assigned fields (`id`, `version`, `status`, `body_hash`) and the set's draft pointer
 * when one exists (versioning §7) — the `PipelineResponses.full` shape, built the same way: the
 * stored document merged with the metadata row, never a re-spelled DTO, so the strict
 * `ParameterSetJson` binding stays the one definition of the body on the wire.
 */
object ParameterSetResponses {
    private val MAPPER = ParameterSetJson.mapper

    /**
     * Full parameter-set JSON: the document's body fields with the server-assigned and lifecycle
     * fields merged in. [version] supplies `version`/`status`/`body_hash` for the row whose body
     * is returned (the WORKING version — the §7 read rule); [draft] the §7 draft pointer,
     * omitted when none exists. `current_version` always names the latest RELEASED version.
     */
    fun full(
        record: ParameterSetRecord,
        body: ParameterSetBody,
        version: ParameterSetVersionDetail? = null,
        draft: ParameterSetVersionDetail? = null,
    ): JsonNode {
        val node = MAPPER.valueToTree<JsonNode>(body) as ObjectNode
        node
            .put("id", record.id.toString())
            .put("name", record.name)
            .put("version", version?.version ?: record.currentVersion)
            .put("created_at", record.createdAt.toString())
            .put("updated_at", record.updatedAt.toString())
            .put("current_version", record.currentVersion)
        version?.let {
            node.put("status", it.status.name)
            node.put("body_hash", it.bodyHash)
        }
        draft?.let {
            val pointer = node.putObject("draft")
            pointer.put("version", it.version)
            pointer.put("body_hash", it.bodyHash)
            pointer.put("updated_by", it.updatedBy?.toString() ?: "")
            pointer.put("updated_at", it.updatedAt?.toString() ?: "")
        }
        return node
    }

    /** One entry of the versions listing — metadata only, no body (the pipelines §5.4 shape). */
    fun versionSummary(version: ParameterSetVersionDetail): Map<String, Any?> =
        mapOf(
            "version" to version.version,
            "status" to version.status.name,
            "body_hash" to version.bodyHash,
            "created_at" to version.createdAt.toString(),
            "created_by" to version.createdBy.toString(),
            "released_at" to (version.releasedAt?.toString() ?: ""),
        )

    /**
     * One entry of the listing/browse: metadata, not the body. `version`/`status` name the
     * WORKING version — the draft's when one exists, else the current release (the §5.7 shape).
     */
    fun listEntry(
        loaded: ParameterSetVersion,
        draft: ParameterSetVersionDetail? = null,
    ): Map<String, Any?> =
        mapOf(
            "id" to loaded.record.id.toString(),
            "name" to loaded.record.name,
            "display_name" to loaded.record.displayName,
            "description" to loaded.record.description,
            "version" to (draft?.version ?: loaded.detail.version),
            "status" to
                when {
                    draft != null -> PipelineVersionStatus.DRAFT.name
                    loaded.detail.status == PipelineVersionStatus.RELEASED -> PipelineVersionStatus.RELEASED.name
                    else -> null
                },
            "created_at" to loaded.record.createdAt.toString(),
            "updated_at" to loaded.record.updatedAt.toString(),
        )
}
