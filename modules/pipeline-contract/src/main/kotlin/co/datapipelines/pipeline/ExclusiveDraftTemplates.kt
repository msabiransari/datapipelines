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
 * "Exclusive" is precise (§3.5): the template's ONLY version is a DRAFT, and no stored
 * pipeline version OTHER than [pipelineId]'s pins it — DRAFT, RELEASED or DISCARDED (owner
 * ruling R12, 2026-09-13: a discarded version can be restored and must keep what it runs).
 * It is this pipeline's private work-in-progress, orphaned by the purge, and safe to offer.
 *
 * #320: "no OTHER pinner" now spans every aggregate that pins templates — a parameter set or a visualization that
 * pins the draft template also keeps it out of the offer ([keptIds] names those, and why). The SQL of the
 * templates module still tests pipelines only, so the filter lives in the implementation this port is bound to.
 */
interface ExclusiveDraftTemplates {
    /**
     * The ids (template names) of the draft-only templates [pipelineId] pins exclusively — no other pipeline,
     * parameter set or visualization pins any version of them.
     */
    fun exclusiveIds(
        workspaceId: UUID,
        pipelineId: UUID,
    ): List<String>

    /**
     * The draft-only templates [pipelineId] pins that the offer SKIPS because a parameter set or a visualization also
     * pins them (#320, D5) — the purge proceeds and leaves them, and its response says which and why. Informational:
     * the safety is [exclusiveIds] leaving them out, so a double that keeps this default cannot delete a template
     * it should keep — it only fails to explain a skip.
     */
    fun keptIds(
        workspaceId: UUID,
        pipelineId: UUID,
    ): List<KeptDraftTemplate> = emptyList()

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

/**
 * A draft-only template the entity purge's offer skipped (#320, D5): [templateId], and who else pins it — under the
 * keys a `template.in_use` refusal already uses, `referencing_parameter_sets` and `referencing_visualizations`
 * (each present only when non-empty), so the two wire shapes read the same.
 */
data class KeptDraftTemplate(
    val templateId: String,
    val referencedBy: Map<String, List<String>>,
)
