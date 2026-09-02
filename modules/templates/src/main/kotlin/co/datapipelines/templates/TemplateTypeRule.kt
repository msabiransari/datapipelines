package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.DatapipelinesException

/**
 * The §5.3 write rule of 046 (template-hierarchy-design): a template's `type` is chosen at
 * **create** and never changes across its versions.
 *
 * Two resolutions, one per situation a write path faces:
 *
 * - **Create** ([forCreate]) — the payload's `type` when stated, else the `sql` default. This
 *   is the only place a null type is legitimate; the returned draft always carries a resolved
 *   type, which is what the repository stores.
 * - **Existing template** ([forExisting]) — the payload may echo the established type or omit
 *   it; a *different* type is refused with `template.validation.type_immutable`, and the
 *   returned draft carries the established type so the stored row's per-version contract stays
 *   self-contained (`chk_type_dialect` is a per-row invariant).
 *
 * The rule lives here rather than in the repository so the refusal carries a catalogued code
 * with the template's identity in `details`, and so every write surface that versions an
 * existing template — the PUT draft path, both import modes — enforces the identical rule.
 */
object TemplateTypeRule {
    /** The create-time resolution: payload type if stated, else `sql` (§5.4). */
    fun forCreate(draft: TemplateDraft): TemplateDraft = draft.copy(type = draft.type ?: TemplateType.SQL)

    /**
     * The existing-template resolution: inherit [established], or refuse a differing payload.
     *
     * @throws DatapipelinesException `template.validation.type_immutable`, with the requested
     *   and established types in `details`.
     */
    fun forExisting(
        draft: TemplateDraft,
        established: TemplateType,
    ): TemplateDraft {
        if (draft.type != null && draft.type != established) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.TYPE_IMMUTABLE,
                message =
                    "A template's type is chosen at creation and never changes: '$established' is established, " +
                        "the payload requests '${draft.type}'.",
                details = mapOf("type" to draft.type.wire, "established_type" to established.wire),
            )
        }
        return draft.copy(type = established)
    }
}
