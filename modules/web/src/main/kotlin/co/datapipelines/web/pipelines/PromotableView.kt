package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.CurrentPipelineVersion
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.CurrentTemplateVersion
import co.datapipelines.visualization.CurrentArtifactVersion
import co.datapipelines.visualization.DashboardCurrentPins

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
 * [visualizationsLens] is the admitted dashboards' pins — the pin of a hidden dashboard is not
 * admitted either (the 404 rule holds through the arm; the lens test pins both readings).
 *
 * ## The pins projection (#330)
 * The dashboard arms read a [DashboardCurrentPins] projection — per current RELEASED dashboard
 * its identity plus the two NAME lists the derivation needs (its source pipelines, its pins) —
 * in ONE statement; the bodies are never loaded. The derivation itself lives in ONE function,
 * [dashboardArms]: the page's full view and the lens's first use run the same rule over the
 * same projection, so they cannot disagree.
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
    /** #330 — the two dashboard arms, derived once by [dashboardArms] over the projection. */
    private val arms: DashboardArms,
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

    /** The two dashboard arms (#330): the admitted dashboards, and the pin names their lenses read. */
    data class DashboardArms(
        val dashboards: List<Candidate>,
        val pinNames: Set<String>,
    ) {
        /** #10 L1c — the promoter's dashboard lens: the admitted dashboards (sources admitted AND newer than the target). */
        val dashboardsLens: ReadLens get() = ReadLens.Only(dashboards.mapTo(LinkedHashSet()) { it.name })

        /** #10 L1c — the promoter's visualization lens: the admitted dashboards' pins (§6.1's rule; see the class KDoc). */
        val visualizationsLens: ReadLens get() = ReadLens.Only(pinNames)
    }

    val pipelineLens: ReadLens = ReadLens.Only(pipelines.mapTo(LinkedHashSet()) { it.name })
    val templateLens: ReadLens = ReadLens.Only(templates.mapTo(LinkedHashSet()) { it.name })
    val parameterSetLens: ReadLens = ReadLens.Only(parameterSets.mapTo(LinkedHashSet()) { it.name })

    /** #10 L1c — the promoter's visualization lens (the arms'; see [DashboardArms.visualizationsLens]). */
    val visualizationsLens: ReadLens get() = arms.visualizationsLens

    /** #10 L1c — the promoter's dashboard lens (the arms'; see [DashboardArms.dashboardsLens]). */
    val dashboardsLens: ReadLens get() = arms.dashboardsLens

    /** The listing row for [name], or null when [name] is not promotable — the promote path's root guard reads this. */
    fun pipeline(name: String): Candidate? = pipelines.firstOrNull { it.name == name }

    /** The set arm of the same guard. */
    fun parameterSet(name: String): Candidate? = parameterSets.firstOrNull { it.name == name }

    companion object {
        /**
         * The dashboard arms' derivation, the ONE function (#330): a current dashboard is admitted when
         * every source pipeline the [pipelineLens] admits, then §10.2's rules 2–4 against the target's
         * entry decide whether it is a [Candidate]; the visualization arm reads the ADMITTED dashboards'
         * pins. The page's [of] and the lens's lazy first use both run this, over the same projection.
         */
        fun dashboardArms(
            pipelineLens: ReadLens,
            inventory: PromotionWire.Inventory,
            currentDashboards: List<DashboardCurrentPins>,
        ): DashboardArms {
            val dashboardsOnTarget = inventory.dashboardByName()
            // A dashboard whose projection is missing is not admitted — the body-unread fail-closed
            // rule, now "projection-missing" (the projection read answers every current dashboard).
            val admitted: Map<String, DashboardCurrentPins> =
                currentDashboards
                    .filter { pins -> pins.sourcePipelineNames.all(pipelineLens::admits) }
                    .associateBy { it.name }
            val dashboards =
                admitted.values
                    .mapNotNull { local ->
                        candidate(local.name, local.displayName, local.version, local.bodyHash, dashboardsOnTarget[local.name])
                    }.sortedBy { it.name }
            val pinNames =
                dashboards
                    .mapNotNull { admitted[it.name] }
                    .flatMapTo(LinkedHashSet()) { it.pinnedVisualizationNames }
            return DashboardArms(dashboards, pinNames)
        }

        /** The rule over the local current-version lists, the pins projection, and the target's inventory. */
        fun of(
            localPipelines: List<CurrentPipelineVersion>,
            localTemplates: List<CurrentTemplateVersion>,
            inventory: PromotionWire.Inventory,
            localParameterSets: List<co.datapipelines.parameters.CurrentParameterSetVersion> = emptyList(),
            localVisualizations: List<CurrentArtifactVersion> = emptyList(),
            currentDashboards: List<DashboardCurrentPins> = emptyList(),
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

            // The dashboard arms over the pins projection — the ONE derivation, shared with the lens.
            val arms = dashboardArms(pipelineLens, inventory, currentDashboards)

            val visualizations =
                localVisualizations
                    .mapNotNull { local ->
                        if (local.name !in arms.pinNames) return@mapNotNull null
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
                dashboards = arms.dashboards,
                arms = arms,
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
