package co.datapipelines.web.visualizations

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.visualization.ArtifactFolder
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.RequestBodies
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * What differs between the two artifact families on the REST surface (rest-api §22, §23): the noun a message uses
 * and the family's catalogued codes — everything else about reading a body and shaping an answer is one rule, spelt
 * once in [ArtifactHttp] and [ArtifactResponses] so the visualizations and dashboards routes cannot drift apart.
 */
internal enum class ArtifactFamily(
    val noun: String,
    /** `*.not_found` — the 404 of an absent, foreign or lens-hidden artifact (§11A.1: never a 403). */
    val notFoundCode: String,
    /** `*.validation.body_invalid` — the family's malformed-body and wrong-shape 400. */
    val bodyInvalidCode: String,
    /** The listing key a browse answers the level's artifacts under. */
    val listKey: String,
    /** The folder row's count key. */
    val folderCountKey: String,
) {
    VISUALIZATION(
        noun = "visualization",
        notFoundCode = co.datapipelines.visualization.VisualizationErrorCodes.NOT_FOUND,
        bodyInvalidCode = co.datapipelines.visualization.VisualizationErrorCodes.BODY_INVALID,
        listKey = "visualizations",
        folderCountKey = "visualization_count",
    ),
    DASHBOARD(
        noun = "dashboard",
        notFoundCode = co.datapipelines.visualization.DashboardErrorCodes.NOT_FOUND,
        bodyInvalidCode = co.datapipelines.visualization.DashboardErrorCodes.BODY_INVALID,
        listKey = "dashboards",
        folderCountKey = "dashboard_count",
    ),
    ;

    /** The family's 404 for [id] (or its [version]) — absent, foreign and lens-hidden answer alike. */
    fun notFound(
        id: String,
        version: Int? = null,
    ): ApiException =
        ApiException(
            notFoundCode,
            if (version == null) {
                "${noun.replaceFirstChar { it.uppercase() }} '$id' not found."
            } else {
                "The $noun '$id' has no version $version."
            },
            if (version == null) mapOf("id" to id) else mapOf("id" to id, "version" to version),
        )

    /** The family's wrong-SHAPE 400, naming the path and the reason — never a value. */
    fun bodyInvalid(
        path: String,
        reason: String,
    ): ApiException =
        ApiException(
            bodyInvalidCode,
            when (reason) {
                ApiErrors.REASON_MISSING -> "The request body is missing '$path'."
                else -> "The request body's '$path' has the wrong JSON type."
            },
            mapOf("path" to path, "reason" to reason),
        )

    /** An unreadable body — not JSON, or nested past §13.21's bound (#291): the family's 400, `reason: malformed_json`. */
    fun malformed(cause: Throwable): ApiException =
        ApiException(
            bodyInvalidCode,
            "Request body is not valid JSON: ${cause.message?.take(MAX_CAUSE_CHARS)}",
            mapOf(ApiErrors.REASON to ApiErrors.MALFORMED_JSON),
            cause,
        )

    private companion object {
        const val MAX_CAUSE_CHARS = 200
    }
}

/** How the two families' routes read a `String` body — the #291 shape, never the bare domain mapper. */
internal object ArtifactHttp {
    /**
     * The REQUEST copy of the module's strict mapper ([RequestLimits.requestMapper]): the stated nesting, string and
     * number bounds apply to the caller's bytes; the tree then goes to the family's READER, which applies the seven
     * document bounds (`datapipelines.visualization.*`) before it walks a member.
     */
    private val REQUEST_MAPPER = RequestLimits.requestMapper(ArtifactJson.mapper)

    /** [body] as a tree, or the family's catalogued malformed-body 400. */
    fun readTree(
        family: ArtifactFamily,
        body: String,
    ): JsonNode = RequestBodies.readTree(REQUEST_MAPPER, body, family::malformed)

    /**
     * The switch's `{"version": n}` — judged BEFORE the lookup (the #300 rule): missing or not an integer is the
     * family's `body_invalid`, never a 404 that reads as if the ARTIFACT were absent.
     */
    fun switchTarget(
        family: ArtifactFamily,
        body: String,
    ): Int {
        val versionNode = readTree(family, body).get("version")
        if (versionNode == null || versionNode.isNull) throw family.bodyInvalid("version", ApiErrors.REASON_MISSING)
        if (!versionNode.isInt) throw family.bodyInvalid("version", ApiErrors.REASON_WRONG_TYPE)
        return versionNode.asInt()
    }
}

/**
 * The REST projection of a stored visualization or dashboard (rest-api §22, §23; the `ParameterSetResponses`
 * shape): the stored body as the module's strict mapper writes it, plus the server-assigned and lifecycle fields —
 * never a re-spelled DTO, so the strict binding stays the one definition of the body on the wire.
 */
internal object ArtifactResponses {
    /**
     * The full artifact: the body's fields merged with `id`, `name`, `version`/`status`/`body_hash` of the returned
     * row (the WORKING version — versioning §7.1), `current_version`, the timestamps, and the §7 draft pointer when
     * [draft] is given (never under a narrowing lens: a promoter never sees a draft's number or hash).
     */
    fun <B : Any> full(
        loaded: ArtifactVersion<B>,
        draft: ArtifactVersionDetail? = null,
    ): JsonNode {
        val node = ArtifactJson.mapper.valueToTree<ObjectNode>(loaded.body)
        node
            .put("id", loaded.record.id.toString())
            .put("name", loaded.record.name)
            .put("version", loaded.detail.version)
            .put("status", loaded.detail.status.name)
            .put("body_hash", loaded.detail.bodyHash)
            .put("created_at", loaded.record.createdAt.toString())
            .put("updated_at", loaded.record.updatedAt.toString())
        loaded.record.currentVersion?.let { node.put("current_version", it) } ?: node.putNull("current_version")
        draft?.let {
            val pointer = node.putObject("draft")
            pointer.put("version", it.version)
            pointer.put("body_hash", it.bodyHash)
            pointer.put("updated_by", it.updatedBy?.toString() ?: "")
            pointer.put("updated_at", it.updatedAt?.toString() ?: "")
        }
        return node
    }

    /** One entry of the versions listing — metadata only, no body. */
    fun versionSummary(version: ArtifactVersionDetail): Map<String, Any?> =
        mapOf(
            "version" to version.version,
            "status" to version.status.name,
            "body_hash" to version.bodyHash,
            "created_at" to version.createdAt.toString(),
            "created_by" to version.createdBy.toString(),
            "released_at" to (version.releasedAt?.toString() ?: ""),
        )

    /** What a lifecycle verb answers: the version it touched, by its detail. */
    fun lifecycleSummary(detail: ArtifactVersionDetail): Map<String, Any?> =
        mapOf(
            "id" to detail.artifactId.toString(),
            "version" to detail.version,
            "status" to detail.status.name,
            "body_hash" to detail.bodyHash,
        )

    /** One listing/browse row: metadata, not the body; `version`/`status` name the LISTED version. */
    fun <B : Any> listEntry(loaded: ArtifactVersion<B>): Map<String, Any?> =
        mapOf(
            "id" to loaded.record.id.toString(),
            "name" to loaded.record.name,
            "display_name" to loaded.record.displayName,
            "description" to loaded.record.description,
            "version" to loaded.detail.version,
            "status" to loaded.detail.status.name,
            "current_version" to loaded.record.currentVersion,
            "created_at" to loaded.record.createdAt.toString(),
            "updated_at" to loaded.record.updatedAt.toString(),
        )

    /** One sub-folder of a browsed level. */
    fun folder(
        family: ArtifactFamily,
        folder: ArtifactFolder,
    ): Map<String, Any?> = mapOf("path" to folder.path, "segment" to folder.segment, family.folderCountKey to folder.count)

    /**
     * A dashboard validation's answer (rest-api §23): the version judged and the verdict — `valid`, and every refusal
     * in the 400's `details.failures` shape (`code`, `path`, `message`, `details`), exhaustive (§17.2).
     */
    fun <B : Any> validation(
        loaded: ArtifactVersion<B>,
        result: ValidationResult,
    ): Map<String, Any?> =
        mapOf(
            "id" to loaded.record.id.toString(),
            "name" to loaded.record.name,
            "version" to loaded.detail.version,
            "status" to loaded.detail.status.name,
            "body_hash" to loaded.detail.bodyHash,
            "valid" to result.isValid,
            "failures" to
                result.failures.map { mapOf("code" to it.code, "path" to it.path, "message" to it.message, "details" to it.details) },
        )

    /** The draft pointer a whole-view reader gets: the working version, when it IS the draft. */
    fun <B : Any> draftOf(loaded: ArtifactVersion<B>): ArtifactVersionDetail? =
        loaded.detail.takeIf { it.status == PipelineVersionStatus.DRAFT }
}
