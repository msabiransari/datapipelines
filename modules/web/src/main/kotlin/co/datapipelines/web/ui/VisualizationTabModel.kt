package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.PreviewCaseEvaluator
import co.datapipelines.visualization.TestRunView
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.visualization.VisualizationTestSessionService
import co.datapipelines.web.dashboards.runtime.RuntimeViews
import co.datapipelines.web.ui.site.ScriptSafeJson
import co.datapipelines.web.visualizations.PreviewViews
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.ui.Model
import java.util.UUID

/**
 * The visualization workspace's READ tabs (#399): Preview, Overview, Evidence (+ one run), Used by and
 * Versions — each a lazy partial on `visualization.read`, each re-admitting the version the page resolved
 * through [VisualizationWorkspaceModel.admitted] (a stale tab open is the family's 404, never another body).
 * Every read is the SAME service call the REST reader makes, through the caller's lens; nothing here writes.
 * The browser never authors a visualization (#396): no tab carries an authoring control.
 */
@Suppress("LongParameterList") // the tabs' read ports ARE the wiring
class VisualizationTabModel(
    private val visualizations: VisualizationService,
    private val workspace: VisualizationWorkspaceModel,
    /** The fixture-mode preview's evaluation — the capability page's own ([PreviewCaseEvaluator]). */
    private val previewCases: PreviewCaseEvaluator,
    /** The Evidence tab's runs, run detail and screenshot probe — the REST evidence routes' service. */
    private val sessions: VisualizationTestSessionService,
    /** The Used-by tab's `pinnedBy` (lensed) and each dashboard's id for its link. */
    private val dashboards: DashboardService,
    /** The Overview's transform-pin status — the port the release cascade itself reads. */
    private val templateStatuses: TemplateVersionStatuses,
) {
    /**
     * The Preview tab: the viewed version rendered in the runtime's FIXTURE mode over its own test cases —
     * the SAME `{config, results}` block the capability page embeds ([PreviewViews.page] over
     * [PreviewCaseEvaluator]), emitted through [ScriptSafeJson] exactly as `visualizations/preview.html` does.
     */
    fun fillPreview(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        version: Int,
    ): String {
        val loaded = workspace.admitted(workspaceId, view, id, version)
        val cases = previewCases.cases(workspaceId, loaded.body)
        model.addAttribute("visualizationId", id.toString())
        model.addAttribute("version", version)
        model.addAttribute("bundle", RuntimeViews.rendererBundle(listOf(loaded)))
        model.addAttribute("cases", cases.map { CaseView(it.name, it.assertions.map(PreviewViews::assertionLabel)) })
        model.addAttribute("previewJson", previewJson(loaded, cases))
        return PREVIEW_VIEW
    }

    /** The Preview block's JSON — the capability builder over the workspace's key, no expiry. */
    fun previewJson(
        loaded: ArtifactVersion<VisualizationBody>,
        cases: List<co.datapipelines.visualization.PreviewCase>,
    ): String =
        ScriptSafeJson.forScriptBlock(
            ArtifactJson.mapper.writeValueAsString(
                PreviewViews.page(loaded, cases, configurationKey(loaded), expiresAt = null),
            ),
        )

    /** The Overview tab: the viewed version's definition, read-only. */
    fun fillOverview(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        version: Int,
    ): String {
        val loaded = workspace.admitted(workspaceId, view, id, version)
        val body = loaded.body
        model.addAttribute("visualizationId", id.toString())
        model.addAttribute("name", loaded.record.name)
        model.addAttribute("version", version)
        model.addAttribute("status", loaded.detail.status.name)
        model.addAttribute("bodyHash", loaded.detail.bodyHash)
        model.addAttribute("displayName", body.displayName)
        model.addAttribute("description", body.description)
        model.addAttribute("renderer", "${body.renderer.kind.wire} ${body.renderer.version}")
        model.addAttribute("bundle", RuntimeViews.rendererBundle(listOf(loaded)))
        model.addAttribute(
            "inputs",
            body.inputs.map { (name, contract) ->
                InputView(
                    name,
                    contract.columns.map {
                        "${it.name} ${it.type.wire}" +
                            if (it.nullable) "?" else ""
                    },
                )
            },
        )
        model.addAttribute(
            "transform",
            body.transform?.let { binding ->
                val pin = binding.template
                val status = templateStatuses.statusOf(workspaceId, pin.name, pin.version)
                TransformView(pin.name, pin.version, status?.name ?: MISSING, binding.inputs.map { (k, v) -> "$k ← $v" })
            },
        )
        model.addAttribute(
            "caseCount",
            body.tests
                ?.cases
                .orEmpty()
                .size,
        )
        return OVERVIEW_VIEW
    }

    /** The Evidence tab: the runs whose version the caller may see, newest first, capped at [RUNS_CAP]. */
    fun fillEvidence(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
    ): String {
        val visible = visibleVersions(workspaceId, view, id)
        val runs = sessions.runs(workspaceId, id).filter { it.version in visible }.take(RUNS_CAP)
        model.addAttribute("visualizationId", id.toString())
        model.addAttribute("runs", runs.map(::RunRow))
        model.addAttribute("runsCap", RUNS_CAP)
        return EVIDENCE_VIEW
    }

    /** One run's detail: cases, environment and mechanical JSON as TEXT, and the screenshot or its absence. */
    fun fillEvidenceRun(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        runId: UUID,
    ): String {
        val visible = visibleVersions(workspaceId, view, id)
        val run = sessions.runById(workspaceId, id, runId)
        if (run.version !in visible) throw runNotFound()
        model.addAttribute("visualizationId", id.toString())
        model.addAttribute("run", RunRow(run))
        model.addAttribute("casesJson", pretty(run.cases))
        model.addAttribute("environmentJson", pretty(run.environment))
        model.addAttribute("mechanicalJson", pretty(run.mechanical))
        model.addAttribute("hasScreenshot", hasScreenshot(workspaceId, id, run))
        return EVIDENCE_RUN_VIEW
    }

    /** The Used-by tab: the dashboards pinning this visualization's NAME, lensed, each linking its workspace. */
    fun fillUsedBy(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
    ): String {
        val working = visualizations.findWorking(workspaceId, view.visualizations, id) ?: throw VisualizationWorkspaceModel.notFound(id)
        val name = working.record.name
        val rows =
            dashboards.pinnedBy(workspaceId, view.dashboards, name).mapNotNull { ref ->
                val dashboardName = ref.substringBeforeLast('@')
                val dashboardVersion = ref.substringAfterLast('@').toIntOrNull() ?: return@mapNotNull null
                val pinned = dashboards.findVersionByName(workspaceId, view.dashboards, dashboardName, dashboardVersion)
                UsedByRow(
                    dashboardName = dashboardName,
                    dashboardVersion = dashboardVersion,
                    dashboardId = pinned?.record?.id,
                    pinnedVersions =
                        pinned
                            ?.body
                            ?.visualizations
                            ?.map { it.visualization }
                            ?.filter { it.name == name }
                            ?.map { it.version }
                            ?.distinct()
                            ?.sorted()
                            .orEmpty(),
                )
            }
        model.addAttribute("visualizationId", id.toString())
        model.addAttribute("name", name)
        model.addAttribute("usedBy", rows)
        return USED_BY_VIEW
    }

    /** The Versions tab: the admitted history with the current/draft/discarded markers and the verbs' affordances. */
    fun fillVersions(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        viewedVersion: Int?,
    ): String {
        val working = visualizations.findWorking(workspaceId, view.visualizations, id) ?: throw VisualizationWorkspaceModel.notFound(id)
        val current = working.record.currentVersion
        val versions = visualizations.listVersions(workspaceId, view.visualizations, id)
        model.addAttribute("visualizationId", id.toString())
        model.addAttribute("name", working.record.name)
        model.addAttribute("currentVersion", current)
        model.addAttribute("soleDraft", versions.size == 1 && versions.single().status == PipelineVersionStatus.DRAFT)
        model.addAttribute(
            "versions",
            versions.map {
                VersionRow(
                    version = it.version,
                    status = it.status.name,
                    createdAt = it.createdAt.toString(),
                    releasedAt = it.releasedAt?.toString(),
                    isCurrent = it.version == current,
                    isDraft = it.status == PipelineVersionStatus.DRAFT,
                    isDiscarded = it.status == PipelineVersionStatus.DISCARDED,
                    isViewed = it.version == viewedVersion,
                )
            },
        )
        return VERSIONS_VIEW
    }

    private fun visibleVersions(
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
    ): Set<Int> {
        val listed = visualizations.listVersions(workspaceId, view.visualizations, id)
        if (listed.isEmpty()) throw VisualizationWorkspaceModel.notFound(id)
        return listed.map { it.version }.toSet()
    }

    /** The probe the screenshot route's own read makes: a stored image, or `no_screenshot` — never a broken `<img>`. */
    private fun hasScreenshot(
        workspaceId: UUID,
        id: UUID,
        run: TestRunView,
    ): Boolean =
        try {
            sessions.screenshot(workspaceId, id, run.sessionId)
            true
        } catch (e: DatapipelinesException) {
            if (e.code != VisualizationErrorCodes.TEST_SCREENSHOT_INVALID) throw e
            false
        }

    private fun pretty(node: JsonNode?): String? = node?.takeUnless { it.isNull }?.toPrettyString()

    /** One case of the Preview tab: its name and its assertions as labels (text, never markup). */
    data class CaseView(
        val name: String,
        val assertions: List<String>,
    )

    data class InputView(
        val name: String,
        val columns: List<String>,
    )

    data class TransformView(
        val template: String,
        val version: Int,
        val status: String,
        val bindings: List<String>,
    ) {
        /** The release hint a DRAFT pin carries: the consent on Release can release it with the visualization. */
        val draftHint: Boolean get() = status == PipelineVersionStatus.DRAFT.name
    }

    /** One Evidence row, read off the redacted run view. */
    data class RunRow(
        val runId: UUID,
        val version: Int,
        val status: String,
        val startedAt: String,
        val completedAt: String?,
        /** `passed` / `failed` from the stored mechanical report; null while none is stored. */
        val mechanical: String?,
    ) {
        constructor(run: TestRunView) : this(
            run.runId,
            run.version,
            run.status.name,
            run.startedAt.toString(),
            run.completedAt?.toString(),
            run.mechanical
                ?.path("ok")
                ?.takeIf { it.isBoolean }
                ?.let { if (it.asBoolean()) "passed" else "failed" },
        )
    }

    data class UsedByRow(
        val dashboardName: String,
        val dashboardVersion: Int,
        /** Null only when the lensed by-name read no longer resolves the pinning version (a race): rendered unlinked. */
        val dashboardId: UUID?,
        val pinnedVersions: List<Int>,
    ) {
        /** `v1, v2` — the pinned visualization versions as the row shows them. */
        val pinnedLabel: String get() = pinnedVersions.joinToString(", ") { "v$it" }
    }

    data class VersionRow(
        val version: Int,
        val status: String,
        val createdAt: String,
        val releasedAt: String?,
        val isCurrent: Boolean,
        val isDraft: Boolean,
        val isDiscarded: Boolean,
        val isViewed: Boolean,
    )

    companion object {
        /** The runs list's bound — the REST runs route's (`limit: 100`, rest-api §22.2). */
        const val RUNS_CAP = 100

        const val PREVIEW_VIEW = "partials/visualization-preview"
        const val OVERVIEW_VIEW = "partials/visualization-overview"
        const val EVIDENCE_VIEW = "partials/visualization-evidence"
        const val EVIDENCE_RUN_VIEW = "partials/visualization-evidence-run"
        const val USED_BY_VIEW = "partials/visualization-used-by"
        const val VERSIONS_VIEW = "partials/visualization-versions"

        private const val MISSING = "MISSING"

        /** The workspace preview's configuration key: per version, so a case's `configuration_id` names it. */
        fun configurationKey(loaded: ArtifactVersion<VisualizationBody>): String = "workspace:${loaded.record.id}:v${loaded.detail.version}"

        /** The evidence routes' own refusal for an unknown run — a lens-hidden run answers identically. */
        private fun runNotFound() =
            DatapipelinesException(
                code = VisualizationErrorCodes.TEST_SESSION_NOT_FOUND,
                message = "No such test session for this visualization.",
                details = mapOf("reason" to "session_unknown"),
            )
    }
}
