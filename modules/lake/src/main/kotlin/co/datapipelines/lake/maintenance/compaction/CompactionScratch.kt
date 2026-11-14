package co.datapipelines.lake.maintenance.compaction

import java.sql.ResultSet
import java.time.Instant

/**
 * The lake's port to the run's scratch database (brief 969 D5 step 5): it names no staging type. `modules/web`
 * implements it over a trusted scratch database opened from the run's reservation; the lake's tests implement it over
 * a plain DuckDB.
 *
 * [open] takes the run's reservation ([co.datapipelines.lake.maintenance.MaintenanceRun.scratchReservation]) and the
 * run's deadline on the caller's clock. The reservation then belongs to the session until its [CompactionScratchSession.close].
 *
 * Failures the part tells apart (all other failures pass as they are and fail the run):
 * - [CompactionScratchExhausted]: the engine's sort spilled past the reservation (the factor was too low);
 * - [CompactionDeadlinePassed]: a failure left the open, the execute or the reading of rows after the run's deadline,
 *   decided by the implementation's clock, never by a message.
 */
fun interface CompactionScratchPort {
    fun open(
        reservation: AutoCloseable,
        deadline: Instant,
    ): CompactionScratchSession
}

/** One run's scratch database: [read] runs D6's statement, any number of times; [close] releases everything, once. */
interface CompactionScratchSession : AutoCloseable {
    /**
     * Runs [statement] with [files] bound as its one array parameter, and hands the open cursor to [consume] (which
     * reads it to the end or throws). Nothing else ever runs on the session.
     */
    fun read(
        statement: CompactionStatement,
        files: List<String>,
        consume: (ResultSet) -> Unit,
    )

    /** The scratch database's bytes on disk now (file, WAL, spill directory): a measurement for D9. */
    fun diskBytes(): Long

    override fun close()
}

/**
 * The engine's sort needed more scratch than the run was granted (D5 step 5, D8): the group is abandoned and the part
 * answers `capacity` with source `scratch`. [grantBytes] is the reservation; [engineReading] the engine's own reading
 * of its temporary directory when it gave one (a size, never a path).
 */
class CompactionScratchExhausted(
    val grantBytes: Long,
    val engineReading: String?,
    cause: Throwable? = null,
) : RuntimeException("the compaction sort needed more than its $grantBytes scratch bytes", cause)

/** The run's deadline had passed when a step of the scratch database failed (D5 step 5): the part answers `deadline`. */
class CompactionDeadlinePassed(
    cause: Throwable? = null,
) : RuntimeException("the maintenance run's deadline passed during a compaction group", cause)
