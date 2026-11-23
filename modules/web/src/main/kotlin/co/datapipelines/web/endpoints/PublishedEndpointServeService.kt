package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointAuthorizer
import co.datapipelines.application.endpoints.EndpointGrants
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.EndpointServeAudit
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointVersion
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceLiveness
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import co.datapipelines.web.pipelines.executedByKeyKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Serving one request on the published subtree (published-endpoints design §5, R-EP5; the method era of #1037,
 * published-mutation-apis §4).
 *
 * The order is the whole design, and it is deliberate: **resolve, authorise, validate, run**. Nothing about the
 * request's data is inspected before the caller has been authorised, so an unauthorised caller cannot use the
 * validator's error messages to learn a pipeline's parameter names; and nothing is executed before the request has
 * been validated, so a typo costs no execution slot.
 *
 * ## The two branches, and the one rule they share (#1037 §4.4)
 *
 * `GET` is the branch that serves, unchanged in its order: session refusal → match → liveness → pointer → read-only
 * → authorise → validate → run. A non-GET method never runs in this build: it answers the session refusal, then the
 * VISIBILITY computation — a method is visible iff a live route of it matches AND the presenting key holds a grant
 * for it (the GET ancestor walk; the mutation grant naming the route entity, or on an authoring deployment the key's
 * namespace-writes opt-in at the deciding node, #1111) — then the opaque `404` (nothing
 * visible) or `405` with `Allow` = exactly the visible methods, then the pin eligibility `503`, then the
 * `endpoint.mutations_disabled` `503` of `datapipelines.endpoints.mutations-enabled` (§7: refused BEFORE any durable
 * business work; both flag branches answer it in this build, with distinct messages, until #1039 replaces the gate
 * with the real admission path). A verb the surface cannot publish at all (PATCH, OPTIONS, …) follows the same
 * visibility rule: `404` when nothing is visible, else `405` without itself in `Allow`.
 *
 * ## Timeout is `202`, never `504` (ruling R-EP3)
 *
 * The execution is launched into the application's own scope and then **awaited with a timeout**. When the wait
 * expires the execution is NOT cancelled — it keeps running, and the caller gets `202` with the execution id and
 * the cursor to come back to. `withTimeoutOrNull` here cancels only the *await*, because the `Deferred` belongs to
 * [scope] and not to the awaiting coroutine; a `withTimeout` around the run itself would have killed the work the
 * `202` promises.
 *
 * A `504` would be a lie in both directions: the upstream is fine, and the answer is coming.
 *
 * ## The read-only rule runs again here
 *
 * §4.2 checked it when the API version was saved. Since #786 an API version pins an EXACT pipeline version (the pin
 * never moves when the pipeline's current moves, record §24/A06), so the body under a live URL changes only through
 * an API release or current switch, both of which re-run the check; the per-serve check stays as the fail-closed
 * backstop that keeps "GET is safe" true at the moment it matters (a child pipeline the version composes is resolved
 * at serve time). It costs one in-memory walk of a body that is already loaded. A GET rule (#1037): a mutation
 * entity's pin admits a writing pipeline by design, and its run-time checks are #1039's.
 *
 * ## Which version runs (#786 B.1)
 *
 * entity → its `current_version` → that API version row → eligible under the posture → the row's exact
 * `(pipeline_name, pipeline_version)` → that pipeline version row → eligible under the posture → execute. The
 * pipeline's own CURRENT pointer is never read. Every refusal on that chain is the one catalogued 503 whose body
 * names no version, so a caller cannot learn which API versions exist.
 */
@Suppress("LongParameterList") // one composition root for one request; every collaborator is used
class PublishedEndpointServeService(
    private val registry: EndpointRegistry,
    /** #786 — the API's current version, read per serve (never the registry snapshot's copy). */
    private val versions: PublishedEndpointVersionRepository,
    /** #786 — the pinned pipeline, by its portable name, and the exact version's status. */
    private val pipelineRepository: PipelineRepository,
    bindings: EndpointKeyBindingRepository,
    authorizer: EndpointAuthorizer,
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
    /**
     * 180 (D15) — the endpoint's OWN workspace, read per serve through the auth liveness cache.
     * A deactivated workspace's endpoint is unknown (the 404 rule), whoever presents the key:
     * before 180 that held only because a key PINNED there was refused at validation, and a
     * `user` key of another workspace reached the authorizer and was told `key_not_bound`
     * (403) — which confirms the endpoint exists. [EndpointAuthorizer] stays pure; the
     * question is asked here, where the endpoint row is in hand.
     */
    private val workspaceLiveness: WorkspaceLiveness,
    /**
     * #1039 — admission, replay and start of a published POST, PUT or DELETE. Null only where a test builds the GET
     * surface alone; a mutation then answers `503 endpoint.mutations_disabled` and nothing runs.
     */
    private val mutations: PublishedMutationService? = null,
    /**
     * #1111 — the one effective grant decision ([EndpointGrants]): GET's ancestor walk, and a mutation's exact route
     * grant OR namespace grant. The bean in production; built from this service's own collaborators where a test builds
     * the surface by hand, so both answer the same rule.
     */
    private val grants: EndpointGrants = EndpointGrants(bindings, authorizer, authoring),
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

        /** Any refusal, with the §5.6 status the controller should send; [allow] names the visible methods for a `405`. */
        data class Refused(
            val status: Int,
            val code: String,
            val message: String,
            val details: Map<String, Any?> = emptyMap(),
            val allow: List<String>? = null,
        ) : Outcome
    }

    /**
     * Resolves and serves [path] (the part after `/api`, with its leading `/`) for [method] — `GET` serves; a
     * mutation method answers the #1037 §4.4 gate; null is a verb this surface cannot publish at all (PATCH, …),
     * which follows the mutation gate's visibility rules. A browser session is refused first on every branch.
     *
     * Blocking by design: a servlet thread calls this, and the `202` contract means the block is bounded by the
     * endpoint's own timeout.
     */
    @Suppress("ReturnCount") // §5's order IS the refusal order; each stage refuses on its own terms
    fun serve(
        path: String,
        method: EndpointMethod?,
        principal: AuthenticatedPrincipal,
        request: EndpointRequestValidator.Request,
        mutation: MutationInput = MutationInput.none(),
    ): Outcome {
        // §5.2 — a machine surface, on every branch: a browser session learns nothing, not even
        // which methods a path serves, so the 401 carries no Allow (§4.4) whatever the verb.
        if (principal.authMethod != AuthMethod.API_KEY) {
            return sessionRequired()
        }
        return if (method == EndpointMethod.GET) {
            serveGet(path, principal, request)
        } else {
            serveMutation(path, method, principal, request, mutation)
        }
    }

    /** The session refusal, first on every branch and identical on every branch (§5.2, §4.4). */
    private fun sessionRequired() =
        Outcome.Refused(
            HTTP_UNAUTHORIZED,
            PipelineErrorCodes.Auth.SESSION_REQUIRED,
            "Published endpoints are a machine surface: present an API key, not a browser session.",
        )

    /** The GET branch — §5's order, unchanged from the pre-method era, answer for answer. */
    @Suppress("ReturnCount") // §5's order IS the refusal order; each stage refuses on its own terms
    private fun serveGet(
        path: String,
        principal: AuthenticatedPrincipal,
        request: EndpointRequestValidator.Request,
    ): Outcome {
        val matched = registry.matcher().match(path, EndpointMethod.GET) ?: return notFound()
        val endpoint = matched.endpoint
        // §5.6 / D15 — BEFORE the pipeline is resolved: nothing about a deactivated
        // workspace's endpoint is read, and the body is the unmatched path's, byte for byte.
        if (!workspaceLiveness.isActive(endpoint.workspaceId)) return notFound()
        val pointed = pointedVersion(endpoint) ?: return notReleased(endpoint)
        val version = pointed.executable

        val readOnly = readOnlyRule.check(version.pipeline, endpoint.workspaceId)
        if (!readOnly.isValid) {
            // Live endpoint, changed target. Say so; do not run it. The body names the node, never a
            // version (#786 §Security: serving never leaks which versions exist).
            val first = readOnly.failures.first()
            return Outcome.Refused(
                HTTP_SERVICE_UNAVAILABLE,
                PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY,
                "This endpoint's pipeline is no longer side-effect-free, so it was not run. ${first.message}",
                mapOf("node_id" to first.details["node_id"]),
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
                // #227 (keys v2 A.8b) — the #222 shape: `details.produces` names what the route
                // CAN produce; the request's raw `Accept` value is attacker-controlled and is
                // never echoed, in the details or in the message. (The validator still READS
                // the header to decide 406-vs-400; this boundary is what must not repeat it.)
                Outcome.Refused(
                    HTTP_NOT_ACCEPTABLE,
                    PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE,
                    "This endpoint serves application/json.",
                    mapOf("produces" to listOf(MediaType.APPLICATION_JSON_VALUE)),
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
                run(endpoint, pointed, principal, validated)
            }
        }
    }

    /**
     * The non-GET branch (#1037 §4.4, #1039) — visibility, then 404/405, then [PublishedMutationService]: the key and
     * the body, the reservation (a replay answers from the ORIGINAL execution, before any newer current version is
     * read), the mutations flag, the pin and the run. [method] null is a verb this surface cannot publish (PATCH,
     * OPTIONS, …) and follows the same rule, so it can never appear in `Allow` and never reaches admission.
     */
    @Suppress("ReturnCount") // §4.4's order IS the refusal order; each stage refuses on its own terms
    private fun serveMutation(
        path: String,
        method: EndpointMethod?,
        principal: AuthenticatedPrincipal,
        request: EndpointRequestValidator.Request,
        input: MutationInput,
    ): Outcome {
        val visible = visibleMethods(path, principal)

        // Nothing visible to THIS caller: the path is, as far as this key is concerned, unknown — the
        // same opaque 404 as an unknown path, with nothing to learn from the difference (§4.4).
        if (visible.isEmpty()) return notFound()

        val requested = method?.takeIf { visible.containsKey(it) }
        if (requested == null) {
            // Some method is visible but the requested one is not published here or not granted to this
            // key: say which methods ARE, and nothing else. The Allow list is bounded by construction —
            // at most the four publishable methods, and only those with a live route AND a grant.
            val allowed = visible.keys.sortedBy { it.ordinal }.map { it.name }
            val requestedName = method?.name ?: "this method"
            return Outcome.Refused(
                HTTP_METHOD_NOT_ALLOWED,
                PipelineErrorCodes.Endpoint.METHOD_NOT_ALLOWED,
                "This path serves ${allowed.joinToString(", ")} for this credential, not $requestedName. " +
                    "The Allow header lists exactly what is granted.",
                mapOf("method" to requestedName, "allow" to allowed),
                allow = allowed,
            )
        }

        // Authorized for this method: the entity is in hand, and the eligibility checks run only now
        // (§4.4 — after authorization, so an ungranted caller learns no version facts).
        val endpoint = visible.getValue(requested)
        val service =
            mutations ?: return Outcome.Refused(
                HTTP_SERVICE_UNAVAILABLE,
                PipelineErrorCodes.Endpoint.MUTATIONS_DISABLED,
                "Serving mutation routes is not configured on this surface. Nothing was written.",
                mapOf("method" to requested.name),
            )
        // The path variables come from the MATCH, as on GET: only the matcher knows which pattern won.
        val pathVariables =
            registry
                .matcher()
                .match(path, requested)
                ?.pathVariables
                .orEmpty()
        return service.serve(
            MutationCall(endpoint, requested, path, pathVariables, principal, request, input, CorrelationId.currentUuid()),
            mutationPins,
        )
    }

    /** The pins a mutation may run, under the same D63 eligibility rule as GET (see [pointedVersion]). */
    private val mutationPins =
        object : MutationPins {
            override fun current(endpoint: PublishedEndpoint): MutationPin? =
                pointedVersion(endpoint)?.let { pointed ->
                    MutationPin(pointed.api.version, pointed.api.body.timeoutSeconds, pointed.pipelineId, pointed.executable)
                }

            override fun original(
                endpoint: PublishedEndpoint,
                reservation: PublishedMutationReservation,
            ): MutationPin? = pinOf(endpoint, reservation, servableOnly = true)

            override fun admittedUnder(
                endpoint: PublishedEndpoint,
                reservation: PublishedMutationReservation,
            ): MutationPin? = pinOf(endpoint, reservation, servableOnly = false)

            /** The reservation's exact pin; [servableOnly] applies D63 (to run it), otherwise its rules are enough. */
            @Suppress("ReturnCount") // one early null per link of the resolution chain, in its order
            private fun pinOf(
                endpoint: PublishedEndpoint,
                reservation: PublishedMutationReservation,
                servableOnly: Boolean,
            ): MutationPin? {
                val api = versions.find(endpoint.id, reservation.apiVersion) ?: return null
                if (servableOnly && !servable(api.status)) return null
                val record = pipelineRepository.findById(endpoint.workspaceId, reservation.pipelineId) ?: return null
                val detail =
                    pipelineRepository.findVersionDetail(endpoint.workspaceId, record.id, reservation.pipelineVersion) ?: return null
                if (servableOnly && !servable(detail.status)) return null
                val executable = pipelines.findExecutable(endpoint.workspaceId, ReadLens.Everything, record, detail.version) ?: return null
                return MutationPin(api.version, api.body.timeoutSeconds, record.id, executable)
            }
        }

    /**
     * The methods THIS key can call at [path]: a live route of the method whose grant the key holds — the GET
     * ancestor walk for GET; for a mutation an exact route-entity grant OR, on an authoring deployment, the key's
     * namespace-writes opt-in at the deciding node (#1111, [EndpointGrants]) (§4.4 "visible"). The same decision runs
     * before an idempotent retry is replayed, because a retry passes through here first. A path's entities share
     * one workspace (the conflict refusal is per method ACROSS workspaces, so a second workspace can never publish
     * at a matching pattern), which is why one liveness answer covers the map; a dead workspace's path is unknown
     * to every key, the 404 rule again.
     */
    private fun visibleMethods(
        path: String,
        principal: AuthenticatedPrincipal,
    ): Map<EndpointMethod, PublishedEndpoint> {
        val matcher = registry.matcher()
        val live =
            buildMap {
                EndpointMethod.entries.forEach { method ->
                    matcher.match(path, method)?.let { match -> put(method, match.endpoint) }
                }
            }
        if (live.isEmpty()) return emptyMap()
        val workspaceId = live.values.first().workspaceId
        // §5.6 / D15 — one liveness answer covers the map (see KDoc); a dead workspace's path is
        // unknown to every key, the 404 rule again.
        if (!workspaceLiveness.isActive(workspaceId)) return emptyMap()

        requireNotNull(principal.keyId) { "an API-key principal always carries its key id" }
        // One snapshot for every method of this path: at most two binding reads per request (EndpointGrants).
        val granted = grants.at(path, principal, workspaceId)
        return live.filterValues { endpoint ->
            if (endpoint.method == EndpointMethod.GET) {
                granted.read() is EndpointAuthorizer.Decision.Allowed
            } else {
                granted.mayMutate(endpoint.id, endpoint.method)
            }
        }
    }

    /** Launch, await with the endpoint's timeout, and shape the answer (§5.4). */
    private fun run(
        endpoint: PublishedEndpoint,
        pointed: Pointed,
        principal: AuthenticatedPrincipal,
        validated: EndpointRequestValidator.Outcome.Valid,
    ): Outcome {
        val version = pointed.executable
        // Minted here so the 202 can NAME the execution the caller must come back for.
        val executionId = UUID.randomUUID()
        val request =
            ExecuteRequest(
                pipelineId = pointed.pipelineId,
                pipelineVersion = version.version,
                pipeline = version.pipeline,
                lakeExecutionMetadata =
                    co.datapipelines.pipeline.LakeExecutionMetadata
                        .from(version),
                lakeAppendGrant =
                    co.datapipelines.application.LakeAppendAuthorization.grant(
                        principal,
                        version.writesManagedLake,
                        java.time.Instant.now(),
                    ),
                userId = principal.userId,
                workspaceId = endpoint.workspaceId,
                parameters = validated.parameters,
                resultTtlSeconds = validated.ttlSeconds,
                triggeredVia = ExecutionTrigger.ENDPOINT,
                // D11 / #215 A.5: attributed to the key's own identity (`userId` above) and to the
                // credential's kind — derived, never hard-coded. The run is the KEY's: the key reads
                // it as its own, workspace admins see it, the key's creator does not.
                executedByKeyKind = principal.executedByKeyKind(),
                executionId = executionId,
                // #463: a published GET reads only — the database refuses a write where the dialect can.
                readOnly = true,
                // rootExecutionId stays null on purpose: a root request takes a concurrency slot,
                // and an endpoint serve must be governed by the same limits every other run is.
            )

        val deferred: Deferred<ExecutionResult> = scope.async { runner.run(request, endpoint.workspaceId, ExecutionTrigger.ENDPOINT) }
        val timeout = endpointsProperties.clampTimeout(pointed.api.body.timeoutSeconds)

        // #192 — the audit tells the truth about WHAT HAPPENED, in two rows. The first is
        // written BEFORE the answer and is what lets this key read the result later — including
        // the 202 case, where reading it later is the entire contract. The second is the
        // terminal outcome OBSERVED after the wait, so forensics can tell "still running when
        // we answered" from "finished" — the pre-#192 row guessed with `deferred.isCompleted`
        // sampled before the await, which recorded `started` for answers that had in fact
        // finished. The audit log is append-only evidence, so the outcome is a SECOND row
        // (AuditEventSink has no update), same event, `outcome` telling them apart.
        auditServe(endpoint, principal, executionId, OUTCOME_STARTED)

        val result: ExecutionResult? =
            runBlocking {
                try {
                    val awaited = withTimeoutOrNull(timeout.seconds) { deferred.await() }
                    // Cancels the AWAIT, never the run: the Deferred is owned by `scope`. A null
                    // here is the 202 branch — the execution continues without us.
                    val observed =
                        when {
                            awaited == null -> OUTCOME_ACCEPTED
                            awaited.status == ExecutionStatus.SUCCESS -> OUTCOME_COMPLETED
                            else -> OUTCOME_FAILED
                        }
                    // The terminal write is THIS method's to make, and it must survive the
                    // awaiting coroutine being cancelled out from under it — a cleanup on an
                    // already-cancelled job is silently skipped unless it runs NonCancellable.
                    withContext(NonCancellable) {
                        auditServe(endpoint, principal, executionId, observed)
                    }
                    awaited
                } catch (e: CancellationException) {
                    // The wait was cancelled from outside; the execution itself continues, so
                    // `accepted` is the truthful terminal even though no answer went out.
                    withContext(NonCancellable) {
                        auditServe(endpoint, principal, executionId, OUTCOME_ACCEPTED)
                    }
                    throw e
                } catch (
                    // The Deferred may fail with anything the run's coroutine threw; the
                    // terminal audit row must name it FAILED whichever exception type it is.
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    // The await died of something else (the run crashed the coroutine): the
                    // execution is over and it did not succeed.
                    withContext(NonCancellable) {
                        auditServe(endpoint, principal, executionId, OUTCOME_FAILED)
                    }
                    throw e
                }
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

    /** What one serve runs: the API's current version and the exact pipeline version it pins. */
    private data class Pointed(
        val api: PublishedEndpointVersion,
        val pipelineId: UUID,
        val executable: PipelineService.ExecutablePipeline,
    )

    /**
     * §5.1 as of #786 B.1 — the API's CURRENT version (its own pointer, never the pipeline's), when it is eligible for
     * this posture (D63: a RELEASED version always; a DRAFT only under the development posture), and the EXACT pipeline
     * version its body pins, when that is present, not DISCARDED and eligible too. Anything else — no current pointer,
     * an ineligible API version, a missing or DISCARDED pinned version — is the §5.6 `pipeline_not_released` refusal.
     * `findCurrentVersion` is never called here: moving the pipeline's current never moves what an API serves (A06).
     */
    @Suppress("ReturnCount") // one early null per link of the resolution chain above, in its order
    private fun pointedVersion(endpoint: PublishedEndpoint): Pointed? {
        val api = versions.findCurrent(endpoint.id) ?: return null
        if (!servable(api.status)) return null
        val record = pipelineRepository.findByName(endpoint.workspaceId, api.body.pipelineName) ?: return null
        val detail = pipelineRepository.findVersionDetail(endpoint.workspaceId, record.id, api.body.pipelineVersion) ?: return null
        if (!servable(detail.status)) return null
        val executable = pipelines.findExecutable(endpoint.workspaceId, ReadLens.Everything, record, detail.version) ?: return null
        return Pointed(api, record.id, executable)
    }

    /** The D63 rule, on its own so a test can read it: released always, a draft in development only. */
    internal fun servable(status: PipelineVersionStatus): Boolean =
        PipelineVersionStatus.eligibleForPointer(status, draftsEligible = authoring.developmentPosture)

    private fun authorize(
        path: String,
        principal: AuthenticatedPrincipal,
        endpoint: PublishedEndpoint,
    ): Outcome.Refused? {
        // #191 — the endpoint's OWN workspace: the query already filters to it (D11 — filter in
        // the query), and the authorizer re-checks in memory as the fail-closed backstop.
        return when (val decision = grants.at(path, principal, endpoint.workspaceId).read()) {
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
            "event=endpoint.pipeline_not_released path={} endpoint_id={} " +
                "message=\"endpoint is live but its current API version or the pipeline version it pins is not servable\"",
            endpoint.pathPattern,
            endpoint.id,
        )
        return Outcome.Refused(
            HTTP_SERVICE_UNAVAILABLE,
            PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED,
            "This endpoint's pipeline has no servable version.",
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
        /**
         * The serve audit's `outcome` vocabulary (#192): `started` (pre-answer, the key's read
         * ticket), then exactly one terminal row — `completed` (the run finished and succeeded),
         * `failed` (the run finished and did not), or `accepted` (the wait expired or the wait
         * itself was cancelled: the execution CONTINUES — "still running when we answered").
         */
        const val OUTCOME_STARTED = "started"
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_ACCEPTED = "accepted"

        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_NOT_ACCEPTABLE = 406
        const val HTTP_GONE = 410
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
