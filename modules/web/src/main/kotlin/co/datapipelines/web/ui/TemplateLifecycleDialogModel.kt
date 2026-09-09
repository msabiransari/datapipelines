package co.datapipelines.web.ui

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant
import java.util.UUID

/**
 * The template twin of [PipelineLifecycleDialogModel] (ui-screens §4.3d/§4.6, 102): the same
 * 094 §B discipline — ask before the button, no button on a refused branch, the POST re-runs
 * the guard — over the template lifecycle 101 wired in [TemplateReleaseService].
 *
 * Two template-specific facts. The pin evidence is `template.in_use` (graph rule 1: no
 * `template.version.pinned` exists — the in-use scan IS the refusal), and there is no Switch
 * dialog: templates are pinned by exact version, so there is no served pointer a human would
 * roll back.
 */
class TemplateLifecycleDialogModel(
    private val templates: TemplateRepository,
    private val pipelines: PipelineRepository,
    private val actors: ActorNames,
    private val authoring: AuthoringGuard,
) {
    data class Refusal(
        val code: String,
        val message: String,
    )

    /** A live pipeline version pinning the target — the `template.in_use` evidence, named. */
    data class PinnerView(
        val pipelineName: String,
        val pipelineVersion: Int,
        val nodeId: String,
    )

    // ------------------------------------------------------------------ release

    data class ReleaseDialog(
        val id: String,
        val version: Int,
        val updatedBy: String,
        val updatedAgo: String,
        val updatedAt: Instant?,
        /** `template.version.not_draft`: the dialog opens and says why there is no button. */
        val refusal: Refusal?,
    )

    fun release(
        workspaceId: UUID,
        id: String,
    ): ReleaseDialog {
        val draft = templates.findDraftDetail(workspaceId, id)
        if (draft == null) {
            return ReleaseDialog(
                id = id,
                version = templates.findLatest(workspaceId, id)?.version ?: 0,
                updatedBy = "",
                updatedAgo = "",
                updatedAt = null,
                refusal = Refusal("template.version.not_draft", "This template has no draft — nothing to release."),
            )
        }
        return ReleaseDialog(
            id = id,
            version = draft.version,
            updatedBy = draft.updatedBy?.let { actorName(it) } ?: actorName(draft.createdBy),
            updatedAgo = RelativeTime.since(draft.updatedAt ?: draft.createdAt, Instant.now()),
            updatedAt = draft.updatedAt ?: draft.createdAt,
            refusal = null,
        )
    }

    // ------------------------------------------------------------------ purge draft

    /**
     * The Purge dialog — irreversible, and the sole-draft case takes the TEMPLATE with it
     * (§5.4's template twin), which the dialog says in as many words.
     */
    data class PurgeDialog(
        val id: String,
        val version: Int,
        val soleVersion: Boolean,
        val inUsePipelines: List<String>,
        val expected: String,
        val refusal: Refusal?,
    )

    fun purge(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): PurgeDialog {
        templates.existsId(workspaceId, id) || throw notFound(id)
        val detail = templates.findVersionDetail(workspaceId, id, version) ?: throw versionNotFound(id, version)
        if (detail.status != PipelineVersionStatus.DRAFT) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_LAST_RELEASE,
                message = "Version $version of '$id' is ${detail.status} — only a draft is purged.",
                details = mapOf("template_id" to id, "version" to version, "status" to detail.status.name),
            )
        }
        // The purge's own guard, pre-read: a live pipeline version pinning THIS version is
        // `template.in_use` and the purge would refuse — the dialog shows who, with no button.
        val pinners =
            pipelines
                .findLiveVersionsPinningTemplateVersion(workspaceId, id, version)
                .map { PinnerView(it.pipelineName, it.pipelineVersion, it.nodeId) }
        val sole = templates.listVersions(workspaceId, id).size == 1
        return PurgeDialog(
            id = id,
            version = version,
            soleVersion = sole,
            inUsePipelines = pinners.map { it.pipelineName }.distinct(),
            expected = "v$version",
            refusal =
                pinners.takeIf { it.isNotEmpty() }?.let {
                    Refusal(
                        "template.in_use",
                        "Version $version is pinned by ${it.size} live pipeline version(s): " +
                            it.map { p -> p.pipelineName }.distinct().joinToString(", ") +
                            " — repoint or discard them first.",
                    )
                },
        )
    }

    // ------------------------------------------------------------------ discard

    data class DiscardDialog(
        val id: String,
        val version: Int,
        val isCurrent: Boolean,
        val fallback: String?,
        val pinnerPipelines: List<String>,
    )

    fun discard(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): DiscardDialog {
        templates.existsId(workspaceId, id) || throw notFound(id)
        val detail = templates.findVersionDetail(workspaceId, id, version) ?: throw versionNotFound(id, version)
        if (detail.status != RELEASED) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_NOT_RELEASED,
                message = "Version $version of '$id' is ${detail.status} — discard targets a RELEASED version; a draft is purged.",
                details = mapOf("template_id" to id, "version" to version, "status" to detail.status.name),
            )
        }
        val pinners =
            pipelines
                .findLiveVersionsPinningTemplateVersion(workspaceId, id, version)
                .map { PinnerView(it.pipelineName, it.pipelineVersion, it.nodeId) }
        // The pointer preview: `findLatest` resolves the pointer to a LIVE version row, so a
        // null means "no current release" — the shape whose discard leaves nothing eligible.
        val current = templates.findLatest(workspaceId, id)?.version
        val isCurrent = current == version
        val fallback =
            if (isCurrent) {
                val survivor =
                    templates
                        .listVersions(workspaceId, id)
                        .filter { it.version != version && eligible(it.status) }
                        .maxOfOrNull { it.version }
                survivor
                    ?.let { "v$it becomes the version a pin without a number resolves to." }
                    ?: "nothing eligible remains — the template will have no current version."
            } else {
                null
            }
        return DiscardDialog(id, version, isCurrent, fallback, pinners.map { it.pipelineName }.distinct())
    }

    // ------------------------------------------------------------------ restore

    data class RestoreDialog(
        val id: String,
        val version: Int,
        val currentVersion: Int?,
        val movesPointer: Boolean,
    )

    fun restore(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): RestoreDialog {
        templates.existsId(workspaceId, id) || throw notFound(id)
        val detail = templates.findVersionDetail(workspaceId, id, version) ?: throw versionNotFound(id, version)
        if (detail.status != PipelineVersionStatus.DISCARDED) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_NOT_DISCARDED,
                message = "Version $version of '$id' is ${detail.status} — restore targets a DISCARDED version.",
                details = mapOf("template_id" to id, "version" to version, "status" to detail.status.name),
            )
        }
        val current = templates.findLatest(workspaceId, id)?.version
        return RestoreDialog(
            id = id,
            version = version,
            currentVersion = current,
            movesPointer = current == null || version > current,
        )
    }

    // ------------------------------------------------------------------ purge entity

    /** The template entity purge: sole-draft-only, refused while ANY pin of ANY version exists. */
    data class PurgeEntityDialog(
        val id: String,
        val leafName: String,
        val inUsePipelines: List<String>,
        val expected: String,
        val refusal: Refusal?,
    )

    fun purgeEntity(
        workspaceId: UUID,
        id: String,
    ): PurgeEntityDialog {
        val versions = templates.listVersions(workspaceId, id)
        if (versions.isEmpty()) {
            return PurgeEntityDialog(
                id,
                id.substringAfterLast('/'),
                emptyList(),
                "",
                Refusal("template.not_found", "This template no longer exists."),
            )
        }
        val soleDraft = versions.size == 1 && versions.single().status == PipelineVersionStatus.DRAFT
        // Graph rule 3's template arm: the ANY-version scan the delete guard itself runs.
        val pinners = pipelines.findAnyVersionTemplatePins(workspaceId, id).map { it.pipelineName }.distinct()
        return PurgeEntityDialog(
            id = id,
            leafName = id.substringAfterLast('/'),
            inUsePipelines = pinners,
            expected = id,
            refusal =
                when {
                    pinners.isNotEmpty() -> {
                        Refusal(
                            "template.in_use",
                            "Pinned by ${pinners.size} pipeline(s): ${pinners.joinToString(", ")} — a pinned template is never deleted.",
                        )
                    }

                    !soleDraft -> {
                        Refusal(
                            "template.version.last_release",
                            "A template purge requires the only version to be a DRAFT — this one holds " +
                                "${versions.size} version(s). Discard is per version; the template stays.",
                        )
                    }

                    else -> {
                        null
                    }
                },
        )
    }

    /** §3.4: RELEASED always, DRAFT only under the development posture. */
    private fun eligible(status: PipelineVersionStatus): Boolean =
        PipelineVersionStatus.eligibleForPointer(status, authoring.developmentPosture)

    private fun actorName(id: UUID): String = actors.lookup(listOf(id))[id] ?: ActorNames.fallback(id)

    private fun notFound(id: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.NOT_FOUND,
            message = "No template named '$id' in this workspace.",
            details = mapOf("template_id" to id),
        )

    private fun versionNotFound(
        id: String,
        version: Int,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Template.NOT_FOUND,
        message = "Template '$id' has no version $version.",
        details = mapOf("template_id" to id, "version" to version),
    )
}
