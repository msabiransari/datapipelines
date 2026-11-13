package co.datapipelines.lake.definitions

import co.datapipelines.persistence.FailureShape
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * One structured line per apply or verify attempt that reached the table (observability §3.4R): ids, names, the
 * version and a code, never a body, a location or a credential. An attempt that found nothing to do logs nothing.
 * Every event name is a literal in its own format string, so `ObservabilityEventCatalogParityTest` reads it against §3.4R.
 */
internal object LakeApplyLog {
    private val log = LoggerFactory.getLogger(LakeDefinitionApplier::class.java)

    /** The fields every line carries after its event name, then the outcome's own tail. */
    private const val FIELDS = " operation={} table_id={} namespace={} table={} version={} operation_id={} {}"

    fun ended(
        operation: String,
        row: LakeManagedTable,
        version: Int?,
        operationId: UUID,
        outcome: LakeApplyOutcome,
    ) {
        val line = Line(operation, row, version, operationId)
        when (outcome) {
            is LakeApplyOutcome.Applied -> {
                line.info("event=lake.definition.applied$FIELDS", "committed=${outcome.committed}")
            }

            is LakeApplyOutcome.Failed -> {
                line.warn("event=lake.definition.apply_failed$FIELDS", "code=${outcome.code}")
            }

            is LakeApplyOutcome.Busy -> {
                line.info("event=lake.definition.apply_deferred$FIELDS", "code=${outcome.code} reason=${outcome.reason}")
            }

            is LakeApplyOutcome.Pending -> {
                line.info("event=lake.definition.apply_deferred$FIELDS", "code=${outcome.code} reason=none")
            }

            is LakeApplyOutcome.Unknown -> {
                line.warn("event=lake.definition.apply_unknown$FIELDS", "code=${outcome.code}")
            }

            LakeApplyOutcome.Superseded -> {
                line.info("event=lake.definition.apply_superseded$FIELDS", "")
            }

            is LakeApplyOutcome.NotApplicable -> {
                // Nothing to do was found: no line (§3.4R).
            }

            is LakeApplyOutcome.Deferred -> {
                // No verdict was reached: the sweep's line counts it by cause (§3.4S).
            }
        }
    }

    /**
     * The step of an apply attempt that caught an unclassified failure (#822): the `step` field of
     * `lake.definition.apply_unclassified`. It names where the failure was caught, so a commit's failure is never
     * logged as `metadata_store`.
     */
    enum class Step(
        val wire: String,
    ) {
        /** The release's deadline read on the catalog clock (path 0). */
        DEADLINE("deadline"),

        /** A step before the commit that commits nothing (path 1): loading the table, planning, creating the namespace. */
        PRE_COMMIT("pre_commit"),

        /** Opening a catalog session (path 2). */
        SESSION_OPEN("session_open"),

        /** DP's own metadata store: the rows, leases, the `applying` mark, the stamps and their audit (paths 3a, 3b). */
        METADATA_STORE("metadata_store"),

        /** Releasing the attempt's leases (path 3c). */
        LEASE_RELEASE("lease_release"),

        /** Closing a catalog session (paths 3c, 3d). */
        SESSION_CLOSE("session_close"),

        /** The commit step, a non-runtime exception included (#811, path 4). */
        COMMIT("commit"),
    }

    /**
     * A failure of an apply step that is none of the catalog's classified refusals (#811, #822), at the point it is
     * caught and before the attempt's ending line: the [step] and the failure's class and SQLSTATE only, never its
     * message or stack (observability §3.4G).
     */
    fun unclassified(
        row: LakeManagedTable,
        version: Int,
        operationId: UUID,
        step: Step,
        failure: Throwable,
    ) = Line(LakeDefinitionApplier.OPERATION_APPLY, row, version, operationId).error(
        "event=lake.definition.apply_unclassified$FIELDS",
        tail(step, failure),
    )

    /** [unclassified] when the table's own row could not be read: `namespace` and `table` are null, nothing else differs. */
    fun unclassified(
        tableId: UUID,
        version: Int,
        operationId: UUID,
        step: Step,
        failure: Throwable,
    ) = Line(LakeDefinitionApplier.OPERATION_APPLY, tableId, null, null, version, operationId).error(
        "event=lake.definition.apply_unclassified$FIELDS",
        tail(step, failure),
    )

    private fun tail(
        step: Step,
        failure: Throwable,
    ) = "step=${step.wire} cause=${FailureShape.cause(failure)} sql_state=${FailureShape.sqlState(failure)}"

    /** The common six fields of one attempt. */
    private class Line(
        val operation: String,
        val tableId: UUID,
        val namespace: String?,
        val name: String?,
        val version: Int?,
        val operationId: UUID,
    ) {
        constructor(operation: String, row: LakeManagedTable, version: Int?, operationId: UUID) :
            this(operation, row.id, row.namespace, row.name, version, operationId)

        fun info(
            format: String,
            tail: String,
        ) = log.info(format, operation, tableId, namespace, name, version, operationId, tail)

        fun warn(
            format: String,
            tail: String,
        ) = log.warn(format, operation, tableId, namespace, name, version, operationId, tail)

        fun error(
            format: String,
            tail: String,
        ) = log.error(format, operation, tableId, namespace, name, version, operationId, tail)
    }
}
