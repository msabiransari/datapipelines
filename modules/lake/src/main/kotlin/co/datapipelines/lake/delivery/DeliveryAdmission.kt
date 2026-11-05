package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeProperties
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * One ingestion slot followed by delivery-sized scratch, before listing or opening a database.
 * [reserve] in [acquire] is the dispatching staging factory's reservation-only operation: null
 * means capacity is temporarily exhausted; other failures propagate after releasing the slot.
 */
class DeliveryAdmission(
    maxConcurrentIngestions: Int,
    val scratchBytes: Long,
    private val wait: Duration,
    private val jobTimeout: Duration,
    private val catalogNowPlus: (Duration) -> Instant,
) {
    constructor(properties: LakeProperties.Admission, catalogNowPlus: (Duration) -> Instant) : this(
        maxConcurrentIngestions = properties.maxConcurrentIngestions,
        scratchBytes = Math.multiplyExact(properties.maxDeliveryBytes.toBytes(), properties.stagingExpansionFactor.toLong()),
        wait = Duration.ofSeconds(properties.leaseWaitSeconds),
        jobTimeout = properties.jobTimeout,
        catalogNowPlus = catalogNowPlus,
    )

    init {
        require(maxConcurrentIngestions > 0 && scratchBytes > 0)
        require(!wait.isNegative && wait.toNanos() >= 0)
        require(!jobTimeout.isNegative && !jobTimeout.isZero)
    }

    private val slots = Semaphore(maxConcurrentIngestions, true)

    /**
     * Null means retryable capacity refusal, with no resource retained. Slot and scratch share one
     * wait budget. The catalog deadline is fixed once both are held; a later lease never extends it.
     */
    fun <T : AutoCloseable> acquire(reserve: (Long) -> T?): AdmittedDelivery<T>? {
        val started = System.nanoTime()
        val waitNanos = wait.toNanos()
        if (!slots.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) return null
        var retained = false
        var attempted = false
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Delivery admission interrupted.")
                if (attempted && System.nanoTime() - started >= waitNanos) return null
                attempted = true
                val reservation = reserve(scratchBytes)
                if (reservation != null) {
                    return transfer(reservation).also { retained = true }
                }
                val remaining = waitNanos - (System.nanoTime() - started)
                if (remaining <= 0) return null
                LockSupport.parkNanos(minOf(remaining, POLL_NANOS))
            }
        } finally {
            if (!retained) slots.release()
        }
    }

    private fun <T : AutoCloseable> transfer(reservation: T): AdmittedDelivery<T> {
        var transferred = false
        try {
            return AdmittedDelivery(reservation, catalogNowPlus(jobTimeout), slots::release).also { transferred = true }
        } finally {
            if (!transferred) reservation.close()
        }
    }

    private companion object {
        val POLL_NANOS = Duration.ofMillis(50).toNanos()
    }
}

/**
 * Owns the ingestion slot for the execution, including parallel target appends. Close the opened
 * staging database before this permit; an unopened preparation releases its scratch here.
 */
class AdmittedDelivery<T : AutoCloseable> internal constructor(
    val reservation: T,
    val deadline: Instant,
    private val releaseSlot: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                reservation.close()
            } finally {
                releaseSlot()
            }
        }
    }
}
