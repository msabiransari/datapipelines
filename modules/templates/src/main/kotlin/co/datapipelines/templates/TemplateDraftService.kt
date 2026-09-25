package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
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
 *
 * ## The citations ride the same write (7e, transform-nodes design §2.3)
 *
 * `implements` is not content: it is outside the hash, so it never decides whether a draft is
 * opened — the §5.1 no-op still returns the RELEASED version, and a write that changes ONLY the
 * citations lands them on that released version (the one post-release write templates.md §5.1
 * allows). After the content write, the version it answered with gets the citations:
 *  - `implements` stated → exactly that list (`[]` clears);
 *  - absent (null) → INHERITED (owner ruling 2026-09-25): an in-place draft write and a
 *    no-op keep their own; a write that OPENED a new draft copies the released version's
 *    forward, so an edit that did not mention them — the transform face's Save draft never
 *    does — cannot silently drop the semantic link.
 * The ids were validated by [TemplateValidator] before the call (every surface validates
 * first). Deliberately not one transaction with the content write: the draft write recovers
 * from a lost race by reading the winner after a unique violation, which an enclosing
 * transaction would abort (`PipelineService.update`'s finding). Each statement is atomic on its
 * own; a failure between them leaves the version with its previous citations — a claim, never
 * content — and the call fails loudly.
 */
class TemplateDraftService(
    private val templates: TemplateRepository,
    private val authoring: co.datapipelines.pipeline.AuthoringGuard,
    /** 7e — the `template_implements` rows the write lands its citations in. */
    private val citations: TemplateImplementsRepository,
) {
    /**
     * Creates the template (version 1, [lifecycle]) from the ALREADY VALIDATED [draft] and lands
     * its stated `implements` on that version — the create half of the rule above (a new
     * template has nothing to inherit). Returns the stored projection, citations included.
     *
     * @throws DatapipelinesException `template.authoring.disabled`, or the repository's
     *   `template.validation.duplicate_name`.
     */
    fun create(
        workspaceId: UUID,
        draft: TemplateDraft,
        actor: UUID,
        lifecycle: co.datapipelines.pipeline.CreateLifecycle,
        via: co.datapipelines.pipeline.WriteSurface,
    ): Template {
        authoring.requireTemplateAuthoring()
        val created = templates.create(workspaceId, draft, actor, lifecycle, via)
        val cited = draft.implements?.let { ImplementsIds.parseLenient(it) }
        if (cited.isNullOrEmpty()) return created
        citations.replace(workspaceId, created.id, created.version, cited)
        return templates.findVersion(workspaceId, created.id, created.version) ?: created
    }

    /**
     * Writes [draft] as the template's version — creating the draft first when the caller is
     * the first writer after a release (§5.1), overwriting it in place otherwise (§5.2).
     *
     * A save whose CONTENT already equals the released content is a no-op (§5.1): the
     * returned detail carries `status = RELEASED` and no draft was created — but index
     * metadata (`display_name`/`description`) still moved, per §6's asymmetry. A draft
     * edited back to its released parent is left alone; discard stays explicit.
     *
     * @throws DatapipelinesException `template.not_found`, `template.authoring.disabled`
     *   (receiver write path, versioning §5.5), `template.version.conflict`
     *   (stale [expectedHash], with the current hash/author in details), or
     *   `template.validation.type_immutable` (046 §5.3: the payload carries a `type` other
     *   than the template's established one).
     */
    fun write(
        workspaceId: UUID,
        id: String,
        draft: TemplateDraft,
        expectedHash: String,
        actor: UUID,
        via: co.datapipelines.pipeline.WriteSurface,
    ): TemplateVersionDetail {
        // §5.5: the template mirror of the pipeline guard — fail-closed at the write path.
        authoring.requireTemplateAuthoring()

        // The WORKING version, not the released one (D55): between a template's creation and its
        // first release there IS no released projection, and reading one would refuse the second
        // write to a draft the same caller had just created.
        val latest =
            templates.findWorking(workspaceId, id) ?: throw notFound(id)

        // 046 §5.3: the draft inherits the template's established type; a payload carrying a
        // different one is refused here, and every row the write paths below store carries the
        // resolved type so the per-version contract stays self-contained.
        val resolved = TemplateTypeRule.forExisting(draft, latest.type)

        val existingDraft = templates.findDraftDetail(workspaceId, id)
        if (existingDraft != null) {
            templates.writeDraft(workspaceId, id, resolved, expectedHash, actor, via)?.let {
                return cite(workspaceId, id, it, resolved.implements, inheritFrom = null)
            }
            // No rows: stale hash, or the draft was discarded mid-write — fall through to
            // the create branch, whose guard decides.
        }
        val written =
            templates.createDraft(workspaceId, id, resolved, expectedHash, actor, via)
                ?: throw staleBase(workspaceId, id)
        // A NEW draft (not the §5.1 no-op, which answers RELEASED) inherits from the released
        // version its hash was checked against — `current_version` by the guard's own predicate.
        val base =
            if (written.status == PipelineVersionStatus.DRAFT && latest.type.isTransform) {
                templates.findLatest(workspaceId, id)?.version
            } else {
                null
            }
        return cite(workspaceId, id, written, resolved.implements, inheritFrom = base)
    }

    /** Lands the citations on the [written] version: the stated list, else the inherited one, else unchanged. */
    private fun cite(
        workspaceId: UUID,
        id: String,
        written: TemplateVersionDetail,
        stated: List<String>?,
        inheritFrom: Int?,
    ): TemplateVersionDetail {
        when {
            stated != null -> citations.replace(workspaceId, id, written.version, ImplementsIds.parseLenient(stated))
            inheritFrom != null && inheritFrom != written.version -> citations.copy(workspaceId, id, inheritFrom, written.version)
        }
        return written
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
