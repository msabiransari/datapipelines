package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointAuthorizer
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.EndpointServeAudit
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Serving one `GET /api/x/…` request (published-endpoints design §5).
 *
 * The order is the whole design, and it is deliberate: **resolve, authorise, validate, run**.
 * Nothing about the request's data is inspected before the caller has been authorised, so an
 * unauthorised caller cannot use the validator's error messages to learn a pipeline's parameter
 * names; and nothing is executed before the request has been validated, so a typo costs no
 * execution slot.
 *
 * ## Timeout is `202`, never `504` (ruling R-EP3)
 *
 * The execution is launched into the application's own scope and then **awaited with a timeout**.
 * When the wait expires the execution is NOT cancelled — it keeps running, and the caller gets
 * `202` with the execution id and the cursor to come back to. `withTimeoutOrNull` here cancels
 * only the *await*, because the `Deferred` belongs to [scope] and not to the awaiting coroutine;
 * a `withTimeout` around the run itself would have killed the work the `202` promises.
 *
 * A `504` would be a lie in both directions: the upstream is fine, and the answer is coming.
 *
 * ## The read-only rule runs again here
 *
 * §4.2 checked it at publish, but an endpoint pins a PIPELINE and serves its latest RELEASED
 * version, so a later release can put a `DML` node under a live URL. Re-checking per serve is
 * what makes "GET is safe" true at the moment it matters rather than at the moment somebody
 * published. It costs one in-memory walk of a body that is already loaded.
 */
@Suppress("LongParameterList") // one composition root for one request; every collaborator is used
class PublishedEndpointServeService(
    private val registry: EndpointRegistry,
    private val bindings: EndpointKeyBindingRepository,
    private val authorizer: EndpointAuthorizer,
    private val readOnlyRule: ReadOnlyPipelineRule,
    private val pipelines: PipelineService,
    private val runner: RecordingExecutionRunner,
    private val resultStore: ResultStore,
    private val resultUrls: ResultUrlFactory,
    private val resultConfig: ResultConfig,
    private val endpointsProperties: EndpointsProperties,
    private val audit: AuditEventSink,
    private val authoring: AuthoringGuard,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(PublishedEndpointServeService::class.java)

    /** What the surface should answer. The HTTP shapes live in the controller; these are the facts. */
    sealed interface Outcome {
        /** §5.4 — the `data_ready` payload, verbatim. */
        data class Served(
            val executionId: UUID,
            val payload: Map<String, Any?>,
        ) : Outcome

        /** §5.4 / R-EP3 — the wait expired; the execution continues. */
        data class Accepted(
            val executionId: UUID,
            val body: Map<String, Any?>,
        ) : Outcome

        /** Any refusal, with the §5.6 status the controller should send. */
        data class Refused(
            val status: Int,
            val code: String,
            val message: String,
            val details: Map<String, Any?> = emptyMap(),
        ) : Outcome
    }

    /**
     * Resolves and serves [path] (the part after `/api/x`, with its leading `/`).
     *
     * Blocking by design: a servlet thread calls this, and the `202` contract means the block is
     * bounded by the endpoint's own timeout.
     */
    @Suppress("ReturnCount") // §5's order IS the refusal order; each stage refuses on its own terms
    fun serve(
        path: String,
        principal: AuthenticatedPrincipal,
        request: EndpointRequestValidator.Request,
    ): Outcome {
        // §5.2 — a machine surface. A browser session is refused before anything is resolved, so
        // a signed-in user cannot discover the registry by navigating to it.
        if (principal.authMethod != AuthMethod.API_KEY) {
            return Outcome.Refused(
                HTTP_UNAUTHORIZED,
                PipelineErrorCodes.Auth.SESSION_REQUIRED,
                "Published endpoints are a machine surface: present an API key, not a browser session.",
            )
        }

        val matched = registry.matcher().match(path) ?: return notFound()
        val endpoint = matched.endpoint
        val version = pointedVersion(endpoint) ?: return notReleased(endpoint)

        val readOnly = readOnlyRule.check(version.pipeline, endpoint.workspaceId)
        if (!readOnly.isValid) {
            // Live endpoint, changed target. Say so; do not run it.
            val first = readOnly.failures.first()
            return Outcome.Refused(
                HTTP_SERVICE_UNAVAILABLE,
                PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY,
                "This endpoint's pipeline is no longer side-effect-free, so it was not run. ${first.message}",
                mapOf("node_id" to first.details["node_id"], "pipeline_version" to version.version),
            )
        }

        authorize(path, principal, endpoint)?.let { return it }

        val validator =
            EndpointRequestValidator(
                declared = version.pipeline.parameters,
                limits =
                    EndpointRequestValidator.Limits(
                        ttlMinSeconds = resultConfig.ttlMinSeconds,
                        ttlMaxSeconds = resultConfig.ttlMaxSeconds,
                        ttlDefaultSeconds = resultConfig.ttlDefaultSeconds,
                        pageMaxRows = resultConfig.pageMaxRows,
                        pageDefaultRows = resultConfig.pageSizeRows,
                    ),
            )
        // The path variables come from the MATCH, not from the caller: only the matcher knows
        // which pattern won and therefore what `{borough}` was bound to.
        return when (val validated = validator.validate(request.copy(pathVariables = matched.pathVariables))) {
            is EndpointRequestValidator.Outcome.Unacceptable -> {
                Outcome.Refused(
                    HTTP_NOT_ACCEPTABLE,
                    PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE,
                    "This endpoint serves application/json.",
                    mapOf("accept" to validated.accept),
                )
            }

            is EndpointRequestValidator.Outcome.Invalid -> {
                Outcome.Refused(
                    HTTP_BAD_REQUEST,
                    PipelineErrorCodes.EndpointRequest.INVALID,
                    "The request has ${validated.defects.size} problem(s); every one of them is listed in details.errors.",
                    mapOf("errors" to validated.defects.map { it.toMap() }),
                )
            }

            is EndpointRequestValidator.Outcome.Valid -> {
                run(endpoint, version, principal, validated)
            }
        }
    }

    /** Launch, await with the endpoint's timeout, and shape the answer (§5.4). */
    private fun run(
        endpoint: PublishedEndpoint,
        version: PipelineService.ExecutablePipeline,
        principal: AuthenticatedPrincipal,
        validated: EndpointRequestValidator.Outcome.Valid,
    ): Outcome {
        // Minted here so the 202 can NAME the execution the caller must come back for.
        val executionId = UUID.randomUUID()
        val request =
            ExecuteRequest(
                pipelineId = endpoint.pipelineId,
                pipelineVersion = version.version,
                pipeline = version.pipeline,
                userId = principal.userId,
                workspaceId = endpoint.workspaceId,
                parameters = validated.parameters,
                resultTtlSeconds = validated.ttlSeconds,
                triggeredVia = ExecutionTrigger.ENDPOINT,
                executionId = executionId,
                // rootExecutionId stays null on purpose: a root request takes a concurrency slot,
                // and an endpoint serve must be governed by the same limits every other run is.
            )

        val deferred: Deferred<ExecutionResult> = scope.async { runner.run(request, endpoint.workspaceId, ExecutionTrigger.ENDPOINT) }
        val timeout = endpointsProperties.clampTimeout(endpoint.timeoutSeconds)

        // The audit row is written for BOTH outcomes and BEFORE the answer, because it is what
        // lets this key read the result later — including the 202 case, where reading it later is
        // the entire contract.
        auditServe(endpoint, principal, executionId, if (deferred.isCompleted) "completed" else "started")

        val result =
            runBlocking {
                // Cancels the AWAIT, never the run: the Deferred is owned by `scope`.
                withTimeoutOrNull(timeout.seconds) { deferred.await() }
            }
        return if (result == null) accepted(executionId, validated) else served(result, validated)
    }

    private fun served(
        result: ExecutionResult,
        validated: EndpointRequestValidator.Outcome.Valid,
    ): Outcome {
        val ref = result.resultRef
        if (ref == null) {
            // A pipeline with no caller node produced nothing to return. It is not an error —
            // §9.4's zero-caller pipelines are legitimate — but it is not servable as an endpoint.
            return Outcome.Refused(
                HTTP_SERVICE_UNAVAILABLE,
                PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY,
                "This endpoint's pipeline has no caller node, so it produces no rows to return.",
                mapOf("execution_id" to result.executionId.toString()),
            )
        }
        val view = resultStore.describe(ref) ?: return resultGone(result.executionId)
        // The first page honours DP-Result-Page-Rows (R-EP4) rather than the store's fixed
        // page-size, which is why this reads a page instead of using view.firstPage.
        val page = resultStore.page(ref, 0, validated.pageRows) ?: return resultGone(result.executionId)
        return Outcome.Served(
            executionId = result.executionId,
            payload =
                mapOf(
                    "execution_id" to result.executionId.toString(),
                    "schema" to view.schema,
                    "rows" to page.rows,
                    "row_count" to page.rows.size,
                    "total_rows" to view.totalRows,
                    "has_more" to (page.rows.size.toLong() < view.totalRows),
                    "result_url" to resultUrls.urlFor(result.executionId),
                    "expires_at" to view.expiresAt.toString(),
                    "ttl_seconds" to validated.ttlSeconds,
                    "warnings" to view.warnings,
                ),
        )
    }

    private fun accepted(
        executionId: UUID,
        validated: EndpointRequestValidator.Outcome.Valid,
    ): Outcome =
        Outcome.Accepted(
            executionId = executionId,
            body =
                mapOf(
                    "execution_id" to executionId.toString(),
                    "result_url" to resultUrls.urlFor(executionId),
                    "status_url" to resultUrls.urlFor(executionId).removeSuffix("/result"),
                    // The TTL the result WILL be written with — the client's own clamp, so it
                    // knows how long it has to come back rather than having to guess.
                    "expires_at" to Instant.now().plusSeconds(validated.ttlSeconds).toString(),
                ),
        )

    /**
     * §5.1 — the version the pipeline's POINTER names (D60), when it is eligible for this posture
     * (D63, 2026-09-09): a RELEASED version always; a DRAFT only under the development posture,
     * where the pointer may legitimately fall back to (or be switched to) a draft so the endpoint
     * can be tested BEFORE the release — "we should be able to point the API to any version in
     * dev". A non-development deployment holds no drafts, so it serves releases by construction,
     * not by this check. A NULL pointer, or a pointer at a version this posture may not serve,
     * is the §5.6 `pipeline_not_released` refusal.
     */
    private fun pointedVersion(endpoint: PublishedEndpoint): PipelineService.ExecutablePipeline? {
        val record = pipelines.findRecord(endpoint.workspaceId, endpoint.pipelineId) ?: return null
        val detail = pipelines.findCurrentVersion(endpoint.workspaceId, endpoint.pipelineId) ?: return null
        if (!servable(detail.status)) return null
        return pipelines.findExecutable(endpoint.workspaceId, record, detail.version)
    }

    /** The D63 rule, on its own so a test can read it: released always, a draft in development only. */
    internal fun servable(status: PipelineVersionStatus): Boolean =
        PipelineVersionStatus.eligibleForPointer(status, draftsEligible = authoring.developmentPosture)

    private fun authorize(
        path: String,
        principal: AuthenticatedPrincipal,
        endpoint: PublishedEndpoint,
    ): Outcome.Refused? {
        val ancestors = EndpointPath.ancestors(path)
        val decision = authorizer.authorize(path, principal, endpoint.workspaceId, bindings.findByPrefixes(ancestors))
        return when (decision) {
            is EndpointAuthorizer.Decision.Allowed -> null
            is EndpointAuthorizer.Decision.Refused -> Outcome.Refused(HTTP_FORBIDDEN, decision.code, decision.message)
        }
    }

    private fun auditServe(
        endpoint: PublishedEndpoint,
        principal: AuthenticatedPrincipal,
        executionId: UUID,
        outcome: String,
    ) {
        audit.log(
            event = EndpointServeAudit.SERVE_EVENT,
            userId = principal.userId,
            keyId = principal.keyId,
            details =
                mapOf(
                    "endpoint_id" to endpoint.id.toString(),
                    "path_pattern" to endpoint.pathPattern,
                    "execution_id" to executionId.toString(),
                    "outcome" to outcome,
                ),
        )
    }

    /**
     * §5.6 — the same body for an unknown path and a disabled one.
     *
     * Distinguishing them would let an unauthenticated caller enumerate the registry one URL at a
     * time, which is why the disabled case never reaches the matcher in the first place (the
     * registry caches only enabled rows) rather than being filtered out later with its own code.
     */
    private fun notFound() =
        Outcome.Refused(HTTP_NOT_FOUND, PipelineErrorCodes.Endpoint.NOT_FOUND, "No published endpoint matches this path.")

    private fun notReleased(endpoint: PublishedEndpoint): Outcome {
        log.warn(
            "event=endpoint.pipeline_not_released path={} pipeline_id={} " +
                "message=\"endpoint is live but its pipeline has no released version\"",
            endpoint.pathPattern,
            endpoint.pipelineId,
        )
        return Outcome.Refused(
            HTTP_SERVICE_UNAVAILABLE,
            PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED,
            "This endpoint's pipeline has no released version to serve.",
        )
    }

    private fun resultGone(executionId: UUID) =
        Outcome.Refused(
            HTTP_GONE,
            PipelineErrorCodes.Result.EXPIRED,
            "The result was produced but is no longer stored.",
            mapOf("execution_id" to executionId.toString()),
        )

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_NOT_ACCEPTABLE = 406
        const val HTTP_GONE = 410
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
