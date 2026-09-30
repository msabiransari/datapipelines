package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.CurrentPipelineVersion
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.CurrentTemplateVersion
import co.datapipelines.visualization.CurrentArtifactVersion
import co.datapipelines.visualization.DashboardBody

/**
 * **What this workspace could promote to the target, right now** — versioning §10.2's rule,
 * computed ONCE for every family and consumed twice: by the promotion page's listing
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
 *
 * ## The two transfer families (#10 L1c)
 * [dashboards] are the ADMITTED dashboards — every source pipeline [pipelineLens] admits AND
 * newer than the target's entry (the "newer than the promotion target" arm is real now that the
 * wire carries a dashboard inventory; a target-held dashboard at the same version and hash is
 * HIDDEN — proven red-first in the lens test). [dashboardsLens] is their names — the promoter's
 * `dashboard.read` lens, L1b's rule with its arm finally load-bearing. [visualizations] are the
 * pins of admitted dashboards that are ALSO newer than the target — the promotion page's rows.
 * [visualizationsLens] is ALL the pins of admitted dashboards WITHOUT the newer filter, because
 * §6.1's confirmed rule admits a visualization by its dashboard's admission alone: a dashboard
 * can be promotable on a NEW version while pinning the SAME visualization the target already
 * holds, and hiding that pin would blind the read the promoter judges the promotion with.
 */
class PromotableView private constructor(
    /** §10.2's set for pipelines, name-ordered. */
    val pipelines: List<Candidate>,
    /** The same set for templates. */
    val templates: List<Candidate>,
    /** #194 lane D — the same set for parameter sets (the record's §8.3). */
    val parameterSets: List<Candidate>,
    /** #10 L1c — the admitted dashboards' pins that are ALSO newer than the target, name-ordered. */
    val visualizations: List<Candidate>,
    /** #10 L1c — the admitted dashboards: pipeline-lens-true AND newer than the target, name-ordered. */
    val dashboards: List<Candidate>,
    /** The current RELEASED bodies of [dashboards] — the pins the visualization lens reads. */
    private val dashboardsBodies: Map<String, DashboardBody>,
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
    val parameterSetLens: ReadLens = ReadLens.Only(parameterSets.mapTo(LinkedHashSet()) { it.name })

    /** #10 L1c — the promoter's visualization lens: ALL the pins of admitted dashboards (§6.1's rule; see the class KDoc). */
    val visualizationsLens: ReadLens =
        ReadLens.Only(
            dashboardsBodies.values.flatMapTo(LinkedHashSet()) { body -> body.visualizations.map { it.visualization.name } },
        )

    /** #10 L1c — the promoter's dashboard lens: the admitted dashboards (sources admitted AND newer than the target). */
    val dashboardsLens: ReadLens = ReadLens.Only(dashboards.mapTo(LinkedHashSet()) { it.name })

    /** The listing row for [name], or null when [name] is not promotable — the promote path's root guard reads this. */
    fun pipeline(name: String): Candidate? = pipelines.firstOrNull { it.name == name }

    /** The set arm of the same guard. */
    fun parameterSet(name: String): Candidate? = parameterSets.firstOrNull { it.name == name }

    companion object {
        /** The rule over the local current-version lists and the target's inventory. */
        fun of(
            localPipelines: List<CurrentPipelineVersion>,
            localTemplates: List<CurrentTemplateVersion>,
            inventory: PromotionWire.Inventory,
            localParameterSets: List<co.datapipelines.parameters.CurrentParameterSetVersion> = emptyList(),
            localVisualizations: List<CurrentArtifactVersion> = emptyList(),
            localDashboards: List<CurrentArtifactVersion> = emptyList(),
            dashboardBodies: Map<String, DashboardBody> = emptyMap(),
        ): PromotableView {
            val pipelinesOnTarget = inventory.pipelineByName()
            val templatesOnTarget = inventory.templateById()
            val setsOnTarget = inventory.parameterSetByName()
            val dashboardsOnTarget = inventory.dashboardByName()
            val visualizationsOnTarget = inventory.visualizationByName()

            val pipelines =
                localPipelines
                    .mapNotNull { local ->
                        candidate(local.name, local.displayName, local.version, local.bodyHash, pipelinesOnTarget[local.name])
                    }.sortedBy { it.name }
            val pipelineLens = ReadLens.Only(pipelines.mapTo(LinkedHashSet()) { it.name })

            // The admitted dashboards (L1b's derivation, its "newer than the target" arm real since the wire
            // carries the inventory): every source pipeline the PIPELINE lens admits, and rules 2–4 against
            // the target's own entry. A dashboard whose body is unread is not admitted (fail closed).
            val admittedBodies: Map<String, DashboardBody> =
                localDashboards
                    .mapNotNull { local -> dashboardBodies[local.name]?.let { local.name to it } }
                    .toMap()
                    .filterValues { body -> body.sources.all { pipelineLens.admits(it.pipeline.name) } }
            val dashboards =
                localDashboards
                    .mapNotNull { local ->
                        val body = admittedBodies[local.name] ?: return@mapNotNull null
                        candidate(local.name, local.displayName, local.version, local.bodyHash, dashboardsOnTarget[local.name])
                    }.sortedBy { it.name }
            val admittedDashboardsBodies = dashboards.mapNotNull { admittedBodies[it.name] }

            val visualizations =
                localVisualizations
                    .mapNotNull { local ->
                        val admittedPin =
                            admittedDashboardsBodies.any { body -> body.visualizations.any { it.visualization.name == local.name } }
                        if (!admittedPin) return@mapNotNull null
                        candidate(local.name, local.displayName, local.version, local.bodyHash, visualizationsOnTarget[local.name])
                    }.sortedBy { it.name }

            return PromotableView(
                pipelines = pipelines,
                templates =
                    localTemplates
                        .mapNotNull { local ->
                            candidate(local.id, local.displayName, local.version, local.bodyHash, templatesOnTarget[local.id])
                        }.sortedBy { it.name },
                parameterSets =
                    localParameterSets
                        .mapNotNull { local ->
                            candidate(local.name, local.displayName, local.version, local.bodyHash, setsOnTarget[local.name])
                        }.sortedBy { it.name },
                visualizations = visualizations,
                dashboards = dashboards,
                dashboardsBodies = admittedBodies.filterKeys { key -> dashboards.any { it.name == key } },
                examinedPipelines = localPipelines.size,
                examinedTemplates = localTemplates.size,
            )
        }

        /**
         * Rules 2–4 for one object that satisfies rule 1 (it is a current version): promotable
         * against the target's [target] entry, or null. Spelled once and used by every arm —
         * pipelines, templates, sets, and since L1c the two transfer families.
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
