package co.datapipelines.lake.definitions

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeCatalogException
import co.datapipelines.lake.LakeCatalogSession
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeNames
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.settle.LakeMetadataRead
import co.datapipelines.lake.settle.LakeMetadataReads
import co.datapipelines.lake.settle.SettleBudget
import co.datapipelines.lake.settle.SettleDeferCause
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
 * current released definition, or records why not. [apply] is called by the release request (A2), [verifyApplying] by
 * P4's sweep; never inside a transaction (leases autocommit). Every stamp is conditional on the attempt's operation id,
 * so a stale attempt changes nothing; the leases are released in `finally`, after the session is closed.
 *
 * [apply] never throws after the caller's landing (record §7, erratum 2026-11-12, #822): a failure of any step is
 * RETURNED as the attempt's outcome and logged once at ERROR naming the step that caught it ([LakeApplyLog.Step]) —
 * [LakeApplyOutcome.Pending] where nothing was committed and the row is, or was put back to, `pending`;
 * [LakeApplyOutcome.Unknown] where the row could not be stamped, left for [verifyApplying]. A failure the catalog
 * classifies keeps its own code; any other is `lake.definition.apply_unclassified`. Thrown only:
 * [LakeDefinitionErrors.notFound] for a table or version that does not exist (a caller's error before any attempt),
 * and an [Error], which is never caught.
 */
@Suppress("TooGenericExceptionCaught") // #822: every Exception of an apply step, runtime or not, becomes an outcome
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
    ): LakeApplyOutcome = apply(tableId, version, { deadline }, actor)

    /**
     * [apply], the deadline read through [deadline] once the table and its version are known to exist and the version
     * is current, before the attempt starts (#822, owner ruling 2026-11-13): the release passes its catalog-clock read
     * here, so that read's failure is this attempt's `pending` outcome (step `deadline`), not an exception after the
     * landing. A failure reading the table's own row answers `pending` too, logged without its names.
     */
    @Suppress("ReturnCount") // a failed row read, a missing table and a stale version each end the call before the attempt
    fun apply(
        tableId: UUID,
        version: Int,
        deadline: () -> Instant,
        actor: UUID? = null,
    ): LakeApplyOutcome {
        val operationId = UUID.randomUUID()
        val row =
            try {
                definitions.findTable(tableId)
            } catch (e: Exception) {
                LakeApplyLog.unclassified(tableId, version, operationId, LakeApplyLog.Step.METADATA_STORE, e)
                return LakeApplyOutcome.Pending(UNCLASSIFIED)
            } ?: throw LakeDefinitionErrors.notFound(tableId)
        if (row.currentDefinitionVersion != version) return LakeApplyOutcome.NotApplicable(row.applyState, row.currentDefinitionVersion)
        val outcome = begin(row, version, deadline, operationId, actor)
        LakeApplyLog.ended(OPERATION_APPLY, row, version, operationId, outcome)
        return outcome
    }

    /** Reads the version's body (3a) and the deadline (0) while nothing is held yet, then runs the leased attempt. */
    @Suppress("ReturnCount") // each read's converted failure ends the attempt before a lease is taken
    private fun begin(
        row: LakeManagedTable,
        version: Int,
        deadline: () -> Instant,
        operationId: UUID,
        actor: UUID?,
    ): LakeApplyOutcome {
        val intent =
            try {
                LakeNames.requireName(row.namespace)
                LakeNames.requireName(row.name)
                bodyOf(row.id, version)
            } catch (e: Exception) {
                LakeApplyLog.unclassified(row, version, operationId, LakeApplyLog.Step.METADATA_STORE, e)
                return LakeApplyOutcome.Pending(UNCLASSIFIED)
            } ?: throw LakeDefinitionErrors.notFound(row.id, version)
        val due =
            try {
                deadline()
            } catch (e: Exception) {
                LakeApplyLog.unclassified(row, version, operationId, LakeApplyLog.Step.DEADLINE, e)
                return LakeApplyOutcome.Pending(UNCLASSIFIED)
            }
        val attempt = Attempt(row, version, intent, operationId, actor)
        return try {
            applyLeased(attempt, due)
        } finally {
            releaseLeases(attempt)
        }
    }

    @Suppress("ReturnCount") // each step's converted failure is its own exit (#822)
    private fun applyLeased(
        attempt: Attempt,
        deadline: Instant,
    ): LakeApplyOutcome {
        val row = attempt.row
        val held =
            try {
                val namespaceExists = applies.icebergNamespaceExists(lakeName, row.namespace)
                when (val taken = leases.take(row.namespace, row.name, namespaceExists, attempt.operationId, deadline, OPERATION_APPLY)) {
                    is LakeApplyLeases.Taken.Busy -> return LakeApplyOutcome.Busy(taken.reason)
                    is LakeApplyLeases.Taken.Ok -> taken.held
                }
            } catch (e: Exception) {
                // 3a: the row is not marked yet, so it is `pending` as the landing left it.
                logUnclassified(attempt, LakeApplyLog.Step.METADATA_STORE, e)
                return LakeApplyOutcome.Pending(UNCLASSIFIED)
            }
        val marked =
            try {
                applies.markApplying(row.id, attempt.version, attempt.operationId)
            } catch (e: Exception) {
                return unknown(attempt, e)
            }
        if (marked == 0) {
            val now =
                try {
                    definitions.findTable(row.id)
                } catch (e: Exception) {
                    return unknown(attempt, e)
                }
            return LakeApplyOutcome.NotApplicable(now?.applyState, now?.currentDefinitionVersion)
        }
        held.namespace?.let { lease ->
            inSession(attempt, lease) { session -> preCommit(attempt) { session.createNamespace(row.namespace) } }?.let { return it }
        }
        // The table step always answers an outcome; the elvis only satisfies the type of the shared session helper.
        return inSession(attempt, held.table) { session -> leased(attempt, session) } ?: pending(attempt, UNCLASSIFIED)
    }

    /**
     * Runs [step] in a catalog session under [lease] and closes it. The open (path 2): a refusal the catalog classifies
     * ends the attempt `pending` with its code, any other failure `pending(apply_unclassified)`. The close (3c, 3d) runs
     * whatever [step] answered; its failure is logged, and the outcome [step] answered stands — a step that completed
     * (null, the namespace step) instead ends the attempt `pending` before anything is committed for the table.
     */
    private fun inSession(
        attempt: Attempt,
        lease: LeaseHandle,
        step: (LakeCatalogSession) -> LakeApplyOutcome?,
    ): LakeApplyOutcome? {
        val session =
            try {
                catalogs.open(lease)
            } catch (e: LakeCatalogException) {
                return pending(attempt, e.code)
            } catch (e: Exception) {
                logUnclassified(attempt, LakeApplyLog.Step.SESSION_OPEN, e)
                return pending(attempt, UNCLASSIFIED)
            }
        var closed = false
        val outcome =
            try {
                step(session)
            } finally {
                closed = close(attempt, session)
            }
        return outcome ?: if (closed) null else pending(attempt, UNCLASSIFIED)
    }

    /** Closes [session]; false when the close failed, which is logged (step `session_close`) and dropped. */
    private fun close(
        attempt: Attempt,
        session: LakeCatalogSession,
    ): Boolean =
        try {
            session.close()
            hooks.afterSessionClose(session.lease)
            true
        } catch (e: Exception) {
            logUnclassified(attempt, LakeApplyLog.Step.SESSION_CLOSE, e)
            false
        }

    /** The table step under its lease: renews it before the commit (3b), then [run]. */
    private fun leased(
        attempt: Attempt,
        session: LakeCatalogSession,
    ): LakeApplyOutcome {
        val renewed =
            try {
                leases.renew(session.lease)
            } catch (e: Exception) {
                return unknown(attempt, e)
            }
        if (!renewed) return pending(attempt, PipelineErrorCodes.Lake.CATALOG_FENCED)
        return run(attempt, session)
    }

    /** 3c: a failed release is logged and dropped — every lease of the attempt ends by its own deadline. */
    private fun releaseLeases(attempt: Attempt) {
        try {
            leases.release(attempt.operationId)
        } catch (e: Exception) {
            logUnclassified(attempt, LakeApplyLog.Step.LEASE_RELEASE, e)
        }
    }

    /**
     * Settles an `applying` row whose attempt is gone (lake.md §3): takes the TABLE lease over (a live holder means a
     * live attempt → [LakeApplyOutcome.Busy], row untouched), then reads the revision log for the attempt's operation
     * id in a separate statement: a revision → `applied` (+ projections), none → `pending`.
     *
     * The sweep's tick runs this (record §8b): [budget] is checked before every statement group, and a spent one answers
     * [LakeApplyOutcome.Deferred] with the row untouched. The committed definition version comes from [metadata], off the
     * tick's thread; no catalog session is opened. A read still stuck from an earlier tick defers this one before it takes
     * a lease. A refused takeover issued no token, so it releases nothing: that statement is not in the tick's overrun.
     */
    @Suppress("ReturnCount") // Each spent-budget check exits before its statement group.
    fun verifyApplying(
        tableId: UUID,
        budget: SettleBudget,
        metadata: LakeMetadataReads,
        actor: UUID? = null,
    ): LakeApplyOutcome {
        if (budget.expired()) return LakeApplyOutcome.Deferred(SettleDeferCause.BUDGET)
        val row = definitions.findTable(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)
        val attemptId = row.applyOperationId
        if (row.applyState != LakeApplyState.APPLYING || attemptId == null) {
            return LakeApplyOutcome.NotApplicable(row.applyState, row.currentDefinitionVersion)
        }
        LakeNames.requireName(row.namespace)
        LakeNames.requireName(row.name)
        if (metadata.stuck()) return LakeApplyOutcome.Deferred(SettleDeferCause.STUCK_READ)
        if (budget.expired()) return LakeApplyOutcome.Deferred(SettleDeferCause.BUDGET)
        val operationId = UUID.randomUUID()
        val outcome =
            when (val taken = leases.tryTable(row.namespace, row.name, operationId, budget.deadline, OPERATION_VERIFY)) {
                is LeaseOutcome.Acquired -> {
                    try {
                        verifyLeased(row, attemptId, budget, metadata, actor)
                    } finally {
                        leases.release(operationId)
                    }
                }

                LeaseOutcome.Busy -> {
                    LakeApplyOutcome.Busy(LakeApplyLeases.REASON_TABLE)
                }

                LeaseOutcome.DeadlinePassed -> {
                    LakeApplyOutcome.Busy(LakeApplyLeases.REASON_DEADLINE)
                }
            }
        LakeApplyLog.ended(OPERATION_VERIFY, row, row.currentDefinitionVersion, attemptId, outcome)
        return outcome
    }

    @Suppress("ReturnCount") // Each spent-budget check exits before its statement group; the caller releases the lease.
    private fun verifyLeased(
        row: LakeManagedTable,
        attemptId: UUID,
        budget: SettleBudget,
        metadata: LakeMetadataReads,
        actor: UUID?,
    ): LakeApplyOutcome {
        if (budget.expired()) return LakeApplyOutcome.Deferred(SettleDeferCause.BUDGET)
        if (!applies.revisionOf(row.namespace, row.name, attemptId)) {
            if (budget.expired()) return LakeApplyOutcome.Deferred(SettleDeferCause.BUDGET)
            return if (applies.finishPending(row.id, attemptId) == 1) LakeApplyOutcome.Pending(null) else LakeApplyOutcome.Superseded
        }
        val applied =
            when (val read = metadata.definitionVersion(row.id, row.namespace, row.name, budget)) {
                is LakeMetadataRead.Deferred -> return LakeApplyOutcome.Deferred(read.cause)
                is LakeMetadataRead.Version -> read.version
            } ?: error("table ${row.namespace}.${row.name} was committed by $attemptId but names no definition version")
        if (budget.expired()) return LakeApplyOutcome.Deferred(SettleDeferCause.BUDGET)
        val body = bodyOf(row.id, applied) ?: throw LakeDefinitionErrors.notFound(row.id, applied)
        if (budget.expired()) return LakeApplyOutcome.Deferred(SettleDeferCause.BUDGET)
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
            LakeApplyPlanner.Plan.AlreadyApplied -> stamped(attempt) { stampApplied(attempt, committed = false) }
            is LakeApplyPlanner.Plan.Refuse -> stamped(attempt) { stampFailed(attempt, decided.code, decided.error) }
            is LakeApplyPlanner.Plan.Commit -> commit(attempt, session, decided)
        }
    }

    /**
     * The commit, through the session so its failure is classified. A fence or restoring refusal means nothing was
     * committed → `pending`; an unknown outcome leaves `applying` for [verifyApplying]. Any other exception from the
     * commit step, runtime or not (#811, #822), is logged at ERROR by class and SQLSTATE (step `commit`) and ALSO leaves
     * `applying`, returned as [LakeApplyOutcome.Unknown] (`lake.definition.apply_unclassified`): whether the commit
     * landed is the revision log's to say, not this exception's, and a release's landing has already committed.
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
        } catch (e: Exception) {
            // The commit may have landed whatever the failure is: never `failed`, never `pending`, never thrown.
            logUnclassified(attempt, LakeApplyLog.Step.COMMIT, e)
            return LakeApplyOutcome.Unknown(UNCLASSIFIED)
        }
        return stamped(attempt) { stampApplied(attempt, committed = true) }
    }

    /**
     * Runs a step that commits nothing. Its catalogued refusals end the attempt as `pending`, an Iceberg validation
     * refusal as `failed(breaking_change)`; any other failure, runtime or not, is logged (step `pre_commit`) and puts
     * the row back to `pending` as `apply_unclassified` (#822). Answers null when the step completed.
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
            stamped(attempt) {
                stampFailed(
                    attempt,
                    PipelineErrorCodes.Lake.DEFINITION_BREAKING_CHANGE,
                    mapOf("reason" to "iceberg_validation", "detail" to e.message),
                )
            }
        } catch (e: Exception) {
            // Nothing was committed whatever the failure is; the row must not stay `applying` for a sweep to settle.
            logUnclassified(attempt, LakeApplyLog.Step.PRE_COMMIT, e)
            pending(attempt, UNCLASSIFIED)
        }

    /** Puts the row back to `pending` with [code]; a put-back the metadata store refuses is [unknown] (3b). */
    private fun pending(
        attempt: Attempt,
        code: String,
    ): LakeApplyOutcome =
        stamped(attempt) {
            if (applies.finishPending(attempt.row.id, attempt.operationId) == 1) {
                LakeApplyOutcome.Pending(code)
            } else {
                LakeApplyOutcome.Superseded
            }
        }

    /** A stamp of the apply path; a failure of DP's metadata store there is [unknown] (3b). */
    private fun stamped(
        attempt: Attempt,
        stamp: () -> LakeApplyOutcome,
    ): LakeApplyOutcome =
        try {
            stamp()
        } catch (e: Exception) {
            unknown(attempt, e)
        }

    /**
     * 3b: DP's metadata store failed at or after the `applying` mark, so the row could not be stamped: whether it is
     * `pending` or `applying` is not this attempt's to say. Logged (step `metadata_store`) and left for [verifyApplying].
     */
    private fun unknown(
        attempt: Attempt,
        failure: Exception,
    ): LakeApplyOutcome {
        logUnclassified(attempt, LakeApplyLog.Step.METADATA_STORE, failure)
        return LakeApplyOutcome.Unknown(UNCLASSIFIED)
    }

    private fun logUnclassified(
        attempt: Attempt,
        step: LakeApplyLog.Step,
        failure: Exception,
    ) = LakeApplyLog.unclassified(attempt.row, attempt.version, attempt.operationId, step, failure)

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

        /** The code of a failure no catalog classified, at any step of the apply (#811, #822). */
        private const val UNCLASSIFIED = PipelineErrorCodes.Lake.DEFINITION_APPLY_UNCLASSIFIED
    }
}
