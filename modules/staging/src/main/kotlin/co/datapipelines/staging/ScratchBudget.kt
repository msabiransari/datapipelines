package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.ReleaseNotifications
import java.util.UUID
import java.util.concurrent.CompletableFuture
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
 * exactly once.
 *
 * Files a crashed process left in the scratch directory that nobody can prove dead are never
 * deleted (owner ruling 2026-11-12, #803): their bytes, counted by the factory's startup sweep, are
 * debited from what this aggregate admits ([leftoverBytes]). A request is then refused one of two
 * ways, told apart by one rule:
 *  - it does not fit even with every running reservation released — `requested > capacity −
 *    leftover` — [StagingScratchBlockedException] (`scratch_blocked`): it can never succeed until an
 *    operator acts, so it is not a [StagingScratchExhaustedException] and no caller retries it;
 *  - it would fit once running reservations release — [StagingScratchExhaustedException]
 *    (`scratch_exhausted`), retryable.
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
    private var leftover: Long? = null

    /** The bytes currently held by unreleased reservations (running executions only, never leftovers). */
    val reservedBytes: Long
        get() = synchronized(lock) { reserved }

    /**
     * The bytes of counted leftover files debited from the aggregate (staging.md §8.5): `null` until
     * the factory's sweep has counted (none yet ran, or it was refused), then the count — `0` when the
     * directory held nothing nobody could vouch for. Reading it does no I/O and never starts a sweep.
     */
    val leftoverBytes: Long?
        get() = synchronized(lock) { leftover }

    /**
     * Debits [bytes] of counted leftovers, once per sweep; a factory sweeps once, so in production
     * (one budget per factory) this is called once. Saturates instead of wrapping.
     */
    internal fun countLeftovers(bytes: Long) {
        require(bytes >= 0) { "counted leftover bytes cannot be negative (got $bytes)" }
        synchronized(lock) { leftover = saturatingAdd(leftover ?: 0L, bytes) }
    }

    /**
     * Reserves [bytes] for [executionId]'s staging database, or refuses.
     *
     * @throws StagingScratchBlockedException the reservation exceeds [capacityBytes] less the counted
     *   leftovers — it cannot fit even when every running reservation is released; nothing is reserved.
     * @throws StagingScratchExhaustedException the reservation does not fit in what running
     *   reservations leave of that; nothing is reserved.
     * @throws IllegalArgumentException [bytes] is not positive (a wiring defect, never author input).
     */
    fun reserve(
        executionId: UUID,
        bytes: Long,
    ): ScratchReservation {
        require(bytes > 0) { "a staging scratch reservation must be positive (got $bytes bytes)" }
        synchronized(lock) {
            // Compared by subtraction, never `reserved + bytes > capacity`: a reservation near
            // Long.MAX_VALUE must refuse, not overflow into a fit. capacity > 0 and 0 <= held, so
            // `capacity - held` cannot wrap; past the first check it is positive and reserved fits in it.
            val held = leftover ?: 0L
            if (bytes > capacityBytes - held) {
                throw StagingScratchBlockedException(executionId, bytes, held, capacityBytes)
            }
            if (bytes > capacityBytes - held - reserved) {
                throw StagingScratchExhaustedException(executionId, bytes, reserved, capacityBytes, held)
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
) : AutoCloseable,
    ReleaseNotifications {
    private val released = AtomicBoolean(false)
    private val completion = CompletableFuture<Unit>()

    /** Returns the bytes to the aggregate; idempotent. */
    fun release() {
        if (released.compareAndSet(false, true)) {
            onRelease(bytes)
            completion.complete(Unit)
        }
    }

    override fun close() = release()

    override fun afterRelease(callback: () -> Unit) {
        completion.thenRun(callback)
    }
}

/**
 * A DuckDB staging database's scratch reservation does not fit in what running reservations leave
 * of the aggregate (staging.md §8.5) — retryable: a running execution's close makes room. Raised by
 * [ScratchBudget.reserve] **before** any file exists; the details carry sizes and the execution id
 * only — the configured aggregate is operator configuration, not a secret.
 */
class StagingScratchExhaustedException(
    val executionId: UUID,
    val requestedBytes: Long,
    val reservedBytes: Long,
    val capacityBytes: Long,
    /** Bytes of counted leftover files debited from the aggregate (#803); `0` when there are none. */
    val leftoverBytes: Long = 0L,
) : DatapipelinesException(
        code = StagingErrorCodes.SCRATCH_EXHAUSTED,
        message =
            "The staging scratch space cannot take another $requestedBytes bytes for execution $executionId now: " +
                "$reservedBytes of $capacityBytes bytes are reserved by running executions" +
                (if (leftoverBytes > 0) " and $leftoverBytes are held by leftover files in the scratch directory" else "") +
                ". Retry once a running execution finishes, or raise datapipelines.lake.admission.scratch-bytes.",
        details =
            mapOf(
                "execution_id" to executionId.toString(),
                "requested_bytes" to requestedBytes,
                "reserved_bytes" to reservedBytes,
                "leftover_bytes" to leftoverBytes,
                "capacity_bytes" to capacityBytes,
            ),
    )

/**
 * A DuckDB staging database's scratch reservation can never fit (staging.md §8.5, #803): it exceeds
 * the aggregate less the bytes of leftover files nobody can vouch for, so no running execution's
 * close makes room. Deliberately NOT a [StagingScratchExhaustedException], which the delivery path
 * catches and polls on: this one fails the execution at once. Sizes and the execution id only —
 * the leftover paths are in the server log's WARN, never here.
 */
class StagingScratchBlockedException(
    val executionId: UUID,
    val requestedBytes: Long,
    val leftoverBytes: Long,
    val capacityBytes: Long,
) : DatapipelinesException(
        code = StagingErrorCodes.SCRATCH_BLOCKED,
        message =
            if (leftoverBytes > 0) {
                "The staging scratch space cannot take $requestedBytes bytes for execution $executionId: " +
                    "$leftoverBytes of its $capacityBytes bytes are held by leftover files in the scratch directory " +
                    "that a crashed process left and nobody can vouch for. An operator must clear the scratch directory " +
                    "while no instance uses it, or raise datapipelines.lake.admission.scratch-bytes."
            } else {
                "The staging scratch space cannot take $requestedBytes bytes for execution $executionId: the request " +
                    "exceeds the configured scratch capacity of $capacityBytes bytes " +
                    "(datapipelines.lake.admission.scratch-bytes)."
            },
        details =
            mapOf(
                "execution_id" to executionId.toString(),
                "requested_bytes" to requestedBytes,
                "leftover_bytes" to leftoverBytes,
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
