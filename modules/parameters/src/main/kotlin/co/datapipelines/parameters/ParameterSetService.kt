package co.datapipelines.parameters

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReleaser
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.UUID

/** An exported set as an import receives it (record §8.3, versioning §9.2) — the id is KEPT (P24). */
data class ParameterSetExport(
    val id: UUID,
    val name: String,
    /** The exact version to land at; null allocates `max + 1` (the version-less path). */
    val version: Int?,
    /** The source's hash — required with [version]; recomputed and compared (the transfer-corruption guard). */
    val bodyHash: String?,
    val releasedAt: Instant?,
    val body: ParameterSetBody,
)

/** What an import did (versioning §9.2's table). */
data class ParameterSetImported(
    val detail: ParameterSetVersionDetail,
    /** True when the set did not exist here before. */
    val created: Boolean,
    /** True when the same version with the same hash was already here — nothing was written. */
    val unchanged: Boolean,
)

/** What a release produced — the released version, and the template pins the cascade released with it (142). */
data class ParameterSetReleased(
    val version: ParameterSetVersion,
    val templatesReleased: List<TemplateRef>,
)

/**
 * The parameter-set aggregate's use cases (record §8.2, §8.3; versioning §3.5's verb table) — the one
 * place every write and every read passes, so the surfaces (lane D) add transport only.
 *
 * **Writes.** Authoring writes (create, the draft write, release, purge, discard, restore) refuse on a
 * promotion receiver with `parameter.authoring.disabled` (versioning §5.5); switch and import are NOT
 * authoring (the receiver's rollout lever and the promotion path). Every body is judged by the
 * [ParameterSetValidator] first; the hash precondition rides the statements (versioning §4.2).
 *
 * **Reads.** The working-version rule (versioning §7.1): an authoring read answers the draft when one
 * exists, else the current version, and says which. Every read takes a [ReadLens] and no default (the
 * `TemplateService` rule): under a narrowing lens — the promoter's — a set outside the admitted names
 * is absent (null, the 404 of a hidden object), and what is visible is RELEASED only: a promoter
 * never sees a draft, as an object or as the pending edits of a visible one.
 *
 * **Scope.** Every method takes the workspace, and the repository filters every statement by it: a set
 * of another workspace does not exist here — no route, tool or permission is needed for that to hold.
 *
 * The metadata work that must be atomic runs inside [transactions] (a `TransactionTemplate` over the
 * metadata transaction manager in production); a release's steps 4–6 run BEFORE it, because the probe
 * opens a customer-datasource connection and `ConnectionLease` refuses one inside a metadata
 * transaction (`datasource.lease_in_transaction`).
 *
 * One façade over the aggregate's verb table (its ports are the constructor); each verb maps each
 * distinct refusal to its own catalogued code, so a verb throws as many as it has preconditions.
 */
@Suppress("TooManyFunctions", "LongParameterList", "ThrowsCount")
class ParameterSetService(
    private val repository: ParameterSetRepository,
    private val validator: ParameterSetValidator,
    private val authoring: AuthoringGuard,
    private val templateStatuses: TemplateVersionStatuses,
    /** The 142 cascade's write, through the template's OWN release path; [TemplateReleaser.NONE] fails loudly if asked. */
    private val templateReleaser: TemplateReleaser = TemplateReleaser.NONE,
    private val transactions: TransactionOperations = DIRECT,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    // ---- writes ---------------------------------------------------------------------------------------

    /** Creates the set as version 1 DRAFT (D55 — a human releases), validated in full (record §4, steps 1–6). */
    fun create(
        workspaceId: UUID,
        document: ParameterSetDocument,
        actor: UUID,
        via: WriteSurface,
    ): ParameterSetVersion {
        requireAuthoring()
        val canonical = validOrThrow(validator.validate(workspaceId, document))
        val id = newId()
        val detail = repository.create(workspaceId, id, canonical.name, canonical.body, actor, CreateLifecycle.DRAFT, via)
        return checkNotNull(repository.findVersion(workspaceId, id, detail.version))
    }

    /**
     * The draft write (versioning §3.6, §5.1/§5.2): the first change after a release opens a draft
     * (copy-on-write, or the no-op when nothing changed); later changes overwrite it. [expectedHash] is
     * the hash of the version the caller based its edit on. Steps 5–6 run only when the body changed.
     *
     * @throws DatapipelinesException `parameter.not_found`, `parameter.authoring.disabled`,
     *   `parameter.version.conflict`; [ParameterSetValidationException] for the body (a different
     *   name is `name_invalid` / `immutable` — a set is never renamed).
     */
    fun write(
        workspaceId: UUID,
        id: UUID,
        document: ParameterSetDocument,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): ParameterSetVersion {
        requireAuthoring()
        val working = repository.findWorking(workspaceId, id) ?: throw notFound(id)
        if (document.name != working.record.name) throw renamed(working.record.name, document.name)
        val canonical = validOrThrow(validator.validate(workspaceId, document, dryRun = false))
        if (repository.computeBodyHash(canonical.body) != working.detail.bodyHash) {
            validator.dryRun(workspaceId, canonical.body).throwIfInvalid()
        }
        // An open draft is overwritten in place (§5.2); a failed write there — a stale hash — falls through to
        // the copy-on-write arm (§5.1), whose guard then decides: both answer null on a stale base.
        val inPlace =
            repository.findDraft(workspaceId, id)?.let {
                repository.writeDraft(workspaceId, id, canonical.body, expectedHash, actor, via)
            }
        val written =
            inPlace ?: repository.createDraft(workspaceId, id, canonical.body, expectedHash, actor, via) ?: throw staleBase(workspaceId, id)
        return checkNotNull(repository.findVersion(workspaceId, id, written.version))
    }

    /**
     * Release (record §8.2, versioning §5.3): the DRAFT at [expectedHash] becomes RELEASED. Every pinned
     * template version must be RELEASED — or, with [releasePinnedTemplates], a DRAFT pin is released
     * with the set in ONE metadata transaction, templates first (142); record §4 steps 4–6 re-run against
     * the pins as they are now, BEFORE that transaction (a draft template edited after the set's save is
     * caught here, with the step's own code).
     */
    @Suppress("ThrowsCount") // each throw is a distinct catalogued refusal
    fun release(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String,
        actor: UUID,
        releasePinnedTemplates: Boolean = false,
    ): ParameterSetReleased {
        requireAuthoring()
        repository.findRecord(workspaceId, id) ?: throw notFound(id)
        val draft = repository.findDraft(workspaceId, id) ?: throw notDraft(id)
        val body = checkNotNull(repository.findVersion(workspaceId, id, draft.version)).body
        val toCascade = draftPinsToRelease(workspaceId, body, releasePinnedTemplates)
        validator.revalidateSources(workspaceId, body).throwIfInvalid()
        val released =
            checkNotNull(
                transactions.execute {
                    val cascaded = toCascade.map { templateReleaser.release(workspaceId, it.id, it.version, actor) }
                    val flipped = repository.releaseDraft(workspaceId, id, expectedHash, actor) ?: throw staleBase(workspaceId, id)
                    flipped to cascaded
                },
            )
        return ParameterSetReleased(checkNotNull(repository.findVersion(workspaceId, id, released.first.version)), released.second)
    }

    /** Purge the DRAFT (versioning §5.4) at [expectedHash]; the sole version takes the set with it. */
    fun purgeDraft(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String,
    ) {
        requireAuthoring()
        repository.findDraft(workspaceId, id) ?: throw notDraft(id)
        val purged = transactions.execute { repository.purgeDraft(workspaceId, id, expectedHash, authoring.developmentPosture) } == true
        if (!purged) throw staleBase(workspaceId, id)
    }

    /** Purge version [version] — drafts only (a release is discarded, never purged: `last_release`). */
    fun purgeVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        expectedHash: String? = null,
    ) {
        requireAuthoring()
        val detail = repository.findVersionDetail(workspaceId, id, version) ?: throw notFound(id, version)
        if (detail.status != PipelineVersionStatus.DRAFT) throw lastRelease(id, version)
        val purged = transactions.execute { repository.purgeDraft(workspaceId, id, expectedHash, authoring.developmentPosture) } == true
        if (!purged) throw staleBase(workspaceId, id)
    }

    /** Purge the whole set — only when its only version is a DRAFT (versioning §3.2, graph rule 3). */
    fun purgeEntity(
        workspaceId: UUID,
        id: UUID,
    ) {
        requireAuthoring()
        repository.findRecord(workspaceId, id) ?: throw notFound(id)
        val versions = repository.listVersions(workspaceId, id)
        versions.firstOrNull { it.status != PipelineVersionStatus.DRAFT }?.let { throw lastRelease(id, it.version) }
        transactions.execute { repository.purgeDraft(workspaceId, id, null, authoring.developmentPosture) }
    }

    /** Discard RELEASED version [version] (versioning §3.1); the pointer falls back when it named it (D60). */
    fun discardVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        actor: UUID,
    ): ParameterSetVersionDetail {
        requireAuthoring()
        val detail = repository.findVersionDetail(workspaceId, id, version) ?: throw notFound(id, version)
        if (detail.status !=
            PipelineVersionStatus.RELEASED
        ) {
            throw wrongStatus(ParameterErrorCodes.VERSION_NOT_RELEASED, id, version, detail.status)
        }
        return transactions.execute { repository.discardVersion(workspaceId, id, version, actor, authoring.developmentPosture) }
            ?: throw staleBase(workspaceId, id)
    }

    /** Restore DISCARDED version [version]; the pointer moves only above-current-or-NULL (D60). */
    fun restoreVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): ParameterSetVersionDetail {
        requireAuthoring()
        val detail = repository.findVersionDetail(workspaceId, id, version) ?: throw notFound(id, version)
        if (detail.status !=
            PipelineVersionStatus.DISCARDED
        ) {
            throw wrongStatus(ParameterErrorCodes.VERSION_NOT_DISCARDED, id, version, detail.status)
        }
        return transactions.execute { repository.restoreVersion(workspaceId, id, version) } ?: throw staleBase(workspaceId, id)
    }

    /** Point the set at [version] (D60) — the receiver's verb, never an authoring write. Answers the new current version. */
    fun switchCurrent(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): Int {
        repository.findRecord(workspaceId, id) ?: throw notFound(id)
        repository.findVersionDetail(workspaceId, id, version) ?: throw notFound(id, version)
        return transactions.execute { repository.switchCurrent(workspaceId, id, version, authoring.developmentPosture) }
            ?: throw DatapipelinesException(
                ParameterErrorCodes.VERSION_NOT_ELIGIBLE,
                "Version $version is not live and eligible for this deployment's pointer.",
                mapOf("id" to id.toString(), "version" to version),
            )
    }

    /**
     * Import (record §8.3; versioning §9.2): the body is validated in full AGAINST THIS DEPLOYMENT — its
     * templates and datasources (steps 4–6 here are the target's); a pin this deployment lacks is
     * `parameter.import.missing_template` (templates are promoted first). It lands RELEASED at the
     * exported version, keeping the exported id; the same version with the same hash is a no-op, any
     * other occupant of the number is `parameter.version.conflict`. The pointer moves only when the set
     * has none (D60). Not an authoring write: a promotion receiver accepts it.
     */
    @Suppress("ThrowsCount", "ReturnCount") // each §9.2 row is its own answer
    fun import(
        workspaceId: UUID,
        export: ParameterSetExport,
        actor: UUID,
    ): ParameterSetImported {
        val validation = validator.validate(workspaceId, ParameterSetDocument(export.name, export.body))
        val canonical = validOrThrow(asImport(validation)).body
        if (export.version != null) {
            val declared = export.bodyHash ?: throw hashRefused("body_hash_missing", null, null)
            val actual = repository.computeBodyHash(canonical)
            if (declared != actual) throw hashRefused("hash_mismatch", declared, actual)
        }
        val existing = repository.findRecord(workspaceId, export.id)
        val byName = repository.findRecordByName(workspaceId, export.name)
        if (existing == null) {
            if (byName != null) throw duplicateName(export.name)
            val detail =
                transactions.execute {
                    repository.importNew(
                        workspaceId,
                        export.id,
                        export.name,
                        canonical,
                        export.version ?: 1,
                        export.releasedAt,
                        actor,
                    )
                }
            return ParameterSetImported(checkNotNull(detail), created = true, unchanged = false)
        }
        if (existing.name != export.name) throw renamed(existing.name, export.name)
        val occupant = export.version?.let { repository.findVersionDetail(workspaceId, export.id, it) }
        if (occupant != null) {
            if (occupant.status == PipelineVersionStatus.RELEASED && occupant.bodyHash == repository.computeBodyHash(canonical)) {
                return ParameterSetImported(occupant, created = false, unchanged = true)
            }
            throw DatapipelinesException(
                ParameterErrorCodes.VERSION_CONFLICT,
                "Version ${export.version} is already taken here by different content or a ${occupant.status.name} row; " +
                    "it is never overwritten.",
                ParameterSetRepository.conflictDetails(occupant) + mapOf("reason" to "version_taken", "version" to export.version),
            )
        }
        val detail =
            transactions.execute { repository.insertReleased(workspaceId, export.id, canonical, export.version, export.releasedAt, actor) }
                ?: throw staleBase(workspaceId, export.id)
        return ParameterSetImported(detail, created = false, unchanged = false)
    }

    // ---- reads (the working-version rule; the lens) ----------------------------------------------------

    /** The working version — the draft, else the current version — or null (absent, discarded, or hidden by the lens). */
    fun findWorking(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): ParameterSetVersion? =
        if (lens.isEverything) {
            repository.findWorking(workspaceId, id)
        } else {
            repository.findCurrent(workspaceId, id)?.takeIf {
                lens.admits(it.record.name) &&
                    it.detail.status == PipelineVersionStatus.RELEASED
            }
        }

    /** One stored version, or null — a version other than RELEASED is null under a narrowing lens. */
    fun findVersion(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
        version: Int,
    ): ParameterSetVersion? = repository.findVersion(workspaceId, id, version)?.takeIf { visible(lens, it.record.name, it.detail.status) }

    /** Every version's detail, newest first — RELEASED ones only under a narrowing lens; empty when hidden. */
    fun listVersions(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): List<ParameterSetVersionDetail> {
        val record = repository.findRecord(workspaceId, id) ?: return emptyList()
        if (!lens.admits(record.name)) return emptyList()
        return repository.listVersions(workspaceId, id).filter { lens.isEverything || it.status == PipelineVersionStatus.RELEASED }
    }

    /** One tree level's sub-folders — counted over the admitted, released sets under a narrowing lens. */
    fun listChildFolders(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String?,
    ): List<ParameterSetFolder> {
        if (lens.isEverything) return repository.listChildFolders(workspaceId, prefix)
        val scope = scope(prefix)
        return admittedNames(workspaceId, lens)
            .filter { it.startsWith(scope) && '/' in it.removePrefix(scope) }
            .groupBy { it.removePrefix(scope).substringBefore('/') }
            .toSortedMap()
            .map { (segment, names) -> ParameterSetFolder(scope + segment, segment, names.size) }
    }

    /** One tree level's sets at their listed version — the current RELEASED one under a narrowing lens. */
    fun listChildSets(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String?,
        offset: Int = 0,
        limit: Int = ParameterSetRepository.DEFAULT_PAGE_LIMIT,
    ): List<ParameterSetVersion> {
        if (lens.isEverything) return repository.listChildSets(workspaceId, prefix, offset, limit)
        val scope = scope(prefix)
        return repository
            .findCurrentVersions(workspaceId)
            .filter { lens.admits(it.name) && it.name.startsWith(scope) && '/' !in it.name.removePrefix(scope) }
            .drop(maxOf(0, offset))
            .take(limit.coerceIn(1, ParameterSetRepository.MAX_PAGE_LIMIT + 1))
            .mapNotNull { repository.findCurrent(workspaceId, it.id) }
    }

    /** The promoter lens's input — every live set's current RELEASED version (versioning §10.2). */
    fun currentVersions(workspaceId: UUID): List<CurrentParameterSetVersion> = repository.findCurrentVersions(workspaceId)

    // ---- rules and refusals ----------------------------------------------------------------------------

    /**
     * The 142 guard and the cascade's worklist: every template pin of [body], distinct, in parameter
     * order. A pin that is neither RELEASED nor a consented DRAFT refuses — the first named at the top
     * level, every non-RELEASED pin under `pins_not_released`.
     */
    private fun draftPinsToRelease(
        workspaceId: UUID,
        body: ParameterSetBody,
        releasePinnedTemplates: Boolean,
    ): List<TemplateRef> {
        val pins =
            body.parameters.mapNotNull { it.source?.template }.distinct().map {
                it to
                    templateStatuses.statusOf(workspaceId, it.id, it.version)
            }
        val notReleased = pins.filter { (_, status) -> status != PipelineVersionStatus.RELEASED }
        val blocking = notReleased.firstOrNull { (_, status) -> !(releasePinnedTemplates && status == PipelineVersionStatus.DRAFT) }
        if (blocking != null) {
            val (ref, status) = blocking
            throw DatapipelinesException(
                ParameterErrorCodes.RELEASE_TEMPLATE_NOT_RELEASED,
                "Template '${ref.id.safeEcho()}' version ${ref.version} is not released; release it first, or release with the templates.",
                mapOf(
                    "template_id" to ref.id.safeEcho(),
                    "template_version" to ref.version,
                    "template_status" to (status?.name ?: "MISSING"),
                    "pins_not_released" to
                        notReleased.map { (pin, pinStatus) ->
                            mapOf(
                                "template_id" to pin.id.safeEcho(),
                                "template_version" to pin.version,
                                "template_status" to (pinStatus?.name ?: "MISSING"),
                            )
                        },
                ),
            )
        }
        return notReleased.map { (ref, _) -> ref }
    }

    /** Record §8.3: on import, a pin this deployment lacks is `import.missing_template` (templates are promoted first). */
    private fun asImport(validation: ParameterSetValidation): ParameterSetValidation {
        if (validation !is ParameterSetValidation.Invalid) return validation
        val failures =
            validation.result.failures.map {
                if (it.code == ParameterErrorCodes.TEMPLATE_NOT_FOUND || it.code == ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND) {
                    it.copy(code = ParameterErrorCodes.IMPORT_MISSING_TEMPLATE)
                } else {
                    it
                }
            }
        return ParameterSetValidation.Invalid(ValidationResult(failures))
    }

    private fun validOrThrow(validation: ParameterSetValidation): ParameterSetDocument =
        when (validation) {
            is ParameterSetValidation.Valid -> validation.document
            is ParameterSetValidation.Invalid -> throw ParameterSetValidationException(validation.result)
        }

    private fun ValidationResult.throwIfInvalid() {
        if (!isValid) throw ParameterSetValidationException(this)
    }

    private fun visible(
        lens: ReadLens,
        name: String,
        status: PipelineVersionStatus,
    ): Boolean = lens.admits(name) && (lens.isEverything || status == PipelineVersionStatus.RELEASED)

    private fun admittedNames(
        workspaceId: UUID,
        lens: ReadLens,
    ): List<String> = repository.findCurrentVersions(workspaceId).map { it.name }.filter { lens.admits(it) }

    private fun requireAuthoring() {
        if (!authoring.developmentPosture) {
            throw DatapipelinesException(
                ParameterErrorCodes.AUTHORING_DISABLED,
                "This deployment has authoring disabled (datapipelines.deployment.authoring-enabled=false) — it is a promotion " +
                    "receiver. Create and edit parameter sets where authoring is enabled and promote; reads, evaluate and import " +
                    "are unaffected.",
                mapOf("capability" to "parameter-sets-authoring", "config_key" to "datapipelines.deployment.authoring-enabled"),
            )
        }
    }

    private fun staleBase(
        workspaceId: UUID,
        id: UUID,
    ): DatapipelinesException {
        val current = repository.findDraft(workspaceId, id) ?: repository.findCurrent(workspaceId, id)?.detail
        return DatapipelinesException(
            ParameterErrorCodes.VERSION_CONFLICT,
            "The parameter set was modified by someone else after you loaded it.",
            ParameterSetRepository.conflictDetails(current),
        )
    }

    private companion object {
        /** No transaction manager: the action runs directly — a directly constructed test's default (PipelineReleaseService's). */
        val DIRECT: TransactionOperations =
            object : TransactionOperations {
                override fun <T : Any?> execute(action: TransactionCallback<T>): T? = action.doInTransaction(SimpleTransactionStatus())
            }

        fun scope(prefix: String?): String = if (prefix.isNullOrEmpty()) "" else "$prefix/"

        fun notFound(
            id: UUID,
            version: Int? = null,
        ) = DatapipelinesException(
            ParameterErrorCodes.NOT_FOUND,
            if (version == null) "Parameter set $id not found." else "Parameter set $id has no version $version.",
            buildMap {
                put("id", id.toString())
                version?.let { put("version", it) }
            },
        )

        fun notDraft(id: UUID) =
            DatapipelinesException(
                ParameterErrorCodes.VERSION_NOT_DRAFT,
                "Parameter set $id has no draft.",
                mapOf("id" to id.toString()),
            )

        fun lastRelease(
            id: UUID,
            version: Int,
        ) = DatapipelinesException(
            ParameterErrorCodes.VERSION_LAST_RELEASE,
            "A released version is discarded, never purged — restore or release something first.",
            mapOf("id" to id.toString(), "version" to version),
        )

        fun wrongStatus(
            code: String,
            id: UUID,
            version: Int,
            status: PipelineVersionStatus,
        ) = DatapipelinesException(
            code,
            "Version $version of parameter set $id is ${status.name}.",
            mapOf(
                "id" to id.toString(),
                "version" to version,
                "status" to status.name,
            ),
        )

        fun renamed(
            stored: String,
            asked: String,
        ) = ParameterSetValidationException(
            ValidationResult(
                listOf(
                    co.datapipelines.pipeline.ValidationFailure(
                        ParameterErrorCodes.NAME_INVALID,
                        "name",
                        "A parameter set is never renamed: this set is '${stored.safeEcho()}', the document names '${asked.safeEcho()}'.",
                        mapOf("reason" to "immutable", "name" to asked.safeEcho()),
                    ),
                ),
            ),
        )

        fun duplicateName(name: String) =
            DatapipelinesException(
                ParameterErrorCodes.DUPLICATE_NAME,
                "A parameter set named '${name.safeEcho()}' already exists here.",
                mapOf("name" to name.safeEcho()),
            )

        fun hashRefused(
            reason: String,
            declared: String?,
            actual: String?,
        ) = DatapipelinesException(
            ParameterErrorCodes.VERSION_CONFLICT,
            "The export's body_hash does not prove its body (${reason.replace('_', ' ')}).",
            mapOf("reason" to reason, "declared_body_hash" to declared?.safeEcho(), "computed_body_hash" to actual),
        )
    }
}
