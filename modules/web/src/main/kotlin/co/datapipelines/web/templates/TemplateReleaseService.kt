package co.datapipelines.web.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * Template release and discard (versioning §5.3/§5.4, mirrored by §6) — the human half of
 * the template lifecycle.
 *
 * Release re-runs the template's own save-time validation on the draft content (§5.3
 * precondition 3's mirror: nothing is released the validator would refuse) and carries the
 * hash precondition on the flip itself. There is no pin-check precondition here — §6's
 * "templates lock first" rule is enforced at PIPELINE release, not template release.
 *
 * Discard is always a hard delete for templates: nothing references a `template_versions`
 * row by FK, so §3.4's executed-draft DISCARDED branch cannot fire.
 */
class TemplateReleaseService(
    private val templates: TemplateRepository,
    private val validator: TemplateValidator,
) {
    /** What a release produced: the released version detail and the stored template at it. */
    data class Released(
        val detail: TemplateVersionDetail,
        val template: co.datapipelines.templates.Template,
    )

    /**
     * Releases the template's DRAFT at [expectedHash].
     *
     * @throws DatapipelinesException `template.version.not_draft` or `template.version.conflict`
     *   (stale hash); `TemplateValidationException` when the draft content no longer validates.
     */
    @Suppress("ThrowsCount") // each throw is a distinct catalogued refusal the caller distinguishes
    fun release(
        workspaceId: UUID,
        id: String,
        expectedHash: String,
        actor: UUID,
    ): Released {
        val draft =
            templates.findDraftDetail(workspaceId, id)
                ?: throw notDraft(id)

        // Re-validate the draft content exactly as a save would — release is the final
        // save-time gate; it must not launder a draft the validator would refuse.
        val stored = templates.findVersion(workspaceId, id, draft.version) ?: throw notDraft(id)
        val redraft =
            TemplateDraft(
                id = stored.id,
                engine = stored.engine,
                dialect = stored.dialect,
                displayName = stored.displayName,
                description = stored.description,
                imports = stored.imports,
                body = stored.body,
                isLibrary = stored.isLibrary,
            )
        validator.validateOrThrow(redraft, workspaceId)

        val released =
            templates.releaseDraft(workspaceId, id, expectedHash, actor)
                ?: throw conflictAfterGuardFailure(workspaceId, id)
        val template = templates.findVersion(workspaceId, id, released.version) ?: throw notDraft(id)
        return Released(released, template)
    }

    /**
     * Discards the template's DRAFT at [expectedHash] (a hard delete — see the class KDoc).
     *
     * @throws DatapipelinesException `template.version.not_draft` or `template.version.conflict`.
     */
    fun discard(
        workspaceId: UUID,
        id: String,
        expectedHash: String,
    ) {
        templates.findDraftDetail(workspaceId, id) ?: throw notDraft(id)
        if (!templates.discardDraft(workspaceId, id, expectedHash)) {
            throw conflictAfterGuardFailure(workspaceId, id)
        }
    }

    private fun conflictAfterGuardFailure(
        workspaceId: UUID,
        id: String,
    ): DatapipelinesException {
        val current = templates.findDraftDetail(workspaceId, id) ?: throw notDraft(id)
        return DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_CONFLICT,
            message = "Template was modified by someone else after you loaded it.",
            details =
                mapOf(
                    "current_body_hash" to current.bodyHash,
                    "current_status" to current.status.name,
                    "updated_by" to (current.updatedBy?.toString() ?: ""),
                    "updated_at" to (current.updatedAt?.toString() ?: ""),
                ),
        )
    }

    private fun notDraft(id: String): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_NOT_DRAFT,
            message = "Template '$id' has no draft to release or discard.",
            details = mapOf("template_id" to id),
        )
}
