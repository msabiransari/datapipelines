package co.datapipelines.visualization

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReleaser
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/** What a visualization release produced — the released version, and the template pins the cascade released with it. */
data class VisualizationReleased(
    val version: ArtifactVersion<VisualizationBody>,
    val templatesReleased: List<TemplateRef>,
)

/**
 * The visualization aggregate's use cases (the spec's §3.1, §11.4, §12; versioning §3.5's verb table) — the one
 * place every write and every read passes, so the surfaces (L1b) add transport only. Validation is the
 * [VisualizationValidator]'s; the verb table is [ArtifactLifecycle]'s; this adds what is the family's own:
 *
 * - **Release** (D56, the 142 cascade): at least one test case (`release.tests_missing` / `no_cases`); the rules
 *   re-run against the pin as it is NOW; the transform pin RELEASED — or, with [release]'s `releasePinnedTemplates`,
 *   a DRAFT pin released through [TemplateReleaser] (else `release.dependency_not_released` naming every pin); then
 *   ONE transaction: the [ReleaseEvidence] gate (whose default refuses until L4), the cascade, the flip — a stale hash
 *   rolls the cascade back.
 * - **The pin guard** (the design record §4.2): a version a LIVE dashboard version pins is never discarded or purged
 *   (`version.pinned`, `details.pinned_by`); the whole artifact is never purged while any of its versions is pinned.
 * - **Import** (§12): the rules against THIS deployment; a transform pin it lacks is `import.missing_template`
 *   (templates are promoted first); then the lifecycle's landing (id kept, C29).
 */
@Suppress("TooManyFunctions", "LongParameterList", "ThrowsCount") // one façade over the verb table; each throw is its own refusal
class VisualizationService(
    val repository: VisualizationRepository,
    private val validator: VisualizationValidator,
    private val dashboards: DashboardRepository,
    authoring: AuthoringGuard,
    private val templateStatuses: TemplateVersionStatuses,
    /** The cascade's write, through the template's OWN release path; [TemplateReleaser.NONE] fails loudly if asked. */
    private val templateReleaser: TemplateReleaser = TemplateReleaser.NONE,
    /** The D56 evidence gate — [ReleaseEvidence.NOT_INSTALLED] refuses every release until L4 replaces it. */
    private val evidence: ReleaseEvidence = ReleaseEvidence.NOT_INSTALLED,
    transactions: TransactionOperations = ArtifactLifecycle.DIRECT,
    newId: () -> UUID = UUID::randomUUID,
) {
    private val lifecycle = ArtifactLifecycle(repository, authoring, transactions, newId)

    // ---- writes ---------------------------------------------------------------------------------------

    /** Creates the visualization as version 1 DRAFT, validated in full. */
    fun create(
        workspaceId: UUID,
        document: VisualizationDocument,
        actor: UUID,
        via: WriteSurface,
    ): ArtifactVersion<VisualizationBody> {
        lifecycle.requireAuthoring()
        val valid = validated(workspaceId, document)
        return lifecycle.create(workspaceId, valid.name, valid.body, actor, via)
    }

    /** The draft write (copy-on-write, or in place) at [expectedHash]; never a rename. */
    fun write(
        workspaceId: UUID,
        id: UUID,
        document: VisualizationDocument,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): ArtifactVersion<VisualizationBody> {
        lifecycle.requireAuthoring()
        val valid = validated(workspaceId, document)
        return lifecycle.write(workspaceId, id, valid.name, valid.body, expectedHash, actor, via)
    }

    /** Release the DRAFT at [expectedHash] (see the class KDoc for the order of the checks). */
    fun release(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String,
        actor: UUID,
        releasePinnedTemplates: Boolean = false,
    ): VisualizationReleased {
        lifecycle.requireAuthoring()
        val record = repository.findRecord(workspaceId, id) ?: throw lifecycle.notFound(id)
        val draft = repository.findDraft(workspaceId, id) ?: throw lifecycle.notDraft(id)
        val body = checkNotNull(repository.findVersion(workspaceId, id, draft.version)).body
        if (body.tests?.cases.isNullOrEmpty()) throw noCases(record.name)
        validated(workspaceId, VisualizationDocument(record.name, body))
        val toCascade = templatesToRelease(workspaceId, body, releasePinnedTemplates)
        val candidate = ReleaseCandidate(id, record.name, draft.version, draft.bodyHash, body)
        val released =
            checkNotNull(
                lifecycle.transactions.execute {
                    when (val verdict = evidence.verdict(workspaceId, candidate)) {
                        EvidenceVerdict.Pass -> Unit
                        is EvidenceVerdict.Refused -> throw DatapipelinesException(verdict.code, verdict.message, verdict.details)
                    }
                    val cascaded = toCascade.map { templateReleaser.release(workspaceId, it.id, it.version, actor) }
                    lifecycle.flipDraft(workspaceId, id, expectedHash, actor) to cascaded
                },
            )
        return VisualizationReleased(checkNotNull(repository.findVersion(workspaceId, id, released.first.version)), released.second)
    }

    /** Purge the DRAFT at [expectedHash] — refused while a live dashboard version pins it. */
    fun purgeDraft(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String,
    ) {
        repository.findDraft(workspaceId, id)?.let { unpinned(workspaceId, id, it.version) }
        lifecycle.purgeDraft(workspaceId, id, expectedHash)
    }

    /** Purge version [version] — drafts only, and never a pinned one. */
    fun purgeVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        expectedHash: String? = null,
    ) {
        unpinned(workspaceId, id, version)
        lifecycle.purgeVersion(workspaceId, id, version, expectedHash)
    }

    /** Purge the whole visualization — only a draft-only one that no live dashboard version pins at any version. */
    fun purgeEntity(
        workspaceId: UUID,
        id: UUID,
    ) {
        unpinned(workspaceId, id, null)
        lifecycle.purgeEntity(workspaceId, id)
    }

    /** Discard RELEASED version [version] — never a pinned one. */
    fun discardVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        actor: UUID,
    ): ArtifactVersionDetail {
        unpinned(workspaceId, id, version)
        return lifecycle.discardVersion(workspaceId, id, version, actor)
    }

    fun restoreVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): ArtifactVersionDetail = lifecycle.restoreVersion(workspaceId, id, version)

    fun switchCurrent(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): Int = lifecycle.switchCurrent(workspaceId, id, version)

    /** Import (§12, versioning §9.2): [validateForImport] then the lifecycle's landing. Not an authoring write. */
    fun import(
        workspaceId: UUID,
        export: ArtifactExport<VisualizationBody>,
        actor: UUID,
    ): ArtifactImported =
        lifecycle.importValidated(
            workspaceId,
            export.copy(body = validateForImport(workspaceId, VisualizationDocument(export.name, export.body)).body),
            actor,
        )

    /** The rules against THIS deployment with the import lens: a transform pin it lacks is `import.missing_template`. */
    fun validateForImport(
        workspaceId: UUID,
        document: VisualizationDocument,
    ): VisualizationDocument =
        when (val validation = validator.validate(workspaceId, document)) {
            is ArtifactValidation.Valid -> validation.document

            is ArtifactValidation.Invalid -> throw ArtifactValidationException(
                importLens(validation.result),
                VisualizationErrorCodes.BODY_INVALID,
            )
        }

    // ---- reads ----------------------------------------------------------------------------------------

    fun findWorking(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): ArtifactVersion<VisualizationBody>? = lifecycle.findWorking(workspaceId, lens, id)

    fun findVersion(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
        version: Int,
    ): ArtifactVersion<VisualizationBody>? = lifecycle.findVersion(workspaceId, lens, id, version)

    fun listVersions(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): List<ArtifactVersionDetail> = lifecycle.listVersions(workspaceId, lens, id)

    fun listChildFolders(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String?,
    ): List<ArtifactFolder> = lifecycle.listChildFolders(workspaceId, lens, prefix)

    fun listChildren(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String?,
        offset: Int = 0,
        limit: Int = ArtifactRepository.DEFAULT_PAGE_LIMIT,
    ): List<ArtifactVersion<VisualizationBody>> = lifecycle.listChildren(workspaceId, lens, prefix, offset, limit)

    fun countChildren(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String?,
    ): Int = lifecycle.countChildren(workspaceId, lens, prefix)

    fun listAll(
        workspaceId: UUID,
        lens: ReadLens,
        offset: Int = 0,
        limit: Int = ArtifactRepository.DEFAULT_PAGE_LIMIT,
    ): List<ArtifactVersion<VisualizationBody>> = lifecycle.listAll(workspaceId, lens, offset, limit)

    fun countAll(
        workspaceId: UUID,
        lens: ReadLens,
    ): Int = lifecycle.countAll(workspaceId, lens)

    fun currentVersions(workspaceId: UUID): List<CurrentArtifactVersion> = lifecycle.currentVersions(workspaceId)

    // ---- rules ----------------------------------------------------------------------------------------

    private fun validated(
        workspaceId: UUID,
        document: VisualizationDocument,
    ): VisualizationDocument = validator.validate(workspaceId, document).orThrow(VisualizationErrorCodes.BODY_INVALID)

    /** The 142 guard and the cascade's worklist: the transform pin, unless RELEASED; refused unless a consented DRAFT. */
    private fun templatesToRelease(
        workspaceId: UUID,
        body: VisualizationBody,
        consent: Boolean,
    ): List<TemplateRef> {
        val pin = body.transform?.template ?: return emptyList()
        val status = templateStatuses.statusOf(workspaceId, pin.name, pin.version)
        if (status == PipelineVersionStatus.RELEASED) return emptyList()
        if (consent && status == PipelineVersionStatus.DRAFT) return listOf(TemplateRef(pin.name, pin.version))
        val refusal =
            mapOf(
                "template" to pin.name.safeEcho(),
                "template_version" to pin.version,
                "template_status" to (status?.name ?: "MISSING"),
            )
        throw DatapipelinesException(
            VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
            "Template '${pin.name.safeEcho()}' version ${pin.version} is not released; release it first, or release with the templates.",
            refusal + ("pins_not_released" to listOf(refusal)),
        )
    }

    /** The pin guard: refused while a live dashboard version pins [version] (any version when null). */
    private fun unpinned(
        workspaceId: UUID,
        id: UUID,
        version: Int?,
    ) {
        val record = repository.findRecord(workspaceId, id) ?: return
        val pinnedBy = dashboards.livePinsOf(workspaceId, record.name, version)
        if (pinnedBy.isNotEmpty()) {
            throw DatapipelinesException(
                VisualizationErrorCodes.VERSION_PINNED,
                "Visualization '${record.name.safeEcho()}'${version?.let { " version $it" }.orEmpty()} is pinned by $pinnedBy; " +
                    "unpin it from those dashboards first.",
                mapOf("id" to id.toString(), "version" to version, "pinned_by" to pinnedBy),
            )
        }
    }

    private fun noCases(name: String) =
        DatapipelinesException(
            VisualizationErrorCodes.RELEASE_TESTS_MISSING,
            "Visualization '${name.safeEcho()}' declares no test case; a release needs at least one (the spec's §11.1).",
            mapOf("reason" to "no_cases", "visualization" to name.safeEcho()),
        )

    private companion object {
        /** The import lens (§12): a transform pin this deployment lacks is `import.missing_template`. */
        fun importLens(result: ValidationResult): ValidationResult =
            ValidationResult(
                result.failures.map {
                    val reason = it.details["reason"]
                    if (it.code == VisualizationErrorCodes.TRANSFORM_BINDING_INVALID && reason in MISSING_TEMPLATE_REASONS) {
                        it.copy(code = VisualizationErrorCodes.IMPORT_MISSING_TEMPLATE)
                    } else {
                        it
                    }
                },
            )

        val MISSING_TEMPLATE_REASONS = setOf("template_not_found", "template_version_not_found")
    }
}
