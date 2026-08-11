package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineVersionRecord
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The REST projection of a stored pipeline (rest-api.md §5): the portable body **plus** the
 * server-assigned fields (`id`, `version`, `owner`, `created_at`, `updated_at`).
 *
 * Built by merging the stored `body_json` tree with the metadata row rather than by binding a DTO:
 * the body is the frozen pipeline-contract shape, and re-spelling its forty fields here would make
 * this projection a second, drifting definition of it. The server fields are added last, so a body
 * key can never shadow them — `body_json` carries no server fields by construction
 * ([PipelineRecord]'s absent-field discipline), and the merge keeps it that way even against a
 * hand-written row.
 */
object PipelineResponses {
    private val MAPPER = PipelineJson.objectMapper()

    /** Full pipeline JSON: body fields with the server-assigned fields merged in (rest-api §5.2). */
    fun full(
        record: PipelineRecord,
        bodyJson: String,
    ): JsonNode {
        val tree = MAPPER.readTree(bodyJson)
        val body =
            tree as? ObjectNode
                // A stored body that is not an object cannot be merged; it also cannot have passed
                // save-time validation, so this is corruption, not a client error.
                ?: error("pipeline ${record.id} body is not a JSON object")
        return body
            .put("id", record.id.toString())
            .put("version", record.currentVersion)
            .put("owner", record.ownerId.toString())
            .put("created_at", record.createdAt.toString())
            .put("updated_at", record.updatedAt.toString())
    }

    /** One entry of the versions listing — metadata only, no body (rest-api §5.4). */
    fun versionSummary(version: PipelineVersionRecord): Map<String, Any?> =
        mapOf(
            "version" to version.version,
            "created_at" to version.createdAt.toString(),
            "created_by" to version.createdBy.toString(),
        )

    /** One entry of the pipelines listing (rest-api §5.7): metadata, not the body. */
    fun listEntry(record: PipelineRecord): Map<String, Any?> =
        mapOf(
            "id" to record.id.toString(),
            "name" to record.name,
            "display_name" to record.displayName,
            "description" to record.description,
            "owner" to record.ownerId.toString(),
            "version" to record.currentVersion,
            "created_at" to record.createdAt.toString(),
            "updated_at" to record.updatedAt.toString(),
        )
}
