package co.datapipelines.lake.definitions

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * One structured line per apply or verify attempt that reached the table (observability §3.4R): ids, names, the
 * version and a code, never a body, a location or a credential. An attempt that found nothing to do logs nothing.
 */
internal object LakeApplyLog {
    private val log = LoggerFactory.getLogger(LakeDefinitionApplier::class.java)

    private const val LINE = "event={} operation={} table_id={} namespace={} table={} version={} operation_id={} {}"

    fun ended(
        operation: String,
        row: LakeManagedTable,
        version: Int?,
        operationId: UUID,
        outcome: LakeApplyOutcome,
    ) {
        val (warn, event, tail) =
            when (outcome) {
                is LakeApplyOutcome.Applied -> Triple(false, "lake.definition.applied", "committed=${outcome.committed}")
                is LakeApplyOutcome.Failed -> Triple(true, "lake.definition.apply_failed", "code=${outcome.code}")
                is LakeApplyOutcome.Busy -> Triple(false, DEFERRED, "code=${outcome.code} reason=${outcome.reason}")
                is LakeApplyOutcome.Pending -> Triple(false, DEFERRED, "code=${outcome.code} reason=none")
                is LakeApplyOutcome.Unknown -> Triple(true, "lake.definition.apply_unknown", "code=${outcome.code}")
                LakeApplyOutcome.Superseded -> Triple(false, "lake.definition.apply_superseded", "")
                is LakeApplyOutcome.NotApplicable -> return
            }
        if (warn) {
            log.warn(LINE, event, operation, row.id, row.namespace, row.name, version, operationId, tail)
        } else {
            log.info(LINE, event, operation, row.id, row.namespace, row.name, version, operationId, tail)
        }
    }

    private const val DEFERRED = "lake.definition.apply_deferred"
}
