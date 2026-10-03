package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The visualization workspace's one version model (#399, parent #396) — which version
 * `GET /visualizations/{id}?version=N&tab=…` shows, in [PipelineWorkspaceModel]'s shape (the
 * workspace spec's resolution rules) over the artifact family's lensed reads:
 *
 * 1. the visibility oracle first — [VisualizationService.findWorking] through the caller's lens; an absent,
 *    foreign or lens-hidden visualization is the family's 404 (`visualization.not_found`), never a
 *    disabled row (parity with the REST reader);
 * 2. an explicit `?version=N` — [admitted]: [VisualizationService.findVersion] through the lens, and
 *    a DISCARDED version is not viewable (it serves nothing; its Restore lives on the Versions tab); anything
 *    else is the family's 404 NAMING the version, never a clamp to a near miss;
 * 3. no explicit version — the CURRENT pointer's version when the lens admits it (under the development
 *    posture the pointer may name a DRAFT: shown as a draft); no current, or a hidden one → the accessible
 *    working DRAFT; nothing → `selected = null`, the choose-a-version state.
 *
 * Every tab partial re-runs [admitted] for the version the page resolved, so a stale tab open is refused
 * the same 404, never served another body.
 */
class VisualizationWorkspaceModel(
    private val visualizations: VisualizationService,
) {
    fun resolve(
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        requestedVersion: Int?,
    ): Resolved {
        val lens = view.visualizations
        val working = visualizations.findWorking(workspaceId, lens, id) ?: throw notFound(id)
        val current = working.record.currentVersion?.let { visualizations.findVersion(workspaceId, lens, id, it) }
        val draft = working.takeIf { it.detail.status == PipelineVersionStatus.DRAFT }
        val selected =
            if (requestedVersion != null) {
                admitted(workspaceId, view, id, requestedVersion)
            } else {
                current ?: draft
            }
        return Resolved(
            name = working.record.name,
            displayName = working.record.displayName,
            selected = selected,
            currentVersion = current?.detail?.version,
            draftVersion = draft?.detail?.version,
            versions = visualizations.listVersions(workspaceId, lens, id),
        )
    }

    /** One named version as the caller may view it — the page's and every tab partial's one admission. */
    fun admitted(
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        version: Int,
    ): ArtifactVersion<VisualizationBody> =
        visualizations
            .findVersion(workspaceId, view.visualizations, id, version)
            ?.takeIf { it.detail.status != PipelineVersionStatus.DISCARDED }
            ?: throw notFound(id, version)

    /** One request's resolved version state — what the chip, the selector and the panes render. */
    data class Resolved(
        /** The full path name — the `h1` and the sidebar's current-leaf hook ride it. */
        val name: String,
        val displayName: String,
        /** The version the page shows; null = the choose-a-version state. */
        val selected: ArtifactVersion<VisualizationBody>?,
        /** The current pointer's version as the caller's lens admits it. */
        val currentVersion: Int?,
        /** The working draft's number, when the caller's lens shows one. */
        val draftVersion: Int?,
        /** The admitted history, newest first, for the selector and the choose-a-version links. */
        val versions: List<ArtifactVersionDetail>,
    ) {
        val selectedVersion: Int? get() = selected?.detail?.version
        val selectedStatus: PipelineVersionStatus? get() = selected?.detail?.status
        val selectedIsDraft: Boolean get() = selectedStatus == PipelineVersionStatus.DRAFT
        val selectedIsCurrent: Boolean get() = selected != null && selectedVersion == currentVersion
        val hasSelected: Boolean get() = selected != null

        /** The chip: `v2 · draft`, `v1 · released · current` — every clause only when the view carries it. */
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

    /** The workspace's closed tab set (#399's floor: five, Preview the default). */
    enum class Tab(
        val wire: String,
    ) {
        PREVIEW("preview"),
        OVERVIEW("overview"),
        EVIDENCE("evidence"),
        USED_BY("used-by"),
        VERSIONS("versions"),
        ;

        companion object {
            /** Unknown or missing resolves to Preview — never an error, never echoed. */
            fun fromWire(raw: String?): Tab = entries.firstOrNull { it.wire == raw } ?: PREVIEW
        }
    }

    companion object {
        /**
         * The `version` query parameter's one parse: optional, and when supplied a positive integer — anything
         * else is the house 400 whose reason NEVER echoes the input (an htmx caller shows the reason in a toast).
         * Bound as a String so a non-numeric value is this 400, not the binder's 500.
         */
        fun parseRequestedVersion(raw: String?): Int? {
            if (raw.isNullOrEmpty()) return null
            val parsed = raw.toIntOrNull()
            if (parsed == null || parsed <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, BAD_VERSION)
            return parsed
        }

        const val BAD_VERSION = "The version parameter must be a positive integer."

        /** The house 404 — an absent id, a foreign one and a lens-hidden one answer identically (auth.md §11A.1). */
        fun notFound(
            id: UUID,
            version: Int? = null,
        ): DatapipelinesException =
            DatapipelinesException(
                code = VisualizationErrorCodes.NOT_FOUND,
                message = if (version == null) "Visualization '$id' not found." else "Visualization '$id' version $version not found.",
                details =
                    buildMap {
                        put("visualization_id", id.toString())
                        version?.let { put("version", it.toString()) }
                    },
            )
    }
}
