package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The native checks of an API version's EXACT pipeline pin (published-endpoints §4.2, dependency-graph record §24 R3),
 * shared by every writer that stores or flips a pin — native creation, a draft save, the current switch and the release
 * participant — so no surface can store a pin another would refuse.
 *
 * Order, cheapest and most specific first: the named pipeline exists, the exact version exists and is not DISCARDED,
 * the version is admitted by the caller's [Admission], then the read-only rule and the path variables against THAT
 * version's body. Eligibility reads [AuthoringGuard.developmentPosture] — the posture the refusal path reads — and
 * never the mail policy's posture (A37).
 *
 * [pipelines] is resolved on first use: the pipeline service reaches the release coordinator, whose API participant
 * reaches this validator, so an eager reference is a construction cycle (the assembly passes a deferred lookup).
 */
class EndpointPinValidator(
    private val pipelineRepository: PipelineRepository,
    pipelines: Lazy<PipelineService>,
    private val readOnlyRule: ReadOnlyPipelineRule,
    private val authoring: AuthoringGuard,
) {
    private val pipelines by pipelines

    constructor(
        pipelineRepository: PipelineRepository,
        pipelines: PipelineService,
        readOnlyRule: ReadOnlyPipelineRule,
        authoring: AuthoringGuard,
    ) : this(pipelineRepository, lazyOf(pipelines), readOnlyRule, authoring)

    /** What a pin's status must be for the caller. */
    enum class Admission {
        /** A save or a current switch: `eligibleForPointer(status, posture)` (R3). */
        ELIGIBLE,

        /**
         * Any live version, DRAFT or RELEASED: a release's preparation (a DRAFT the same release flips first; the
         * coordinator's blockers refuse one it does not), and the receiver's import (E.1's status rule decides v1's).
         */
        LIVE,

        /** A release's in-transaction recheck and a restore's direct check: RELEASED only. */
        RELEASED_ONLY,
    }

    /** A pin that passed: its pipeline and the executable exact version. */
    data class Resolved(
        val record: PipelineRecord,
        val status: PipelineVersionStatus,
        val executable: PipelineService.ExecutablePipeline,
    )

    /** Whether [status] may be pointed at under this deployment's posture (the R3 rule, shared with serve). */
    fun eligible(status: PipelineVersionStatus): Boolean =
        PipelineVersionStatus.eligibleForPointer(status, draftsEligible = authoring.developmentPosture)

    /**
     * Checks [body]'s pin for an API at [parsed] in [workspaceId]; refuses with the catalogued code.
     *
     * [method] is the ENTITY's method (#1037): the §4.2 read-only rule is a GET rule — a GET route serves an
     * anonymous, side-effect-free read, while a mutation route exists to admit a writing pipeline (published-mutation-
     * apis §3.1) — so the check runs for GET only, and every other pin check (existence, exactness, admission,
     * declared variables) runs for every method. Defaults to GET: the pre-#1037 callers, and every surface that
     * addresses a route by path alone.
     */
    @Suppress("ThrowsCount") // each precondition is its own refusal, and each names itself
    fun check(
        workspaceId: UUID,
        parsed: EndpointPath.Parsed,
        body: EndpointVersionBody,
        admission: Admission,
        method: EndpointMethod = EndpointMethod.GET,
    ): Resolved {
        val name = body.pipelineName
        val record =
            pipelineRepository.findByName(workspaceId, name)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.NOT_FOUND,
                    message = "No pipeline named '$name' in this workspace.",
                    details = mapOf("pipeline" to name),
                )
        val detail = pipelineRepository.findVersionDetail(workspaceId, record.id, body.pipelineVersion)
        if (detail == null || detail.status == PipelineVersionStatus.DISCARDED) throw versionNotFound(body)
        val admitted =
            when (admission) {
                Admission.ELIGIBLE -> eligible(detail.status)
                Admission.LIVE -> detail.status == PipelineVersionStatus.RELEASED || detail.status == PipelineVersionStatus.DRAFT
                Admission.RELEASED_ONLY -> detail.status == PipelineVersionStatus.RELEASED
            }
        if (!admitted) throw notEligible(body, detail.status)
        val executable =
            pipelines.findExecutable(workspaceId, ReadLens.Everything, record, body.pipelineVersion) ?: throw versionNotFound(body)
        if (method == EndpointMethod.GET) requireReadOnly(executable, workspaceId, name)
        requireBodyDeliverable(method, executable, name)
        requireDeclaredVariables(parsed, executable, name)
        return Resolved(record, detail.status, executable)
    }

    /**
     * The request-body contract of [pipelineName]@[pipelineVersion] in [workspaceId] (#1040) — what the authoring
     * surfaces show beside the exact pin before a save; null when the pipeline or that exact version is gone. A read,
     * never a check: refusals stay [check]'s.
     */
    fun bodyContract(
        workspaceId: UUID,
        pipelineName: String,
        pipelineVersion: Int,
    ): EndpointBodyContract? {
        val record = pipelineRepository.findByName(workspaceId, pipelineName) ?: return null
        val executable = pipelines.findExecutable(workspaceId, ReadLens.Everything, record, pipelineVersion) ?: return null
        return EndpointBodyContract.of(executable.pipeline)
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
                "'$pipelineName' v${version.version} cannot be served as a GET endpoint: ${first.message} " +
                    "A published endpoint serves GET, which must be side-effect-free.",
            details = mapOf("pipeline" to pipelineName, "node_id" to first.details["node_id"], "via" to first.details["via"]),
        )
    }

    /**
     * #1038 (published-mutation-apis §3.3, "at publish, verify that the pinned pipeline admits this input") — a GET
     * carries no body, so a GET route cannot serve a pipeline whose `request_body` is required: every call would be
     * refused. An OPTIONAL body is fine on GET (the transformer sees it absent), and every mutation method may carry
     * one. Whether each reader's template admits a body input is the pipeline's own save-time rule (§12.13).
     */
    private fun requireBodyDeliverable(
        method: EndpointMethod,
        version: PipelineService.ExecutablePipeline,
        pipelineName: String,
    ) {
        if (method != EndpointMethod.GET || version.pipeline.requestBody?.required != true) return
        throw DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.REQUEST_BODY_INCOMPATIBLE,
            message =
                "'$pipelineName' v${version.version} requires a request body, which a GET route cannot deliver. " +
                    "Publish it under POST, PUT or DELETE, or make its request_body optional.",
            details = mapOf("pipeline" to pipelineName, "method" to method.name),
        )
    }

    /** §4.2 — every path variable names a declared parameter (a SUBSET check: the rest come from the query string). */
    private fun requireDeclaredVariables(
        parsed: EndpointPath.Parsed,
        version: PipelineService.ExecutablePipeline,
        pipelineName: String,
    ) {
        val undeclared = parsed.variableNames.filterNot { it in version.pipeline.parameters }
        if (undeclared.isEmpty()) return
        throw DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PATH_VARIABLE_UNKNOWN,
            message =
                "The path binds ${undeclared.joinToString(", ") { "{$it}" }}, which '$pipelineName' " +
                    "v${version.version} does not declare. It declares: ${version.pipeline.parameters.keys.sorted()}.",
            details = mapOf("path_pattern" to parsed.pattern, "undeclared" to undeclared),
        )
    }

    private fun versionNotFound(body: EndpointVersionBody) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PIPELINE_VERSION_NOT_FOUND,
            message = "'${body.pipelineName}' has no live version ${body.pipelineVersion} to pin.",
            details = mapOf("pipeline" to body.pipelineName, "pipeline_version" to body.pipelineVersion),
        )

    private fun notEligible(
        body: EndpointVersionBody,
        status: PipelineVersionStatus,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Endpoint.PIPELINE_VERSION_NOT_ELIGIBLE,
        message =
            "'${body.pipelineName}' version ${body.pipelineVersion} is ${status.name}, which this deployment may not pin: " +
                "a RELEASED version always; a DRAFT only where authoring is enabled.",
        details = mapOf("pipeline" to body.pipelineName, "pipeline_version" to body.pipelineVersion, "status" to status.name),
    )
}
