package co.datapipelines.application.templates

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetPin
import co.datapipelines.parameters.ParameterSetTemplatePins
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.through
import co.datapipelines.templates.TemplateUsageService
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
 * narrows, only RELEASED versions are reported on both arms (a promoter never sees a draft's
 * number or status through this answer).
 */
class TemplateUsage(
    private val pipelines: TemplateUsageService,
    private val parameterSets: ParameterSetTemplatePins,
) {
    /** The combined used-by answer: one row per pinning NODE (pipelines) and per pinning PARAMETER (sets). */
    data class Combined(
        val templateId: String,
        val version: Int,
        val pipelineCount: Int,
        val pipelineReferences: List<co.datapipelines.pipeline.TemplatePin>,
        /** The sets' working-version pins, set-lensed and RELEASED-only under a narrowing view. */
        val parameterSetReferences: List<ParameterSetPin>,
    ) {
        /** Everything that pins it, set or pipeline — "is anything using this at all?". */
        fun isEmpty(): Boolean = pipelineReferences.isEmpty() && parameterSetReferences.isEmpty()
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
        return Combined(
            templateId = id,
            version = version,
            pipelineCount = pipelinesAnswer.pipelineCount,
            pipelineReferences = pipelinesAnswer.references,
            parameterSetReferences = setPins,
        )
    }

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
     * The pipeline arm of question 2, under the same lens — [TemplateUsageService.referencedAnywhere]'s
     * lensed answer, re-exposed so a consumer asks THIS object one question and gets both
     * aggregates' evidence from the one place.
     */
    fun pipelinesReferencedAnywhere(
        workspaceId: UUID,
        view: LensedView,
        id: String,
    ): List<co.datapipelines.pipeline.TemplatePin> =
        pipelines.referencedAnywhere(workspaceId, view.templates, view.pipelines, id)

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
        return (fromPipelines.keys + fromSets.keys).associateWith { version ->
            (fromPipelines[version] ?: 0) + (fromSets[version] ?: 0)
        }
    }

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

    /** A narrowing view reports RELEASED rows only (178b, both arms). */
    private fun visible(
        view: LensedView,
        status: PipelineVersionStatus,
    ): Boolean =
        (view.pipelines.isEverything && view.parameterSets.isEverything) || status == PipelineVersionStatus.RELEASED
}
