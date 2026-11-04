package co.datapipelines.lake.definitions

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeCatalogException
import co.datapipelines.lake.LakeCatalogSession
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeNames
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.pipeline.PipelineErrorCodes
import com.fasterxml.jackson.databind.json.JsonMapper
import org.apache.iceberg.Table
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.TableIdentifier
import org.apache.iceberg.exceptions.ValidationException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * The apply state machine (dp-lake design record §7, lake.md §3): brings one managed table's Iceberg state to its
 * current released definition, or records why not. Called by the release request (A2), by P4's sweep and by the
 * retry path; never inside a transaction (leases autocommit). Every stamp is conditional on the attempt's operation id,
 * so a stale attempt changes nothing; the leases are released in `finally`, after the session is closed.
 */
class LakeDefinitionApplier(
    private val definitions: LakeDefinitionRepository,
    private val applies: LakeApplyRepository,
    private val leases: LakeApplyLeases,
    private val catalogs: LakeCatalogs,
    private val lakeName: String,
    private val tx: TransactionTemplate,
    private val audit: AuditEventSink,
    private val hooks: LakeApplyHooks = LakeApplyHooks.NONE,
) {
    private val json = JsonMapper.builder().build()

    /**
     * Applies [version], which must be the table's current definition, before [deadline] (the caller's admission
     * deadline, from the catalog clock). [actor] is the audit user; null = the system actor (the sweep).
     */
    fun apply(
        tableId: UUID,
        version: Int,
        deadline: Instant,
        actor: UUID? = null,
    ): LakeApplyOutcome {
        val row = definitions.findTable(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)
        if (row.currentDefinitionVersion != version) return LakeApplyOutcome.NotApplicable(row.applyState, row.currentDefinitionVersion)
        val intent = bodyOf(tableId, version) ?: throw LakeDefinitionErrors.notFound(tableId, version)
        LakeNames.requireName(row.namespace)
        LakeNames.requireName(row.name)
        val operationId = UUID.randomUUID()
        val outcome =
            try {
                applyLeased(Attempt(row, version, intent, operationId, actor), deadline)
            } finally {
                leases.release(operationId)
            }
        LakeApplyLog.ended(OPERATION_APPLY, row, version, operationId, outcome)
        return outcome
    }

    private fun applyLeased(
        attempt: Attempt,
        deadline: Instant,
    ): LakeApplyOutcome {
        val row = attempt.row
        val namespaceExists = applies.icebergNamespaceExists(lakeName, row.namespace)
        val held =
            when (val taken = leases.take(row.namespace, row.name, namespaceExists, attempt.operationId, deadline, OPERATION_APPLY)) {
                is LakeApplyLeases.Taken.Busy -> return LakeApplyOutcome.Busy(taken.reason)
                is LakeApplyLeases.Taken.Ok -> taken.held
            }
        if (applies.markApplying(row.id, attempt.version, attempt.operationId) == 0) {
            val now = definitions.findTable(row.id)
            return LakeApplyOutcome.NotApplicable(now?.applyState, now?.currentDefinitionVersion)
        }
        held.namespace?.let { lease ->
            preCommit(attempt) { catalogs.open(lease).use { it.createNamespace(row.namespace) } }?.let { return it }
        }
        return catalogs.open(held.table).use { session ->
            if (!leases.renew(held.table)) return@use pending(attempt, PipelineErrorCodes.Lake.CATALOG_FENCED)
            run(attempt, session)
        }
    }

    /**
     * Settles an `applying` row whose attempt is gone (lake.md §3): takes the TABLE lease over (a live holder means a
     * live attempt → [LakeApplyOutcome.Busy], row untouched), then reads the revision log for the attempt's operation
     * id in a separate statement: a revision → `applied` (+ projections), none → `pending`.
     */
    fun verifyApplying(
        tableId: UUID,
        deadline: Instant,
        actor: UUID? = null,
    ): LakeApplyOutcome {
        val row = definitions.findTable(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)
        val attemptId = row.applyOperationId
        if (row.applyState != LakeApplyState.APPLYING || attemptId == null) {
            return LakeApplyOutcome.NotApplicable(row.applyState, row.currentDefinitionVersion)
        }
        LakeNames.requireName(row.namespace)
        LakeNames.requireName(row.name)
        val operationId = UUID.randomUUID()
        val outcome =
            try {
                verifyLeased(row, attemptId, operationId, deadline, actor)
            } finally {
                leases.release(operationId)
            }
        LakeApplyLog.ended(OPERATION_VERIFY, row, row.currentDefinitionVersion, attemptId, outcome)
        return outcome
    }

    private fun verifyLeased(
        row: LakeManagedTable,
        attemptId: UUID,
        operationId: UUID,
        deadline: Instant,
        actor: UUID?,
    ): LakeApplyOutcome {
        val lease =
            when (val o = leases.tryTable(row.namespace, row.name, operationId, deadline, OPERATION_VERIFY)) {
                is LeaseOutcome.Acquired -> o.lease
                LeaseOutcome.Busy -> return LakeApplyOutcome.Busy(LakeApplyLeases.REASON_TABLE)
                LeaseOutcome.DeadlinePassed -> return LakeApplyOutcome.Busy(LakeApplyLeases.REASON_DEADLINE)
            }
        if (!applies.revisionOf(row.namespace, row.name, attemptId)) {
            return if (applies.finishPending(row.id, attemptId) == 1) LakeApplyOutcome.Pending(null) else LakeApplyOutcome.Superseded
        }
        val applied =
            catalogs.open(lease).use { session ->
                LakeTableShape.definitionVersionOf(session.loadTable(row.namespace, row.name))
            } ?: error("table ${row.namespace}.${row.name} was committed by $attemptId but names no definition version")
        val body = bodyOf(row.id, applied) ?: throw LakeDefinitionErrors.notFound(row.id, applied)
        return stampApplied(Attempt(row, applied, body, attemptId, actor), committed = true)
    }

    private fun run(
        attempt: Attempt,
        session: LakeCatalogSession,
    ): LakeApplyOutcome {
        val row = attempt.row
        val planner = LakeApplyPlanner { bodyOf(row.id, it) }
        var plan: LakeApplyPlanner.Plan? = null
        preCommit(attempt) {
            val identifier = TableIdentifier.of(Namespace.of(row.namespace), row.name)
            val table: Table? = session.write { if (it.tableExists(identifier)) it.loadTable(identifier) else null }
            plan =
                if (table == null) {
                    planner.forNewTable(attempt.intent) {
                        val schema = LakeTableShape.schemaOf(attempt.intent)
                        session.createTable(
                            row.namespace,
                            row.name,
                            row.id,
                            schema,
                            LakeTableShape.specOf(attempt.intent, schema),
                            attempt.version.toLong(),
                        )
                    }
                } else {
                    planner.forExistingTable(table, attempt.version, attempt.intent)
                }
        }?.let { return it }
        return when (val decided = requireNotNull(plan) { "the planner decided nothing" }) {
            LakeApplyPlanner.Plan.AlreadyApplied -> stampApplied(attempt, committed = false)
            is LakeApplyPlanner.Plan.Refuse -> stampFailed(attempt, decided.code, decided.error)
            is LakeApplyPlanner.Plan.Commit -> commit(attempt, session, decided)
        }
    }

    /**
     * The commit, through the session so its failure is classified. A fence or restoring refusal means nothing was
     * committed → `pending`; an unknown outcome leaves `applying` for [verifyApplying]. Any other exception from the
     * commit step (#811) is logged at ERROR by class and SQLSTATE and ALSO leaves `applying`, returned as
     * [LakeApplyOutcome.Unknown] (`lake.definition.apply_unclassified`): whether the commit landed is the revision
     * log's to say, not this exception's, and a release's landing has already committed (lake.md §3).
     */
    private fun commit(
        attempt: Attempt,
        session: LakeCatalogSession,
        plan: LakeApplyPlanner.Plan.Commit,
    ): LakeApplyOutcome {
        try {
            session.write { plan.run() }
            hooks.afterCommit(attempt.row.id, attempt.operationId)
        } catch (e: LakeCatalogException.OutcomeUnknown) {
            return LakeApplyOutcome.Unknown(e.code)
        } catch (e: LakeCatalogException) {
            return pending(attempt, e.code)
        } catch (
            // The commit may have landed whatever the failure is: never `failed`, never `pending`, never thrown.
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            LakeApplyLog.unclassified(attempt.row, attempt.version, attempt.operationId, e)
            return LakeApplyOutcome.Unknown(PipelineErrorCodes.Lake.DEFINITION_APPLY_UNCLASSIFIED)
        }
        return stampApplied(attempt, committed = true)
    }

    /**
     * Runs a step that commits nothing. Its catalogued refusals end the attempt as `pending`, an Iceberg validation
     * refusal as `failed(breaking_change)`; any other failure puts the row back to `pending` and propagates.
     * Answers null when the step completed.
     */
    private fun preCommit(
        attempt: Attempt,
        step: () -> Unit,
    ): LakeApplyOutcome? =
        try {
            step()
            null
        } catch (e: LakeCatalogException) {
            pending(attempt, e.code)
        } catch (e: ValidationException) {
            stampFailed(
                attempt,
                PipelineErrorCodes.Lake.DEFINITION_BREAKING_CHANGE,
                mapOf("reason" to "iceberg_validation", "detail" to e.message),
            )
        } catch (
            // Nothing was committed whatever the failure is; the row must not stay `applying` for a sweep to settle.
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            applies.finishPending(attempt.row.id, attempt.operationId)
            throw e
        }

    private fun pending(
        attempt: Attempt,
        code: String,
    ): LakeApplyOutcome =
        if (applies.finishPending(attempt.row.id, attempt.operationId) == 1) LakeApplyOutcome.Pending(code) else LakeApplyOutcome.Superseded

    private fun stampApplied(
        attempt: Attempt,
        committed: Boolean,
    ): LakeApplyOutcome =
        requireNotNull(
            tx.execute {
                if (applies.finishApplied(attempt.row.id, attempt.version, attempt.operationId) == 0) {
                    return@execute LakeApplyOutcome.Superseded
                }
                applies.rebuildSemantics(attempt.row.id, attempt.version, LakeSemanticsColumn.of(attempt.intent))
                applies.upsertRegistryRow(lakeName, attempt.row.namespace, attempt.row.name, attempt.row.createdBy)
                audit.log(LakeAuditEvents.APPLIED, userId = attempt.actor, details = attempt.details(code = null))
                LakeApplyOutcome.Applied(attempt.version, committed)
            },
        )

    private fun stampFailed(
        attempt: Attempt,
        code: String,
        error: Map<String, Any?>,
    ): LakeApplyOutcome =
        requireNotNull(
            tx.execute {
                val recorded = mapOf("code" to code) + error
                if (applies.finishFailed(attempt.row.id, attempt.version, attempt.operationId, json.writeValueAsString(recorded)) == 0) {
                    return@execute LakeApplyOutcome.Superseded
                }
                audit.log(LakeAuditEvents.APPLY_FAILED, userId = attempt.actor, details = attempt.details(code))
                LakeApplyOutcome.Failed(attempt.version, code, recorded)
            },
        )

    private fun bodyOf(
        tableId: UUID,
        version: Int,
    ): LakeDefinitionBody? = definitions.findVersion(tableId, version)?.let { LakeDefinitionBodies.read(it.bodyJson) }

    /** One attempt's identity: the row as read, the version and body it brings the table to, its operation id. */
    private class Attempt(
        val row: LakeManagedTable,
        val version: Int,
        val intent: LakeDefinitionBody,
        val operationId: UUID,
        val actor: UUID?,
    ) {
        fun details(code: String?): Map<String, Any?> =
            mapOf(
                "table_id" to row.id.toString(),
                "namespace" to row.namespace,
                "table" to row.name,
                "version" to version,
                "operation_id" to operationId.toString(),
                "code" to code,
            )
    }

    companion object {
        /** `lake_leases.operation` of an apply attempt. */
        const val OPERATION_APPLY = "lake.definition.apply"

        /** `lake_leases.operation` of a verify's takeover. */
        const val OPERATION_VERIFY = "lake.definition.verify"
    }
}
