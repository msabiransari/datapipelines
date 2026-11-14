package co.datapipelines.lake.maintenance.remove

import co.datapipelines.persistence.FailureShape
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The remover's log lines (observability §3.4T): the table id, the run id and counts — never a key list, a presigned URL
 * or a credential; a failure in §3.4G's safe shape (class and SQLSTATE only). The one key ever logged is the first
 * unexpected `dp/` key, relative to the table location (D8).
 */
internal object RemovalEvents {
    private val log = LoggerFactory.getLogger(LakeRemover::class.java)

    fun refused(
        target: RemovalTarget,
        runId: UUID,
        reason: RefusalReason,
        marker: MarkerFate,
        skew: SkewInterval?,
    ) = log.warn(
        "event=lake.removal.refused table_id={} run_id={} reason={} code={} marker={} skew_lower_s={} skew_upper_s={}",
        target.tableId,
        runId,
        reason.wire,
        reason.code ?: NONE,
        markerWord(marker),
        skew?.lower?.seconds ?: NONE,
        skew?.upper?.seconds ?: NONE,
    )

    fun ended(
        target: RemovalTarget,
        runId: UUID,
        purge: Boolean,
        result: RemovalResult.Completed,
    ) = log.info(
        "event=lake.removal.ended table_id={} run_id={} pass={} listed={} reached={} deleted={} bytes={} left={}" +
            " stopped={} marker={} purge={}",
        target.tableId,
        runId,
        if (purge) "purge" else "orphans",
        result.listed,
        result.reachedKeys,
        result.deleted,
        result.bytesDeleted,
        result.left.entries.joinToString(",") { "${it.key.wire}=${it.value}" },
        result.stopped?.wire ?: NONE,
        markerWord(result.marker),
        result.purge?.wire ?: NONE,
    )

    fun failed(
        target: RemovalTarget,
        runId: UUID,
        cause: Throwable,
    ) = log.warn(
        "event=lake.removal.failed table_id={} run_id={} cause={} sql_state={}",
        target.tableId,
        runId,
        FailureShape.cause(cause),
        FailureShape.sqlState(cause),
    )

    fun batchFailed(
        target: RemovalTarget,
        runId: UUID,
        keys: Int,
        outcome: String,
        cause: Throwable?,
    ) = log.warn(
        "event=lake.removal.batch_failed table_id={} run_id={} keys={} outcome={} cause={} sql_state={}",
        target.tableId,
        runId,
        keys,
        outcome,
        cause?.let(FailureShape::cause) ?: NONE,
        cause?.let(FailureShape::sqlState) ?: NONE,
    )

    fun markerLeft(
        target: RemovalTarget,
        runId: UUID,
        reason: MarkerLeftReason,
    ) = log.warn("event=lake.removal.marker_left table_id={} run_id={} reason={}", target.tableId, runId, reason.wire)

    fun unexpectedDpKeys(
        target: RemovalTarget,
        keys: List<String>,
    ) = log.warn("event=lake.removal.unexpected_dp_key table_id={} count={} first={}", target.tableId, keys.size, keys.first())

    /** A lease or mode check that could not be made: counted as a no; the class only, at DEBUG. */
    fun checkFailed(cause: Throwable) = log.debug("lake removal check failed: {}", FailureShape.cause(cause))

    fun markerWord(fate: MarkerFate): String =
        when (fate) {
            MarkerFate.NotWritten -> "not_written"
            MarkerFate.Removed -> "removed"
            is MarkerFate.Left -> "left:${fate.reason.wire}"
        }

    private const val NONE = "none"
}
