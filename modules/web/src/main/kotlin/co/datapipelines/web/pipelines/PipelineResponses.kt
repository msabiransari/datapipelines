package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionRecord
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The REST projection of a stored pipeline (rest-api.md §5): the portable body **plus** the
 * server-assigned fields (`id`, `version`, `owner`, `created_at`, `updated_at`) and, since
 * the version lifecycle, the version's lifecycle state (`status`, `body_hash`) and the
 * pipeline's draft pointer when one exists (versioning §7).
 *
 * Built by merging the stored `body_json` tree with the metadata row rather than by binding a DTO:
 * the body is the frozen pipeline-contract shape, and re-spelling its forty fields here would make
 * this projection a second, drifting definition of it. The server fields are added last, so a body
 * key can never shadow them — `body_json` carries no server fields by construction
 * ([PipelineRecord]'s absent-field discipline), and the merge keeps it that way even against a
 * hand-written row.
 *
 * `version` names the version WHOSE BODY is being returned — the released version for a GET,
 * the draft for a PUT's response — while `current_version` always names the latest RELEASED
 * version (versioning §3.4: it does not move while a draft exists). A caller about to mutate
 * echoes back the response's `body_hash` as its precondition, whatever row that hash belongs to.
 */
object PipelineResponses {
    private val MAPPER = PipelineJson.objectMapper()

    /**
     * Full pipeline JSON: body fields with the server-assigned and lifecycle fields merged in
     * (rest-api §5.2). [version] supplies `status`/`body_hash`/`version` for the row whose
     * body [bodyJson] is; [draft] the §7 draft pointer, omitted (null) when none exists.
     */
    fun full(
        record: PipelineRecord,
        bodyJson: String,
        version: PipelineVersionDetail? = null,
        draft: PipelineVersionDetail? = null,
    ): JsonNode {
        val tree = MAPPER.readTree(bodyJson)
        val body =
            tree as? ObjectNode
                // A stored body that is not an object cannot be merged; it also cannot have passed
                // save-time validation, so this is corruption, not a client error.
                ?: error("pipeline ${record.id} body is not a JSON object")
        body
            .put("id", record.id.toString())
            .put("version", version?.version ?: record.currentVersion)
            .put("owner", record.ownerId.toString())
            .put("created_at", record.createdAt.toString())
            .put("updated_at", record.updatedAt.toString())
            .put("current_version", record.currentVersion)
        version?.let {
            body.put("status", it.status.name)
            body.put("body_hash", it.bodyHash)
        }
        draft?.let {
            val pointer = body.putObject("draft")
            pointer.put("version", it.version)
            pointer.put("body_hash", it.bodyHash)
            pointer.put("updated_by", it.updatedBy?.toString() ?: "")
            pointer.put("updated_at", it.updatedAt?.toString() ?: "")
        }
        return body
    }

    /** One entry of the versions listing — metadata only, no body (rest-api §5.4). */
    fun versionSummary(version: PipelineVersionRecord): Map<String, Any?> =
        mapOf<String, Any?>(
            "version" to version.version,
            "status" to version.status.name,
            "body_hash" to version.bodyHash,
            "created_at" to version.createdAt.toString(),
            "created_by" to version.createdBy.toString(),
            "released_at" to (version.releasedAt?.toString() ?: ""),
        )

    /** One entry of the pipelines listing (rest-api §5.7): metadata, not the body. */
    fun listEntry(record: PipelineRecord): Map<String, Any?> =
        mapOf<String, Any?>(
            "id" to record.id.toString(),
            "name" to record.name,
            "display_name" to record.displayName,
            "description" to record.description,
            "owner" to record.ownerId.toString(),
            "version" to record.currentVersion,
            "created_at" to record.createdAt.toString(),
            "updated_at" to record.updatedAt.toString(),
        )

    /** The §7 read shape's draft pointer, as the executions/history surfaces render it. */
    fun draftPointer(draft: PipelineVersionDetail): Map<String, Any?> =
        mapOf<String, Any?>(
            "version" to draft.version,
            "body_hash" to draft.bodyHash,
            "updated_by" to (draft.updatedBy?.toString() ?: ""),
            "updated_at" to (draft.updatedAt?.toString() ?: ""),
        )
}
