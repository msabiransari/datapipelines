package co.datapipelines.web.templates

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Template release, purge, discard and restore (versioning §5.3/§5.4/§3.1, mirrored by §6;
 * the 101 verbs added here) — the human half of the template lifecycle.
 *
 * Release re-runs the template's own save-time validation on the draft content (§5.3
 * precondition 3's mirror: nothing is released the validator would refuse) and carries the
 * hash precondition on the flip itself. There is no pin-check precondition here — §6's
 * "templates lock first" rule is enforced at PIPELINE release, not template release.
 *
 * The draft verb is PURGE (a hard delete — nothing references a `template_versions` row by
 * FK); DISCARD is the RELEASED verb with `template.in_use` as its pin guard; RESTORE brings
 * a discarded version back. All are authoring writes (§3.1) except the manual switch.
 */
open class TemplateReleaseService(
    private val templates: TemplateRepository,
    private val validator: TemplateValidator,
    private val authoring: AuthoringGuard,
    private val pipelines: PipelineRepository,
) {
    /** What a release produced: the released version detail and the stored template at it. */
    data class Released(
        val detail: TemplateVersionDetail,
        val template: co.datapipelines.templates.Template,
    )

    /**
     * Releases the template's DRAFT at [expectedHash].
     *
     * @throws DatapipelinesException `template.authoring.disabled` (§5.5),
     *   `template.version.not_draft` or `template.version.conflict`
     *   (stale hash); `TemplateValidationException` when the draft content no longer validates.
     */
    @Suppress("ThrowsCount") // each throw is a distinct catalogued refusal the caller distinguishes
    open fun release(
        workspaceId: UUID,
        id: String,
        expectedHash: String,
        actor: UUID,
    ): Released {
        // §5.5: release is an authoring action — a promotion receiver refuses it.
        authoring.requireTemplateAuthoring()

        val draft =
            templates.findDraftDetail(workspaceId, id)
                ?: throw notDraft(id)

        // Re-validate the draft content exactly as a save would — release is the final
        // save-time gate; it must not launder a draft the validator would refuse.
        val stored = templates.findVersion(workspaceId, id, draft.version) ?: throw notDraft(id)
        val redraft =
            TemplateDraft(
                id = stored.id,
                engine = stored.engine,
                type = stored.type,
                dialect = stored.dialect,
                displayName = stored.displayName,
                description = stored.description,
                imports = stored.imports,
                body = stored.body,
                isLibrary = stored.isLibrary,
            )
        validator.validateOrThrow(redraft, workspaceId)

        val released =
            templates.releaseDraft(workspaceId, id, expectedHash, actor)
                ?: throw conflictAfterGuardFailure(workspaceId, id)
        val template = templates.findVersion(workspaceId, id, released.version) ?: throw notDraft(id)
        return Released(released, template)
    }

    /**
     * Purges the template's DRAFT at [expectedHash] (versioning §5.4, 101 — a hard delete;
     * the sole-draft case takes the entity row with it, D57's twin).
     *
     * @throws DatapipelinesException `template.authoring.disabled` (§5.5),
     *   `template.version.not_draft` or `template.version.conflict`.
     */
    @Transactional("metadataTransactionManager")
    open fun purge(
        workspaceId: UUID,
        id: String,
        expectedHash: String,
    ) {
        // §5.5: purging authored content is authoring — a promotion receiver refuses it.
        authoring.requireTemplateAuthoring()

        templates.findDraftDetail(workspaceId, id) ?: throw notDraft(id)
        if (!templates.purgeDraft(workspaceId, id, expectedHash, authoring.developmentPosture)) {
            throw conflictAfterGuardFailure(workspaceId, id)
        }
    }

    /**
     * §3.1 (101) — discard RELEASED version [version] of template [id]: flip to DISCARDED,
     * pointer per D60. The pin guard rides the repository statement (graph rule 1,
     * `template.in_use`), and this service-side arm names the pinners in `details`.
     *
     * @throws DatapipelinesException `template.authoring.disabled`, `template.not_found`,
     *   `template.version.not_released`, `template.in_use`.
     */
    open fun discardVersion(
        workspaceId: UUID,
        id: String,
        version: Int,
        actor: UUID,
    ): TemplateVersionDetail {
        authoring.requireTemplateAuthoring()
        requireTemplate(workspaceId, id)
        val detail = findVersionOr404(workspaceId, id, version)
        if (detail.status != PipelineVersionStatus.RELEASED) throw notReleased(id, version, detail.status)
        refuseIfPinned(workspaceId, id, version)

        return templates.discardVersion(workspaceId, id, version, actor, authoring.developmentPosture)
            ?: throw pinnedOrConcurrent(workspaceId, id, version)
    }

    /** §3.1 (101) — restore DISCARDED version [version]; the pointer moves only above-current-or-NULL. */
    open fun restoreVersion(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): TemplateVersionDetail {
        authoring.requireTemplateAuthoring()
        requireTemplate(workspaceId, id)
        val detail = findVersionOr404(workspaceId, id, version)
        if (detail.status != PipelineVersionStatus.DISCARDED) throw notDiscarded(id, version, detail.status)
        return templates.restoreVersion(workspaceId, id, version)
            ?: throw templateNotFound(id, version)
    }

    /**
     * §3.1 (101) — purge DRAFT version [version] (drafts only; the sole-draft case takes the
     * entity with it). A non-draft target is `template.version.last_release`.
     */
    @Transactional("metadataTransactionManager")
    open fun purgeVersion(
        workspaceId: UUID,
        id: String,
        version: Int,
    ) {
        authoring.requireTemplateAuthoring()
        requireTemplate(workspaceId, id)
        val detail = findVersionOr404(workspaceId, id, version)
        if (detail.status != PipelineVersionStatus.DRAFT) throw lastRelease(id, version, detail.status)
        if (!templates.purgeDraft(workspaceId, id, null, authoring.developmentPosture)) {
            throw templateNotFound(id, version)
        }
    }

    /**
     * §3.2 (101) — the template entity purge: only when the ONLY version is a DRAFT and no
     * live pipeline version pins ANY version of the template (`template.in_use` otherwise;
     * a non-draft version present is `template.version.last_release`).
     */
    @Transactional("metadataTransactionManager")
    open fun purgeEntity(
        workspaceId: UUID,
        id: String,
    ) {
        authoring.requireTemplateAuthoring()
        requireTemplate(workspaceId, id)
        val versions = templates.listVersions(workspaceId, id)
        if (versions.size != 1 || versions[0].status != PipelineVersionStatus.DRAFT) {
            throw lastReleaseEntity(id, versions.size)
        }
        // Graph rule 3: no inbound edges — any pin of ANY version, from any live pipeline version.
        val pinners = pipelines.findAnyVersionTemplatePins(workspaceId, id)
        if (pinners.isNotEmpty()) throw inUse(id, pinners.map { it.pipelineName })
        templates.deleteTemplateRow(workspaceId, id)
    }

    /** §3.4 (101) — the manual switch; NOT an authoring verb (the promotion receiver's lever). */
    open fun switchCurrent(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): Int {
        requireTemplate(workspaceId, id)
        val detail = findVersionOr404(workspaceId, id, version)
        if (!PipelineVersionStatus.eligibleForPointer(detail.status, authoring.developmentPosture)) {
            throw notEligible(id, version, detail.status)
        }
        return templates.switchCurrent(workspaceId, id, version, authoring.developmentPosture)
            ?: throw notEligible(id, version, detail.status)
    }

    private fun requireTemplate(
        workspaceId: UUID,
        id: String,
    ) {
        if (!templates.existsId(workspaceId, id)) throw templateNotFound(id, null)
    }

    private fun findVersionOr404(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): TemplateVersionDetail = templates.findVersionDetail(workspaceId, id, version) ?: throw templateNotFound(id, version)

    private fun refuseIfPinned(
        workspaceId: UUID,
        id: String,
        version: Int,
    ) {
        val pinners = pipelines.findLiveVersionsPinningTemplateVersion(workspaceId, id, version)
        if (pinners.isNotEmpty()) throw inUse(id, pinners.map { it.pipelineName })
    }

    private fun pinnedOrConcurrent(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): DatapipelinesException {
        val pinners = pipelines.findLiveVersionsPinningTemplateVersion(workspaceId, id, version)
        if (pinners.isNotEmpty()) return inUse(id, pinners.map { it.pipelineName })
        val current = findVersionOr404(workspaceId, id, version)
        return if (current.status == PipelineVersionStatus.RELEASED) {
            DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_CONFLICT,
                message = "Template was modified by someone else after you loaded it.",
                details = mapOf("current_status" to current.status.name),
            )
        } else {
            notReleased(id, version, current.status)
        }
    }

    private fun inUse(
        id: String,
        pinnedBy: List<String>,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.IN_USE,
            message =
                "Version of template '$id' is pinned by ${pinnedBy.size} live pipeline version(s): " +
                    pinnedBy.distinct().joinToString(", ") + "; discard or repoint them first.",
            details = mapOf("template_id" to id, "pinned_by" to pinnedBy.distinct()),
        )

    private fun lastRelease(
        id: String,
        version: Int,
        status: PipelineVersionStatus,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_LAST_RELEASE,
            message =
                "Version $version of template '$id' is ${status.name} and is never purged — " +
                    "discard is per version, the entity stays; restore or release something first.",
            details = mapOf("template_id" to id, "version" to version, "status" to status.name),
        )

    private fun lastReleaseEntity(
        id: String,
        versionCount: Int,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_LAST_RELEASE,
            message =
                "Template '$id' cannot be purged: an entity purge requires the only version to be " +
                    "a DRAFT (this one has $versionCount).",
            details = mapOf("template_id" to id, "version_count" to versionCount),
        )

    private fun notReleased(
        id: String,
        version: Int,
        status: PipelineVersionStatus,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_NOT_RELEASED,
            message =
                "Version $version of template '$id' is ${status.name}; discard targets a RELEASED " +
                    "version — a draft is purged, not discarded.",
            details = mapOf("template_id" to id, "version" to version, "status" to status.name),
        )

    private fun notDiscarded(
        id: String,
        version: Int,
        status: PipelineVersionStatus,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_NOT_DISCARDED,
            message = "Version $version of template '$id' is ${status.name}; restore targets a DISCARDED version.",
            details = mapOf("template_id" to id, "version" to version, "status" to status.name),
        )

    private fun notEligible(
        id: String,
        version: Int,
        status: PipelineVersionStatus,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_NOT_ELIGIBLE,
            message =
                "Version $version of template '$id' is ${status.name} and cannot be switched to — " +
                    "the pointer names a live version eligible for this deployment's posture.",
            details = mapOf("template_id" to id, "version" to version, "status" to status.name),
        )

    private fun templateNotFound(
        id: String,
        version: Int?,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.NOT_FOUND,
            message =
                if (version == null) {
                    "Template '$id' does not exist."
                } else {
                    "Template '$id' has no version $version."
                },
            details =
                buildMap<String, Any> {
                    put("template_id", id)
                    if (version != null) put("version", version)
                },
        )

    private fun conflictAfterGuardFailure(
        workspaceId: UUID,
        id: String,
    ): DatapipelinesException {
        val current = templates.findDraftDetail(workspaceId, id) ?: throw notDraft(id)
        return DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_CONFLICT,
            message = "Template was modified by someone else after you loaded it.",
            details =
                mapOf(
                    "current_body_hash" to current.bodyHash,
                    "current_status" to current.status.name,
                    "updated_by" to (current.updatedBy?.toString() ?: ""),
                    "updated_at" to (current.updatedAt?.toString() ?: ""),
                ),
        )
    }

    private fun notDraft(id: String): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.VERSION_NOT_DRAFT,
            message = "Template '$id' has no draft to release or discard.",
            details = mapOf("template_id" to id),
        )
}
