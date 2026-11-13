package co.datapipelines.web.ui

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant
import java.util.UUID

/**
 * The parameter-set release dialog's own facts (#876 §F.3, the family's first human release shell — the 2026-10-13
 * release-dependencies spec §9): which root — the set's working DRAFT, its version, its body hash and its last writer —
 * and nothing else. What the release covers is the shared plan ([ReleaseDialogModel], via [ReleasePreviews]). Reads
 * are [ReadLens.Everything]: the route is the author's (PARAMETER_SET_RELEASE), and the dialog judges the whole set.
 */
class ParameterSetLifecycleDialogModel(
    private val sets: ParameterSetService,
    private val actors: ActorNames,
) {
    data class Refusal(
        val code: String,
        val message: String,
    )

    data class ReleaseDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val updatedBy: String,
        val updatedAgo: String,
        val updatedAt: Instant?,
        /** The hash of the SAME draft these facts come from; the POST releases AT it. Empty on the no-draft branch. */
        val bodyHash: String,
        /** `parameter.version.not_draft`: the dialog opens and says why there is no button. */
        val refusal: Refusal?,
    )

    fun release(
        workspaceId: UUID,
        id: UUID,
    ): ReleaseDialog {
        val working =
            sets.findWorking(workspaceId, ReadLens.Everything, id)
                ?: throw DatapipelinesException(
                    ParameterErrorCodes.NOT_FOUND,
                    "Parameter set $id not found.",
                    mapOf("id" to id.toString()),
                )
        val detail = working.detail
        if (detail.status != PipelineVersionStatus.DRAFT) {
            return ReleaseDialog(
                id = id,
                name = working.record.name,
                version = detail.version,
                updatedBy = "",
                updatedAgo = "",
                updatedAt = null,
                bodyHash = "",
                refusal = Refusal(ParameterErrorCodes.VERSION_NOT_DRAFT, "This parameter set has no draft — nothing to release."),
            )
        }
        val writer = detail.updatedBy ?: detail.createdBy
        val writtenAt = detail.updatedAt ?: detail.createdAt
        return ReleaseDialog(
            id = id,
            name = working.record.name,
            version = detail.version,
            updatedBy = actors.lookup(listOf(writer))[writer] ?: ActorNames.fallback(writer),
            updatedAgo = RelativeTime.since(writtenAt, Instant.now()),
            updatedAt = writtenAt,
            bodyHash = detail.bodyHash,
            refusal = null,
        )
    }
}
