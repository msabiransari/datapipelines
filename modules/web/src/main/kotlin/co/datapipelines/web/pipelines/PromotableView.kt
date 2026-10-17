package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.CurrentPipelineVersion
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.CurrentTemplateVersion
import co.datapipelines.visualization.CurrentArtifactVersion
import co.datapipelines.visualization.DashboardCurrentPins

/**
 * §10.2's ONE computation — the promotion page's rows, the promoter lens and the family
 * inventory's factory all read it — built per request over the current-version readers and
 * the #330 dashboard projection, against the wire inventory read off the target.
 *
 * ## The rule, four lines, the same for a pipeline and a template
 * A local object is promotable iff
 * 1. it HAS a current version whose STATUS is RELEASED — a never-released object has no row in
 *    either `findCurrentVersions` (D55), and since D60 a development-posture pointer CAN name
 *    the DRAFT (the discard/purge fallback or a manual switch): a draft-pointer row is dropped
 *    here (#563), so a draft-pointer object is invisible to a promoter whatever releases it
 *    holds, and
 * 2. the target LACKS the name (a pipeline the target does not have counts as version 0), or
 * 3. the content hash DIFFERS from the target's (same hash ⇒ nothing to push, whatever the
 *    numbers say — version is for humans and hash is for machines) AND
 * 4. the local version is strictly GREATER than the target's.
 *
 * ## The name lenses
 * [pipelineLens], [templateLens] and [parameterSetLens] are the admitted candidates' names,
 * computed once in the constructor from the same lists the page renders, so the page rows and
 * the lens names cannot diverge; the dashboard and visualization arms live on [DashboardArms]
 * and are reached through [visualizationsLens] and [dashboardsLens]. A hidden object answers
 * a promoter exactly as an absent one (the 404 rule, auth.md §11A.1); when the target cannot
 * be read the caller builds nothing at all and the screens say why (fail closed,
 * [PromotableViews]).
 *
 * ## The lens arms, stated once more
 * [visualizationsLens] is the admitted dashboards' pins — the pin of a hidden dashboard is not
 * admitted either (the 404 rule holds through the arm; the lens test pins both readings). The
 * pins arm admits only RELEASED pins: a draft visualization is not promotable, so its pin name
 * is not a lens name either (#563 — the caller passes the RELEASED visualization names, which
 * both readers answer directly).
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
    /** §10.2's set for templates, id-ordered (the id is the candidate's name). */
    val templates: List<Candidate>,
    /** §10.2's set for parameter sets, name-ordered. */
    val parameterSets: List<Candidate>,
    /** §10.2's set for dashboard-pinned RELEASED visualizations, name-ordered. */
    val visualizations: List<Candidate>,
    /** §10.2's set for dashboards whose every source pipeline the pipeline lens admits, name-ordered. */
    val dashboards: List<Candidate>,
    private val arms: DashboardArms,
    /**
     * How many live pipelines held a current version — so an empty listing reads as "in sync",
     * not "broken". #563: the count is over POINTER rows, taken before the RELEASED filter — a
     * draft-pointer pipeline IS examined (it exists; it is just not promotable).
     */
    val examinedPipelines: Int,
    /** The same count for templates, over pointer rows (a draft-pointer template IS examined). */
    val examinedTemplates: Int,
) {
    /** One row of §10.2's listing: what the target has, what this deployment would send. */
    data class Candidate(
        val name: String,
        val displayName: String,
        /** The local current version's number. */
        val localVersion: Int,
        /** The version the target serves; a target that lacks the name counts as version 0. */
        val targetVersion: Int,
        val bodyHash: String,
    )

    /**
     * The two dashboard arms (#330): the admitted RELEASED dashboards, and the pin names their
     * lenses read — kept only when a RELEASED visualization carries the name (#563).
     */
    data class DashboardArms(
        val dashboards: List<Candidate>,
        val pinNames: Set<String>,
    ) {
        /** The pins of the admitted dashboards that are also RELEASED visualizations. */
        val visualizationsLens: ReadLens
            get() = ReadLens.Only(pinNames)

        /** The admitted dashboards' names. */
        val dashboardsLens: ReadLens
            get() = ReadLens.Only(dashboards.mapTo(LinkedHashSet()) { it.name })
    }

    /** The name lens the reads of pipelines run through (auth.md §11A.1's 404 rule). */
    val pipelineLens: ReadLens

    /** The name lens for templates. */
    val templateLens: ReadLens

    /** The name lens for parameter sets. */
    val parameterSetLens: ReadLens

    /** The visualization arm — the admitted dashboards' RELEASED pins. */
    val visualizationsLens: ReadLens
        get() = arms.visualizationsLens

    /** §10.2's dashboard row set, through the arms. */
    val dashboardsLens: ReadLens
        get() = arms.dashboardsLens

    init {
        pipelineLens = ReadLens.Only(pipelines.mapTo(LinkedHashSet()) { it.name })
        templateLens = ReadLens.Only(templates.mapTo(LinkedHashSet()) { it.name })
        parameterSetLens = ReadLens.Only(parameterSets.mapTo(LinkedHashSet()) { it.name })
    }

    /** The pipeline row the lens admits by name, or null when it is hidden (the 404 rule). */
    fun pipeline(name: String): Candidate? = pipelines.firstOrNull { it.name == name }

    /** The parameter-set row the lens admits by name, or null when it is hidden. */
    fun parameterSet(name: String): Candidate? = parameterSets.firstOrNull { it.name == name }

    companion object {
        /**
         * The dashboard arms' derivation, the ONE function (#330): a current dashboard is admitted when
         * every source pipeline the [pipelineLens] admits, then §10.2's rules 2–4 against the target's
         * entry decide whether it is a [Candidate]; the visualization arm reads the ADMITTED dashboards'
         * pins — kept only when the RELEASED visualizations carry the name ([releasedVisualizationNames],
         * #563: a draft visualization's pin is not promotable, so it is not a lens name). The page's
         * [of] and the lens's lazy first use both run this, over the same projection.
         */
        fun dashboardArms(
            pipelineLens: ReadLens,
            inventory: PromotionWire.Inventory,
            releasedVisualizationNames: Set<String>,
            currentDashboards: List<DashboardCurrentPins>,
        ): DashboardArms {
            val dashboardsOnTarget = inventory.dashboardByName()
            // A dashboard whose projection is missing is not admitted — the body-unread fail-closed
            // rule, now "projection-missing" (the projection read answers every current dashboard).
            val admitted =
                currentDashboards
                    .filter { pins -> pins.status == PipelineVersionStatus.RELEASED }
                    .filter { pins -> pins.sourcePipelineNames.all(pipelineLens::admits) }
                    .associateBy { it.name }
            val dashboards =
                admitted.values
                    .mapNotNull { local ->
                        candidate(
                            local.name,
                            local.displayName,
                            local.version,
                            local.bodyHash,
                            dashboardsOnTarget[local.name],
                        )
                    }.sortedBy { it.name }
            val pinNames =
                dashboards
                    .mapNotNull { admitted[it.name] }
                    .flatMapTo(LinkedHashSet()) { it.pinnedVisualizationNames }
                    .intersect(releasedVisualizationNames)
            return DashboardArms(dashboards, pinNames)
        }

        /**
         * The full §10.2 view for the promotion page and the plan: the five candidate sets and the
         * examined counts, from the four current-version readers and the #330 projection. The last
         * three parameters are defaulted so every earlier caller keeps compiling; a family left at its
         * default contributes no candidates.
         */
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
                        if (local.status != PipelineVersionStatus.RELEASED) return@mapNotNull null
                        candidate(local.name, local.displayName, local.version, local.bodyHash, pipelinesOnTarget[local.name])
                    }.sortedBy { it.name }
            val pipelineLens = ReadLens.Only(pipelines.mapTo(LinkedHashSet()) { it.name })
            val arms =
                dashboardArms(
                    pipelineLens,
                    inventory,
                    localVisualizations.filter { it.status == PipelineVersionStatus.RELEASED }.mapTo(LinkedHashSet()) { it.name },
                    currentDashboards,
                )
            val visualizations =
                localVisualizations
                    .mapNotNull { local ->
                        if (local.status != PipelineVersionStatus.RELEASED) return@mapNotNull null
                        if (local.name !in arms.pinNames) return@mapNotNull null
                        candidate(local.name, local.displayName, local.version, local.bodyHash, visualizationsOnTarget[local.name])
                    }.sortedBy { it.name }
            val templates =
                localTemplates
                    .mapNotNull { local ->
                        if (local.status != PipelineVersionStatus.RELEASED) return@mapNotNull null
                        candidate(local.id, local.displayName, local.version, local.bodyHash, templatesOnTarget[local.id])
                    }.sortedBy { it.name }
            val parameterSets =
                localParameterSets
                    .mapNotNull { local ->
                        if (local.status != PipelineVersionStatus.RELEASED) return@mapNotNull null
                        candidate(local.name, local.displayName, local.version, local.bodyHash, setsOnTarget[local.name])
                    }.sortedBy { it.name }
            return PromotableView(
                pipelines,
                templates,
                parameterSets,
                visualizations,
                arms.dashboards,
                arms,
                examinedPipelines = localPipelines.size,
                examinedTemplates = localTemplates.size,
            )
        }

        /** §10.2's rules 2–4 against one target entry: absent, hash-differing and strictly newer. */
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
