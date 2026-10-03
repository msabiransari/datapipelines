package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The canonical pipeline workspace read page's one model (workspace spec §3.1) — **which body
 * the page shows, and which facts about it**. `/pipelines/{id}?version=N` is the read surface
 * the old editor route redirects into, and its admission rule is the READ floor's, not the old
 * editor's: the caller's lens narrows every read here, an explicit version never falls back,
 * and the default resolution is the actual current pointer first — the working-version rule
 * ([PipelineService.workingVersion], draft-first) stays where it belongs, with the authoring
 * and REST surfaces that exist to serve it.
 *
 * The resolution, in the spec's order:
 *
 * 1. an explicit admitted version — its own body, or the house 404 (never clamp, never
 *    re-resolve to a "nearby" version);
 * 2. the actual current pointer, when its body is admitted by the caller's lens — a
 *    development-posture current DRAFT is shown as the draft it is, never rewritten as a
 *    release, and under a narrowing lens an unadmitted current simply does not resolve here;
 * 3. no current pointer (or a hidden one) and an accessible draft — that draft, labelled;
 * 4. otherwise the choose-a-version state over the admitted history, or the empty state when
 *    no version is admitted — and no Execute and no node SQL until a body is selected.
 *
 * The narrow-read tolerance the old editor carried is preserved deliberately: a body whose
 * detail row is absent renders (no lifecycle badge) where a composite read would 404, without
 * inventing status metadata or authorizing anything from the gap.
 */
class PipelineWorkspaceModel(
    private val pipelines: PipelineService,
) {
    /**
     * Resolves the page's version state for one request and stamps the model. [requestedVersion]
     * is the already-validated explicit version (null = the default resolution). The caller's
     * [view] carries the lens, so a promoter and an author resolve DIFFERENT bodies from the
     * same URL — that is the read lens working, not a second permission check.
     *
     * The house 404 ([PipelineWorkspaceModel.notFound]) covers every absence the URL can name:
     * an unknown pipeline id, a lens-hidden one, and an explicit version that is absent or not
     * admitted — all the same answer, so none of them can be probed (auth.md §11A.1).
     */
    fun resolve(
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        requestedVersion: Int?,
    ): Resolved {
        val record =
            pipelines.findRecord(workspaceId, view.pipelines, id)
                ?: throw notFound(id)
        val draft = pipelines.findDraft(workspaceId, view.pipelines, id)
        val selected =
            requestedVersion
                ?.let { explicit(record, view, workspaceId, id, it) }
                ?: default(record, view, workspaceId, id, draft)
        val versions = pipelines.listVersions(workspaceId, view.pipelines, id)
        // The current marker names only what the lens permits: under a narrowing lens a
        // development-posture current draft is hidden — its number and status are draft
        // metadata (workspace spec §3.1: do not expose the number/status of a hidden current).
        val currentVisible =
            if (view.pipelines.isEverything) {
                record.currentVersion
            } else {
                pipelines
                    .findCurrentVersion(workspaceId, view.pipelines, id)
                    ?.takeIf { it.status == PipelineVersionStatus.RELEASED }
                    ?.let { record.currentVersion }
            }
        return Resolved(
            record = record,
            selected = selected,
            draft = draft,
            currentVisible = currentVisible,
            versions = versions.map { VersionChoice(it, it.version == currentVisible, it.version == selected.version) },
        )
    }

    /**
     * Rule 1 — the explicit version, admitted or absent. [PipelineService.findVersionBody] is
     * the admission: under a narrowing lens only a RELEASED body resolves, so a draft number
     * asked through a promoter's lens is the same 404 an unknown number is — a status cannot
     * be probed by number (178b). The detail row is the narrow read's optional second half.
     */
    private fun explicit(
        record: PipelineRecord,
        view: LensedView,
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): Selected {
        val body =
            pipelines.findVersionBody(workspaceId, view.pipelines, id, version)
                ?: throw notFound(id, version)
        val detail = pipelines.findVersion(workspaceId, view.pipelines, record, version)
        return Selected(version, body, detail?.version)
    }

    /**
     * Rules 2–4. The current pointer first — admitted body or move on; a development-posture
     * current draft is the page's view for an author, labelled draft, never re-resolved to the
     * release beneath it. Then the accessible draft (an Everything-lens object by construction,
     * so it carries no hidden-metadata risk). Then the choose-a-version state.
     */
    private fun default(
        record: PipelineRecord,
        view: LensedView,
        workspaceId: UUID,
        id: UUID,
        draft: PipelineVersionDetail?,
    ): Selected {
        val current = record.currentVersion
        if (current != null) {
            pipelines.findVersionBody(workspaceId, view.pipelines, id, current)?.let { body ->
                val detail = pipelines.findVersion(workspaceId, view.pipelines, record, current)
                return Selected(current, body, detail?.version)
            }
        }
        draft?.let { d ->
            pipelines.findVersionBody(workspaceId, view.pipelines, id, d.version)?.let { body ->
                return Selected(d.version, body, d)
            }
        }
        // No body resolved: the choose-a-version state. `selected.version` is null and the
        // page renders admitted history (or the empty state) instead of a graph.
        return Selected(null, null, null)
    }

    /**
     * The house 404 for this surface — [PipelineNodeSqlPartialController]'s shape: the same
     * code the REST twin answers through `ApiErrorCatalog` (404), never the exception message,
     * so an absent id, a foreign one and a lens-hidden one answer identically.
     */
    private fun notFound(
        id: UUID,
        version: Int? = null,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Execution.NOT_FOUND,
        message =
            if (version == null) {
                "Pipeline '$id' not found."
            } else {
                "Pipeline '$id' version $version not found."
            },
        details =
            buildMap {
                put("pipeline_id", id.toString())
                version?.let { put("version", it.toString()) }
            },
    )

    /** One request's resolved version state — the model the page renders and the JSON pins. */
    data class Resolved(
        val record: PipelineRecord,
        /** The selected body: null version = the choose-a-version/empty state. */
        val selected: Selected,
        /** The §7 draft pointer, when the caller's lens admits one. */
        val draft: PipelineVersionDetail?,
        /** The current pointer as the caller may see it — a hidden current is absent here. */
        val currentVisible: Int?,
        /** The admitted history, newest first, marked with the viewed and current rows. */
        val versions: List<VersionChoice>,
    ) {
        val hasSelectedBody: Boolean get() = selected.bodyJson != null
        val viewedVersion: Int? get() = selected.version

        /** The viewed row's status word for the chip; null when the detail row is absent (no badge). */
        val viewedStatus: PipelineVersionStatus? get() = selected.detail?.status
        val viewedIsDraft: Boolean get() = viewedStatus == PipelineVersionStatus.DRAFT
        val viewedIsCurrent: Boolean get() = viewedVersion != null && viewedVersion == currentVisible
    }

    /** One viewed version's facts — the body JSON and whatever detail row exists beside it. */
    data class Selected(
        val version: Int?,
        /** The stored body exactly as [PipelineService.findVersionBody] returned it. */
        val bodyJson: String?,
        val detail: PipelineVersionDetail?,
    )

    /** One row of the version selector: the fact lines the header chips read. */
    data class VersionChoice(
        val record: PipelineVersionRecord,
        val isCurrent: Boolean,
        val isViewed: Boolean,
    ) {
        val version: Int get() = record.version
        val status: PipelineVersionStatus get() = record.status
    }

    companion object {
        /**
         * The `version` query parameter's one parse, shared by the canonical page and the old
         * editor's redirect (workspace spec §3.1/§3.2): [RequestedVersion.parse] — optional, and
         * when supplied a positive integer, anything else the house 400 whose reason never
         * echoes the input.
         */
        fun parseRequestedVersion(raw: String?): Int? = RequestedVersion.parse(raw)
    }
}
