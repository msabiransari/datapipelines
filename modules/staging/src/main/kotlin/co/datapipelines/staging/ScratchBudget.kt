package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The process-wide scratch aggregate every DuckDB staging database reserves from before its file
 * exists (staging.md §8.5; dp-lake design §11 "scratch reservation"). One instance per factory,
 * sized by `datapipelines.lake.admission.scratch-bytes`.
 *
 * The aggregate is an **admission** bound, not a measurement: a database reserves the bytes it
 * may use on local disk (its file, WAL and spill directory) up front, the engine's
 * `max_temp_directory_size` is set to that reservation, and the staging database refuses to grow
 * past it (`scratch_limit_exceeded`). Reservations are released when the staging database closes,
 * exactly once. A reservation that does not fit is refused with [StagingScratchExhaustedException]
 * — retryable, because a running execution's close makes room.
 *
 * Thread-safe: every read and write of the reserved total is under one monitor, and the critical
 * sections hold no I/O.
 */
class ScratchBudget(
    /** The aggregate every reservation shares, in bytes; positive. */
    val capacityBytes: Long,
) {
    init {
        require(capacityBytes > 0) { "the staging scratch aggregate must be positive (got $capacityBytes bytes)" }
    }

    private val lock = Any()
    private var reserved = 0L

    /** The bytes currently held by unreleased reservations. */
    val reservedBytes: Long
        get() = synchronized(lock) { reserved }

    /**
     * Reserves [bytes] for [executionId]'s staging database, or refuses.
     *
     * @throws StagingScratchExhaustedException the reservation does not fit in what is left of
     *   [capacityBytes]; nothing is reserved.
     * @throws IllegalArgumentException [bytes] is not positive (a wiring defect, never author input).
     */
    fun reserve(
        executionId: UUID,
        bytes: Long,
    ): ScratchReservation {
        require(bytes > 0) { "a staging scratch reservation must be positive (got $bytes bytes)" }
        synchronized(lock) {
            // Compared as `bytes > capacity - reserved`, never `reserved + bytes > capacity`: a
            // reservation near Long.MAX_VALUE must refuse, not overflow into a fit.
            if (bytes > capacityBytes - reserved) {
                throw StagingScratchExhaustedException(executionId, bytes, reserved, capacityBytes)
            }
            reserved += bytes
        }
        return ScratchReservation(executionId, bytes, ::release)
    }

    private fun release(bytes: Long) {
        synchronized(lock) {
            check(bytes <= reserved) { "staging scratch release of $bytes bytes exceeds the $reserved reserved" }
            reserved -= bytes
        }
    }
}

/**
 * One staging database's share of a [ScratchBudget]. [release] returns the bytes to the aggregate
 * **exactly once**, however many times it is called and from whichever thread: the staging
 * database releases it from `close()`, and a failed open releases it from its own `finally`.
 */
class ScratchReservation internal constructor(
    /** The execution the bytes are reserved for. */
    val executionId: UUID,
    /** The reserved bytes; also the engine's `max_temp_directory_size` and the database's growth cap. */
    val bytes: Long,
    private val onRelease: (Long) -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    /** Whether [release] has run. */
    val isReleased: Boolean
        get() = released.get()

    /** Returns the bytes to the aggregate; idempotent. */
    fun release() {
        if (released.compareAndSet(false, true)) onRelease(bytes)
    }

    override fun close() = release()
}

/**
 * A DuckDB staging database's scratch reservation does not fit in the aggregate (staging.md §8.5).
 * Raised by [ScratchBudget.reserve] **before** any file exists; the details carry sizes and the
 * execution id only — the configured aggregate is operator configuration, not a secret.
 */
class StagingScratchExhaustedException(
    val executionId: UUID,
    val requestedBytes: Long,
    val reservedBytes: Long,
    val capacityBytes: Long,
) : DatapipelinesException(
        code = StagingErrorCodes.SCRATCH_EXHAUSTED,
        message =
            "The staging scratch space cannot take another $requestedBytes bytes for execution $executionId: " +
                "$reservedBytes of $capacityBytes bytes are reserved by running executions. Retry once one " +
                "finishes, or raise datapipelines.lake.admission.scratch-bytes.",
        details =
            mapOf(
                "execution_id" to executionId.toString(),
                "requested_bytes" to requestedBytes,
                "reserved_bytes" to reservedBytes,
                "capacity_bytes" to capacityBytes,
            ),
    )

/**
 * A DuckDB staging database outgrew its own reservation (staging.md §8.5): the engine refused a
 * spill past `max_temp_directory_size`, or the database file, WAL and spill directory measured more
 * than the reservation after a write. [measuredBytes] is `null` when the engine refused the spill
 * itself; [engineReading] is then the engine's own sentence, bounded.
 */
class StagingScratchLimitException(
    val executionId: UUID,
    val reservationBytes: Long,
    val measuredBytes: Long?,
    val engineReading: String?,
    cause: Throwable? = null,
) : DatapipelinesException(
        code = StagingErrorCodes.SCRATCH_LIMIT_EXCEEDED,
        message =
            "The staging database for execution $executionId outgrew its $reservationBytes-byte scratch reservation" +
                (measuredBytes?.let { " (measured $it bytes on disk)" } ?: "") +
                (engineReading?.let { ": $it" } ?: "") +
                ". Stage fewer rows or columns, or raise datapipelines.lake.admission.staging-expansion-factor.",
        details =
            mapOf(
                "execution_id" to executionId.toString(),
                "reservation_bytes" to reservationBytes,
                "measured_bytes" to measuredBytes,
                "engine_reading" to engineReading,
            ),
        cause = cause,
    )
