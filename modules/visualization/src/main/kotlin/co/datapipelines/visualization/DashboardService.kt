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
    /** Module-internal (O3): the surfaces read through the lensed reads below, never through this. */
    internal val repository: DashboardRepository,
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
        validator
            .validate(workspaceId, DashboardDocument(record.name, body), requireReleasedSources = true)
            .orThrow(DashboardErrorCodes.BODY_INVALID)
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
    ): Purged = lifecycle.purgeDraft(workspaceId, id, expectedHash)

    fun purgeVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        expectedHash: String? = null,
    ): Purged = lifecycle.purgeVersion(workspaceId, id, version, expectedHash)

    fun purgeEntity(
        workspaceId: UUID,
        id: UUID,
    ): Purged = lifecycle.purgeEntity(workspaceId, id)

    fun discardVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        actor: UUID,
    ): VersionMoved = lifecycle.discardVersion(workspaceId, id, version, actor)

    /**
     * Restore DISCARDED version [version] — after its DEPENDENCIES are judged against today's state (#320, D7). While a
     * version is DISCARDED it protects nothing (the pin guards count LIVE versions), so the pipeline release, the set
     * version or the visualization version it pins may have been discarded from under it; restoring it blind would
     * bring back a RELEASED version the validator refuses on its own (`dependency_not_found`) and the runtime refuses to
     * serve. Only the dependency failures block a restore — a rule that tightened since (a bound, a name) is not a
     * dangling pin. A wrong-state version falls through to the lifecycle's own refusals, unchanged.
     */
    fun restoreVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): VersionMoved {
        lifecycle.requireAuthoring()
        val stored = repository.findVersion(workspaceId, id, version)
        if (stored != null && stored.detail.status == PipelineVersionStatus.DISCARDED) refuseDanglingPins(workspaceId, id, stored.body)
        return lifecycle.restoreVersion(workspaceId, id, version)
    }

    private fun refuseDanglingPins(
        workspaceId: UUID,
        id: UUID,
        body: DashboardBody,
    ) {
        val record = repository.findRecord(workspaceId, id) ?: return
        val invalid = validator.validate(workspaceId, DashboardDocument(record.name, body)) as? ArtifactValidation.Invalid ?: return
        val dangling = invalid.result.failures.filter { it.code == DashboardErrorCodes.DEPENDENCY_NOT_FOUND }
        if (dangling.isNotEmpty()) throw ArtifactValidationException(ValidationResult(dangling), DashboardErrorCodes.BODY_INVALID)
    }

    fun switchCurrent(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): Switched = lifecycle.switchCurrent(workspaceId, id, version)

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
        when (val validation = validator.validate(workspaceId, document, requireReleasedSources = true)) {
            is ArtifactValidation.Valid -> validation.document

            is ArtifactValidation.Invalid -> throw ArtifactValidationException(
                importLens(validation.result),
                DashboardErrorCodes.BODY_INVALID,
            )
        }

    // ---- reads ----------------------------------------------------------------------------------------

    /** The lifecycle audit rows' pre-read — BODY-FREE, [ArtifactLifecycle.auditIdentity]'s lens rule (#372's B2). */
    fun auditIdentity(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): Pair<String, Int>? = lifecycle.auditIdentity(workspaceId, lens, id)

    /** A named version's audit pre-read — BODY-FREE, [ArtifactLifecycle.auditVersionIdentity]'s lens rule. */
    fun auditVersionIdentity(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
        version: Int,
    ): Pair<String, Int>? = lifecycle.auditVersionIdentity(workspaceId, lens, id, version)

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

    /**
     * The version the RUNTIME serves (#10 L2, spec §18 premise 12): the CURRENT RELEASED version of dashboard [id],
     * through [lens] — nothing else. A draft is never served here (the draft preview names its version through
     * [findServedVersion], §6.3), a dashboard whose pointer names no live release (never released, or every release
     * discarded) is absent, and a hidden one (the promoter lens) is the same absence. `ArtifactLifecycle.findWorking`
     * cannot answer this: under the whole view it prefers the draft.
     */
    fun findServed(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): ArtifactVersion<DashboardBody>? =
        repository
            .findCurrent(
                workspaceId,
                id,
            )?.takeIf { it.detail.status == PipelineVersionStatus.RELEASED && lens.admits(it.record.name) }

    /**
     * One NAMED version the RUNTIME serves (#369, the draft preview's R2): the version [version] of dashboard [id]
     * through [lens] — DRAFT or RELEASED. Absent = [findServed]'s answer, exactly today's behaviour; a value names a
     * version. The lens holds the record's line: under the whole view a DRAFT or RELEASED version is served (the
     * engineer perfects the board before releasing it, R1 — the pins are judged RELEASED-only by the resolver either
     * way), while a narrowing lens never learns of a draft — RELEASED only, the same answer [findVersion] gives, so a
     * hidden or draft version is the family's 404, never a served row. DISCARDED is served to no one under any lens.
     */
    fun findServedVersion(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
        version: Int,
    ): ArtifactVersion<DashboardBody>? =
        repository.findVersion(workspaceId, id, version)?.takeIf {
            lens.admits(it.record.name) &&
                (
                    it.detail.status == PipelineVersionStatus.RELEASED ||
                        (it.detail.status == PipelineVersionStatus.DRAFT && lens.isEverything)
                )
        }

    /** A version by name, through [lens] — `ArtifactLifecycle.findVersionByName`. */
    fun findVersionByName(
        workspaceId: UUID,
        lens: ReadLens,
        name: String,
        version: Int,
    ): ArtifactVersion<DashboardBody>? = lifecycle.findVersionByName(workspaceId, lens, name, version)

    /**
     * The dashboards that pin visualization [visualizationName] at any version, as `name@version` (D30's `used_by`),
     * through [lens]: under the whole view every LIVE (DRAFT or RELEASED) version — the pin guard's own probe; under a
     * narrowing lens only the admitted dashboards' current RELEASED versions, so a promoter never learns of a draft
     * or a hidden dashboard. Under the whole view ONE containment probe — [DashboardRepository.livePinsOf], the
     * statement the V46 GIN index serves (the pin guards and `visualizations_get` read it per probe); under a
     * narrowing lens the current-RELEASED pins read whole and filtered in memory, as [pinnedByAll] does.
     */
    fun pinnedBy(
        workspaceId: UUID,
        lens: ReadLens,
        visualizationName: String,
    ): List<String> =
        if (lens.isEverything) {
            repository.livePinsOf(workspaceId, visualizationName, null)
        } else {
            pinnedByAll(workspaceId, lens, listOf(visualizationName))[visualizationName] ?: emptyList()
        }

    /**
     * [pinnedBy] for a PAGE of names in ONE answer (#331): each queried visualization -> the dashboards pinning it
     * (`name@version`), the same per-name semantics and the same [lens]. Under the whole view the answer is ONE
     * statement; under a narrowing lens also ONE (the current-RELEASED pins read whole, the lens filters the
     * dashboard names in memory — the house shape, never in a template). The `visualizations_list` tool calls
     * this ONCE per page, never per row.
     */
    fun pinnedByAll(
        workspaceId: UUID,
        lens: ReadLens,
        names: Collection<String>,
    ): Map<String, List<String>> {
        if (names.isEmpty()) return emptyMap()
        val found =
            if (lens.isEverything) {
                repository.livePinsOfAll(workspaceId, names)
            } else {
                repository
                    .currentPinsOfAll(workspaceId, names)
                    .filter { lens.admits(it.dashboardName) }
                    .groupBy({ it.pinnedName }, { "${it.dashboardName}@${it.dashboardVersion}" })
                    .mapValues { (_, refs) -> refs.sorted() }
            }
        // Every queried name is answered — an unpinned or wholly hidden visualization is an EMPTY
        // list, the same answer the per-row read gave (never an absent key).
        return names.associateWith { found[it] ?: emptyList() }
    }

    /**
     * The current RELEASED dashboards' pins-and-sources projection (#330), ONE statement: per dashboard the
     * identity the promotion page's rows name plus the pinned-visualization and source-pipeline NAME lists —
     * the only parts of the body the dashboard-arm derivation reads. Never the bodies themselves.
     */
    fun findCurrentPinsAndSources(workspaceId: UUID): List<DashboardCurrentPins> = repository.findCurrentPinsAndSources(workspaceId)

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
