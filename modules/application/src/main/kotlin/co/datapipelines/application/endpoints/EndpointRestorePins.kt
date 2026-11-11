package co.datapipelines.application.endpoints

import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleaseRefusal
import co.datapipelines.pipeline.release.ReleaseRefusals
import co.datapipelines.pipeline.release.RestoreBlocker
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * An API RESTORE's recheck of the restored version's outbound pin (record §24 "Restore checks outgoing pins
 * transitively", §25.4 "For API restore"; brief C.4). A restore lands the version RELEASED without a release's consent,
 * and a DISCARDED version protects nothing, so the pinned pipeline version — and everything IT pins — may have been
 * discarded or purged since. Runs inside the restore's guarded transaction; metadata reads only: no probe, no check
 * gate, no automatic release or repin.
 *
 * - The direct pin missing or DISCARDED → the native 400 `endpoint.pipeline_version.not_found`.
 * - The direct pin present but DRAFT → 409 `endpoint.release.dependency_not_released` with `reason: not_released`.
 * - Then the pinned version's closure through G1's planner walk, in the caller's transaction: any MISSING or DISCARDED
 *   node is the same 400 naming its field path; any DRAFT node the same 409 in the shared restore-blockers shape. The
 *   walk starts at the PIPELINE version (a DISCARDED API version is a leaf of every walk, so walking from it reads
 *   nothing); a cycle or limit refusal it raises is re-spelled with the API family's prefix, so the code a restore of an
 *   API answers always carries the API's family (record §14).
 */
class EndpointRestorePins(
    private val pipelines: PipelineRepository,
    /** G1's walk; null in a directly constructed unit test (the direct check alone). */
    private val planning: ReleasePlanningWalk?,
    private val maxBlockers: Int,
) {
    /** Refuses the restore of [restored] at [pathPattern] when its pin dangles (400) or reaches a draft (409). */
    @Suppress("ThrowsCount") // each refusal is its own catalogued code and field path, in record §25.4's order
    fun recheck(
        workspaceId: UUID,
        pathPattern: String,
        restored: PublishedEndpointVersion,
        actor: UUID,
    ) {
        val body = restored.body
        val root = ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, pathPattern, restored.version)
        val record = pipelines.findByNameAnyStatus(workspaceId, body.pipelineName) ?: throw dangling(body, PIN_PATH)
        val detail = pipelines.findVersionDetail(workspaceId, record.id, body.pipelineVersion)
        if (detail == null || detail.status == PipelineVersionStatus.DISCARDED) throw dangling(body, PIN_PATH)
        val pinned = PipelineDependencySource.identity(workspaceId, body.pipelineName, body.pipelineVersion)
        if (detail.status == PipelineVersionStatus.DRAFT) {
            throw ReleaseRefusals.restoreDependencyNotReleased(
                root,
                pathPattern,
                listOf(RestoreBlocker(pinned, body.pipelineName, ReleaseNodeStatus.DRAFT, PIN_PATH)),
                maxBlockers,
            )
        }
        transitive(root, pathPattern, pinned, body, actor)
    }

    @Suppress("ThrowsCount") // the walk's dangling node (400) and its drafts (409) are two catalogued refusals
    private fun transitive(
        root: ReleaseNodeIdentity,
        pathPattern: String,
        pinned: ReleaseNodeIdentity,
        body: EndpointVersionBody,
        actor: UUID,
    ) {
        val walk = planning ?: return
        val closure = respelled { walk.walkInCurrentTransaction(pinned, actor) } ?: throw dangling(body, PIN_PATH)
        val below = closure.nodes.drop(1)
        below.firstOrNull { it.status == ReleaseNodeStatus.MISSING || it.status == ReleaseNodeStatus.DISCARDED }?.let { node ->
            throw dangling(body, "$PIN_PATH.${closure.pathsTo(node.identity).first()}")
        }
        val drafts =
            below.filter { it.status == ReleaseNodeStatus.DRAFT }.flatMap { node ->
                closure.pathsTo(node.identity).map { path -> RestoreBlocker(node.identity, node.name, node.status, "$PIN_PATH.$path") }
            }
        if (drafts.isNotEmpty()) throw ReleaseRefusals.restoreDependencyNotReleased(root, pathPattern, drafts, maxBlockers)
    }

    /** The walk's cycle/limit refusals carry the PIPELINE root's prefix; the API restore answers its own family's. */
    private fun <T> respelled(block: () -> T): T =
        try {
            block()
        } catch (e: DatapipelinesException) {
            val refusal = ReleaseRefusal.entries.firstOrNull { e.code == ReleaseFamily.PIPELINE.code(it) } ?: throw e
            throw DatapipelinesException(ReleaseFamily.ENDPOINT.code(refusal), e.message.orEmpty(), e.details, e)
        }

    private fun dangling(
        body: EndpointVersionBody,
        path: String,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Endpoint.PIPELINE_VERSION_NOT_FOUND,
        message =
            "This API version pins '${body.pipelineName}' version ${body.pipelineVersion}, and a version it depends on is " +
                "missing or discarded; a restore lands RELEASED and may pin only live versions.",
        details = mapOf("pipeline" to body.pipelineName, "pipeline_version" to body.pipelineVersion, "path" to path),
    )

    private companion object {
        /** The field locator of an API version's one pin. */
        const val PIN_PATH = "pipeline_version"
    }
}
