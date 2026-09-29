package co.datapipelines.visualization

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/** What a dashboard release produced — the released version, and the visualization pins the cascade released with it. */
data class DashboardReleased(
    val version: ArtifactVersion<DashboardBody>,
    val visualizationsReleased: List<ArtifactRef>,
)

/**
 * The dashboard aggregate's use cases (the spec's §3.2, §11.4, §12; D61) — [VisualizationService]'s twin. What is
 * the family's own:
 *
 * - **Release** (D61): the §3.2 rules re-run against the pinned dependencies' CURRENT state (a source no longer
 *   RELEASED or read-only is its validation code); the pinned set release RELEASED; every pinned visualization
 *   RELEASED — or, with `releasePinnedVisualizations`, a DRAFT pin released through [VisualizationService.release]
 *   (which runs ITS gate: until L4 installs the evidence gate, that refuses, so a dashboard pinning a draft
 *   visualization cannot be released yet). Anything else is `release.dependency_not_released` naming every
 *   dependency. Then ONE transaction: the visualization cascade, the dashboard's flip LAST — a stale hash rolls
 *   the cascade back. The visualizations' own template drafts are NOT cascaded by this consent (they need the
 *   visualization's own `releasePinnedTemplates`, the conservative reading of D61's "same consent flag").
 * - **Import** (§12): the rules against THIS deployment; a pin it lacks (`dependency_not_found`) is
 *   `import.missing_dependency` (the promotion order: pipelines, sets and visualizations first).
 */
@Suppress("TooManyFunctions", "LongParameterList") // one façade over the aggregate's verb table; its ports are the constructor
class DashboardService(
    val repository: DashboardRepository,
    private val validator: DashboardValidator,
    private val visualizations: VisualizationService,
    private val sets: ParameterSetFacts,
    authoring: AuthoringGuard,
    transactions: TransactionOperations = ArtifactLifecycle.DIRECT,
    newId: () -> UUID = UUID::randomUUID,
) {
    private val lifecycle = ArtifactLifecycle(repository, authoring, transactions, newId)

    // ---- writes ---------------------------------------------------------------------------------------

    fun create(
        workspaceId: UUID,
        document: DashboardDocument,
        actor: UUID,
        via: WriteSurface,
    ): ArtifactVersion<DashboardBody> {
        lifecycle.requireAuthoring()
        val valid = validated(workspaceId, document)
        return lifecycle.create(workspaceId, valid.name, valid.body, actor, via)
    }

    fun write(
        workspaceId: UUID,
        id: UUID,
        document: DashboardDocument,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): ArtifactVersion<DashboardBody> {
        lifecycle.requireAuthoring()
        val valid = validated(workspaceId, document)
        return lifecycle.write(workspaceId, id, valid.name, valid.body, expectedHash, actor, via)
    }

    /** The §3.2 rules against the dependencies' current state, no write — `POST /{id}/validate`, `dashboards_validate`. */
    fun validate(
        workspaceId: UUID,
        document: DashboardDocument,
    ): ArtifactValidation<DashboardDocument> = validator.validate(workspaceId, document)

    /** Release the DRAFT at [expectedHash] (see the class KDoc for the order of the checks). */
    fun release(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String,
        actor: UUID,
        releasePinnedVisualizations: Boolean = false,
    ): DashboardReleased {
        lifecycle.requireAuthoring()
        val record = repository.findRecord(workspaceId, id) ?: throw lifecycle.notFound(id)
        val draft = repository.findDraft(workspaceId, id) ?: throw lifecycle.notDraft(id)
        val body = checkNotNull(repository.findVersion(workspaceId, id, draft.version)).body
        validated(workspaceId, DashboardDocument(record.name, body))
        val toCascade = dependenciesToRelease(workspaceId, body, releasePinnedVisualizations)
        val released =
            checkNotNull(
                lifecycle.transactions.execute {
                    val cascaded = toCascade.map { (visualizationId, ref) -> cascade(workspaceId, visualizationId, ref, actor) }
                    lifecycle.flipDraft(workspaceId, id, expectedHash, actor) to cascaded
                },
            )
        return DashboardReleased(checkNotNull(repository.findVersion(workspaceId, id, released.first.version)), released.second)
    }

    fun purgeDraft(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String,
    ) = lifecycle.purgeDraft(workspaceId, id, expectedHash)

    fun purgeVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        expectedHash: String? = null,
    ) = lifecycle.purgeVersion(workspaceId, id, version, expectedHash)

    fun purgeEntity(
        workspaceId: UUID,
        id: UUID,
    ) = lifecycle.purgeEntity(workspaceId, id)

    fun discardVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        actor: UUID,
    ): ArtifactVersionDetail = lifecycle.discardVersion(workspaceId, id, version, actor)

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

    /** Import (§12): [validateForImport] then the lifecycle's landing. Not an authoring write. */
    fun import(
        workspaceId: UUID,
        export: ArtifactExport<DashboardBody>,
        actor: UUID,
    ): ArtifactImported =
        lifecycle.importValidated(
            workspaceId,
            export.copy(body = validateForImport(workspaceId, DashboardDocument(export.name, export.body)).body),
            actor,
        )

    /** The rules against THIS deployment with the import lens: a pin it lacks is `import.missing_dependency`. */
    fun validateForImport(
        workspaceId: UUID,
        document: DashboardDocument,
    ): DashboardDocument =
        when (val validation = validator.validate(workspaceId, document)) {
            is ArtifactValidation.Valid -> validation.document

            is ArtifactValidation.Invalid -> throw ArtifactValidationException(
                importLens(validation.result),
                DashboardErrorCodes.BODY_INVALID,
            )
        }

    // ---- reads ----------------------------------------------------------------------------------------

    fun findWorking(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): ArtifactVersion<DashboardBody>? = lifecycle.findWorking(workspaceId, lens, id)

    fun findVersion(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
        version: Int,
    ): ArtifactVersion<DashboardBody>? = lifecycle.findVersion(workspaceId, lens, id, version)

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
    ): List<ArtifactVersion<DashboardBody>> = lifecycle.listChildren(workspaceId, lens, prefix, offset, limit)

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
    ): List<ArtifactVersion<DashboardBody>> = lifecycle.listAll(workspaceId, lens, offset, limit)

    fun countAll(
        workspaceId: UUID,
        lens: ReadLens,
    ): Int = lifecycle.countAll(workspaceId, lens)

    fun currentVersions(workspaceId: UUID): List<CurrentArtifactVersion> = lifecycle.currentVersions(workspaceId)

    // ---- rules ----------------------------------------------------------------------------------------

    private fun validated(
        workspaceId: UUID,
        document: DashboardDocument,
    ): DashboardDocument = validator.validate(workspaceId, document).orThrow(DashboardErrorCodes.BODY_INVALID)

    /**
     * D61's release preconditions beyond the §3.2 rules, and the cascade's worklist: the set RELEASED; every pinned
     * visualization RELEASED or a consented DRAFT. Every blocking dependency is named under
     * `details.dependencies_not_released`, the first at the top level.
     */
    private fun dependenciesToRelease(
        workspaceId: UUID,
        body: DashboardBody,
        consent: Boolean,
    ): List<Pair<UUID, ArtifactRef>> {
        val blocking = mutableListOf<Map<String, Any?>>()
        body.parameterSet?.let { ref ->
            val status = sets.setOf(workspaceId, ref)?.status
            if (status != PipelineVersionStatus.RELEASED) blocking += dependency("parameter_set", ref, status)
        }
        val cascade = mutableListOf<Pair<UUID, ArtifactRef>>()
        body.visualizations.map { it.visualization }.distinct().forEach { ref ->
            val record = visualizations.repository.findRecordByName(workspaceId, ref.name)
            val status = record?.let { visualizations.repository.findVersionDetail(workspaceId, it.id, ref.version)?.status }
            when {
                status == PipelineVersionStatus.RELEASED -> Unit
                consent && status == PipelineVersionStatus.DRAFT -> cascade += checkNotNull(record).id to ref
                else -> blocking += dependency("visualization", ref, status)
            }
        }
        if (blocking.isNotEmpty()) {
            throw DatapipelinesException(
                DashboardErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
                "The dashboard pins ${blocking.size} dependency(ies) that are not released; release them first" +
                    (if (consent) "." else ", or release with the pinned visualizations."),
                blocking.first() + ("dependencies_not_released" to blocking),
            )
        }
        return cascade
    }

    /** One cascaded release — the visualization's OWN release path, gate and all, inside this transaction. */
    private fun cascade(
        workspaceId: UUID,
        visualizationId: UUID,
        ref: ArtifactRef,
        actor: UUID,
    ): ArtifactRef {
        val draft =
            checkNotNull(
                visualizations.repository.findDraft(workspaceId, visualizationId),
            ) { "the pinned DRAFT ${ref.name}@${ref.version} vanished" }
        visualizations.release(workspaceId, visualizationId, draft.bodyHash, actor)
        return ref
    }

    private companion object {
        fun dependency(
            kind: String,
            ref: ArtifactRef,
            status: PipelineVersionStatus?,
        ): Map<String, Any?> =
            mapOf(
                "kind" to kind,
                "name" to ref.name.safeEcho(),
                "version" to ref.version,
                "status" to (status?.name ?: "MISSING"),
            )

        /** The import lens (§12): a pin this deployment lacks is `import.missing_dependency`. */
        fun importLens(result: ValidationResult): ValidationResult =
            ValidationResult(
                result.failures.map {
                    if (it.code ==
                        DashboardErrorCodes.DEPENDENCY_NOT_FOUND
                    ) {
                        it.copy(code = DashboardErrorCodes.IMPORT_MISSING_DEPENDENCY)
                    } else {
                        it
                    }
                },
            )
    }
}
