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
     * An exception from the apply's commit step that is none of the catalog's classified refusals (#811), at the
     * point it is caught and before the attempt's ending line: its class and SQLSTATE only, never its message or
     * stack (observability §3.4G).
     */
    fun unclassified(
        row: LakeManagedTable,
        version: Int,
        operationId: UUID,
        failure: Throwable,
    ) = Line(LakeDefinitionApplier.OPERATION_APPLY, row, version, operationId).error(
        "event=lake.definition.apply_unclassified$FIELDS",
        "cause=${FailureShape.cause(failure)} sql_state=${FailureShape.sqlState(failure)}",
    )

    /** The common six fields of one attempt. */
    private class Line(
        val operation: String,
        val row: LakeManagedTable,
        val version: Int?,
        val operationId: UUID,
    ) {
        fun info(
            format: String,
            tail: String,
        ) = log.info(format, operation, row.id, row.namespace, row.name, version, operationId, tail)

        fun warn(
            format: String,
            tail: String,
        ) = log.warn(format, operation, row.id, row.namespace, row.name, version, operationId, tail)

        fun error(
            format: String,
            tail: String,
        ) = log.error(format, operation, row.id, row.namespace, row.name, version, operationId, tail)
    }
}
