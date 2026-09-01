package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The template-side write rule (versioning §6: same lifecycle as pipelines, plus the pin
 * rule) — the service `PUT /api/v1/templates/{id}` goes through.
 *
 * A template draft versions the **content fields**; `display_name` / `description` move on
 * the index row at save time (the documented asymmetry — they are not part of the versioned
 * artifact). Templates have no rename, so §3.5's name-uniqueness check does not apply.
 *
 * The hash precondition is identical to pipelines' (§4.2): [expectedHash] must equal the
 * stored hash of the version the caller based its edit on — the DRAFT's for an in-place
 * write, the current RELEASED row's for a first write. Zero rows ⇒ 409
 * `template.version.conflict` with the current state in `details`.
 */
class TemplateDraftService(
    private val templates: TemplateRepository,
) {
    /**
     * Writes [draft] as the template's version — creating the draft first when the caller is
     * the first writer after a release (§5.1), overwriting it in place otherwise (§5.2).
     *
     * A save whose CONTENT already equals the released content is a no-op (§5.1): the
     * returned detail carries `status = RELEASED` and no draft was created — but index
     * metadata (`display_name`/`description`) still moved, per §6's asymmetry. A draft
     * edited back to its released parent is left alone; discard stays explicit.
     *
     * @throws DatapipelinesException `template.not_found`, or `template.version.conflict`
     *   (stale [expectedHash], with the current hash/author in details).
     */
    fun write(
        workspaceId: UUID,
        id: String,
        draft: TemplateDraft,
        expectedHash: String,
        actor: UUID,
    ): TemplateVersionDetail {
        if (templates.findLatest(workspaceId, id) == null) throw notFound(id)

        val existingDraft = templates.findDraftDetail(workspaceId, id)
        if (existingDraft != null) {
            templates.writeDraft(workspaceId, id, draft, expectedHash, actor)?.let { return it }
            // No rows: stale hash, or the draft was discarded mid-write — fall through to
            // the create branch, whose guard decides.
        }
        return templates.createDraft(workspaceId, id, draft, expectedHash, actor)
            ?: throw staleBase(workspaceId, id)
    }

    private fun staleBase(
        workspaceId: UUID,
        id: String,
    ): DatapipelinesException {
        val draft = templates.findDraftDetail(workspaceId, id)
        val current =
            draft
                ?: templates.findLatest(workspaceId, id)?.let { templates.findVersionDetail(workspaceId, id, it.version) }
        return DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_CONFLICT,
            message = "Template was modified by someone else after you loaded it.",
            details =
                mapOf(
                    "current_body_hash" to (current?.bodyHash ?: ""),
                    "current_status" to (current?.status?.name ?: "UNKNOWN"),
                    "updated_by" to (current?.updatedBy?.toString() ?: ""),
                    "updated_at" to (current?.updatedAt?.toString() ?: ""),
                ),
        )
    }

    private fun notFound(id: String): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.NOT_FOUND,
            message = "Template '$id' not found.",
            details = mapOf("template_id" to id),
        )
}
