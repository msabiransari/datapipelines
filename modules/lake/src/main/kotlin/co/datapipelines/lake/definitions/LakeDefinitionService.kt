package co.datapipelines.lake.definitions

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.definitions.LakeDefinitionErrors.invalidBody
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/** What a release did: the version that landed RELEASED, and what its apply in the same request reached. */
data class LakeReleaseResult(
    val version: Int,
    val apply: LakeApplyOutcome,
)

/**
 * The managed lake definitions' lifecycle (lake.md §2): create, write, release, discard and purge-draft of a table's
 * definition versions. Every write is an authoring write, refused on a receiver with
 * `lake.definition.authoring_disabled` (promotion imports land RELEASED versions without this service, P9).
 * Authorization is the surfaces' (P8): every write takes the acting user and the surface it came through (`actor`,
 * `via`); release, discard and purge-draft each write their audit row inside their own transaction (#814).
 *
 * Each lifecycle step is one transaction; the release's apply runs AFTER its transaction commits (leases autocommit,
 * and a failed apply never rolls the landing back, dp-lake design record §7).
 */
@Suppress("ThrowsCount") // each lifecycle refusal is its own catalogued code (lake.md §2)
class LakeDefinitionService(
    private val definitions: LakeDefinitionRepository,
    private val applier: LakeDefinitionApplier,
    private val leases: LakeLeases,
    private val jobTimeout: Duration,
    private val policyJson: String,
    private val tx: TransactionTemplate,
    private val audit: AuditEventSink,
    private val authoring: AuthoringGuard,
) {
    /** The (namespace, table) lookup P4 admits appends by: the managed table of that name, or null. */
    fun findTable(
        namespace: String,
        table: String,
    ): LakeManagedTable? = definitions.findTable(namespace, table)

    /** Creates managed table `<namespace>.<table>` (and its namespace row when absent) with draft version 1. */
    fun createDraft(
        namespace: String,
        table: String,
        bodyJson: String,
        actor: UUID,
        via: WriteSurface,
    ): LakeDefinitionVersion {
        requireAuthoring()
        requireName(namespace, "namespace")
        requireName(table, "table")
        val body = LakeDefinitionBodies.parse(bodyJson, base = null, lockBasis = null)
        return inTransaction {
            val namespaceId = definitions.namespaceId(namespace, actor)
            val tableId = UUID.randomUUID()
            if (!definitions.insertTable(tableId, namespaceId, table, policyJson, actor)) {
                throw LakeDefinitionErrors.nameTaken(namespace, table)
            }
            definitions.insertDraft(tableId, FIRST_VERSION, LakeDefinitionBodies.toJson(body), actor, via)
                ?: throw LakeDefinitionErrors.versionConflict(tableId)
        }
    }

    /**
     * Writes the table's draft at [expectedHash] (`If-Match`). With a draft, it is overwritten when its hash is still
     * [expectedHash]; without one, a new draft opens from the current released version when THAT version's hash is
     * [expectedHash] (copy-on-write, versioning §3.3). A stale hash is `lake.definition.version_conflict`. The body is
     * validated against the current released definition (its new fields take the next ids), and its sort columns
     * against the table's first version ever released (the lock, [LakeDefinitionRules.sortRules]).
     */
    fun writeDraft(
        tableId: UUID,
        bodyJson: String,
        expectedHash: String,
        actor: UUID,
        via: WriteSurface,
    ): LakeDefinitionVersion {
        requireAuthoring()
        return inTransaction {
            val table = definitions.findTableForUpdate(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)
            val released = table.currentDefinitionVersion?.let { definitions.findVersion(tableId, it) }
            val body =
                LakeDefinitionBodies.parse(
                    bodyJson,
                    base = released?.let { LakeDefinitionBodies.read(it.bodyJson) },
                    lockBasis = lockBasis(tableId),
                )
            val canonical = LakeDefinitionBodies.toJson(body)
            val draft = definitions.findDraft(tableId)
            when {
                draft != null -> {
                    definitions.overwriteDraft(tableId, canonical, expectedHash, actor, via)
                        ?: throw LakeDefinitionErrors.versionConflict(tableId)
                }

                released == null -> {
                    throw LakeDefinitionErrors.notFound(tableId)
                }

                released.bodyHash != expectedHash -> {
                    throw LakeDefinitionErrors.versionConflict(tableId)
                }

                else -> {
                    definitions.insertDraft(tableId, definitions.maxVersion(tableId) + 1, canonical, actor, via)
                        ?: throw LakeDefinitionErrors.versionConflict(tableId)
                }
            }
        }
    }

    /**
     * DRAFT [version] → RELEASED (at [expectedHash] when given), the table's current definition → [version], apply
     * state → `pending`, `lake.definition.released` audited — one transaction; then the apply, in this request, with
     * the deadline `now + lake.admission.job-timeout` read from the catalog clock. The landing stands whatever the
     * apply reaches, and the apply's outcome is RETURNED in [LakeReleaseResult.apply]: applied, a recorded refusal, a
     * busy table, a catalog refusal, or an unknown commit outcome, which includes an exception the catalog does not
     * classify from the commit step (`Unknown`, `lake.definition.apply_unclassified`, #811).
     *
     * Before the draft is flipped, its stored body is judged by the sort-column rule, and only it, against the table's
     * first version ever released (none for a first release): a draft stored before the rule existed, or one whose
     * table was released since it was written, is refused `lake.definition.invalid_body` like an authoring write, with
     * nothing released and no audit row ([LakeDefinitionRules.sortRules]). The other rules held when it was stored.
     *
     * Nothing is thrown after the landing committed (#822, record §7): a failure at any step of the apply — the
     * deadline read itself, which the applier performs, the catalog session, DP's metadata store — is returned as
     * `Pending` or `Unknown` (`lake.definition.apply_unclassified` unless the catalog classified it). Thrown after the
     * landing only: `lake.definition.not_found`, for a table or version that does not exist when the apply starts.
     */
    fun release(
        tableId: UUID,
        version: Int,
        actor: UUID,
        via: WriteSurface,
        expectedHash: String? = null,
    ): LakeReleaseResult {
        requireAuthoring()
        inTransaction {
            val table = definitions.findTableForUpdate(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)
            definitions
                .findVersion(tableId, version)
                ?.takeIf { it.status == PipelineVersionStatus.DRAFT && (expectedHash == null || it.bodyHash == expectedHash) }
                ?.let { draft -> LakeDefinitionRules.sortRules(LakeDefinitionBodies.read(draft.bodyJson), lockBasis(tableId)) }
            if (definitions.releaseDraft(tableId, version, expectedHash, actor) == 0) {
                val row = definitions.findVersion(tableId, version) ?: throw LakeDefinitionErrors.notFound(tableId, version)
                if (row.status == PipelineVersionStatus.DRAFT) throw LakeDefinitionErrors.versionConflict(tableId)
                throw LakeDefinitionErrors.notDraft(tableId, version, row.status.name)
            }
            check(definitions.pointAtRelease(tableId, version) == 1) { "released version $version is not above the current definition" }
            audit.log(
                LakeAuditEvents.RELEASED,
                userId = actor,
                details =
                    mapOf(
                        "table_id" to tableId.toString(),
                        "namespace" to table.namespace,
                        "table" to table.name,
                        "version" to version,
                        "via" to via.wire,
                    ),
            )
        }
        return LakeReleaseResult(version, applier.apply(tableId, version, { leases.catalogNowPlus(jobTimeout) }, actor))
    }

    /**
     * RELEASED [version] → DISCARDED; the current definition falls back to the highest version still RELEASED. The
     * version this environment has applied is refused (`lake.definition.applied_version`). Discard is also refused
     * while any apply is in flight (`lake.definition.table_busy`): a newer release may already have moved the pointer
     * beyond the version that attempt is still applying.
     *
     * `lake.definition.discarded` is audited in the same transaction, after the pointer's recompute, with the pointer
     * it left (`current_version`, null when no released version is left): an audit failure rolls the discard back
     * (record §12, #814). A refused discard writes no audit row.
     */
    fun discard(
        tableId: UUID,
        version: Int,
        actor: UUID,
        via: WriteSurface,
    ) {
        requireAuthoring()
        inTransaction {
            val table = definitions.findTableForUpdate(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)
            if (table.appliedVersionHere == version) throw LakeDefinitionErrors.appliedVersion(tableId, version)
            if (table.applyState == LakeApplyState.APPLYING) {
                throw LakeDefinitionErrors.tableBusy(tableId, LakeApplyState.APPLYING.wire)
            }
            if (definitions.discardRelease(tableId, version, actor) == 0) {
                val row = definitions.findVersion(tableId, version) ?: throw LakeDefinitionErrors.notFound(tableId, version)
                throw LakeDefinitionErrors.notReleased(tableId, version, row.status.name)
            }
            definitions.recomputeCurrent(tableId)
            val current = definitions.findTable(tableId)?.currentDefinitionVersion
            audit.log(
                LakeAuditEvents.DISCARDED,
                userId = actor,
                details =
                    mapOf(
                        "table_id" to tableId.toString(),
                        "namespace" to table.namespace,
                        "table" to table.name,
                        "version" to version,
                        "via" to via.wire,
                        "current_version" to current,
                    ),
            )
        }
    }

    /**
     * Deletes the table's draft (at [expectedHash] when given). A table left with no version at all (it was never
     * released) is deleted with it, and its namespace row when that was its last table.
     *
     * `lake.definition.draft_purged` is audited in the same transaction, after the last delete, with the draft's
     * version and whether the table row went with it (`table_deleted`): an audit failure rolls the purge back (record
     * §12, #814). A refused purge writes no audit row.
     */
    fun purgeDraft(
        tableId: UUID,
        actor: UUID,
        via: WriteSurface,
        expectedHash: String? = null,
    ) {
        requireAuthoring()
        inTransaction {
            val table = definitions.findTableForUpdate(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)
            val draft = definitions.findDraft(tableId) ?: throw LakeDefinitionErrors.notDraft(tableId, null, null)
            if (definitions.deleteDraft(tableId, expectedHash) == 0) throw LakeDefinitionErrors.versionConflict(tableId)
            val tableDeleted = definitions.deleteTableIfVersionless(tableId) == 1
            if (tableDeleted) definitions.deleteNamespaceIfEmpty(table.namespaceId)
            audit.log(
                LakeAuditEvents.DRAFT_PURGED,
                userId = actor,
                details =
                    mapOf(
                        "table_id" to tableId.toString(),
                        "namespace" to table.namespace,
                        "table" to table.name,
                        "version" to draft.version,
                        "via" to via.wire,
                        "table_deleted" to tableDeleted,
                    ),
            )
        }
    }

    /** The body of the table's first version ever released (the sort columns' lock), or null when there is none. */
    private fun lockBasis(tableId: UUID): LakeDefinitionBody? =
        definitions.findFirstRelease(tableId)?.let { LakeDefinitionBodies.read(it.bodyJson) }

    private fun requireAuthoring() {
        if (!authoring.developmentPosture) throw LakeDefinitionErrors.authoringDisabled()
    }

    private fun requireName(
        name: String,
        path: String,
    ) {
        if (!NAME.matches(name)) throw invalidBody(path, "grammar", "a lake $path name is [a-z0-9_]{1,63}")
    }

    private fun <T : Any> inTransaction(block: () -> T): T = requireNotNull(tx.execute { block() }) { "the transaction answered nothing" }

    private companion object {
        const val FIRST_VERSION = 1
        val NAME = Regex("^[a-z0-9_]{1,63}$")
    }
}
