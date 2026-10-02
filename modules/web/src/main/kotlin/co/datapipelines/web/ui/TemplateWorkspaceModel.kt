package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant
import java.util.UUID

/**
 * The template workspace READ page's one model (#398) — **which version the page shows, and
 * which facts about it** — in [PipelineWorkspaceModel]'s shape. The admission rule is the READ
 * floor's: the caller's lens narrows every read here, an explicit version never falls back,
 * and the default resolution is the current RELEASE first.
 *
 * The resolution, in the workspace spec §3.1's order (templates have no execution, so A4/A8–A10
 * do not apply):
 *
 * 1. an explicit admitted version — its own body, or the family's 404 (never clamp, never
 *    re-resolve to a "nearby" version; a draft asked through a promoter's lens is the same
 *    404 an unknown number is, so a status cannot be probed by number);
 * 2. the current RELEASE (`findLatest` resolves the served pointer to a live release row);
 * 3. no current release and an admitted draft (the draft-only shape) — that draft, labelled;
 * 4. no current and no admitted draft — the choose-a-version state over the admitted history,
 *    or the empty state when nothing is admitted. No Edit, no Save, no render affordance
 *    until a body is selected.
 *
 * The old editor column's tolerance (`TemplateSourceModel.displayedOf`'s `?: latest`) is
 * deliberately NOT carried here: on a version-explicit page a hand-typed `version=99` naming
 * no row would silently paint the release instead, which is the fallback the spec forbids.
 *
 * A template that does not exist, or that the caller's lens hides entirely, answers the
 * family's 404 — an absent name, a foreign one and a hidden one are the same answer, so none
 * of them can be probed (auth.md §11A.1; `DashboardUiController`'s parity).
 */
class TemplateWorkspaceModel(
    private val reads: TemplateService,
    private val usage: TemplateUsage,
    private val actors: ActorNames,
) {
    /**
     * Resolves the page's version state for one request and returns it. [requestedVersion] is
     * the already-validated explicit version (null = the default resolution). The caller's
     * [view] carries the lens, so a promoter and an author resolve DIFFERENT versions from the
     * same URL — that is the read lens working, not a second permission check.
     */
    fun resolve(
        workspaceId: UUID,
        view: LensedView,
        name: String,
        requestedVersion: Int?,
    ): Resolved {
        // The family's 404 covers every absence the URL can name: an unknown template, a
        // lens-hidden one — one answer, so nothing about the template's existence leaks.
        if (!reads.existsId(workspaceId, view.templates, name)) throw notFound(name)
        val current = reads.findLatest(workspaceId, view.templates, name)
        val draft = reads.findDraftDetail(workspaceId, view.templates, name)
        val versions = reads.listVersions(workspaceId, view.templates, name)
        val selected =
            requestedVersion
                ?.let { explicit(workspaceId, view, name, it) }
                ?: default(workspaceId, view, name, current, draft)
        val inUse = usage.inUseCounts(workspaceId, view, name)
        val now = Instant.now()
        val names = actors.lookup(versions.map { it.createdBy })
        val rows =
            versions.map { v ->
                VersionRowView.of(
                    version = v.version,
                    status = v.status,
                    createdAt = v.createdAt,
                    actor = names[v.createdBy] ?: ActorNames.fallback(v.createdBy),
                    now = now,
                    usage = inUse[v.version] ?: 0,
                    // The count is the composed reverse arrow's — pipelines, parameter sets AND
                    // (#320) visualizations — so the unit names none of them.
                    usageUnit = "use",
                    isCurrent = v.version == current?.version,
                    // The chip's fact (V20): a draft row shows its last write's surface.
                    via = if (v.status == PipelineVersionStatus.DRAFT) v.updatedVia else v.createdVia,
                )
            }
        return Resolved(
            name = name,
            selected = selected,
            draft = draft,
            currentVisible = current?.version,
            versions = rows,
            usedBy = usedByFacts(workspaceId, view, name),
        )
    }

    /**
     * Marks every row with the version the page is viewing — called once, after [resolve], so
     * the Versions tab and the selector cannot disagree with the page about which row is viewed.
     */
    fun markViewed(versions: List<VersionRowView>, viewedVersion: Int?): List<VersionRowView> =
        versions.map { if (it.version == viewedVersion) it.copy(isViewed = true) else it }

    /** Rule 1 — the explicit version, admitted or the family's 404. */
    private fun explicit(
        workspaceId: UUID,
        view: LensedView,
        name: String,
        version: Int,
    ): Selected {
        val body = reads.findVersion(workspaceId, view.templates, name, version) ?: throw notFound(name, version)
        val detail = reads.findVersionDetail(workspaceId, view.templates, name, version)
        return Selected(body, detail)
    }

    /** Rules 2–4 — the current release first, then the draft-only shape, then choose-a-version. */
    private fun default(
        workspaceId: UUID,
        view: LensedView,
        name: String,
        current: Template?,
        draft: TemplateVersionDetail?,
    ): Selected {
        current?.let { return Selected(it, reads.findVersionDetail(workspaceId, view.templates, name, it.version)) }
        draft?.let { d ->
            // Rule 3 is reached only when the caller can SEE the draft (the lens returned it),
            // so its body resolves for them by construction — but the read still goes through
            // the lens, never the raw repository.
            reads.findVersion(workspaceId, view.templates, name, d.version)?.let { body ->
                return Selected(body, d)
            }
        }
        return Selected(null, null)
    }

    /**
     * The "Used by" tab's facts. 178/178b: neither a hidden pipeline nor a visible one's DRAFT
     * pin leaks through the reverse arrow. #320: the tab lists what the `template.in_use`
     * refusal would name — parameter sets and visualizations beside the pipelines, each under
     * its own lens, from the same evidence methods every guard reads.
     */
    private fun usedByFacts(
        workspaceId: UUID,
        view: LensedView,
        name: String,
    ): UsedByFacts {
        val pins = usage.pipelinesReferencedAnywhere(workspaceId, view, name)
        val setPins = usage.referencedAnywhere(workspaceId, view, name)
        val visualizationPins = usage.visualizationsReferencedAnywhere(workspaceId, view, name)
        val pipelineCount = pins.map { it.pipelineId }.distinct().size
        return UsedByFacts(
            pipelines = pins,
            pipelineCount = pipelineCount,
            sets = setPins,
            visualizations = visualizationPins,
            summary =
                usedBySummary(
                    pipelines = pipelineCount,
                    parameterSets = setPins.map { it.setId }.distinct().size,
                    visualizations = visualizationPins.map { it.artifactId }.distinct().size,
                ),
        )
    }

    /** The "Used by" header: each kind that pins the template, counted once per object ("nothing" when none does). */
    private fun usedBySummary(
        pipelines: Int,
        parameterSets: Int,
        visualizations: Int,
    ): String =
        listOfNotNull(
            plural(pipelines, "pipeline", "pipelines"),
            plural(parameterSets, "parameter set", "parameter sets"),
            plural(visualizations, "visualization", "visualizations"),
        ).joinToString(" · ").ifEmpty { "nothing" }

    private fun plural(
        count: Int,
        one: String,
        many: String,
    ): String? = if (count == 0) null else "$count ${if (count == 1) one else many}"

    /**
     * The family's 404 for this surface — the same code the REST twin answers through
     * `ApiErrorCatalog` (404), never the exception message, so an absent name, a foreign one
     * and a lens-hidden one answer identically (auth.md §11A.1). Shared with the controller,
     * whose grammar check throws it before any read.
     */
    internal fun notFound(
        name: String,
        version: Int? = null,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Template.NOT_FOUND,
        message =
            if (version == null) {
                "Template '$name' not found."
            } else {
                "Template '$name' version $version not found."
            },
        details =
            buildMap {
                put("template_id", name)
                version?.let { put("version", it.toString()) }
            },
    )

    /**
     * One request's resolved version state — the model the page renders. The page paints the
     * version-explicit facts off [selected]; [versions] is the admitted history the selector,
     * the chip marks and the Versions tab all read.
     */
    data class Resolved(
        /** The template's name — the id IS the name, the folder path the sidebar tree keys on. */
        val name: String,
        /** The selected version: null body = the choose-a-version/empty state. */
        val selected: Selected,
        /** The §7 draft pointer, when the caller's lens admits one. */
        val draft: TemplateVersionDetail?,
        /** The current release the caller may see — a lens-hidden draft is never here. */
        val currentVisible: Int?,
        /** The admitted history, newest first, marked with the viewed and current rows. */
        val versions: List<VersionRowView>,
        val usedBy: UsedByFacts,
    ) {
        val hasSelectedBody: Boolean get() = selected.template != null
        val viewedVersion: Int? get() = selected.template?.version
        val viewedTemplate: Template? get() = selected.template

        /** The viewed row's status word for the chip; null when the detail row is absent (no badge). */
        val viewedStatus: PipelineVersionStatus?
            get() = selected.detail?.status ?: selected.template?.status

        val viewedIsDraft: Boolean get() = viewedStatus == PipelineVersionStatus.DRAFT
        val viewedIsCurrent: Boolean get() = viewedVersion != null && viewedVersion == currentVisible

        /** R5: the viewed version is editable only when it IS the working draft. */
        val viewedIsWorkingDraft: Boolean
            get() = viewedVersion != null && viewedVersion == draft?.version && viewedIsDraft

        /** The one string the header chip prints: vN · status · current, each clause only when the view carries it. */
        val viewedLabel: String
            get() =
                viewedVersion?.let { v ->
                    buildString {
                        append('v').append(v)
                        viewedStatus?.let { append(" · ").append(it.name.lowercase()) }
                        if (viewedIsCurrent) append(" · current")
                    }
                } ?: NO_VERSION_SELECTED

        companion object {
            /** The choose-a-version state's chip word — no version, no invented metadata. */
            const val NO_VERSION_SELECTED = "no version selected"
        }
    }

    /** One viewed version's facts — the body exactly as the lensed read returned it, plus its optional detail row. */
    data class Selected(
        /** The stored version, or null for the choose-a-version/empty state. */
        val template: Template?,
        val detail: TemplateVersionDetail?,
    )

    /** The "Used by" tab's facts, gathered once. */
    data class UsedByFacts(
        val pipelines: List<co.datapipelines.pipeline.TemplatePin>,
        val pipelineCount: Int,
        val sets: List<co.datapipelines.parameters.ParameterSetPin>,
        val visualizations: List<co.datapipelines.visualization.ArtifactPin>,
        val summary: String,
    )

    companion object {
        /**
         * The "References" reading — the distinct leading identifiers the body interpolates:
         * `start_date` from a `start_date` interpolation, `row` from a `row.borough` one.
         *
         * A derived READING, not a declared contract: a template declares no parameter schema
         * anywhere in this system (only a pipeline does), so the honest thing to show is what
         * the text references, labelled as that. Directives (`<#if …>`) are deliberately not
         * scanned — a loop variable is not an input, and listing one would invent a parameter.
         */
        fun interpolations(body: String): List<String> =
            INTERPOLATION
                .findAll(body)
                .map { it.groupValues[1] }
                .distinct()
                .sorted()
                .toList()

        /** A Freemarker interpolation's leading identifier — the scan [interpolations] runs. */
        private val INTERPOLATION = Regex("""\$\{\s*([A-Za-z_][A-Za-z0-9_]*)""")
    }
}
