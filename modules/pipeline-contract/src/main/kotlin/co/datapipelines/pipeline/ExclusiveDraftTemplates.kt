package co.datapipelines.pipeline

import java.util.UUID

/**
 * "Which DRAFT-ONLY templates does this draft pipeline pin exclusively?" — the one fact
 * the pipeline aggregate needs from `templates` for the entity purge's offer (versioning
 * §3.5, 101), plus the purge of an offered template.
 *
 * Declared here as a port for the same reason [TemplateVersionStatuses] is: the module
 * arrow runs `templates → pipeline-contract`, never the reverse (module-structure §4.2),
 * and the aggregation layer supplies the implementation over `TemplateRepository` — the
 * established pattern, not a new one.
 *
 * "Exclusive" is precise (§3.5): the template's ONLY version is a DRAFT, and no live
 * pipeline version OTHER than [pipelineId]'s pins it — it is this pipeline's private
 * work-in-progress, orphaned by the purge, and safe to offer.
 */
interface ExclusiveDraftTemplates {
    /** The ids (template names) of the draft-only templates [pipelineId] pins exclusively. */
    fun exclusiveIds(
        workspaceId: UUID,
        pipelineId: UUID,
    ): List<String>

    /**
     * Purges one offered template: its only version is a DRAFT (the caller verified it
     * through [exclusiveIds]) and its sole pinner — the purged pipeline — is already gone,
     * so the delete is a plain entity delete. Refuses with `template.in_use` semantics if
     * a pin appeared since; the caller's transaction rolls the whole purge back.
     */
    fun purge(
        workspaceId: UUID,
        templateId: String,
    )
}
