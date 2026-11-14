package co.datapipelines.staging

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A trusted scratch database (staging.md §8.5 "The trusted scratch database"; #969 D5): the factory's file-backed
 * database, opened from a [StagingPreparation] by [StagingPreparation.openTrusted] in the author database's open
 * sequence with ONE difference: `enable_external_access` is left on, so DP's own statement can read the lake's object
 * store. The configuration is locked and read back as an author database's is (lock, `memory_limit`,
 * `max_temp_directory_size`).
 *
 * It is NOT a [Staging]: no author lease, no SQL gate, no staged table, nothing can lease it. It offers DP's root
 * connection to one body at a time ([withRoot]) and [close]. Only DP's own SQL may ever run on it: after the lock the
 * engine still accepts `CREATE SECRET`, `LOAD` and a local file read on such a database (measured 2026-11-14, store
 * `notes/2026-11-14-969-trusted-open-probe/`).
 */
interface TrustedScratchDatabase : AutoCloseable {
    /** The id the reservation was made for; the database's file name. */
    val executionId: UUID

    /** The scratch reserved for this database: `max_temp_directory_size` and the bound of [withRoot]'s measurement. */
    val reservedBytes: Long

    /**
     * Runs [body] on DP's root connection, one body at a time; the body owns every statement and cursor it opens. A
     * [SQLException] leaving the body, from an execute or from reading rows, is classified exactly as an author
     * database's ([DuckDbEngineFailures]): a spill past the reservation → [StagingScratchLimitException], another
     * engine out-of-memory → [StagingEngineMemoryLimitException], anything else unchanged. After a body that returned,
     * the file, WAL and spill directory are measured; past the reservation → [StagingScratchLimitException].
     */
    fun <T> withRoot(body: (Connection) -> T): T

    /** Bytes on disk now (file, WAL, spill directory): a measurement, never a bound. */
    fun diskBytes(): Long

    /**
     * The factory's finalizer, once: root closed; database, WAL and spill directory deleted; marker removed;
     * reservation released. Never throws; a second call does nothing. It does not wait for a running body: a body on
     * another thread sees its connection closed.
     */
    override fun close()
}

/** The DuckDB implementation: [finalizer] is the factory's, run once. */
internal class DuckDbTrustedScratchDatabase(
    override val executionId: UUID,
    private val root: Connection,
    private val files: DuckDbStagingFiles,
    override val reservedBytes: Long,
    private val failures: DuckDbEngineFailures,
    private val finalizer: () -> Unit,
) : TrustedScratchDatabase {
    private val oneBody = ReentrantLock()
    private val closed = AtomicBoolean(false)

    override fun <T> withRoot(body: (Connection) -> T): T =
        oneBody.withLock {
            check(!closed.get()) { "trusted scratch database $executionId is closed" }
            val result =
                try {
                    body(root)
                } catch (e: SQLException) {
                    throw failures.classify(e)
                }
            val measured = files.diskBytes()
            if (measured > reservedBytes) {
                throw StagingScratchLimitException(executionId, reservedBytes, measuredBytes = measured, engineReading = null)
            }
            result
        }

    override fun diskBytes(): Long = files.diskBytes()

    override fun close() {
        if (closed.compareAndSet(false, true)) finalizer()
    }
}
