package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.transaction.annotation.Transactional
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
 * tested`. The draft verb is PURGE (§5.4, 101): the row is hard-deleted together with its
 * executions — the FK never forces a tombstone — and the sole-draft case takes the entity
 * row with it.
 */
open class PipelineReleaseService(
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
    open fun release(
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
        //
        // Only the nodes that HAVE a template pin. A PIPELINE node pins a child pipeline and a
        // CALCULATOR node evaluates a catalog function; neither declares a template, so
        // `node.template` is the empty default there and asking the registry about `@0` answers
        // MISSING — which refused the release of every pipeline containing one, naming
        // `template_id: ""`. Latent since composition shipped (a composite pipeline could be
        // created, but never re-released after an edit) and unmissable since D55, because now
        // EVERY pipeline needs a release. Found by `PromotionTwoDeploymentE2eTest`, whose parent
        // pipeline has a PIPELINE node.
        pipeline.nodes
            .filter { it.type != NodeType.PIPELINE && it.type != NodeType.CALCULATOR }
            .map { it.template }
            .forEach { ref ->
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

    /** What a draft purge did (§5.4, 101) — the row (and its executions) are gone either way. */
    sealed interface Purged {
        /** How many execution rows went with the draft (§5.4) — the audit detail. */
        val executionsDeleted: Int

        /** The draft went; other versions remain, so the entity stays. */
        data class Version(
            override val executionsDeleted: Int,
            val record: PipelineRecord,
        ) : Purged

        /** The draft was the ONLY version: the entity row went with it (§3.2). */
        data class Entity(
            override val executionsDeleted: Int,
        ) : Purged
    }

    /**
     * Purges the pipeline's DRAFT at [expectedHash] (versioning §5.4, 101): the row is
     * hard-deleted **together with its executions** — the pre-101 flip-to-DISCARDED branch
     * is withdrawn; development runs of a thing that never shipped are not history. When
     * the draft was the sole version the entity row goes too, and when the draft had
     * become `current_version` (the development fallback) the pointer recomputes.
     *
     * @throws DatapipelinesException `pipeline.authoring.disabled` (§5.5) or
     *   `pipeline.version.conflict` (stale hash / the draft vanished).
     */
    @Transactional("metadataTransactionManager")
    open fun purge(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
    ): Purged {
        // §5.5: purging authored content is authoring — a receiver's sole writer is promotion.
        authoring.requirePipelineAuthoring()

        return when (
            val outcome =
                pipelines.purgeDraft(
                    workspaceId,
                    pipelineId,
                    expectedHash,
                    draftEligible = authoring.developmentPosture,
                )
        ) {
            is PurgeOutcome.VersionPurged -> Purged.Version(outcome.executionsDeleted, outcome.record)
            is PurgeOutcome.EntityPurged -> Purged.Entity(outcome.executionsDeleted)
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
