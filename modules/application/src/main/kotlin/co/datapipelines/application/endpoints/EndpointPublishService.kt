package co.datapipelines.application.endpoints

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
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
    /**
     * 178 — the promoter lens on the two reads: an endpoint is visible iff its pipeline is
     * (auth.md §7.6 puts `endpoints_list/get` and `GET /api/v1/endpoints` in the promoter's
     * `lens` cell), so a hidden pipeline never leaks through the endpoint that publishes it.
     * Nullable so the deployments and tests wired before 178 keep the unlensed behaviour.
     */
    private val lens: PromoterLens? = null,
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
        // R-EP5 — the category is the engineer's namespace; the product's (`v<n>`, and the
        // literal `api`) is reserved. Refused here and re-checked when the serve registry is
        // built, so a row written around this check is never served either.
        EndpointPath.reservedCategory(parsed)?.let { segment ->
            throw DatapipelinesException(
                code = PipelineErrorCodes.Endpoint.PATH_RESERVED,
                message =
                    "The category '$segment' is reserved: v<number> is the product's own API namespace and 'api' " +
                        "re-opens the stripped prefix. Pick your own namespace — a business domain, a team, a product line.",
                details = mapOf("path_pattern" to parsed.pattern, "segment" to segment),
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
        // Normalised like a publish (R-EP5): the stored form never carries the `/api` prefix,
        // so an addressed `/api/trade/v1/x` names the row published as `/trade/v1/x`.
        val stored = EndpointPath.normalize(pathPattern)
        val existing = endpoints.findByPath(stored) ?: return false
        // A URL is global, but managing one is not: an endpoint belongs to the workspace that
        // published it, and another workspace's endpoint is invisible rather than forbidden —
        // the same not-found discipline every workspace-scoped read follows.
        if (existing.workspaceId != workspaceId && !principal.isSuperAdmin) return false

        val removed = endpoints.deleteByPath(stored)
        if (removed) {
            registry.invalidate()
            audit.log(
                event = AUDIT_UNPUBLISHED,
                userId = principal.userId,
                keyId = principal.keyId,
                details = mapOf("endpoint_id" to existing.id.toString(), "path_pattern" to stored),
            )
        }
        return removed
    }

    /** The endpoints of the caller's workspace (§6's listing). */
    fun list(principal: AuthenticatedPrincipal): List<PublishedEndpoint> {
        val workspaceId = principal.requireWorkspace().id
        val all = endpoints.findByWorkspace(workspaceId)
        val visiblePipelines = visiblePipelineIds(principal, workspaceId) ?: return all
        return all.filter { it.pipelineId in visiblePipelines }
    }

    /** One endpoint by path, or null when it does not exist, belongs to another workspace, or serves a pipeline the lens hides. */
    fun get(
        principal: AuthenticatedPrincipal,
        pathPattern: String,
    ): PublishedEndpoint? {
        val found =
            endpoints.findByPath(EndpointPath.normalize(pathPattern))?.takeIf {
                it.workspaceId == principal.requireWorkspace().id || principal.isSuperAdmin
            } ?: return null
        val visiblePipelines = visiblePipelineIds(principal, found.workspaceId) ?: return found
        return found.takeIf { it.pipelineId in visiblePipelines }
    }

    /**
     * The ids of the pipelines [principal]'s view admits in [workspaceId], or null when the
     * view is not a narrowing one (every role but the promoter — no read, no cost). An
     * endpoint names its pipeline by id, the lens by name, so the admitted index rows are
     * read once and matched by id.
     */
    private fun visiblePipelineIds(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
    ): Set<UUID>? {
        val view = lens?.viewFor(principal) ?: return null
        if (!view.isLensed) return null
        return pipelines.list(workspaceId, view.pipelines).mapTo(HashSet()) { it.id }
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
        val detail = pipelines.findCurrentVersion(workspaceId, ReadLens.Everything, record.id) ?: return null
        if (detail.status != PipelineVersionStatus.RELEASED) return null
        return pipelines.findExecutable(workspaceId, ReadLens.Everything, record, detail.version)
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
            // D55: a freshly authored pipeline has NO released version — the commonest case now,
            // so the message names the way forward instead of restating the rule.
            message = "'$name' has no released version. Release it from the UI first, then publish an endpoint over it.",
            details = mapOf("pipeline" to name),
        )

    private companion object {
        const val AUDIT_PUBLISHED = "endpoint.published"
        const val AUDIT_UNPUBLISHED = "endpoint.unpublished"
    }
}
