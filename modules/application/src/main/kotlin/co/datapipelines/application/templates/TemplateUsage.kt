package co.datapipelines.application.templates

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetPin
import co.datapipelines.parameters.ParameterSetTemplatePins
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.pipeline.through
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.visualization.ArtifactDependents
import co.datapipelines.visualization.ArtifactPin
import co.datapipelines.visualization.PinScope
import java.util.UUID

/**
 * The COMBINED templates reverse arrow (the record's §8.4, #194 lane D): `TemplateUsageService`
 * answers "who pins this template?" over PIPELINES; with template-backed selectors a parameter
 * set pins templates too, so the used-by listing, the delete guard and the screen's in-use
 * counts must cover BOTH aggregates — `templates_used_by` alone answering "no pipelines" would
 * let a set-only pin be deleted, breaking the set's release.
 *
 * The composition lives in `application` because the two scans are cross-aggregate (§8.4);
 * `templates` is not edited beyond consuming the composed answer. Every consumer — the
 * `templates_used_by` tool, `templates_purge_draft`'s guard, the web delete guard, the
 * template screen's counts — reads THIS, never its own copy of a scan (the 040 D1 rule, one
 * level up).
 *
 * ## The two questions, still kept apart
 * The split is [TemplateUsageService]'s and is preserved verbatim: the used-by answer scans
 * each object's WORKING version (a draft that just adopted the pin is counted); the delete
 * guard's evidence scans EVERY version ever stored (immutable and executable rows make a
 * historical pin a real reference).
 *
 * ## The lens
 * A narrowing [LensedView] (178/194d): pinning PIPELINES a view does not admit drop out —
 * [TemplateUsageService] already does that — and now pinning PARAMETER SETS too, the same
 * rule on the set arm: a hidden set never leaks through the reverse arrow. When either lens
 * narrows, only RELEASED versions are reported on every arm (a promoter never sees a draft's
 * number or status through this answer).
 *
 * ## The third arm (#320)
 * A VISUALIZATION pins a transform template by name and version (`transform.template`), so the
 * same two questions are asked of `visualization_versions` through [ArtifactDependents], under
 * `view.visualizations`. The guards' evidence has its OWN two methods, both unlensed — a guard
 * runs over the whole workspace and the caller's view decides only what its refusal may echo:
 * [liveVersionPins] (the discard and draft-purge verbs) and [everPins] (the entity purge).
 */
class TemplateUsage(
    private val pipelines: TemplateUsageService,
    private val parameterSets: ParameterSetTemplatePins,
    private val pipelineVersions: PipelineRepository,
    private val visualizations: ArtifactDependents,
) {
    /** The combined used-by answer: one row per pinning NODE (pipelines) and per pinning PARAMETER (sets). */
    data class Combined(
        val templateId: String,
        val version: Int,
        val pipelineCount: Int,
        val pipelineReferences: List<co.datapipelines.pipeline.TemplatePin>,
        /** The sets' working-version pins, set-lensed and RELEASED-only under a narrowing view. */
        val parameterSetReferences: List<ParameterSetPin>,
        /** #320 — the visualizations' working-version pins, visualization-lensed and RELEASED-only under a narrowing view. */
        val visualizationReferences: List<ArtifactPin>,
    ) {
        /** Everything that pins it, set, pipeline or visualization — "is anything using this at all?". */
        fun isEmpty(): Boolean = pipelineReferences.isEmpty() && parameterSetReferences.isEmpty() && visualizationReferences.isEmpty()
    }

    /**
     * What pins a template — the guards' evidence from all three aggregates, UNLENSED (a guard reads the whole
     * workspace; only the echo of its refusal is narrowed, by the surface that throws it).
     */
    data class Pins(
        val pipelines: List<TemplatePin>,
        val parameterSets: List<ParameterSetPin>,
        val visualizations: List<ArtifactPin>,
    ) {
        fun isEmpty(): Boolean = pipelines.isEmpty() && parameterSets.isEmpty() && visualizations.isEmpty()
    }

    /**
     * Question 1, combined: who pins `id@version` in their working version right now? The
     * template must exist and carry that version — [TemplateUsageService.usedBy]'s refusal —
     * before either scan runs.
     *
     * @throws co.datapipelines.typesystem.DatapipelinesException `template.not_found`, exactly
     *   as the pipeline-only answer did (a hidden template is an absent one).
     */
    fun usedBy(
        workspaceId: UUID,
        view: LensedView,
        id: String,
        version: Int,
    ): Combined {
        val pipelinesAnswer =
            pipelines.usedBy(workspaceId, view.templates, view.pipelines, id, version)
        val setPins =
            parameterSets
                .workingVersionPins(workspaceId, id, version)
                .through(view.parameterSets) { it.setName }
                .filter { visible(view, it.versionStatus) }
        val visualizationPins =
            visualizations
                .visualizationsPinningTemplate(workspaceId, id, version, PinScope.WORKING)
                .through(view.visualizations) { it.name }
                .filter { visible(view, it.status) }
        return Combined(
            templateId = id,
            version = version,
            pipelineCount = pipelinesAnswer.pipelineCount,
            pipelineReferences = pipelinesAnswer.references,
            parameterSetReferences = setPins,
            visualizationReferences = visualizationPins,
        )
    }

    /**
     * Question 2's exact-pin form, the discard and draft-purge verbs' evidence: every LIVE (DRAFT or RELEASED)
     * version of a pipeline, a parameter set or a visualization that pins `id@version` exactly (graph rule 1).
     * Unlensed and workspace-scoped on every arm.
     */
    fun liveVersionPins(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): Pins =
        Pins(
            pipelines = pipelineVersions.findLiveVersionsPinningTemplateVersion(workspaceId, id, version),
            parameterSets = parameterSets.liveVersionPins(workspaceId, id, version),
            visualizations = visualizations.visualizationsPinningTemplate(workspaceId, id, version, PinScope.LIVE),
        )

    /**
     * Question 2's any-version form, the entity purge's evidence (graph rule 3): EVERY stored version of a pipeline,
     * a set or a visualization that pins ANY version of `id` — a DISCARDED version included, because a restore would
     * resurrect a dangling pin (owner ruling R12). Unlensed and workspace-scoped on every arm.
     */
    fun everPins(
        workspaceId: UUID,
        id: String,
    ): Pins =
        Pins(
            pipelines = pipelineVersions.findAnyVersionTemplatePins(workspaceId, id),
            parameterSets = parameterSets.anyVersionPins(workspaceId, id),
            visualizations = visualizations.visualizationsPinningTemplate(workspaceId, id, null, PinScope.ANY),
        )

    /**
     * Question 2, combined — the delete guard's evidence: EVERY version, ever, of both
     * aggregates. Lensed exactly as [usedBy] (178b): a hidden set's pins drop out for a
     * narrowing view, RELEASED-only when either lens narrows — and the guard runs under the
     * CALLER's full view, so a delete admitted at all sees everything its caller would act on.
     */
    fun referencedAnywhere(
        workspaceId: UUID,
        view: LensedView,
        id: String,
    ): List<ParameterSetPin> =
        parameterSets
            .anyVersionPins(workspaceId, id)
            .through(view.parameterSets) { it.setName }
            .filter { visible(view, it.versionStatus) }

    /**
     * The visualization arm of question 2 (#320), the sets' [referencedAnywhere] twin: EVERY stored version of a
     * visualization that pins ANY version of `id` (a discarded one included, R12), lensed exactly as [usedBy] — a
     * hidden visualization drops out for a narrowing view, RELEASED-only when any lens narrows.
     */
    fun visualizationsReferencedAnywhere(
        workspaceId: UUID,
        view: LensedView,
        id: String,
    ): List<ArtifactPin> =
        visualizations
            .visualizationsPinningTemplate(workspaceId, id, null, PinScope.ANY)
            .through(view.visualizations) { it.name }
            .filter { visible(view, it.status) }

    /**
     * The pipeline arm of question 2, under the same lens — [TemplateUsageService.referencedAnywhere]'s
     * lensed answer, re-exposed so a consumer asks THIS object one question and gets both
     * aggregates' evidence from the one place.
     */
    fun pipelinesReferencedAnywhere(
        workspaceId: UUID,
        view: LensedView,
        id: String,
    ): List<co.datapipelines.pipeline.TemplatePin> = pipelines.referencedAnywhere(workspaceId, view.templates, view.pipelines, id)

    /** The template screen's per-version in-use counts, sets included (040 D6, one level up). */
    fun inUseCounts(
        workspaceId: UUID,
        view: LensedView,
        id: String,
    ): Map<Int, Int> {
        val fromPipelines =
            pipelines.inUseCounts(workspaceId, id)
        val fromSets =
            parameterSets
                .countWorkingPinsByPinnedVersion(workspaceId, id)
                .let { counts ->
                    if (view.parameterSets.isEverything) {
                        counts
                    } else {
                        // A narrowing view counts only sets it admits — re-derive from the
                        // pinned rows, the counts statement has no lens.
                        workingSetCounts(workspaceId, view, id)
                    }
                }
        val fromVisualizations = workingVisualizationCounts(workspaceId, view, id)
        return (fromPipelines.keys + fromSets.keys + fromVisualizations.keys).associateWith { version ->
            (fromPipelines[version] ?: 0) + (fromSets[version] ?: 0) + (fromVisualizations[version] ?: 0)
        }
    }

    private fun workingVisualizationCounts(
        workspaceId: UUID,
        view: LensedView,
        id: String,
    ): Map<Int, Int> =
        visualizations
            .visualizationsPinningTemplate(workspaceId, id, null, PinScope.WORKING)
            .asSequence()
            .filter { view.visualizations.admits(it.name) && visible(view, it.status) }
            .groupBy { it.pinnedVersion }
            .mapValues { (_, pins) -> pins.map(ArtifactPin::artifactId).distinct().size }

    private fun workingSetCounts(
        workspaceId: UUID,
        view: LensedView,
        id: String,
    ): Map<Int, Int> =
        parameterSets
            .workingVersionPins(workspaceId, id)
            .asSequence()
            .filter { view.parameterSets.admits(it.setName) && visible(view, it.versionStatus) }
            .groupBy { it.pinnedVersion }
            .mapValues { (_, pins) -> pins.map(ParameterSetPin::setId).distinct().size }

    /** A narrowing view reports RELEASED rows only (178b, every arm). */
    private fun visible(
        view: LensedView,
        status: PipelineVersionStatus,
    ): Boolean =
        (view.pipelines.isEverything && view.parameterSets.isEverything && view.visualizations.isEverything) ||
            status == PipelineVersionStatus.RELEASED
}
