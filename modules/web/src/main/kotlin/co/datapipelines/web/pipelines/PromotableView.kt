package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.CurrentPipelineVersion
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.CurrentTemplateVersion

/**
 * **What this workspace could promote to the target, right now** — versioning §10.2's rule,
 * computed ONCE for both kinds and consumed twice: by the promotion page's listing
 * (`PromotionService.plan`) and by the promoter LENS on every read a promoter makes (roles
 * design §3.1, 178). One function, so the page and the lens cannot disagree.
 *
 * ## The rule, four lines, the same for a pipeline and a template
 * A local object is promotable iff
 * 1. it HAS a current version — RELEASED by the pointer invariant (a never-released object has
 *    no row in either `findCurrentVersions`, D55), and
 * 2. the target LACKS the name (a pipeline the target does not have counts as version 0), or
 * 3. the content hash DIFFERS from the target's (same hash ⇒ nothing to push, whatever the
 *    numbers say — version is for humans and hash is for machines) AND
 * 4. the local version is strictly GREATER than the target's.
 *
 * The template arm is exactly the pipeline arm with a template's id for its name. Before 178
 * it was implied by the push closure's skip rule and stated nowhere; the owner's ruling makes
 * a promoter see "released templates newer than the higher environment's" too, so it is
 * stated here and tested as its own case.
 *
 * ## What the lens takes from it
 * [pipelineLens] and [templateLens] are the promotable NAMES — the whole of what a promoter
 * sees. Drafts are absent (rule 1), already-promoted objects are absent (rules 3–4), and the
 * only way to widen the view is to release something newer here or to have the target
 * fall behind, which is the owner's intent verbatim.
 */
class PromotableView private constructor(
    /** §10.2's set for pipelines, name-ordered. */
    val pipelines: List<Candidate>,
    /** The same set for templates. */
    val templates: List<Candidate>,
    /** How many live pipelines held a current version — so an empty listing reads as "in sync", not "broken". */
    val examinedPipelines: Int,
    val examinedTemplates: Int,
) {
    /** One row of §10.2's listing: what the target has, what this deployment would send. */
    data class Candidate(
        val name: String,
        val displayName: String,
        val localVersion: Int,
        /** The target's current version, or 0 when the target does not have this object (§10.2). */
        val targetVersion: Int,
        val bodyHash: String,
    )

    val pipelineLens: ReadLens = ReadLens.Only(pipelines.mapTo(LinkedHashSet()) { it.name })
    val templateLens: ReadLens = ReadLens.Only(templates.mapTo(LinkedHashSet()) { it.name })

    /** The listing row for [name], or null when [name] is not promotable — the promote path's root guard reads this. */
    fun pipeline(name: String): Candidate? = pipelines.firstOrNull { it.name == name }

    companion object {
        /** The rule over the two local current-version lists and the target's inventory. */
        fun of(
            localPipelines: List<CurrentPipelineVersion>,
            localTemplates: List<CurrentTemplateVersion>,
            inventory: PromotionWire.Inventory,
        ): PromotableView {
            val pipelinesOnTarget = inventory.pipelineByName()
            val templatesOnTarget = inventory.templateById()
            return PromotableView(
                pipelines =
                    localPipelines
                        .mapNotNull { local ->
                            candidate(local.name, local.displayName, local.version, local.bodyHash, pipelinesOnTarget[local.name])
                        }.sortedBy { it.name },
                templates =
                    localTemplates
                        .mapNotNull { local ->
                            candidate(local.id, local.displayName, local.version, local.bodyHash, templatesOnTarget[local.id])
                        }.sortedBy { it.name },
                examinedPipelines = localPipelines.size,
                examinedTemplates = localTemplates.size,
            )
        }

        /**
         * Rules 2–4 for one object that satisfies rule 1 (it is a current version): promotable
         * against the target's [target] entry, or null. Spelled once and used by both arms.
         */
        fun isNewer(
            localVersion: Int,
            localHash: String,
            target: PromotionWire.Entry?,
        ): Boolean = target == null || (target.bodyHash != localHash && localVersion > target.currentVersion)

        private fun candidate(
            name: String,
            displayName: String,
            version: Int,
            bodyHash: String,
            target: PromotionWire.Entry?,
        ): Candidate? =
            if (isNewer(version, bodyHash, target)) {
                Candidate(name, displayName, version, target?.currentVersion ?: 0, bodyHash)
            } else {
                null
            }
    }
}
