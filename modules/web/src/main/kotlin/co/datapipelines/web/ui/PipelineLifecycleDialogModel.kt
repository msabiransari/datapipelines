package co.datapipelines.web.ui

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.ExclusiveDraftTemplates
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.RetiredFactCitation
import co.datapipelines.pipeline.TemplateReviewMarks
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.schedules.PipelineJobExecutor
import java.time.Instant
import java.util.UUID

/**
 * The facts a lifecycle DIALOG shows before its one button (ui-screens §4.3d, 102) — one build
 * per verb, the 094 §B discipline: the question is answered BEFORE the button exists, a
 * refused branch carries no button at all, and every list is the SAME evidence the service's
 * own guard reads (the parents scan behind `pipeline.version.pinned`, the exclusive-template
 * offer behind §3.5, the run counts the purge deletes), so the dialog and the POST cannot
 * disagree.
 *
 * The POST still re-runs the guard — a pin can land between the dialog opening and the button
 * being pressed, and the screen is never the authority. These models only decide what to SAY.
 */
class PipelineLifecycleDialogModel(
    private val repository: PipelineRepository,
    private val templates: TemplateVersionStatuses,
    private val exclusiveTemplates: ExclusiveDraftTemplates,
    private val runStats: PipelineRunStats,
    private val actors: ActorNames,
    private val authoring: AuthoringGuard,
    /**
     * 142 — the used-by service behind "also pinned by K other draft pipelines" on a
     * cascadable pin: the promoter is releasing a shared object, and the dialog says so
     * from the ONE used-by scan every surface reads (040), never a scan of its own.
     */
    private val usage: TemplateUsageService,
    /**
     * 7e (transform-nodes design §8.2) — which pins cite a retired learned fact: the SAME port the
     * release service's `warnings` read, so the dialog's rows and the POST's warnings agree.
     * [TemplateReviewMarks.NONE] renders no rows (constructions that predate 7e).
     */
    private val reviewMarks: TemplateReviewMarks = TemplateReviewMarks.NONE,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
    /**
     * #273 — the discard dialog's schedules evidence: the scheduler's by-target read, the SAME
     * `listByTarget` the Usage tab makes (no new scheduler query), through the caller's
     * [co.datapipelines.scheduler.TargetViewer] — a promoter's view is R3's (only the schedules
     * the lens admits).
     */
    private val schedules: co.datapipelines.scheduler.ScheduleService,
    /** #320 — the dashboards that pin a release: the SAME port `PipelineService.refuseIfPinned` asks, so the dialog and the POST agree. */
    private val dashboards: co.datapipelines.pipeline.PipelineVersionConsumers,
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

    /** One template pin of the draft body, with the status the release gate would read. */
    data class PinView(
        val id: String,
        val version: Int,
        /** Null = the pinned version does not exist at all (a MISSING pin refuses release). */
        val status: PipelineVersionStatus?,
        /**
         * 142 — how many OTHER pipelines pin this version in their working version (the
         * used-by count minus this one); shown on a cascadable pin so the promoter knows the
         * template is shared. Zero for a pin the cascade cannot release.
         */
        val otherPinners: Int = 0,
        /**
         * 7e — the retired learned facts this pinned version cites (§8.2). Non-empty makes the
         * pin [needsReview]: a WARNING row above the confirm, never a refusal — the button is
         * untouched, because a fact edit never blocks a release on its own.
         */
        val retiredFacts: List<RetiredFactCitation> = emptyList(),
    ) {
        val label: String get() = "$id@$version"

        /** 7e — the pinned version reads `needs_review` (it cites a retired fact). */
        val needsReview: Boolean get() = retiredFacts.isNotEmpty()

        /** `<id> — superseded by <id>; …` — the phrase the release warning's message uses too. */
        val retiredFactsLabel: String get() = retiredFacts.joinToString("; ") { it.describe() }

        /** §5.3's rule, stated as the row's colour: anything but RELEASED blocks the release. */
        val blocksRelease: Boolean get() = status != RELEASED

        /**
         * 142 — a DRAFT pin is the one kind of blocking pin the release can CASCADE to with
         * consent; DISCARDED and MISSING are not releasable and refuse as before.
         */
        val cascadable: Boolean get() = status == PipelineVersionStatus.DRAFT

        val statusLabel: String get() = status?.name ?: "MISSING"
    }

    /** A live pipeline version pinning the target — the `pipeline.version.pinned` evidence. */
    data class PinnerView(
        val pipelineName: String,
        val pipelineVersion: Int,
        val nodeId: String,
    )

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
        /**
         * #416 — the body hash of the SAME draft the dialog's version and last-writer facts come
         * from. The form posts it back and the release is made AT it, so a draft that changed after
         * the dialog opened is refused `pipeline.version.conflict`, never released silently. Empty on
         * the no-draft branch, whose [refusal] renders no form. The default keeps the render tests'
         * hand-built dialogs compiling; both production constructions pass it explicitly.
         */
        val bodyHash: String = "",
        /**
         * 140: the draft body declares `checks[]` — the dialog runs them as it opens and the
         * run's own fragment decides the submit footer (all pass → Release; failing → the
         * override disclosure). False renders the pre-140 shape: no run, the submit enabled.
         */
        val hasChecks: Boolean,
        /** §3.5's `not_draft` branch: the dialog opens and says why there is no button. */
        val refusal: Refusal?,
    ) {
        /**
         * §5.3 precondition 2, pre-read: a pin the release can neither pass nor cascade past
         * (DISCARDED or MISSING), so no button is rendered. A DRAFT pin is no longer here
         * since 142 — it is a [draftPins] row behind the consent checkbox.
         */
        val blockingPins: List<PinView> get() = pins.filter { it.blocksRelease && !it.cascadable }

        /** 142 — the DRAFT pins the consent checkbox offers to release, one row per version. */
        val draftPins: List<PinView> get() = pins.filter { it.cascadable }

        /** 142 — every pin that is NOT a cascade row: the RELEASED ones and the blocking ones. */
        val otherPins: List<PinView> get() = pins.filter { !it.cascadable }

        /** 7e — the pins that cite a retired fact: one warning row each, above the confirm. */
        val needsReviewPins: List<PinView> get() = pins.filter { it.needsReview }
    }

    fun release(
        workspaceId: UUID,
        id: UUID,
    ): ReleaseDialog {
        val record = repository.findById(workspaceId, id) ?: throw notFound(id)
        val draft = repository.findDraftDetail(workspaceId, id)
        if (draft == null) {
            return ReleaseDialog(
                id = id,
                name = record.name,
                version = record.currentVersion ?: 0,
                updatedBy = "",
                updatedAgo = "",
                updatedAt = null,
                pins = emptyList(),
                bodyHash = "",
                hasChecks = false,
                refusal = refusal("pipeline.version.not_draft", "This pipeline has no draft — nothing to release."),
            )
        }
        val body = repository.findVersionBody(workspaceId, id, draft.version)
        val parsed = body?.let { runCatching { deserializer.readOrThrow(it) }.getOrNull() }
        val refs =
            parsed
                ?.nodes
                ?.filter { it.template.id.isNotBlank() }
                ?.map { it.template }
                ?.distinct()
                .orEmpty()
        // 7e: one read for every pin — the retired citations the release would warn about.
        val retired = if (refs.isEmpty()) emptyMap() else reviewMarks.retiredCitations(workspaceId, refs)
        val pins =
            refs.map { ref ->
                val status = templates.statusOf(workspaceId, ref.id, ref.version)
                PinView(
                    id = ref.id,
                    version = ref.version,
                    status = status,
                    otherPinners = if (status == PipelineVersionStatus.DRAFT) otherPinners(workspaceId, id, ref) else 0,
                    retiredFacts = retired[ref].orEmpty(),
                )
            }
        return ReleaseDialog(
            id = id,
            name = record.name,
            version = draft.version,
            updatedBy = draft.updatedBy?.let { actorName(it) } ?: actorName(draft.createdBy),
            updatedAgo = RelativeTime.since(draft.updatedAt ?: draft.createdAt, Instant.now()),
            updatedAt = draft.updatedAt ?: draft.createdAt,
            pins = pins,
            bodyHash = draft.bodyHash,
            hasChecks = parsed?.checks?.isNotEmpty() == true,
            refusal = null,
        )
    }

    /**
     * 142 — the pipelines OTHER than [pipelineId] whose working version pins [ref], from the
     * used-by service's question 1. The version was just read as DRAFT, so the template
     * exists; the ONE expected failure is the race that removes it between the two reads —
     * the service's own `template.not_found` — and that answers zero rather than refusing
     * to open the dialog (the POST is the guard, never this count). Anything else (the
     * database, a programming error, a different refusal) propagates through the dialog
     * GET's existing error path: an unknown failure must never read as "shared by nobody".
     */
    private fun otherPinners(
        workspaceId: UUID,
        pipelineId: UUID,
        ref: co.datapipelines.pipeline.TemplateRef,
    ): Int {
        val usedBy =
            try {
                usage.usedBy(workspaceId, ReadLens.Everything, ReadLens.Everything, ref.id, ref.version)
            } catch (e: DatapipelinesException) {
                if (e.code != PipelineErrorCodes.Template.NOT_FOUND) throw e
                return 0
            }
        return usedBy.references
            .map { it.pipelineId }
            .distinct()
            .count { it != pipelineId }
    }

    // ------------------------------------------------------------------ purge draft

    /** The Purge dialog — the irreversible one. [expected] is what the typed confirm must say. */
    data class PurgeDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val runCount: Int,
        val expected: String,
        val pinnerPipelines: List<PinnerView> = emptyList(),
        val pinnerDashboards: List<co.datapipelines.pipeline.DashboardPin> = emptyList(),
    )

    @Suppress("ThrowsCount") // each throw is a distinct catalogued refusal the dialog renders
    fun purge(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): PurgeDialog {
        val record = repository.findByIdAnyStatus(workspaceId, id) ?: throw notFound(id)
        val detail =
            repository.findVersionDetail(workspaceId, id, version) ?: throw versionNotFound(id, version)
        if (detail.status != PipelineVersionStatus.DRAFT) {
            // §3.5's table: a release is never purged (last_release); history likewise.
            throw DatapipelinesException(
                code = PipelineErrorCodes.Versioning.LAST_RELEASE,
                message = "Version $version of '${record.name}' is ${detail.status} — only a draft is purged.",
                details = mapOf("pipeline_id" to id.toString(), "version" to version, "status" to detail.status.name),
            )
        }
        return PurgeDialog(
            id = id,
            name = record.name,
            version = version,
            runCount = runStats.runsByVersion(id)[version] ?: 0,
            expected = "v$version",
            pinnerPipelines =
                repository.findLiveParentsPinningVersion(workspaceId, record.name, version).map {
                    PinnerView(it.pipelineName, it.pipelineVersion, it.nodeId)
                },
            pinnerDashboards =
                if (repository.listVersions(workspaceId, id).size == 1) {
                    dashboards.anyVersionPins(workspaceId, record.name)
                } else {
                    dashboards.liveVersionPins(workspaceId, record.name, version)
                },
        )
    }

    // ------------------------------------------------------------------ discard

    /**
     * One schedule whose target names the pipeline (#273) — the evidence a discard's dialog
     * carries beside the pin scan: the discard SUCCEEDS, and each of these then blocks at its
     * next run (the pointer it follows is gone) until repointed or deleted. [state] is the
     * Usage tab's vocabulary (`enabled` / `paused` / `blocked` — [co.datapipelines.scheduler.Schedule.condition]).
     */
    data class ScheduleEvidence(
        val id: UUID,
        val name: String,
        val state: String,
        /** The stored next occurrence (`schedules.next_due_at`); null renders "—". */
        val nextRunLabel: String?,
    )

    /** The Discard dialog: reversible, but it can move the pointer (D60) — so it says where to. */
    data class DiscardDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val isCurrent: Boolean,
        /** What §3.4's fallback will do when THIS version is the pointer; null when it is not. */
        val fallback: String?,
        val pinnerPipelines: List<PinnerView>,
        /** #273 — the live schedules that run this pipeline, by name; empty renders no section. */
        val schedules: List<ScheduleEvidence> = emptyList(),
        /** #320 — the live dashboard versions whose sources pin this version (`referencing_dashboards`); each refuses the discard. */
        val pinnerDashboards: List<co.datapipelines.pipeline.DashboardPin> = emptyList(),
    )

    @Suppress("ThrowsCount") // each throw is a distinct catalogued refusal the dialog renders
    fun discard(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        /** #273 — the SAME lens the Usage tab's schedule read asks ([PrincipalTargetViewer]); a
         * lensed principal sees only the schedules the lens admits, never a second answer. */
        viewer: co.datapipelines.scheduler.TargetViewer,
    ): DiscardDialog {
        val record = repository.findByIdAnyStatus(workspaceId, id) ?: throw notFound(id)
        val detail =
            repository.findVersionDetail(workspaceId, id, version) ?: throw versionNotFound(id, version)
        if (detail.status != RELEASED) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Versioning.NOT_RELEASED,
                message =
                    "Version $version of '${record.name}' is ${detail.status} — discard targets a RELEASED version; " +
                        "a draft is purged.",
                details = mapOf("pipeline_id" to id.toString(), "version" to version, "status" to detail.status.name),
            )
        }
        val pinners =
            repository.findLiveParentsPinningVersion(workspaceId, record.name, version).map {
                PinnerView(it.pipelineName, it.pipelineVersion, it.nodeId)
            }
        val pinnerDashboards = dashboards.liveVersionPins(workspaceId, record.name, version)
        val isCurrent = record.currentVersion == version
        val fallback =
            if (isCurrent) {
                val survivor =
                    repository
                        .listVersions(workspaceId, id)
                        .filter { it.version != version && eligible(it.status) }
                        .maxOfOrNull { it.version }
                survivor
                    ?.let { "v$it becomes current." }
                    ?: "nothing eligible remains — the pipeline will have no current version, and its endpoints answer 503."
            } else {
                null
            }
        // #273 — the scheduler's existing by-target read (the one the Usage tab makes; no new
        // query), through the caller's lens. A read failure propagates: a dialog that says
        // "no schedules" when the read broke would read as a safe discard.
        val runsOnIt =
            schedules
                .listByTarget(workspaceId, PipelineJobExecutor.TARGET_PREFIX + record.name, viewer)
                .map { ScheduleEvidence(it.id, it.name, it.condition, it.nextDueAt?.let(RelativeTime::absolute)) }
        return DiscardDialog(id, record.name, version, isCurrent, fallback, pinners, runsOnIt, pinnerDashboards)
    }

    // ------------------------------------------------------------------ restore

    /** The Restore dialog: whether the pointer follows (v > current, or current NULL — §3.4). */
    data class RestoreDialog(
        val id: UUID,
        val name: String,
        val version: Int,
        val currentVersion: Int?,
        val movesPointer: Boolean,
    )

    @Suppress("ThrowsCount") // each throw is a distinct catalogued refusal the dialog renders
    fun restore(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): RestoreDialog {
        val record = repository.findByIdAnyStatus(workspaceId, id) ?: throw notFound(id)
        val detail =
            repository.findVersionDetail(workspaceId, id, version) ?: throw versionNotFound(id, version)
        if (detail.status != PipelineVersionStatus.DISCARDED) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Versioning.NOT_DISCARDED,
                message = "Version $version of '${record.name}' is ${detail.status} — restore targets a DISCARDED version.",
                details = mapOf("pipeline_id" to id.toString(), "version" to version, "status" to detail.status.name),
            )
        }
        // A local of the pointer: `record.currentVersion` is a public property from another
        // module, and §3.4's rule reads it twice — smart-cast it once instead.
        val pointer = record.currentVersion
        return RestoreDialog(
            id = id,
            name = record.name,
            version = version,
            currentVersion = pointer,
            movesPointer = pointer == null || version > pointer,
        )
    }

    // ------------------------------------------------------------------ purge entity

    /** The entity purge: sole-draft-only (§3.5 `{D}`), with the exclusive-templates offer. */
    data class PurgeEntityDialog(
        val id: UUID,
        val name: String,
        val leafName: String,
        val runCount: Int,
        val exclusiveTemplates: List<String>,
        val expected: String,
        /** The `last_release` branch: the dialog opens and says why there is no button. */
        val refusal: Refusal?,
        /**
         * #335 — the draft-only templates the offer SKIPS because a parameter set or a visualization also pins them
         * (`ExclusiveDraftTemplates.keptIds`, the list the REST response reports as `kept_draft_templates`). Not lensed:
         * the verb is author-or-above, whose view is everything.
         */
        val keptTemplates: List<KeptTemplate> = emptyList(),
    )

    /** A draft-only template the purge leaves in place, and the parameter sets and visualizations that hold it (#335). */
    data class KeptTemplate(
        val name: String,
        val parameterSets: List<String>,
        val visualizations: List<String>,
    )

    fun purgeEntity(
        workspaceId: UUID,
        id: UUID,
    ): PurgeEntityDialog {
        val record =
            repository.findByIdAnyStatus(workspaceId, id)
                ?: return PurgeEntityDialog(
                    id,
                    "—",
                    "—",
                    0,
                    emptyList(),
                    "",
                    refusal("pipeline.not_found", "This pipeline no longer exists."),
                )
        val versions = repository.listVersions(workspaceId, id)
        val soleDraft = versions.size == 1 && versions.single().status == PipelineVersionStatus.DRAFT
        return PurgeEntityDialog(
            id = id,
            name = record.name,
            leafName = record.name.substringAfterLast('/'),
            runCount = runStats.totalRuns(id),
            exclusiveTemplates = if (soleDraft) exclusiveTemplates.exclusiveIds(workspaceId, id) else emptyList(),
            // #335: the SAME port the service reports `kept_draft_templates` from, so the dialog and the REST answer agree.
            keptTemplates =
                if (soleDraft) {
                    exclusiveTemplates.keptIds(workspaceId, id).map {
                        KeptTemplate(
                            name = it.templateId,
                            parameterSets = it.referencedBy[KEPT_BY_SETS].orEmpty(),
                            visualizations = it.referencedBy[KEPT_BY_VISUALIZATIONS].orEmpty(),
                        )
                    }
                } else {
                    emptyList()
                },
            expected = record.name,
            refusal =
                if (soleDraft) {
                    null
                } else {
                    refusal(
                        "pipeline.version.last_release",
                        "An entity purge requires the only version to be a DRAFT — this pipeline holds " +
                            "${versions.size} version(s) (${versions.joinToString { "v${it.version} ${it.status.name.lowercase()}" }}). " +
                            "Discard is per version; the entity stays.",
                    )
                },
        )
    }

    // ------------------------------------------------------------------ switch

    /** The Switch dialog's one row. */
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
        val record = repository.findByIdAnyStatus(workspaceId, id) ?: throw notFound(id)
        val versions = repository.listVersions(workspaceId, id)
        return SwitchDialog(
            id = id,
            name = record.name,
            currentVersion = record.currentVersion,
            options =
                versions
                    .sortedByDescending { it.version }
                    .map { v ->
                        SwitchOption(
                            version = v.version,
                            status = v.status,
                            isCurrent = record.currentVersion == v.version,
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
            code = PipelineErrorCodes.Validation.PIPELINE_NOT_FOUND,
            message = "No pipeline with id '$id' in this workspace.",
            details = mapOf("pipeline_id" to id.toString()),
        )

    private fun versionNotFound(
        id: UUID,
        version: Int,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Validation.PIPELINE_VERSION_NOT_FOUND,
        message = "Pipeline '$id' has no version $version.",
        details = mapOf("pipeline_id" to id.toString(), "version" to version),
    )

    private companion object {
        /** The keys of a kept template's `referencedBy` — the `template.in_use` keys the REST `kept_draft_templates` uses. */
        const val KEPT_BY_SETS = "referencing_parameter_sets"
        const val KEPT_BY_VISUALIZATIONS = "referencing_visualizations"
    }
}
