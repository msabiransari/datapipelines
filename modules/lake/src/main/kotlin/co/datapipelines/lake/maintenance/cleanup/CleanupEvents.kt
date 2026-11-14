package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.MaintenanceErrors
import co.datapipelines.persistence.FailureShape
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The cleanup's two log lines (observability §3.4, brief 962 D2a, D2b): the table id, the run id, the part's name and
 * counts — never a key, a location, an exception's message or a credential; a failure as its code and class only.
 */
internal object CleanupEvents {
    private val log = LoggerFactory.getLogger(CleanupPart::class.java)

    /**
     * `lake.cleanup.stopped`: the part throws, so the run ends `failed` and records no figures (957); this line carries
     * them instead. Logged once per throw after the lease was acquired.
     */
    fun stopped(
        part: String,
        tableId: UUID,
        runId: UUID,
        failure: Throwable,
        figures: CleanupFigures,
    ) = log.warn(
        "event=lake.cleanup.stopped part={} table_id={} run_id={} code={} reason={} cause={} tags_created={} tags_changed={}" +
            " tags_removed={} not_kept={} snapshots_expired={} floor={} listed={} deleted={} bytes={} left={} deferred={}" +
            " hints_deferred={} observer={}",
        part,
        tableId,
        runId,
        MaintenanceErrors.codeOf(failure),
        (failure as? CleanupIncomplete)?.reason?.wire ?: NONE,
        FailureShape.cause(failure),
        figures.tagsCreated,
        figures.tagsChanged,
        figures.tagsRemoved,
        figures.notKept.entries.joinToString(",") { "${it.key.wire}=${it.value}" },
        figures.snapshotsExpired,
        figures.floor?.wire ?: NONE,
        figures.listed,
        figures.deleted,
        figures.bytesDeleted,
        figures.left.entries.joinToString(",") { "${it.key.wire}=${it.value}" },
        figures.deferred,
        figures.hintsDeferred.entries.joinToString(",") { "${it.key.wire}=${it.value}" },
        figures.observer.wire,
    )

    /** `lake.cleanup.observer_failed`: the observer threw; the cleanup ends as the remover's result says (D2b). */
    fun observerFailed(
        tableId: UUID,
        runId: UUID,
        failure: Exception,
    ) = log.warn(
        "event=lake.cleanup.observer_failed table_id={} run_id={} cause={}",
        tableId,
        runId,
        FailureShape.cause(failure),
    )

    private const val NONE = "none"
}
