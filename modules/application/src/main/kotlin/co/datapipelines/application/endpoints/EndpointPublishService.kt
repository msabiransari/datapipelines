package co.datapipelines.application.endpoints

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant
import java.util.UUID

/**
 * Publishing and unpublishing endpoints (published-endpoints design §4.2, §6) — the one validated
 * path the REST surface, the MCP tools and promotion all go through.
 *
 * That sharing is the point, and it is 049's rule applied again: three entry points, one
 * validated path. A publish that skipped the read-only rule because it arrived over MCP instead
 * of REST would be a hole in the property the whole feature rests on.
 *
 * ## Pipelines are named, not id'd
 *
 * A publish names its pipeline the way everything portable does (pipeline-contract §11.1): the
 * NAME is the cross-environment identity, and an id is local. That is what lets promotion carry
 * an endpoint row to another deployment at all — the target resolves the same name to its own
 * pipeline id.
 *
 * ## Order of checks
 *
 * Grammar, then existence, then released-ness, then read-only, then path variables, then the
 * clamp, then the conflict. Cheapest and most-specific first, so the message an author gets names
 * the thing they can act on: "that path is malformed" rather than "that pipeline has no released
 * version" when both are true.
 */
class EndpointPublishService(
    private val endpoints: PublishedEndpointRepository,
    private val pipelines: PipelineService,
    /**
     * Name lookup lives on the repository, not the service — an endpoint names its pipeline the
     * way everything portable does, and `PipelineService` exposes no by-name read.
     */
    private val pipelineRepository: co.datapipelines.pipeline.PipelineRepository,
    private val readOnlyRule: ReadOnlyPipelineRule,
    private val registry: EndpointRegistry,
    private val audit: AuditEventSink,
    private val timeouts: TimeoutBounds,
) {
    /** The §5.5 clamp, passed in so `modules/application` reads no configuration itself. */
    data class TimeoutBounds(
        val defaultSeconds: Int,
        val minSeconds: Int,
        val maxSeconds: Int,
    ) {
        fun clamp(requested: Int?): Int = (requested ?: defaultSeconds).coerceIn(minSeconds, maxSeconds)
    }

    /**
     * Publishes [pathPattern] over the pipeline named [pipelineName].
     *
     * MUST run inside a transaction: [PublishedEndpointRepository.insert] takes a
     * transaction-scoped advisory lock to make the §4.1 ambiguity check race-proof.
     */
    @Suppress("ThrowsCount") // each §4.2 precondition is its own refusal, and each names itself
    fun publish(
        principal: AuthenticatedPrincipal,
        pathPattern: String,
        pipelineName: String,
        timeoutSeconds: Int?,
        description: String,
    ): PublishedEndpoint {
        val workspaceId = principal.requireWorkspace().id
        val parsed =
            EndpointPath.parse(pathPattern).getOrElse {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.PATH_INVALID,
                    message = it.message.orEmpty(),
                    details = mapOf("path_pattern" to pathPattern),
                )
            }

        val record = pipelineRepository.findByName(workspaceId, pipelineName) ?: throw pipelineNotFound(pipelineName)
        val version = releasedVersion(workspaceId, record) ?: throw notReleased(pipelineName)

        requireReadOnly(version, workspaceId, pipelineName)
        requireDeclaredVariables(parsed, version, pipelineName, pathPattern)

        val endpoint =
            endpoints.insert(
                PublishedEndpoint.of(
                    id = UUID.randomUUID(),
                    workspaceId = workspaceId,
                    pathPattern = parsed.pattern,
                    pipelineId = record.id,
                    timeoutSeconds = timeouts.clamp(timeoutSeconds),
                    description = description,
                    isEnabled = true,
                    createdBy = principal.userId,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                ),
            )

        // AFTER the write, so a peer that reloads on the message cannot read the pre-write state.
        registry.invalidate()
        audit.log(
            event = AUDIT_PUBLISHED,
            userId = principal.userId,
            keyId = principal.keyId,
            details =
                mapOf(
                    "endpoint_id" to endpoint.id.toString(),
                    "path_pattern" to endpoint.pathPattern,
                    "pipeline" to pipelineName,
                    "timeout_seconds" to endpoint.timeoutSeconds,
                ),
        )
        return endpoint
    }

    /** Unpublishes by path. Returns false when nothing was published there. */
    fun unpublish(
        principal: AuthenticatedPrincipal,
        pathPattern: String,
    ): Boolean {
        val workspaceId = principal.requireWorkspace().id
        val existing = endpoints.findByPath(pathPattern) ?: return false
        // A URL is global, but managing one is not: an endpoint belongs to the workspace that
        // published it, and another workspace's endpoint is invisible rather than forbidden —
        // the same not-found discipline every workspace-scoped read follows.
        if (existing.workspaceId != workspaceId && !principal.isAdmin) return false

        val removed = endpoints.deleteByPath(pathPattern)
        if (removed) {
            registry.invalidate()
            audit.log(
                event = AUDIT_UNPUBLISHED,
                userId = principal.userId,
                keyId = principal.keyId,
                details = mapOf("endpoint_id" to existing.id.toString(), "path_pattern" to pathPattern),
            )
        }
        return removed
    }

    /** The endpoints of the caller's workspace (§6's listing). */
    fun list(principal: AuthenticatedPrincipal): List<PublishedEndpoint> = endpoints.findByWorkspace(principal.requireWorkspace().id)

    /** One endpoint by path, or null when it does not exist or belongs to another workspace. */
    fun get(
        principal: AuthenticatedPrincipal,
        pathPattern: String,
    ): PublishedEndpoint? =
        endpoints.findByPath(pathPattern)?.takeIf {
            it.workspaceId == principal.requireWorkspace().id || principal.isAdmin
        }

    /** §4.2 — the rule that makes serving over GET defensible, re-checked on every serve too. */
    private fun requireReadOnly(
        version: PipelineService.ExecutablePipeline,
        workspaceId: UUID,
        pipelineName: String,
    ) {
        val verdict = readOnlyRule.check(version.pipeline, workspaceId)
        if (verdict.isValid) return
        val first = verdict.failures.first()
        throw DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY,
            message =
                "'$pipelineName' cannot be published as a GET endpoint: ${first.message} " +
                    "A published endpoint serves GET, which must be side-effect-free.",
            details = mapOf("pipeline" to pipelineName, "node_id" to first.details["node_id"], "via" to first.details["via"]),
        )
    }

    /**
     * §4.2 — every path variable names a declared parameter.
     *
     * A SUBSET check, not an equality one: the pipeline may declare more parameters than the path
     * binds, and those come from the query string.
     */
    private fun requireDeclaredVariables(
        parsed: EndpointPath.Parsed,
        version: PipelineService.ExecutablePipeline,
        pipelineName: String,
        pathPattern: String,
    ) {
        val undeclared = parsed.variableNames.filterNot { it in version.pipeline.parameters }
        if (undeclared.isEmpty()) return
        throw DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PATH_VARIABLE_UNKNOWN,
            message =
                "The path binds ${undeclared.joinToString(", ") { "{$it}" }}, which '$pipelineName' " +
                    "v${version.version} does not declare. It declares: ${version.pipeline.parameters.keys.sorted()}.",
            details = mapOf("path_pattern" to pathPattern, "undeclared" to undeclared),
        )
    }

    private fun releasedVersion(
        workspaceId: UUID,
        record: PipelineRecord,
    ): PipelineService.ExecutablePipeline? {
        val detail = pipelines.findCurrentVersion(workspaceId, record.id) ?: return null
        if (detail.status != PipelineVersionStatus.RELEASED) return null
        return pipelines.findExecutable(workspaceId, record, detail.version)
    }

    private fun pipelineNotFound(name: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.NOT_FOUND,
            message = "No pipeline named '$name' in this workspace.",
            details = mapOf("pipeline" to name),
        )

    private fun notReleased(name: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED,
            message = "'$name' has no released version. Release it before publishing an endpoint over it.",
            details = mapOf("pipeline" to name),
        )

    private companion object {
        const val AUDIT_PUBLISHED = "endpoint.published"
        const val AUDIT_UNPUBLISHED = "endpoint.unpublished"
    }
}
