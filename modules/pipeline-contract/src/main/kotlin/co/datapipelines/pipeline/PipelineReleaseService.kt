package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * Pipeline release and discard (versioning §5.3/§5.4) — the human half of the lifecycle.
 *
 * Release is an explicit, UI-driven action (D4: agents never release; the REST endpoint
 * exists for the editor, and an agent holding a raw MUTATE-scoped key calling it directly
 * is acceptable — "not in the agent" means no first-class tool surface). Its three
 * preconditions, evaluated server-side in order before the one-statement flip:
 *
 * 1. A DRAFT exists (`pipeline.version.not_draft` otherwise — there is nothing to release).
 * 2. Full pipeline-contract §12 validation re-runs on the draft body. Release is the final
 *    save-time gate; nothing is released the validator would refuse.
 * 3. **Every template version pinned by the draft body is RELEASED** (versioning §6 —
 *    templates lock first). A pin on a DRAFT template version fails with
 *    `pipeline.release.template_not_released` naming the template and version; pinning a
 *    draft template from a draft pipeline is legal while iterating and only becomes an
 *    error here.
 *
 * The hash precondition (§4.2) rides the flip statement itself: `you release what you
 * tested`. Discard mirrors it (§5.4): delete a never-executed draft, flip an executed one
 * to DISCARDED — the `pipeline_executions` composite FK decides which, transparently to
 * the caller.
 */
class PipelineReleaseService(
    private val pipelines: PipelineRepository,
    private val templates: TemplateVersionStatuses,
    private val validator: PipelineValidator,
    private val authoring: AuthoringGuard,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
) {
    /** What a release produced: the bumped record, the released version, the released body. */
    data class Released(
        val record: PipelineRecord,
        val version: PipelineVersionDetail,
        val bodyJson: String,
    )

    /**
     * Releases the pipeline's DRAFT at [expectedHash].
     *
     * @throws DatapipelinesException / [PipelineValidationException]:
     *   `pipeline.version.not_draft`, §12 validation codes re-run on the draft body,
     *   `pipeline.release.template_not_released`, `pipeline.version.conflict` (stale hash).
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued code
    fun release(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
        actor: UUID,
    ): Released {
        // §5.5: release is an authoring action — a promotion receiver refuses it.
        authoring.requirePipelineAuthoring()

        val draft =
            pipelines.findDraftDetail(workspaceId, pipelineId)
                ?: throw notDraft(pipelineId)

        // The body that will become immutable — re-validate it exactly as a save would
        // (§5.3 precondition 3), so release can never launder an invalid draft.
        val bodyJson =
            pipelines.findVersionBody(workspaceId, pipelineId, draft.version)
                ?: throw notDraft(pipelineId)
        val pipeline = deserializer.readOrThrow(bodyJson)
        validator.validateOrThrow(pipeline, workspaceId)

        // §6: templates lock first — a DRAFT template pin blocks the pipeline's release.
        pipeline.nodes.map { it.template }.forEach { ref ->
            val status = templates.statusOf(workspaceId, ref.id, ref.version)
            if (status != PipelineVersionStatus.RELEASED) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NOT_RELEASED,
                    message = "Template '${ref.id}' version ${ref.version} is not released; release the template first.",
                    details =
                        mapOf(
                            "template_id" to ref.id,
                            "template_version" to ref.version,
                            "template_status" to (status?.name ?: "MISSING"),
                        ),
                )
            }
        }

        val released =
            pipelines.releaseDraft(
                workspaceId = workspaceId,
                pipelineId = pipelineId,
                name = pipeline.name,
                displayName = pipeline.displayName,
                description = pipeline.description,
                expectedHash = expectedHash,
                actor = actor,
            ) ?: throw conflictAfterGuardFailure(workspaceId, pipelineId)
        return Released(released.record, released.version, bodyJson)
    }

    /** What a discard did (§5.4) — both are a success to the caller. */
    sealed interface Discarded {
        /** A never-executed draft: the row is gone and the version number returns to the pool. */
        data object Deleted : Discarded

        /** An executed draft: history keeps the number, the row flipped to DISCARDED. */
        data class Flipped(
            val version: PipelineVersionDetail,
        ) : Discarded
    }

    /**
     * Discards the pipeline's DRAFT at [expectedHash] — hard-delete when never executed,
     * DISCARDED-flip when the executions FK blocks the delete.
     *
     * @throws DatapipelinesException `pipeline.authoring.disabled` (§5.5),
     *   `pipeline.version.not_draft` or `pipeline.version.conflict`.
     */
    fun discard(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
    ): Discarded {
        // §5.5: discard is an authoring action — a promotion receiver refuses it.
        authoring.requirePipelineAuthoring()

        return when (val outcome = pipelines.discardDraft(workspaceId, pipelineId, expectedHash)) {
            DiscardOutcome.Deleted -> Discarded.Deleted
            is DiscardOutcome.FlippedToDiscarded -> Discarded.Flipped(outcome.detail)
            null -> throw conflictAfterGuardFailure(workspaceId, pipelineId)
        }
    }

    /**
     * The guard failed AFTER we saw a draft — either the hash went stale under us (409
     * conflict with the current state) or the draft vanished (discard raced us: not_draft).
     */
    private fun conflictAfterGuardFailure(
        workspaceId: UUID,
        pipelineId: UUID,
    ): DatapipelinesException {
        val current = pipelines.findDraftDetail(workspaceId, pipelineId) ?: throw notDraft(pipelineId)
        return DatapipelinesException(
            code = PipelineErrorCodes.Versioning.VERSION_CONFLICT,
            message = "Pipeline was modified by someone else after you loaded it.",
            details =
                mapOf(
                    "current_body_hash" to current.bodyHash,
                    "current_status" to current.status.name,
                    "updated_by" to (current.updatedBy?.toString() ?: ""),
                    "updated_at" to (current.updatedAt?.toString() ?: ""),
                ),
        )
    }

    private fun notDraft(pipelineId: UUID): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.NOT_DRAFT,
            message = "Pipeline '$pipelineId' has no draft to release or discard.",
            details = mapOf("pipeline_id" to pipelineId.toString()),
        )
}
