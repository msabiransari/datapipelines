package co.datapipelines.visualization

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.UUID

/** An exported artifact as an import receives it (versioning §9.2) — the id is KEPT (C29). */
data class ArtifactExport<B>(
    val id: UUID,
    val name: String,
    /** The exact version to land at; null allocates `max + 1` (the version-less path). */
    val version: Int?,
    /** The source's hash — required with [version]; recomputed and compared (the transfer-corruption guard). */
    val bodyHash: String?,
    val releasedAt: Instant?,
    val body: B,
)

/** What an import did (versioning §9.2's table). */
data class ArtifactImported(
    val detail: ArtifactVersionDetail,
    /** True when the artifact did not exist here before. */
    val created: Boolean,
    /** True when the same version with the same hash was already here — nothing was written. */
    val unchanged: Boolean,
)

/**
 * The versioning §3.5 verb table for one family — `ParameterSetService`'s shape, ONE implementation over both
 * families. It takes CANONICAL bodies: the family services ([VisualizationService], [DashboardService]) run their
 * validator first and own release (each family's release has its own gate and cascade).
 *
 * **Writes.** Authoring writes (create, the draft write, purge, discard, restore) refuse on a promotion receiver
 * with the family's `authoring.disabled` (versioning §5.5); switch and import are NOT authoring. The hash
 * precondition rides the statements (versioning §4.2).
 *
 * **Reads.** The working-version rule (§7.1): the draft when one exists, else the current version. Every read takes
 * a [ReadLens] and no default: under a narrowing lens an artifact outside the admitted names is absent (null — the
 * 404 of a hidden object) and what is visible is RELEASED only — a draft's number, hash or editor never reaches a
 * promoter (the 194d-merge F2 rule).
 *
 * The metadata work that must be atomic runs inside [transactions] (a `TransactionTemplate` over the metadata
 * transaction manager in production; [DIRECT] in a directly constructed test).
 */
@Suppress("TooManyFunctions", "ThrowsCount") // one method per verb and read; each throw is a distinct catalogued refusal
class ArtifactLifecycle<B : Any>(
    val repository: ArtifactRepository<B>,
    private val authoring: AuthoringGuard,
    val transactions: TransactionOperations = DIRECT,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    private val kind = repository.kind
    private val codes = kind.codes

    // ---- writes ---------------------------------------------------------------------------------------

    /** Creates the artifact as version 1 DRAFT (D55 — a human releases). [body] is canonical. */
    fun create(
        workspaceId: UUID,
        name: String,
        body: B,
        actor: UUID,
        via: WriteSurface,
    ): ArtifactVersion<B> {
        requireAuthoring()
        val id = newId()
        val detail = repository.create(workspaceId, id, name, body, actor, CreateLifecycle.DRAFT, via)
        return checkNotNull(repository.findVersion(workspaceId, id, detail.version))
    }

    /**
     * The draft write (versioning §3.6, §5.1/§5.2): the first change after a release opens a draft (copy-on-write, or
     * the no-op when nothing changed); later changes overwrite it. [expectedHash] is the hash of the version the
     * caller based its edit on. A write naming another artifact than the one it addresses is `name_invalid` /
     * `immutable` — an artifact is never renamed.
     */
    @Suppress("LongParameterList") // the target, the document, the precondition and two stamps
    fun write(
        workspaceId: UUID,
        id: UUID,
        name: String,
        body: B,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): ArtifactVersion<B> {
        requireAuthoring()
        val working = repository.findWorking(workspaceId, id) ?: throw notFound(id)
        if (name != working.record.name) throw renamed(working.record.name, name)
        // An open draft is overwritten in place (§5.2); a failed write there — a stale hash — falls through to the
        // copy-on-write arm (§5.1), whose guard then decides: both answer null on a stale base.
        val inPlace = repository.findDraft(workspaceId, id)?.let { repository.writeDraft(workspaceId, id, body, expectedHash, actor, via) }
        val written = inPlace ?: repository.createDraft(workspaceId, id, body, expectedHash, actor, via) ?: throw staleBase(workspaceId, id)
        return checkNotNull(repository.findVersion(workspaceId, id, written.version))
    }

    /** The DRAFT at [expectedHash] flips to RELEASED — the family's release body, run inside ITS transaction. */
    fun flipDraft(
        workspaceId: UUID,
        id: UUID,
        expectedHash: String,
        actor: UUID,
    ): ArtifactVersionDetail = repository.releaseDraft(workspaceId, id, expectedHash, actor) ?: throw staleBase(workspaceId, id)

    /** Purge the DRAFT (versioning §5.4) at [expectedHash]; the sole version takes the artifact with it. */
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

    /** Purge the whole artifact — only when its only version is a DRAFT (versioning §3.2, graph rule 3). */
    fun purgeEntity(
        workspaceId: UUID,
        id: UUID,
    ) {
        requireAuthoring()
        repository.findRecord(workspaceId, id) ?: throw notFound(id)
        repository
            .listVersions(
                workspaceId,
                id,
            ).firstOrNull { it.status != PipelineVersionStatus.DRAFT }
            ?.let { throw lastRelease(id, it.version) }
        transactions.execute { repository.purgeDraft(workspaceId, id, null, authoring.developmentPosture) }
    }

    /** Discard RELEASED version [version] (versioning §3.1); the pointer falls back when it named it (D60). */
    fun discardVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
        actor: UUID,
    ): ArtifactVersionDetail {
        requireAuthoring()
        val detail = repository.findVersionDetail(workspaceId, id, version) ?: throw notFound(id, version)
        if (detail.status != PipelineVersionStatus.RELEASED) throw wrongStatus(codes.notReleased, id, version, detail.status)
        return transactions.execute { repository.discardVersion(workspaceId, id, version, actor, authoring.developmentPosture) }
            ?: throw staleBase(workspaceId, id)
    }

    /** Restore DISCARDED version [version]; the pointer moves only above-current-or-NULL (D60). */
    fun restoreVersion(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): ArtifactVersionDetail {
        requireAuthoring()
        val detail = repository.findVersionDetail(workspaceId, id, version) ?: throw notFound(id, version)
        if (detail.status != PipelineVersionStatus.DISCARDED) throw wrongStatus(codes.notDiscarded, id, version, detail.status)
        return transactions.execute { repository.restoreVersion(workspaceId, id, version) } ?: throw staleBase(workspaceId, id)
    }

    /** Point the artifact at [version] (D60) — the receiver's verb, never an authoring write. */
    fun switchCurrent(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): Int {
        repository.findRecord(workspaceId, id) ?: throw notFound(id)
        repository.findVersionDetail(workspaceId, id, version) ?: throw notFound(id, version)
        return transactions.execute { repository.switchCurrent(workspaceId, id, version, authoring.developmentPosture) }
            ?: throw DatapipelinesException(
                codes.notEligible,
                "Version $version is not live and eligible for this deployment's pointer.",
                mapOf("id" to id.toString(), "version" to version),
            )
    }

    /**
     * Import's landing half (versioning §9.2) over a body already proven by the family's validator: the hash
     * check, the id/rename/version-taken rules and the insert. It lands RELEASED at the exported version, keeping the
     * exported id; the same version with the same hash is a no-op; any other occupant of the number is
     * `version.conflict` / `version_taken`; a name held by another artifact is `name_taken`; an id held elsewhere is
     * `import.id_taken` (C29). The pointer moves only when there is none (D60). Not an authoring write.
     */
    @Suppress("ThrowsCount", "ReturnCount") // each §9.2 row is its own refusal/answer
    fun importValidated(
        workspaceId: UUID,
        export: ArtifactExport<B>,
        actor: UUID,
    ): ArtifactImported {
        if (export.version != null) {
            val declared = export.bodyHash ?: throw hashRefused("body_hash_missing", null, null)
            val actual = repository.computeBodyHash(export.body)
            if (declared != actual) throw hashRefused("hash_mismatch", declared, actual)
        }
        val existing = repository.findRecord(workspaceId, export.id)
        if (existing == null) {
            repository.findRecordByName(workspaceId, export.name)?.let { throw nameTaken(export.name) }
            val detail =
                transactions.execute {
                    repository.importNew(workspaceId, export.id, export.name, export.body, export.version ?: 1, export.releasedAt, actor)
                }
            return ArtifactImported(checkNotNull(detail), created = true, unchanged = false)
        }
        if (existing.name != export.name) throw renamed(existing.name, export.name)
        val occupant = export.version?.let { repository.findVersionDetail(workspaceId, export.id, it) }
        if (occupant != null) {
            if (occupant.status == PipelineVersionStatus.RELEASED && occupant.bodyHash == repository.computeBodyHash(export.body)) {
                return ArtifactImported(occupant, created = false, unchanged = true)
            }
            throw DatapipelinesException(
                codes.versionConflict,
                "Version ${export.version} is already taken here by different content or a ${occupant.status.name} row; " +
                    "it is never overwritten.",
                repository.conflictDetails(occupant) + mapOf("reason" to "version_taken", "version" to export.version),
            )
        }
        val detail =
            transactions.execute {
                repository.insertReleased(
                    workspaceId,
                    export.id,
                    export.body,
                    export.version,
                    export.releasedAt,
                    actor,
                )
            }
                ?: throw staleBase(workspaceId, export.id)
        return ArtifactImported(detail, created = false, unchanged = false)
    }

    // ---- reads (the working-version rule; the lens) ----------------------------------------------------

    /** The working version — the draft, else the current version — or null (absent, discarded, or hidden by the lens). */
    fun findWorking(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): ArtifactVersion<B>? =
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
    ): ArtifactVersion<B>? = repository.findVersion(workspaceId, id, version)?.takeIf { visible(lens, it.record.name, it.detail.status) }

    /** Every version's detail, newest first — RELEASED ones only under a narrowing lens; empty when hidden. */
    fun listVersions(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): List<ArtifactVersionDetail> {
        val record = repository.findRecord(workspaceId, id) ?: return emptyList()
        if (!lens.admits(record.name)) return emptyList()
        return repository.listVersions(workspaceId, id).filter { lens.isEverything || it.status == PipelineVersionStatus.RELEASED }
    }

    /** One tree level's sub-folders — counted over the admitted, released artifacts under a narrowing lens. */
    fun listChildFolders(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String?,
    ): List<ArtifactFolder> {
        if (lens.isEverything) return repository.listChildFolders(workspaceId, prefix)
        val scope = scope(prefix)
        return admitted(workspaceId, lens)
            .map { it.name }
            .filter { it.startsWith(scope) && '/' in it.removePrefix(scope) }
            .groupBy { it.removePrefix(scope).substringBefore('/') }
            .toSortedMap()
            .map { (segment, names) -> ArtifactFolder(scope + segment, segment, names.size) }
    }

    /** One tree level's artifacts at their listed version — the current RELEASED one under a narrowing lens. */
    fun listChildren(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String?,
        offset: Int = 0,
        limit: Int = ArtifactRepository.DEFAULT_PAGE_LIMIT,
    ): List<ArtifactVersion<B>> {
        if (lens.isEverything) return repository.listChildren(workspaceId, prefix, offset, limit)
        return page(
            admitted(workspaceId, lens).filter {
                atLevel(it.name, prefix)
            },
            offset,
            limit,
        ).mapNotNull { repository.findCurrent(workspaceId, it.id) }
    }

    /** The truthful total of [listChildren] over the WHOLE level — lens-true, with the listing's own predicate (#300). */
    fun countChildren(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String?,
    ): Int =
        if (lens.isEverything) {
            repository.countChildren(workspaceId, prefix)
        } else {
            admitted(workspaceId, lens).count {
                atLevel(it.name, prefix)
            }
        }

    /** The FLAT listing (#312's shape): every artifact the lens admits, at its listed version. */
    fun listAll(
        workspaceId: UUID,
        lens: ReadLens,
        offset: Int = 0,
        limit: Int = ArtifactRepository.DEFAULT_PAGE_LIMIT,
    ): List<ArtifactVersion<B>> {
        if (lens.isEverything) return repository.listAll(workspaceId, offset, limit)
        return page(admitted(workspaceId, lens), offset, limit).mapNotNull { repository.findCurrent(workspaceId, it.id) }
    }

    /** The truthful total of [listAll] — lens-true. */
    fun countAll(
        workspaceId: UUID,
        lens: ReadLens,
    ): Int = if (lens.isEverything) repository.countAll(workspaceId) else admitted(workspaceId, lens).size

    /** The promoter lens's input — every live artifact's current RELEASED version (versioning §10.2). */
    fun currentVersions(workspaceId: UUID): List<CurrentArtifactVersion> = repository.findCurrentVersions(workspaceId)

    // ---- rules and refusals ----------------------------------------------------------------------------

    /** The family's `authoring.disabled` unless this deployment authors (versioning §5.5). */
    fun requireAuthoring() {
        if (!authoring.developmentPosture) {
            throw DatapipelinesException(
                codes.authoringDisabled,
                "This deployment has authoring disabled (datapipelines.deployment.authoring-enabled=false) — it is a promotion " +
                    "receiver. Create and edit ${kind.noun}s where authoring is enabled and promote; reads and import are unaffected.",
                mapOf("capability" to "${kind.index}-authoring", "config_key" to AuthoringGuard.CONFIG_KEY),
            )
        }
    }

    fun staleBase(
        workspaceId: UUID,
        id: UUID,
    ): DatapipelinesException =
        DatapipelinesException(
            codes.versionConflict,
            "The ${kind.noun} was modified by someone else after you loaded it.",
            repository.conflictDetails(repository.findDraft(workspaceId, id) ?: repository.findCurrent(workspaceId, id)?.detail),
        )

    fun notFound(
        id: UUID,
        version: Int? = null,
    ) = DatapipelinesException(
        codes.notFound,
        if (version ==
            null
        ) {
            "${kind.noun.replaceFirstChar { it.uppercase() }} $id not found."
        } else {
            "The ${kind.noun} $id has no version $version."
        },
        buildMap {
            put("id", id.toString())
            version?.let { put("version", it) }
        },
    )

    fun notDraft(id: UUID) = DatapipelinesException(codes.notDraft, "The ${kind.noun} $id has no draft.", mapOf("id" to id.toString()))

    private fun lastRelease(
        id: UUID,
        version: Int,
    ) = DatapipelinesException(
        codes.lastRelease,
        "A released version is discarded, never purged — restore or release something first.",
        mapOf("id" to id.toString(), "version" to version),
    )

    private fun wrongStatus(
        code: String,
        id: UUID,
        version: Int,
        status: PipelineVersionStatus,
    ) = DatapipelinesException(
        code,
        "Version $version of ${kind.noun} $id is ${status.name}.",
        mapOf(
            "id" to id.toString(),
            "version" to version,
            "status" to status.name,
        ),
    )

    private fun renamed(
        stored: String,
        asked: String,
    ) = ArtifactValidationException(
        ValidationResult(
            listOf(
                ValidationFailure(
                    codes.nameInvalid,
                    "name",
                    "A ${kind.noun} is never renamed: this one is '${stored.safeEcho()}', the document names '${asked.safeEcho()}'.",
                    mapOf("reason" to "immutable", "name" to asked.safeEcho()),
                ),
            ),
        ),
        codes.nameInvalid,
    )

    private fun nameTaken(name: String) =
        DatapipelinesException(
            codes.nameTaken,
            "A ${kind.noun} named '${name.safeEcho()}' already exists here.",
            mapOf("name" to name.safeEcho()),
        )

    private fun hashRefused(
        reason: String,
        declared: String?,
        actual: String?,
    ) = DatapipelinesException(
        codes.versionConflict,
        "The export's body_hash does not prove its body (${reason.replace('_', ' ')}).",
        mapOf("reason" to reason, "declared_body_hash" to declared?.safeEcho(), "computed_body_hash" to actual),
    )

    private fun visible(
        lens: ReadLens,
        name: String,
        status: PipelineVersionStatus,
    ): Boolean = lens.admits(name) && (lens.isEverything || status == PipelineVersionStatus.RELEASED)

    private fun admitted(
        workspaceId: UUID,
        lens: ReadLens,
    ): List<CurrentArtifactVersion> = repository.findCurrentVersions(workspaceId).filter { lens.admits(it.name) }

    private fun atLevel(
        name: String,
        prefix: String?,
    ): Boolean {
        val scope = scope(prefix)
        return name.startsWith(scope) && '/' !in name.removePrefix(scope)
    }

    private fun <T> page(
        items: List<T>,
        offset: Int,
        limit: Int,
    ): List<T> = items.drop(maxOf(0, offset)).take(limit.coerceIn(1, ArtifactRepository.MAX_PAGE_LIMIT + 1))

    companion object {
        /** No transaction manager: the action runs directly — a directly constructed test's default (the mould's). */
        val DIRECT: TransactionOperations =
            object : TransactionOperations {
                override fun <T : Any?> execute(action: TransactionCallback<T>): T? = action.doInTransaction(SimpleTransactionStatus())
            }

        private fun scope(prefix: String?): String = if (prefix.isNullOrEmpty()) "" else "$prefix/"
    }
}
