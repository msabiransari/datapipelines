package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.application.endpoints.PublishedEndpointVersion
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.IfMatchHeader
import co.datapipelines.web.pipelines.LifecycleVerbs
import co.datapipelines.web.release.ReleaseCoordinator
import co.datapipelines.web.release.ReleasePlanBody
import co.datapipelines.web.release.RootReleaseRequest
import co.datapipelines.web.requestlimits.StrictRequestBodies
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** rest-api §19.5 — an API draft body: the exact pipeline pin and the serve settings. */
data class SaveEndpointDraftRequest(
    @field:JsonProperty("pipeline") @get:JsonProperty("pipeline") @param:JsonProperty("pipeline")
    val pipeline: String,
    @field:JsonProperty("pipeline_version") @get:JsonProperty("pipeline_version") @param:JsonProperty("pipeline_version")
    val pipelineVersion: Int,
    @field:JsonProperty("timeout_seconds") @get:JsonProperty("timeout_seconds") @param:JsonProperty("timeout_seconds")
    val timeoutSeconds: Int? = null,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String? = null,
)

/**
 * An API's versions (#786; rest-api §19 version resources, §19.5 lifecycle verbs; dependency-graph record §24): read the
 * versions, save the working draft, release it through the shared planner, and the lifecycle verbs — discard the
 * draft, discard/restore/purge a version, switch the API's current. Addressed by the entity id (a path contains `/`,
 * see [EndpointsController]'s addressing note); every write refuses on an authoring-disabled deployment regardless of
 * role (the service's capability check). The lifecycle verbs and the release are session-only (brief §Security), like
 * `PipelinesController`'s; the draft save follows the create's row (`endpoint.publish`) and admits an `mcp` key.
 *
 * The versions list never carries bindings or key data: those are entity-level and appear on the entity's own read.
 */
@RestController
@RequestMapping("/api/v1/endpoints/{id}")
class EndpointVersionsController(
    private val versions: EndpointVersionService,
    private val coordinator: ReleaseCoordinator,
    private val releaseBounds: ReleaseBounds,
) {
    /** §19 — every version of the API, newest first, under the caller's lens; `eligible` under the caller's posture. */
    @GetMapping("/versions")
    @RequiredScope(Permission.ENDPOINT_READ)
    fun list(
        @PathVariable id: UUID,
    ): ApiResponse<List<Map<String, Any?>>> {
        val listed = versions.versions(currentPrincipal(), id) ?: throw notFound(id)
        return ApiResponse.of(listed.map { it.toResponse() })
    }

    /** §19 — one version of the API. */
    @GetMapping("/versions/{version}")
    @RequiredScope(Permission.ENDPOINT_READ)
    fun get(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<Map<String, Any?>> {
        val found = versions.version(currentPrincipal(), id, version) ?: throw versionNotFound(id, version)
        return ApiResponse.of(found.toResponse())
    }

    /** §19.5 — save the working draft under `If-Match: <body_hash>` (the draft's, or the current version's when none). */
    @PutMapping("/draft")
    @RequiredScope(Permission.ENDPOINT_PUBLISH)
    fun saveDraft(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody tree: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val body = StrictRequestBodies.bind<SaveEndpointDraftRequest>(tree)
        val saved =
            versions.saveDraft(
                currentPrincipal(),
                id,
                EndpointVersionService.DraftInput(body.pipeline, body.pipelineVersion, body.timeoutSeconds, body.description),
                IfMatchHeader.required(ifMatch),
            )
        return ApiResponse.of(saved.toResponse())
    }

    /**
     * §19.5 — release the working draft through the shared planner (record §6): `If-Match` is the draft's body hash; an
     * optional JSON plan body carries `tree_hash`, `include_dependencies` and `check_overrides`. Without a plan only the
     * API flips, and a DRAFT pipeline version below it is `endpoint.release.dependency_not_released`.
     */
    @PostMapping("/release")
    @RequiredScope(Permission.ENDPOINT_RELEASE)
    fun release(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody(required = false) body: String? = null,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val expectedHash = IfMatchHeader.required(ifMatch)
        val plan = ReleasePlanBody.parse(body, ReleaseFamily.ENDPOINT, releaseBounds, ::malformedBody)
        val released =
            coordinator.releaseRoot(RootReleaseRequest(ReleaseFamily.ENDPOINT, workspaceId, id, expectedHash, principal.userId, plan))
        val version = released.root.result as PublishedEndpointVersion
        return ApiResponse.of(
            version.toResponse(isCurrent = true, eligible = true) +
                ("dependencies_released" to released.dependenciesReleased.map { it.toWire() }),
        )
    }

    /** §19.5 — purge the working draft (record §24: no discarded-draft status); the sole version takes the entity. */
    @PostMapping("/draft/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun discardDraft(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ) {
        val principal = LifecycleVerbs.requireSession()
        versions.discardDraft(principal, id, IfMatchHeader.required(ifMatch))
    }

    /** §19.5 — discard RELEASED version v (reversible by restore); the current pointer per the pipeline rule. */
    @PostMapping("/versions/{version}/discard")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun discardVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of(versions.discardVersion(LifecycleVerbs.requireSession(), id, version).toResponse())

    /** §19.5 — restore DISCARDED version v to RELEASED after the transitive pin recheck (record §25.4). */
    @PostMapping("/versions/{version}/restore")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun restoreVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of(versions.restoreVersion(LifecycleVerbs.requireSession(), id, version).toResponse())

    /** §19.5 — purge DRAFT version v (drafts only); the sole version takes the entity. */
    @DeleteMapping("/versions/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun purgeVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ) {
        versions.purgeVersion(LifecycleVerbs.requireSession(), id, version)
    }

    /** §19.5 — the explicit API-current switch (A06): `{"version": n}`, eligible under the posture, its pin rechecked. */
    @PostMapping("/current")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun switchCurrent(
        @PathVariable id: UUID,
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val target = body["version"]?.takeIf { it.isInt && it.asInt() >= 1 } ?: throw versionMissing()
        return ApiResponse.of(versions.switchCurrent(principal, id, target.asInt()).toResponse())
    }

    private fun EndpointVersionService.VersionView.toResponse(): Map<String, Any?> = version.toResponse(isCurrent, eligible)

    private fun PublishedEndpointVersion.toResponse(
        isCurrent: Boolean? = null,
        eligible: Boolean? = null,
    ): Map<String, Any?> =
        buildMap {
            put("endpoint_id", endpointId.toString())
            put("version", version)
            put("status", status.name)
            put("pipeline", body.pipelineName)
            put("pipeline_version", body.pipelineVersion)
            put("timeout_seconds", body.timeoutSeconds)
            put("description", body.description)
            put("body_hash", bodyHash)
            if (isCurrent != null) put("is_current", isCurrent)
            if (eligible != null) put("eligible", eligible)
            put("created_at", createdAt.toString())
            put("released_at", releasedAt?.toString())
        }

    private fun EndpointVersionService.Moved.toResponse(): Map<String, Any?> =
        mapOf("version" to version, "current_version_before" to currentBefore, "current_version_after" to currentAfter)

    /**
     * An unreadable release plan body — not JSON, or past the request mapper's bounds (#291's nesting depth): the
     * house shape every string-body route answers (`details.reason = "malformed_json"`, the parser's own message), under
     * the code the API family already answers an unusable body with (the endpoint family has no body code of its own).
     */
    private fun malformedBody(cause: JsonProcessingException) =
        ApiException(
            PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
            "Request body is not valid JSON: ${cause.message?.take(MAX_CAUSE_CHARS)}",
            mapOf(ApiErrors.REASON to ApiErrors.MALFORMED_JSON),
            cause,
        )

    private fun versionMissing() =
        ApiException(
            PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
            "The switch names its target as {\"version\": n}, an integer of at least 1.",
            mapOf("reason" to "version_missing", "field" to "version"),
        )

    private fun notFound(id: UUID) =
        ApiException(PipelineErrorCodes.Endpoint.NOT_FOUND, "No API with that id in this workspace.", mapOf("endpoint_id" to id.toString()))

    private fun versionNotFound(
        id: UUID,
        version: Int,
    ) = ApiException(
        PipelineErrorCodes.Endpoint.VERSION_NOT_FOUND,
        "This API has no version $version.",
        mapOf("endpoint_id" to id.toString(), "version" to version),
    )

    private companion object {
        const val MAX_CAUSE_CHARS = 200
    }
}
