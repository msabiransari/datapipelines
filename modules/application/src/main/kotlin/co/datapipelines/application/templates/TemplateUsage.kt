package co.datapipelines.application.templates

import co.datapipelines.application.dependencies.IncomingRows
import co.datapipelines.application.dependencies.InverseArm
import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetPin
import co.datapipelines.parameters.ParameterSetTemplatePins
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.pipeline.inverse.PipelinePinRows
import co.datapipelines.pipeline.through
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.visualization.ArtifactDependents
import co.datapipelines.visualization.ArtifactPin
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
 * A VISUALIZATION pins a script template by name and version (`transform.template`), so the
 * same two questions are asked of `visualization_versions` through [ArtifactDependents], under
 * `view.visualizations`. The guards' evidence has its OWN two methods, both unlensed — a guard
 * runs over the whole workspace and the caller's view decides only what its refusal may echo:
 * [liveVersionPins] (the discard and draft-purge verbs) and [everPins] (the entity purge).
 *
 * ## An adapter over the inverse query (#877 §C)
 * Every pin comes from [IncomingRows] — the target family's `findIncoming`, paged to exhaustion, one bounded statement
 * per page — mapped and ordered by the SAME functions the domain scans use ([PipelinePinRows],
 * [ParameterSetTemplatePins.pinsOf], [ArtifactDependents.pinsOf]), so every caller receives the complete answer it
 * received before, in the same order. [templates] is asked only for [usedBy]'s not-found refusal; it scans nothing here.
 * Filtering owners (§C.2): the methods taking a [LensedView] narrow inside, as before; [liveVersionPins] and
 * [everPins] are unlensed (the refusal builder narrows the echo).
 *
 * ## The fourth arm — importers (#877 §D, record §23.3 item 5)
 * A template version pins a LIBRARY by `imports[i]` (`{id, version, alias}`), so a library pinned by a live importer is
 * protected exactly as one a live pipeline pins: [importedBy] reads the template family's importer arm at the scope the
 * verb asks, and the guards' [Pins.templates] carries it — LIVE for the discard and the draft purge (a DISCARDED
 * importer does not protect, the pipelines' rule), ANY for the entity purge. The read answers ([usedBy],
 * [inUseCounts]) are unchanged: `templates_used_by`'s output and the Used-by view keep their shapes.
 */
class TemplateUsage(
    private val incoming: IncomingRows,
    private val templates: TemplateUsageService,
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
        /** #877 §D — the template versions whose `imports` pin it (the importer arm). */
        val templates: List<TemplateImportPin>,
    ) {
        fun isEmpty(): Boolean = pipelines.isEmpty() && parameterSets.isEmpty() && visualizations.isEmpty() && templates.isEmpty()
    }

    /**
     * One stored IMPORT occurrence: version [importerVersion] of template [importerName] (status [versionStatus]) declares
     * `imports[i]` = the pinned template at [pinnedVersion] under [alias].
     */
    data class TemplateImportPin(
        val importerId: UUID,
        val importerName: String,
        val importerVersion: Int,
        val versionStatus: PipelineVersionStatus,
        val pinnedVersion: Int,
        val alias: String?,
    )

    /**
     * Question 1, combined: who pins `id@version` in their working version right now? The
     * template must exist and carry that version — [TemplateUsageService.requireVisibleVersion]'s refusal —
     * before any scan runs.
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
        templates.requireVisibleVersion(workspaceId, view.templates, id, version)
        val pipelinesAnswer =
            TemplateUsageService.usedByOf(
                id,
                version,
                pipelinePins(workspaceId, id, version, InverseScope.WORKING, PipelinePinRows.BY_NAME_THEN_NODE),
                view.templates,
                view.pipelines,
            )
        val setPins =
            setPins(workspaceId, id, version, InverseScope.WORKING, ParameterSetTemplatePins.BY_NAME_THEN_PARAMETER)
                .through(view.parameterSets) { it.setName }
                .filter { visible(view, it.versionStatus) }
        val visualizationPins =
            visualizationPins(workspaceId, id, version, InverseScope.WORKING)
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
            pipelines = pipelinePins(workspaceId, id, version, InverseScope.LIVE, PipelinePinRows.BY_NAME_THEN_NODE),
            parameterSets = setPins(workspaceId, id, version, InverseScope.LIVE, ParameterSetTemplatePins.BY_NAME_NEWEST_THEN_PARAMETER),
            visualizations = visualizationPins(workspaceId, id, version, InverseScope.LIVE),
            templates = importedBy(workspaceId, id, version, InverseScope.LIVE),
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
            pipelines = anyVersionPipelinePins(workspaceId, id),
            parameterSets = anyVersionSetPins(workspaceId, id),
            visualizations = visualizationPins(workspaceId, id, null, InverseScope.ANY),
            templates = importedBy(workspaceId, id, null, InverseScope.ANY),
        )

    /**
     * The importer arm (#877 §D): every template version under [scope] — WORKING (each importer's working version), LIVE
     * (every DRAFT or RELEASED version) or ANY (every stored version of an importer that still has a live one, a DISCARDED
     * one included) — whose `imports` pin `id@version`, or any version of `id` when [version] is null. Unlensed and
     * workspace-scoped: the guard decides on the whole workspace; only a refusal's echo is narrowed, by its builder.
     * Ordered by importer name, newest importer version first, then the declared alias.
     */
    fun importedBy(
        workspaceId: UUID,
        id: String,
        version: Int?,
        scope: InverseScope,
    ): List<TemplateImportPin> =
        incoming
            .rows(workspaceId, InverseArm.TEMPLATE_FROM_TEMPLATE, id, version, scope)
            .map(::importPin)
            .sortedWith(BY_IMPORTER_NEWEST_THEN_ALIAS)

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
        anyVersionSetPins(workspaceId, id)
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
        visualizationPins(workspaceId, id, null, InverseScope.ANY)
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
    ): List<co.datapipelines.pipeline.TemplatePin> =
        TemplateUsageService.narrow(anyVersionPipelinePins(workspaceId, id), view.templates, view.pipelines)

    /** The template screen's per-version in-use counts, sets included (040 D6, one level up). */
    fun inUseCounts(
        workspaceId: UUID,
        view: LensedView,
        id: String,
    ): Map<Int, Int> {
        val pipelineRows = incoming.rows(workspaceId, InverseArm.TEMPLATE_FROM_PIPELINE, id, null, InverseScope.WORKING)
        val fromPipelines =
            if (view.pipelines.isEverything && view.templates.isEverything) {
                PipelinePinRows.countByPinnedVersion(pipelineRows)
            } else {
                // #340: a narrowing view counts only pipelines it admits, RELEASED working versions only — the rule
                // [usedBy] applies to the same rows, so the count and the listing agree.
                workingPipelineCounts(pipelineRows, view)
            }
        val setPins = setPins(workspaceId, id, null, InverseScope.WORKING, ParameterSetTemplatePins.BY_NAME_THEN_PARAMETER)
        val fromSets =
            if (view.parameterSets.isEverything) {
                ParameterSetTemplatePins.countByPinnedVersion(setPins)
            } else {
                // A narrowing view counts only sets it admits.
                val admitted = setPins.filter { view.parameterSets.admits(it.setName) && visible(view, it.versionStatus) }
                distinctByPinned(admitted, ParameterSetPin::pinnedVersion, ParameterSetPin::setId)
            }
        val admittedVisualizations =
            visualizationPins(workspaceId, id, null, InverseScope.WORKING).filter {
                view.visualizations.admits(it.name) && visible(view, it.status)
            }
        val fromVisualizations = distinctByPinned(admittedVisualizations, ArtifactPin::pinnedVersion, ArtifactPin::artifactId)
        return (fromPipelines.keys + fromSets.keys + fromVisualizations.keys).associateWith { version ->
            (fromPipelines[version] ?: 0) + (fromSets[version] ?: 0) + (fromVisualizations[version] ?: 0)
        }
    }

    /** Per stored version, the distinct visible pipelines among the working pins (the narrowed count, #340). */
    private fun workingPipelineCounts(
        rows: List<co.datapipelines.pipeline.inverse.InverseRow>,
        view: LensedView,
    ): Map<Int, Int> =
        rows
            .filter { it.storedVersion != null }
            .groupBy { checkNotNull(it.storedVersion) }
            .mapValues { (_, pinning) ->
                pinning
                    .map(PipelinePinRows::templatePin)
                    .through(view.pipelines) { it.pipelineName }
                    .filter { it.versionStatus == PipelineVersionStatus.RELEASED }
                    .map(TemplatePin::pipelineId)
                    .distinct()
                    .size
            }.filterValues { it > 0 }

    private fun <T> distinctByPinned(
        pins: List<T>,
        pinned: (T) -> Int,
        identity: (T) -> Any,
    ): Map<Int, Int> = pins.groupBy(pinned).mapValues { (_, pinning) -> pinning.map(identity).distinct().size }

    private fun pipelinePins(
        workspaceId: UUID,
        id: String,
        version: Int?,
        scope: InverseScope,
        order: Comparator<TemplatePin>,
    ): List<TemplatePin> =
        incoming
            .rows(workspaceId, InverseArm.TEMPLATE_FROM_PIPELINE, id, version, scope)
            .map(PipelinePinRows::templatePin)
            .sortedWith(order)

    private fun anyVersionPipelinePins(
        workspaceId: UUID,
        id: String,
    ): List<TemplatePin> = pipelinePins(workspaceId, id, null, InverseScope.ANY, PipelinePinRows.BY_NAME_NEWEST_THEN_NODE)

    private fun setPins(
        workspaceId: UUID,
        id: String,
        version: Int?,
        scope: InverseScope,
        order: Comparator<ParameterSetPin>,
    ): List<ParameterSetPin> =
        ParameterSetTemplatePins
            .pinsOf(incoming.rows(workspaceId, InverseArm.TEMPLATE_FROM_PARAMETER_SET, id, version, scope))
            .sortedWith(order)

    private fun anyVersionSetPins(
        workspaceId: UUID,
        id: String,
    ): List<ParameterSetPin> = setPins(workspaceId, id, null, InverseScope.ANY, ParameterSetTemplatePins.BY_NAME_NEWEST_THEN_PARAMETER)

    private fun visualizationPins(
        workspaceId: UUID,
        id: String,
        version: Int?,
        scope: InverseScope,
    ): List<ArtifactPin> = ArtifactDependents.pinsOf(incoming.rows(workspaceId, InverseArm.TEMPLATE_FROM_VISUALIZATION, id, version, scope))

    private fun importPin(row: InverseRow): TemplateImportPin =
        TemplateImportPin(
            importerId = UUID.fromString(row.dependentId),
            importerName = row.dependentName,
            importerVersion = row.dependentVersion,
            versionStatus = checkNotNull(row.status) { "an import row carries its version status" },
            pinnedVersion = checkNotNull(row.storedVersion) { "an import pins an exact version" },
            alias = row.elementId,
        )

    /** A narrowing view reports RELEASED rows only (178b, every arm). */
    private fun visible(
        view: LensedView,
        status: PipelineVersionStatus,
    ): Boolean =
        (view.pipelines.isEverything && view.parameterSets.isEverything && view.visualizations.isEverything) ||
            status == PipelineVersionStatus.RELEASED

    private companion object {
        val BY_IMPORTER_NEWEST_THEN_ALIAS: Comparator<TemplateImportPin> =
            compareBy<TemplateImportPin> { it.importerName }
                .thenByDescending { it.importerVersion }
                .thenBy(nullsFirst()) { it.alias }
    }
}
