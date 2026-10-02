package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The canonical dashboard workspace read page's one model (#400) — which version the page
 * shows, in [PipelineWorkspaceModel]'s shape copied for the dashboard family. The read
 * surface is `GET /dashboards/{id}?version=N&tab=…` (the old board route gains the query
 * contract; `/dashboards/{id}/preview` is a compatibility redirect into it, SEE_OTHER).
 *
 * The resolution rule is the family's, not a port of the pipeline draft-first default:
 *
 * 1. the visibility oracle first — [DashboardService.findWorking] through the caller's lens;
 *    an absent, foreign or lens-hidden dashboard is the family's 404 (the board handler's
 *    rule since L3b, never a disabled row);
 * 2. an explicit `?version=N` — [DashboardService.findServedVersion] through the lens
 *    (#369's rule: a DRAFT serves only to the everything-lens, DISCARDED to nobody);
 *    anything else is the family's 404 NAMING the version, never a clamp to a near miss;
 * 3. no explicit version — [DashboardService.findServed], the CURRENT RELEASED version;
 *    `null` is NOT an error: it is the choose-a-version state (#409) — the record exists
 *    in the caller's tree, the heading shows its name, and the draft is offered with its
 *    preview link and the "no release yet" sentence. This is the sentence
 *    `dashboards/list.html` promised; the old route answered an empty `h1` and a
 *    "not found" for a dashboard the tree had just linked to.
 *
 * A version that RESOLVES but cannot run (a pin that does not hold, a source no longer
 * admissible) is the board's in-place refusal, decided by the controller's runtime read —
 * this model only resolves what the page would show.
 */
class DashboardWorkspaceModel(
    private val dashboards: DashboardService,
) {
    /**
     * Resolves the page's version state for one request. [requestedVersion] is the already
     * validated explicit version (null = the default resolution). The caller's [view] carries
     * the lens, so a promoter and an author resolve DIFFERENT bodies from the same URL — the
     * read lens working, not a second permission check.
     */
    fun resolve(
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        requestedVersion: Int?,
    ): Resolved {
        val working =
            dashboards.findWorking(workspaceId, view.dashboards, id)
                ?: throw notFound(id)
        val served = dashboards.findServed(workspaceId, view.dashboards, id)
        val selected =
            requestedVersion
                ?.let { version ->
                    dashboards.findServedVersion(workspaceId, view.dashboards, id, version)
                        ?: throw notFound(id, version)
                }
                ?: served
        // The draft pointer is lens metadata: findWorking prefers the draft under the whole
        // view, so its DRAFT row IS the working draft; under a narrowing lens findWorking
        // answers the admitted version only, and a hidden draft names nothing here.
        val draftVersion =
            working.detail.status
                .takeIf { it == PipelineVersionStatus.DRAFT }
                ?.let { working.detail.version }
        return Resolved(
            name = working.record.name,
            selected = selected?.detail,
            served = served?.detail,
            draftVersion = draftVersion,
            versions = dashboards.listVersions(workspaceId, view.dashboards, id),
        )
    }

    /**
     * One request's resolved version state — what the page's chip, selector, panes and
     * choose-a-version state render.
     */
    data class Resolved(
        /** The full path name — the `h1` and the sidebar's current-leaf hook ride it. */
        val name: String,
        /** The version the page shows; null = the choose-a-version state (#409). */
        val selected: ArtifactVersionDetail?,
        /** The served (current RELEASED) version as the caller's lens admits it. */
        val served: ArtifactVersionDetail?,
        /** The working draft's number, when the caller's lens shows one. */
        val draftVersion: Int?,
        /** The admitted history, for the selector, the Versions tab and the choose-a-version links. */
        val versions: List<ArtifactVersionDetail>,
    ) {
        val selectedVersion: Int? get() = selected?.version
        val selectedStatus: PipelineVersionStatus? get() = selected?.status
        val selectedIsDraft: Boolean get() = selectedStatus == PipelineVersionStatus.DRAFT
        val selectedIsCurrent: Boolean get() = selected != null && selected.version == served?.version
        val hasSelected: Boolean get() = selected != null

        /**
         * The one string the chip prints: `v2 · draft`, `v1 · released · current` — every
         * clause only when the caller's view carries it (the pipeline workspace's rule).
         */
        val viewedLabel: String?
            get() =
                selectedVersion?.let { v ->
                    buildString {
                        append('v').append(v)
                        selectedStatus?.let { append(" · ").append(it.name.lowercase()) }
                        if (selectedIsCurrent) append(" · current")
                    }
                }
    }

    companion object {
        /**
         * The `version` query parameter's one parse, shared by the workspace page and the old
         * preview's redirect ([PipelineWorkspaceModel]'s rule verbatim): optional, and when
         * supplied a positive integer — anything else is the house 400, never a silent clamp.
         * Bound as a STRING on purpose: an `Int` binding would answer a non-numeric value with
         * the unhandled-mismatch 500; this way the caller's malformed input gets the 400 page.
         */
        fun parseRequestedVersion(raw: String?): Int? {
            if (raw.isNullOrEmpty()) return null
            val parsed = raw.toIntOrNull() ?: throw badVersion(raw)
            if (parsed <= 0) throw badVersion(raw)
            return parsed
        }

        private fun badVersion(raw: String) =
            ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The version parameter must be a positive integer (got '$raw').",
            )
    }
}

/** The house 404 for this surface — an absent id, a foreign one and a lens-hidden one answer identically (auth.md §11A.1). */
private fun notFound(
    id: UUID,
    version: Int? = null,
): DatapipelinesException =
    DatapipelinesException(
        code = DashboardErrorCodes.NOT_FOUND,
        message =
            if (version == null) {
                "Dashboard '$id' not found."
            } else {
                "Dashboard '$id' version $version not found."
            },
        details =
            buildMap {
                put("dashboard_id", id.toString())
                version?.let { put("version", it.toString()) }
            },
    )
