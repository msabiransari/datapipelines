package co.datapipelines.web.ui

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.ParameterSetFacts
import co.datapipelines.visualization.PipelineReleaseFacts
import co.datapipelines.visualization.VisualizationPins
import java.time.Instant
import java.util.UUID

/**
 * The dashboard lifecycle dialogs' facts (#400), one build per verb in [PipelineLifecycleDialogModel]'s
 * shape — the 094 §B discipline: the question is answered BEFORE the button exists, a refused branch
 * carries no button at all, and every list is the SAME evidence the service's own guard reads (the
 * §3.2 dependency scans behind `dashboard.release.dependency_not_released`, the D61 cascade's worklist),
 * so the dialog and the POST cannot disagree.
 *
 * The POST still re-runs the guard — a pin can land between the dialog opening and the button being
 * pressed, and the screen is never the authority. These models only decide what to SAY.
 *
 * #397 is NOT built here (held on an owner question): the Release dialog is the pipeline release
 * dialog's shape over the family's EXISTING rule — every pinned visualization RELEASED, or the one
 * D61 consent (`release_pinned_visualizations`) cascading a DRAFT visualization pin through its own
 * gate in the dashboard's transaction. No dependency tree, no include checkboxes beyond that one
 * consent; the parameter set and the source pipelines have no cascade: their pins must be RELEASED.
 */
class DashboardLifecycleDialogModel(
    private val dashboards: DashboardService,
    /** The source pipelines' release facts — the §3.2 read the validator and the release guard run. */
    private val pipelines: PipelineReleaseFacts,
    /** The pinned set's status — the same port the validator reads (D40). */
    private val sets: ParameterSetFacts,
    /** The pinned visualizations' statuses — the same port the validator and the release guard read. */
    private val pins: VisualizationPins,
    private val actors: ActorNames,
    private val authoring: AuthoringGuard,
) {
    /** "v3 is not draft" and friends: a dialog for a shape the table refuses still OPENS. */
    data class Refusal(
        val code: String,
        val message: String,
    )

    private fun refusal(
        code: String,
        message: String,
    ) = Refusal(code, message)

    /** One pinned dependency of the draft body, with the status the release gate would read. */
    data class PinView(
        val kind: String,
        val name: String,
        val version: Int,
        /** Null = the pinned version does not exist at all (a MISSING pin refuses release). */
        val status: PipelineVersionStatus?,
    ) {
        val label: String get() = "$name@$version"

        /** §3.2's rule, stated as the row's colour: anything but RELEASED blocks the release. */
        val blocksRelease: Boolean get() = status != PipelineVersionStatus.RELEASED

        /**
         * D61 — a DRAFT visualization pin is the one kind of blocking pin the release can CASCADE
         * to with the one consent; a DRAFT set, a DRAFT or DISCARDED source, and everything MISSING
         * or DISCARDED refuse as before.
         */
        val cascadable: Boolean get() = kind == KIND_VISUALIZATION && status == PipelineVersionStatus.DRAFT

        val statusLabel: String get() = status?.name ?: "MISSING"
    }

    // ------------------------------------------------------------------ release

    /** The Release dialog: the draft, who last wrote it, and the pins that would refuse. */
    data class ReleaseDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val updatedBy: String,
        val updatedAgo: String,
        val updatedAt: Instant?,
        val pins: List<PinView>,
        /** The no-draft branch: the dialog opens and says why there is no button. */
        val refusal: Refusal?,
    ) {
        /**
         * §3.2 precondition pre-read: a pin the release can neither pass nor cascade past — a
         * non-RELEASED set, a source not RELEASED (or not read-only: the dialog shows the status
         * word; the read-only sentence is the POST's refusal to name), DISCARDED or MISSING —
         * so no button is rendered. A DRAFT visualization pin is not here since D61: it is a
         * [draftPins] row behind the one consent checkbox.
         */
        val blockingPins: List<PinView> get() = pins.filter { it.blocksRelease && !it.cascadable }

        /** D61 — the DRAFT visualization pins the consent checkbox offers to release. */
        val draftPins: List<PinView> get() = pins.filter { it.cascadable }

        /** Every pin that is NOT a cascade row: the RELEASED ones and the blocking ones. */
        val otherPins: List<PinView> get() = pins.filter { !it.cascadable }
    }

    fun release(
        workspaceId: UUID,
        id: UUID,
    ): ReleaseDialog {
        val working =
            dashboards.findWorking(workspaceId, ReadLens.Everything, id)
                ?: throw notFound(id)
        val record = working.record
        if (working.detail.status != PipelineVersionStatus.DRAFT) {
            return ReleaseDialog(
                id = id,
                name = record.name,
                version = record.currentVersion ?: 0,
                updatedBy = "",
                updatedAgo = "",
                updatedAt = null,
                pins = emptyList(),
                refusal = refusal(DashboardErrorCodes.VERSION_NOT_DRAFT, "This dashboard has no draft — nothing to release."),
            )
        }
        val body = working.body
        val pins =
            buildList {
                body.sources.forEach { source ->
                    add(
                        PinView(
                            KIND_SOURCE,
                            source.pipeline.name,
                            source.pipeline.version,
                            pipelines.releaseOf(workspaceId, source.pipeline)?.status,
                        ),
                    )
                }
                body.parameterSet?.let { ref ->
                    add(PinView(KIND_SET, ref.name, ref.version, sets.setOf(workspaceId, ref)?.status))
                }
                body.visualizations
                    .map { it.visualization }
                    .distinct()
                    .forEach { ref ->
                        add(PinView(KIND_VISUALIZATION, ref.name, ref.version, pinStatus(workspaceId, ref)))
                    }
            }
        return ReleaseDialog(
            id = id,
            name = record.name,
            version = working.detail.version,
            updatedBy = working.detail.updatedBy?.let { actorName(it) } ?: actorName(working.detail.createdBy),
            updatedAgo = RelativeTime.since(working.detail.updatedAt ?: working.detail.createdAt, Instant.now()),
            updatedAt = working.detail.updatedAt ?: working.detail.createdAt,
            pins = pins,
            refusal = null,
        )
    }

    /** The pin status read the validator itself makes: a DISCARDED pin reads as the absence that refuses. */
    private fun pinStatus(
        workspaceId: UUID,
        ref: ArtifactRef,
    ): PipelineVersionStatus? = pins.pinOf(workspaceId, ref)?.status

    // ------------------------------------------------------------------ purge draft / purge version

    /** The Purge dialog — the irreversible one. [expected] is what the typed confirm must say. */
    data class PurgeDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val expected: String,
    )

    fun purge(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): PurgeDialog {
        val working =
            dashboards.findWorking(workspaceId, ReadLens.Everything, id)
                ?: throw notFound(id)
        if (working.detail.status != PipelineVersionStatus.DRAFT || working.detail.version != version) {
            // §3.2's table: a release is never purged (last_release); the named version must be
            // the working draft, for the same reason — a dashboard has at most one draft.
            throw DatapipelinesException(
                code = DashboardErrorCodes.VERSION_NOT_DRAFT,
                message = "Version $version of '${working.record.name}' is not the working draft — only a draft is purged.",
                details = mapOf("dashboard_id" to id.toString(), "version" to version),
            )
        }
        return PurgeDialog(id, working.record.name, version, expected = "v$version")
    }

    // ------------------------------------------------------------------ discard

    /** The Discard dialog: reversible, but it can move the pointer (D60) — so it says where to. */
    data class DiscardDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val isServed: Boolean,
        /** What the pointer fallback will do when THIS version is served; null when it is not. */
        val fallback: String?,
    )

    @Suppress("ThrowsCount") // each throw is a distinct catalogued refusal the dialog renders
    fun discard(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): DiscardDialog {
        val working =
            dashboards.findWorking(workspaceId, ReadLens.Everything, id)
                ?: throw notFound(id)
        val detail =
            dashboards
                .listVersions(workspaceId, ReadLens.Everything, id)
                .firstOrNull { it.version == version }
                ?: throw versionNotFound(id, version)
        if (detail.status != PipelineVersionStatus.RELEASED) {
            throw DatapipelinesException(
                code = DashboardErrorCodes.VERSION_NOT_RELEASED,
                message =
                    "Version $version of '${working.record.name}' is ${detail.status} — discard targets a RELEASED version; " +
                        "a draft is purged.",
                details = mapOf("dashboard_id" to id.toString(), "version" to version, "status" to detail.status.name),
            )
        }
        val served = dashboards.findServed(workspaceId, ReadLens.Everything, id)
        val isServed = served?.detail?.version == version
        val fallback =
            if (isServed) {
                val survivor =
                    dashboards
                        .listVersions(workspaceId, ReadLens.Everything, id)
                        .filter { it.version != version && eligible(it.status) }
                        .maxOfOrNull { it.version }
                survivor?.let { "v$it becomes the served version." } ?: "no eligible version remains — the board has no release to serve."
            } else {
                null
            }
        return DiscardDialog(id, working.record.name, version, isServed, fallback)
    }

    // ------------------------------------------------------------------ restore

    /** The Restore dialog: whether the pointer follows (v > served, or none — D60). */
    data class RestoreDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val servedVersion: Int?,
        val movesPointer: Boolean,
    )

    @Suppress("ThrowsCount") // each throw is a distinct catalogued refusal the dialog renders
    fun restore(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): RestoreDialog {
        val working =
            dashboards.findWorking(workspaceId, ReadLens.Everything, id)
                ?: throw notFound(id)
        val detail =
            dashboards
                .listVersions(workspaceId, ReadLens.Everything, id)
                .firstOrNull { it.version == version }
                ?: throw versionNotFound(id, version)
        if (detail.status != PipelineVersionStatus.DISCARDED) {
            throw DatapipelinesException(
                code = DashboardErrorCodes.VERSION_NOT_DISCARDED,
                message = "Version $version of '${working.record.name}' is ${detail.status} — restore targets a DISCARDED version.",
                details = mapOf("dashboard_id" to id.toString(), "version" to version, "status" to detail.status.name),
            )
        }
        val served = dashboards.findServed(workspaceId, ReadLens.Everything, id)
        val servedVersion = served?.detail?.version
        return RestoreDialog(
            id,
            working.record.name,
            version,
            servedVersion,
            movesPointer =
                servedVersion == null || version > servedVersion,
        )
    }

    // ------------------------------------------------------------------ purge entity

    /** The entity purge: sole-draft-only (§3.2), with the refusal that says what is held. */
    data class PurgeEntityDialog(
        val id: UUID,
        val name: String,
        val expected: String,
        /** The `last_release` branch: the dialog opens and says why there is no button. */
        val refusal: Refusal?,
    )

    fun purgeEntity(
        workspaceId: UUID,
        id: UUID,
    ): PurgeEntityDialog {
        val working =
            dashboards.findWorking(workspaceId, ReadLens.Everything, id)
                ?: return PurgeEntityDialog(id, "—", "", refusal(DashboardErrorCodes.NOT_FOUND, "This dashboard no longer exists."))
        val versions = dashboards.listVersions(workspaceId, ReadLens.Everything, id)
        val soleDraft = versions.size == 1 && versions.single().status == PipelineVersionStatus.DRAFT
        return PurgeEntityDialog(
            id = id,
            name = working.record.name,
            expected = working.record.name,
            refusal =
                if (soleDraft) {
                    null
                } else {
                    refusal(
                        DashboardErrorCodes.VERSION_NOT_DRAFT,
                        "An entity purge requires the only version to be a DRAFT — this dashboard holds " +
                            "${versions.size} version(s) (${versions.joinToString { "v${it.version} ${it.status.name.lowercase()}" }}). " +
                            "Discard is per version; the dashboard stays.",
                    )
                },
        )
    }

    // ------------------------------------------------------------------ switch

    /** The Switch dialog's one row. */
    data class SwitchOption(
        val version: Int,
        val status: PipelineVersionStatus,
        val isServed: Boolean,
        val eligible: Boolean,
    )

    data class SwitchDialog(
        val id: UUID,
        val name: String,
        val servedVersion: Int?,
        val options: List<SwitchOption>,
    )

    fun switch(
        workspaceId: UUID,
        id: UUID,
    ): SwitchDialog {
        val working =
            dashboards.findWorking(workspaceId, ReadLens.Everything, id)
                ?: throw notFound(id)
        val versions = dashboards.listVersions(workspaceId, ReadLens.Everything, id)
        val served = dashboards.findServed(workspaceId, ReadLens.Everything, id)
        val servedVersion = served?.detail?.version
        return SwitchDialog(
            id = id,
            name = working.record.name,
            servedVersion = servedVersion,
            options =
                versions
                    .sortedByDescending { it.version }
                    .map { v ->
                        SwitchOption(
                            version = v.version,
                            status = v.status,
                            isServed = v.version == servedVersion,
                            eligible = eligible(v.status),
                        )
                    },
        )
    }

    /** §3.4: RELEASED always, DRAFT only under the development posture (the D63/D60 flag). */
    private fun eligible(status: PipelineVersionStatus): Boolean =
        PipelineVersionStatus.eligibleForPointer(status, authoring.developmentPosture)

    private fun actorName(id: UUID): String = actors.lookup(listOf(id))[id] ?: ActorNames.fallback(id)

    private fun notFound(id: UUID) =
        DatapipelinesException(
            code = DashboardErrorCodes.NOT_FOUND,
            message = "No dashboard with id '$id' in this workspace.",
            details = mapOf("dashboard_id" to id.toString()),
        )

    private fun versionNotFound(
        id: UUID,
        version: Int,
    ) = DatapipelinesException(
        code = DashboardErrorCodes.NOT_FOUND,
        message = "Dashboard '$id' has no version $version.",
        details = mapOf("dashboard_id" to id.toString(), "version" to version),
    )

    private companion object {
        const val KIND_SOURCE = "source"
        const val KIND_SET = "parameter_set"
        const val KIND_VISUALIZATION = "visualization"
    }
}
