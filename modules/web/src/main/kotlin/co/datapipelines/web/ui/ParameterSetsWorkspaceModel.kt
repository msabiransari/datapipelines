package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The canonical Parameter Sets workspace page's one model (#374, the workspace spec §6.2) — **which
 * version the page shows, and which facts about it**. `GET /parameter-sets/{id}?version=N&tab=…` is
 * [PipelineWorkspaceModel]'s shape for the third artifact family: the caller's lens narrows every read
 * here, an explicit version never falls back, and the default resolution is the actual current pointer
 * first (the working-version rule, draft-first, stays with the authoring surfaces).
 *
 * The resolution, in the spec's order:
 *
 * 1. an explicit admitted version — its own body, or the house 404. Never clamped, never re-resolved
 *    to a "nearby" version, and a status cannot be probed by number: under a narrowing lens only a
 *    RELEASED version resolves, so a draft number asked through a promoter's lens is the same 404 an
 *    unknown number is;
 * 2. the actual current pointer, when the caller's lens admits that version;
 * 3. no admitted current and an accessible draft — that draft, labelled (an Everything-lens object by
 *    construction: the promoter's lens never reads one);
 * 4. otherwise the choose-a-version state over the admitted history ([Resolved.selected] is null) —
 *    nothing to render a graph OF, no evaluate, no invented pointer.
 *
 * A set the caller cannot see at all — unknown id, foreign workspace, lens-hidden, nothing readable —
 * is ONE answer, the house 404 ([notFound]), so none of those can be told apart by probing.
 *
 * Declared as an explicit `@Bean` in [UiConfig]; every read goes through [ParameterSetService] (the
 * transports' direct repository reads are an inventoried, closed list — `ArchitectureGuardTest`).
 */
class ParameterSetsWorkspaceModel(
    private val sets: ParameterSetService,
) {
    /**
     * Resolves the page's version state for one request. [requestedVersion] is the already-validated
     * explicit version (null = the default resolution). The caller's [view] carries the lens, so a
     * promoter and an author resolve DIFFERENT bodies from the same URL — the read lens working, not a
     * second permission check.
     */
    fun resolve(
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        requestedVersion: Int?,
    ): Resolved {
        val lens = view.parameterSets
        // The visibility oracle: the working version through the lens. Absent, foreign, hidden — null.
        val working = sets.findWorking(workspaceId, lens, id) ?: throw notFound()
        val record = working.record
        val versions = sets.listVersions(workspaceId, lens, id)
        val currentVisible = record.currentVersion?.takeIf { current -> versions.any { it.version == current } }
        val selected =
            if (requestedVersion != null) {
                sets.findVersion(workspaceId, lens, id, requestedVersion) ?: throw notFound()
            } else {
                currentVisible?.let { sets.findVersion(workspaceId, lens, id, it) }
                    ?: working.takeIf { it.detail.status == PipelineVersionStatus.DRAFT }
            }
        // The draft pointer, when the caller's lens admits one: the working version IS the draft only under
        // the Everything lens, so a promoter's page carries none (178b — a hidden draft has no pointer here).
        val draft = working.detail.takeIf { it.status == PipelineVersionStatus.DRAFT }
        return Resolved(
            record = record,
            selected = selected,
            draft = draft,
            currentVisible = currentVisible,
            versions = versions.map { VersionChoice(it, it.version == currentVisible, it.version == selected?.detail?.version) },
        )
    }

    /**
     * The house 404 for this surface — one answer for every absence the URL can name, the message naming
     * nothing (not the id, not a version), so an absent set, a foreign one, a lens-hidden one and an
     * explicit version that is absent or not admitted cannot be told apart.
     */
    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Parameter set not found")

    /** One request's resolved version state — the model the page renders and its JSON blocks pin. */
    data class Resolved(
        val record: ParameterSetRecord,
        /** The viewed version with its body; null = the choose-a-version state. */
        val selected: ParameterSetVersion?,
        /** The draft pointer, when the caller's lens admits one. */
        val draft: ParameterSetVersionDetail?,
        /** The current pointer as the caller may see it — a hidden current is absent here. */
        val currentVisible: Int?,
        /** The admitted history, newest first, marked with the viewed and current rows. */
        val versions: List<VersionChoice>,
    ) {
        val hasSelectedBody: Boolean get() = selected != null
        val viewedVersion: Int? get() = selected?.detail?.version
        val viewedStatus: PipelineVersionStatus? get() = selected?.detail?.status
        val viewedIsDraft: Boolean get() = viewedStatus == PipelineVersionStatus.DRAFT
        val viewedIsCurrent: Boolean get() = viewedVersion != null && viewedVersion == currentVisible

        /** The one string the header chip prints: `vN · status · current`, each clause only when the view carries it. */
        val viewedLabel: String
            get() =
                viewedVersion?.let { v ->
                    buildString {
                        append('v').append(v)
                        viewedStatus?.let { append(" · ").append(it.name.lowercase()) }
                        if (viewedIsCurrent) append(" · current")
                    }
                } ?: NO_VERSION_SELECTED
    }

    /** One row of the version selector: the fact lines the header chips read. */
    data class VersionChoice(
        val detail: ParameterSetVersionDetail,
        val isCurrent: Boolean,
        val isViewed: Boolean,
    ) {
        val version: Int get() = detail.version
        val status: PipelineVersionStatus get() = detail.status
    }

    /**
     * The tab set the page's query contract admits (workspace spec §6.2): the closed set
     * `workspace | history`, an unknown or missing value is `workspace`. `history` renders the set's
     * evaluation records (#376, spec §6.4) in place of the live form and the graph.
     */
    enum class ParameterSetWorkspaceTab(
        val wire: String,
    ) {
        WORKSPACE("workspace"),
        HISTORY("history"),
        ;

        companion object {
            fun fromWire(raw: String?): ParameterSetWorkspaceTab = entries.firstOrNull { it.wire == raw } ?: WORKSPACE
        }
    }

    companion object {
        /** The choose-a-version state's chip word — no version, no invented metadata. */
        const val NO_VERSION_SELECTED = "no version selected"

        /**
         * The `version` query parameter's one parse — [PipelineWorkspaceModel.parseRequestedVersion]'s
         * rule, shared rather than restated: optional, and when supplied a positive integer, anything
         * else the house 400 — never a silent clamp to another version.
         */
        fun parseRequestedVersion(raw: String?): Int? = PipelineWorkspaceModel.parseRequestedVersion(raw)
    }
}
