package co.datapipelines.web.ui

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.EvidenceVerdict
import co.datapipelines.visualization.ReleaseCandidate
import co.datapipelines.visualization.ReleaseEvidence
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationService
import java.time.Instant
import java.util.UUID

/**
 * The visualization lifecycle dialogs' facts (#399), one build per verb in [DashboardLifecycleDialogModel]'s shape —
 * the 094 §B discipline: the question is answered BEFORE the button exists, a refused branch carries no button, and
 * every fact is the SAME read the service's own guard makes, so the dialog and the POST cannot disagree:
 *
 * - Release: at least one test case (the service's first check), the transform pin's status through
 *   [TemplateVersionStatuses] (the cascade's own read — RELEASED passes, DRAFT is the ONE consent's row,
 *   MISSING/DISCARDED refuse), then the [ReleaseEvidence] verdict for exactly this draft (the gate the release runs in
 *   its transaction: latest run for this hash, GREEN, the mechanical check passing now).
 * - Discard / purge: the pin guard — the dashboards whose LIVE versions pin this version (refused `version.pinned`).
 *
 * The POST still re-runs every guard — a run, a pin or an edit can land between the dialog opening and the click;
 * the screen is never the authority. Only authors and admins reach these routes (the verbs' rows), whose read is the
 * whole view, so the reads here are [ReadLens.Everything] (400's model's rule).
 */
@Suppress("TooManyFunctions") // one build per verb, plus the shared reads
class VisualizationLifecycleDialogModel(
    private val visualizations: VisualizationService,
    private val dashboards: DashboardService,
    private val templateStatuses: TemplateVersionStatuses,
    private val evidence: ReleaseEvidence,
    private val actors: ActorNames,
    private val authoring: AuthoringGuard,
) {
    /** "v3 is not draft" and friends: a dialog for a shape the table refuses still OPENS and says why. */
    data class Refusal(
        val code: String,
        val message: String,
    )

    /** The transform pin as the release gate reads it. */
    data class PinView(
        val name: String,
        val version: Int,
        /** Null = the pinned template version does not exist (MISSING refuses). */
        val status: PipelineVersionStatus?,
    ) {
        val label: String get() = "$name@$version"
        val statusLabel: String get() = status?.name ?: "MISSING"

        /** The ONE consent's row: a DRAFT template pin the release can release with the visualization. */
        val cascadable: Boolean get() = status == PipelineVersionStatus.DRAFT

        /** Anything but RELEASED or a cascadable DRAFT refuses. */
        val blocks: Boolean get() = status != PipelineVersionStatus.RELEASED && !cascadable
    }

    // ------------------------------------------------------------------ release

    data class ReleaseDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val bodyHash: String,
        val updatedBy: String,
        val updatedAgo: String,
        val caseCount: Int,
        val pin: PinView?,
        /** The refusals the POST would answer, in the service's order; empty = the button renders. */
        val refusals: List<Refusal>,
    ) {
        val canRelease: Boolean get() = refusals.isEmpty()
        val needsConsent: Boolean get() = pin?.cascadable == true
    }

    fun release(
        workspaceId: UUID,
        id: UUID,
    ): ReleaseDialog {
        val working = working(workspaceId, id)
        if (working.detail.status != PipelineVersionStatus.DRAFT) {
            return ReleaseDialog(
                id = id,
                name = working.record.name,
                version = working.record.currentVersion ?: working.detail.version,
                bodyHash = "",
                updatedBy = "",
                updatedAgo = "",
                caseCount = 0,
                pin = null,
                refusals =
                    listOf(
                        Refusal(VisualizationErrorCodes.VERSION_NOT_DRAFT, "This visualization has no draft — nothing to release."),
                    ),
            )
        }
        val body = working.body
        val caseCount =
            body.tests
                ?.cases
                .orEmpty()
                .size
        val pin =
            body.transform?.template?.let {
                PinView(
                    it.name,
                    it.version,
                    templateStatuses.statusOf(workspaceId, it.name, it.version),
                )
            }
        val refusals = releaseRefusals(workspaceId, id, working, caseCount, pin)
        return ReleaseDialog(
            id = id,
            name = working.record.name,
            version = working.detail.version,
            bodyHash = working.detail.bodyHash,
            updatedBy = actorName(working.detail.updatedBy ?: working.detail.createdBy),
            updatedAgo = RelativeTime.since(working.detail.updatedAt ?: working.detail.createdAt, Instant.now()),
            caseCount = caseCount,
            pin = pin,
            refusals = refusals,
        )
    }

    /**
     * The release refusals in the service's order: no test cases, then a blocking transform pin, then — only when
     * there are cases — the evidence gate's own verdict (the same port the POST consults).
     */
    private fun releaseRefusals(
        workspaceId: UUID,
        id: UUID,
        working: ArtifactVersion<VisualizationBody>,
        caseCount: Int,
        pin: PinView?,
    ): List<Refusal> =
        buildList {
            if (caseCount == 0) {
                add(
                    Refusal(
                        VisualizationErrorCodes.RELEASE_TESTS_MISSING,
                        "The draft has no test cases — a release needs at least one.",
                    ),
                )
            }
            if (pin != null && pin.blocks) {
                add(
                    Refusal(
                        VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
                        "Template ${pin.label} is ${pin.statusLabel} — only a RELEASED pin, or a DRAFT one released with this " +
                            "visualization, can be released.",
                    ),
                )
            }
            if (caseCount > 0) {
                val candidate = ReleaseCandidate(id, working.record.name, working.detail.version, working.detail.bodyHash, working.body)
                when (val verdict = evidence.verdict(workspaceId, candidate)) {
                    EvidenceVerdict.Pass -> Unit
                    is EvidenceVerdict.Refused -> add(Refusal(verdict.code, verdict.message))
                }
            }
        }

    // ------------------------------------------------------------------ purge draft / purge version

    /** The Purge dialog — irreversible; [expected] is what the typed confirm must say; [pinnedBy] refuses. */
    data class PurgeDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val expected: String,
        val pinnedBy: List<String>,
        /** The purge takes the whole visualization (its only version is this draft). */
        val takesEntity: Boolean,
    ) {
        val canPurge: Boolean get() = pinnedBy.isEmpty()
    }

    fun purge(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): PurgeDialog {
        val working = working(workspaceId, id)
        val versions = visualizations.listVersions(workspaceId, ReadLens.Everything, id)
        val detail = versions.firstOrNull { it.version == version } ?: throw versionNotFound(id, version)
        if (detail.status != PipelineVersionStatus.DRAFT) {
            throw DatapipelinesException(
                code = VisualizationErrorCodes.VERSION_NOT_DRAFT,
                message =
                    "Version $version of '${working.record.name}' is ${detail.status} — only a draft is purged; a release is discarded.",
                details = mapOf("visualization_id" to id.toString(), "version" to version, "status" to detail.status.name),
            )
        }
        return PurgeDialog(
            id = id,
            name = working.record.name,
            version = version,
            expected = "v$version",
            pinnedBy = pinnersOf(workspaceId, working.record.name, version),
            takesEntity = versions.size == 1,
        )
    }

    // ------------------------------------------------------------------ discard

    /** The Discard dialog: reversible, but it can move the pointer (D60) — so it says where to. */
    data class DiscardDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val isCurrent: Boolean,
        val fallback: String?,
        val pinnedBy: List<String>,
    ) {
        val canDiscard: Boolean get() = pinnedBy.isEmpty()
    }

    fun discard(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): DiscardDialog {
        val working = working(workspaceId, id)
        val versions = visualizations.listVersions(workspaceId, ReadLens.Everything, id)
        val detail = versions.firstOrNull { it.version == version } ?: throw versionNotFound(id, version)
        if (detail.status != PipelineVersionStatus.RELEASED) {
            throw DatapipelinesException(
                code = VisualizationErrorCodes.VERSION_NOT_RELEASED,
                message =
                    "Version $version of '${working.record.name}' is ${detail.status} — discard targets a RELEASED version; " +
                        "a draft is purged.",
                details = mapOf("visualization_id" to id.toString(), "version" to version, "status" to detail.status.name),
            )
        }
        val current = working.record.currentVersion
        val isCurrent = current == version
        val fallback =
            if (isCurrent) {
                versions
                    .filter { it.version != version && eligible(it.status) }
                    .maxOfOrNull { it.version }
                    ?.let { "v$it becomes the current version." }
                    ?: "no eligible version remains — the visualization has no current version until one is released."
            } else {
                null
            }
        return DiscardDialog(id, working.record.name, version, isCurrent, fallback, pinnersOf(workspaceId, working.record.name, version))
    }

    // ------------------------------------------------------------------ restore

    data class RestoreDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val currentVersion: Int?,
        val movesPointer: Boolean,
    )

    fun restore(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): RestoreDialog {
        val working = working(workspaceId, id)
        val detail =
            visualizations.listVersions(workspaceId, ReadLens.Everything, id).firstOrNull { it.version == version }
                ?: throw versionNotFound(id, version)
        if (detail.status != PipelineVersionStatus.DISCARDED) {
            throw DatapipelinesException(
                code = VisualizationErrorCodes.VERSION_NOT_DISCARDED,
                message = "Version $version of '${working.record.name}' is ${detail.status} — restore targets a DISCARDED version.",
                details = mapOf("visualization_id" to id.toString(), "version" to version, "status" to detail.status.name),
            )
        }
        val current = working.record.currentVersion
        return RestoreDialog(id, working.record.name, version, current, movesPointer = current == null || version > current)
    }

    // ------------------------------------------------------------------ purge entity

    data class PurgeEntityDialog(
        val id: UUID,
        val name: String,
        val expected: String,
        val refusal: Refusal?,
        val pinnedBy: List<String>,
    ) {
        val canPurge: Boolean get() = refusal == null && pinnedBy.isEmpty()
    }

    fun purgeEntity(
        workspaceId: UUID,
        id: UUID,
    ): PurgeEntityDialog {
        val working = working(workspaceId, id)
        val versions = visualizations.listVersions(workspaceId, ReadLens.Everything, id)
        val soleDraft = versions.size == 1 && versions.single().status == PipelineVersionStatus.DRAFT
        return PurgeEntityDialog(
            id = id,
            name = working.record.name,
            expected = working.record.name,
            refusal =
                if (soleDraft) {
                    null
                } else {
                    Refusal(
                        VisualizationErrorCodes.VERSION_LAST_RELEASE,
                        "An entity purge requires the only version to be a DRAFT — this visualization holds ${versions.size} " +
                            "version(s) (${versions.joinToString { "v${it.version} ${it.status.name.lowercase()}" }}). Discard is per " +
                            "version; the visualization stays.",
                    )
                },
            pinnedBy = pinnersOf(workspaceId, working.record.name, null),
        )
    }

    // ------------------------------------------------------------------ switch

    data class SwitchOption(
        val version: Int,
        val status: PipelineVersionStatus,
        val isCurrent: Boolean,
        val eligible: Boolean,
    )

    data class SwitchDialog(
        val id: UUID,
        val name: String,
        val currentVersion: Int?,
        val options: List<SwitchOption>,
    )

    fun switch(
        workspaceId: UUID,
        id: UUID,
    ): SwitchDialog {
        val working = working(workspaceId, id)
        val current = working.record.currentVersion
        return SwitchDialog(
            id = id,
            name = working.record.name,
            currentVersion = current,
            options =
                visualizations
                    .listVersions(workspaceId, ReadLens.Everything, id)
                    .sortedByDescending { it.version }
                    .map { SwitchOption(it.version, it.status, it.version == current, eligible(it.status)) },
        )
    }

    // ------------------------------------------------------------------ shared reads

    private fun working(
        workspaceId: UUID,
        id: UUID,
    ): ArtifactVersion<VisualizationBody> =
        visualizations.findWorking(workspaceId, ReadLens.Everything, id) ?: throw VisualizationWorkspaceModel.notFound(id)

    /**
     * The pin guard's answer, as `name@version` of the LIVE dashboard versions pinning [version] (any version when
     * null): [DashboardService.pinnedBy] under the whole view is the guard's own probe (`livePinsOf`), narrowed to
     * the pinned version through each pinning body — the Used-by tab's read.
     */
    private fun pinnersOf(
        workspaceId: UUID,
        name: String,
        version: Int?,
    ): List<String> {
        val pinning = dashboards.pinnedBy(workspaceId, ReadLens.Everything, name)
        if (version == null) return pinning
        return pinning.filter { ref ->
            val dashboardVersion = ref.substringAfterLast('@').toIntOrNull() ?: return@filter true
            val body = dashboards.findVersionByName(workspaceId, ReadLens.Everything, ref.substringBeforeLast('@'), dashboardVersion)?.body
            // An unreadable pinner is kept: the dialog over-warns rather than under-warns, and the POST decides.
            body == null || body.visualizations.any { it.visualization.name == name && it.visualization.version == version }
        }
    }

    /** §3.4: RELEASED always, DRAFT only under the development posture. */
    private fun eligible(status: PipelineVersionStatus): Boolean =
        PipelineVersionStatus.eligibleForPointer(status, authoring.developmentPosture)

    private fun actorName(id: UUID): String = actors.lookup(listOf(id))[id] ?: ActorNames.fallback(id)

    private fun versionNotFound(
        id: UUID,
        version: Int,
    ) = VisualizationWorkspaceModel.notFound(id, version)
}
