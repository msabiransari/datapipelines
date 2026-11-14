package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.ReleaseNotifications
import org.slf4j.Logger
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DP-owned setup before author SQL is exposed. Do not retain the connection. A domain refusal must
 * already be sanitized; other exception messages and causes are removed by the factory boundary.
 */
fun interface TrustedStagingInitializer {
    fun initialize(connection: Connection)

    companion object {
        val NONE = TrustedStagingInitializer { }
    }
}

/** Scratch admission without opening a database; either opened once or closed without opening. */
interface StagingPreparation :
    AutoCloseable,
    ReleaseNotifications {
    val executionId: UUID
    val reservedBytes: Long

    /** Transfers the reservation to the database after trusted setup and the author configuration lock. */
    fun open(initializer: TrustedStagingInitializer = TrustedStagingInitializer.NONE): Staging

    /**
     * Opens a [TrustedScratchDatabase] instead (#969 D5; staging.md §8.5): the same open sequence and the same claim
     * as [open] — once, the reservation moving to the database — with external access left on. Either entry claims
     * the preparation; the other is then refused.
     */
    fun openTrusted(initializer: TrustedStagingInitializer): TrustedScratchDatabase

    /** Releases unopened admission. An opened database retains its reservation until its own close. */
    override fun close()
}

internal class ReservedStagingPreparation(
    private val reservation: ScratchReservation,
    private val trustedOpener: (TrustedStagingInitializer) -> TrustedScratchDatabase,
    private val opener: (TrustedStagingInitializer) -> Staging,
) : StagingPreparation {
    private val claimed = AtomicBoolean(false)
    override val executionId: UUID get() = reservation.executionId
    override val reservedBytes: Long get() = reservation.bytes

    override fun afterRelease(callback: () -> Unit) = reservation.afterRelease(callback)

    override fun open(initializer: TrustedStagingInitializer): Staging = claimOnce { opener(initializer) }

    override fun openTrusted(initializer: TrustedStagingInitializer): TrustedScratchDatabase = claimOnce { trustedOpener(initializer) }

    private fun <T> claimOnce(opening: () -> T): T {
        check(claimed.compareAndSet(false, true)) { "Staging admission was already opened or closed." }
        var handedOut = false
        try {
            return opening().also { handedOut = true }
        } finally {
            if (!handedOut) reservation.release()
        }
    }

    override fun close() {
        if (claimed.compareAndSet(false, true)) reservation.release()
    }
}

@Suppress("TooGenericExceptionCaught", "ThrowsCount") // sanitize initializer errors; preserve cancellation and domain refusals
internal fun TrustedStagingInitializer.initializeSafely(
    root: Connection,
    executionId: UUID,
    log: Logger,
) {
    try {
        initialize(root)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: DatapipelinesException) {
        // A trusted domain refusal is already sanitized by its owning boundary.
        throw failure
    } catch (failure: Exception) {
        // In particular, never log a CREATE SECRET error's message, SQL text or cause chain.
        log.warn(
            "delivery staging initialization failed execution_id={} cause={} sql_state=none",
            executionId,
            failure.javaClass.simpleName,
        )
        throw DatapipelinesException(
            StagingErrorCodes.CREATION_FAILED,
            "Could not initialize delivery staging for execution $executionId.",
            mapOf("execution_id" to executionId.toString()),
        )
    }
}
