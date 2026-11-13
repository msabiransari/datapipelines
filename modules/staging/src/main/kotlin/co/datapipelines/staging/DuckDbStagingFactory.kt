package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.StagingEngine
import org.duckdb.DuckDBConnection
import org.duckdb.DuckDBDriver
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The DuckDB staging factory (staging.md §8.5): one file-backed database per execution, bounded,
 * scratch-reserved and locked before any author SQL can reach it.
 *
 * ## Open sequence
 *
 * 0. **Sweep, once per factory, before its first admission** (the first [create] or
 *    [prepareDelivery]; #803): remove what crashed processes left in the scratch directory when
 *    their owner is provably dead, and debit from [budget] the bytes of what nobody can vouch for
 *    ([DuckDbScratchHousekeeper]). Concurrent first calls run one sweep and all wait for it.
 * 1. **Reserve** the scratch bytes from [budget] — `scratch_blocked` or `scratch_exhausted` before
 *    any file exists.
 * 2. Create the scratch directory owner-only, or check that a pre-existing one belongs to the
 *    process user and grants nothing to group or other (POSIX, [DuckDbStagingFiles.prepareScratchDir]);
 *    hold this factory's owner lock; write the database's ownership marker, after removing a
 *    leftover of this execution id only when its owner is provably dead or is this factory (an
 *    earlier attempt, no longer open). A same-id leftover that is live or that nobody can vouch for
 *    fails the open, every file left as it was ([DuckDbScratchHousekeeper.claim]).
 * 3. Open DP's own **root** connection on `<scratch dir>/exec_<id>.duckdb` with
 *    `allow_persistent_secrets=false` and streamed results, then apply, in order: `memory_limit`,
 *    `temp_directory` (beside the file), `max_temp_directory_size` = the reservation, `threads`,
 *    `TimeZone = 'UTC'`, extension autoinstall/autoload and community extensions off,
 *    optional DP-owned delivery initialization, `enable_external_access = false`, and last
 *    `lock_configuration = true`.
 * 4. **Read the configuration back** in one SELECT on the locked root — the lock, external access
 *    off, `memory_limit` and `max_temp_directory_size` ([DuckDbConfigurationReadBack]); a database
 *    where any of them did not take is never handed out.
 *
 * Any failure after the reservation closes the root, deletes the files THAT attempt created (none
 * when it failed before its marker was written) and releases the reservation (`try`/`finally`).
 * Setup failures surface as `creation_failed`; sanitized trusted initialization refusals retain
 * their domain code. Every lease afterwards is a `duplicate()` of the locked root: it inherits the
 * locked configuration and cannot change it
 * (measured, lane #795 probe 12 — `SET`, `RESET` and `SET lock_configuration = false` are refused).
 *
 * `memory_limit` bounds DuckDB's buffer manager only, not the JVM heap and not every native
 * allocation (dp-lake design §11). Combined JVM/native process-memory admission is separate
 * acceptance work; this factory does not enforce it.
 *
 * @param budget the aggregate every database of this factory reserves from; one per factory.
 * @param beforeSweep runs inside the sweep's critical section, before the sweep reads anything — a
 *   test seam that holds a sweep open; nothing in production.
 * @param connect the JDBC open — `DriverManager` in production. A fault-injection seam for tests.
 *   Kept LAST: callers pass it as a trailing lambda.
 */
class DuckDbStagingFactory internal constructor(
    /** The resolved configuration; readable so wiring is testable. */
    val properties: DuckDbStagingProperties,
    val budget: ScratchBudget,
    private val beforeSweep: () -> Unit = {},
    private val connect: (url: String, props: Properties) -> Connection,
) : StagingFactory {
    constructor(properties: DuckDbStagingProperties) :
        this(properties, ScratchBudget(properties.scratchCapacityBytes), connect = DriverManager::getConnection)

    /** This factory's owner id in the scratch directory; memory only until the first open. */
    private val ownerId = DuckDbScratchOwnership.newOwner()
    private val housekeeper = DuckDbScratchHousekeeper(properties.scratchDir, ownerId, log)
    private val sweepLock = Any()

    @Volatile private var swept = false
    private val ownerLock = Any()

    /** The owner file's channel, open (and locked) for the life of the process once the first open ran. */
    @Volatile private var ownerChannel: FileChannel? = null
    private val openHere: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override fun create(
        executionId: UUID,
        engine: StagingEngine,
    ): Staging {
        when (engine) {
            StagingEngine.DUCKDB -> Unit
            StagingEngine.H2 -> throw engineUnavailable(executionId, engine)
        }
        return create(executionId, properties.defaultReservationBytes)
    }

    /**
     * Opens a database reserving [reservationBytes] of scratch instead of the default
     * `memory-limit × expansion-factor` — the caller-supplied reservation a lake delivery passes
     * (delivery bytes × factor, dp-lake P4). [create] with an engine is unchanged (§13.1).
     *
     * @throws StagingScratchExhaustedException the reservation does not fit; no file was created.
     */
    fun create(
        executionId: UUID,
        reservationBytes: Long,
    ): Staging = prepareDelivery(executionId, reservationBytes).open()

    override fun prepareDelivery(
        executionId: UUID,
        reservationBytes: Long,
    ): StagingPreparation {
        sweepOnce(executionId)
        val reservation = budget.reserve(executionId, reservationBytes)
        return ReservedStagingPreparation(reservation) { initializer -> open(executionId, reservation, initializer) }
    }

    /**
     * The startup sweep, once: concurrent first callers wait on [sweepLock], so no reservation is
     * granted before the leftover debit. A refused directory is not read, the call fails as
     * `creation_failed`, and the next admission tries again.
     */
    private fun sweepOnce(executionId: UUID) {
        if (swept) return
        synchronized(sweepLock) {
            if (swept) return
            beforeSweep()
            val result =
                try {
                    housekeeper.sweep()
                } catch (e: IOException) {
                    throw creationFailed(executionId, e)
                } catch (e: IllegalArgumentException) {
                    throw creationFailed(executionId, e)
                }
            budget.countLeftovers(result.countedBytes)
            swept = true
        }
    }

    /** Marks [executionId] open in this factory; a second open of the same id is refused. */
    private fun holdId(executionId: UUID) {
        if (!openHere.add(executionId)) throw IOException("execution $executionId is already open in this factory")
    }

    /** Locks this factory's owner file once, after the directory was prepared. */
    private fun holdOwnerFile() {
        if (ownerChannel != null) return
        synchronized(ownerLock) {
            if (ownerChannel == null) ownerChannel = DuckDbScratchOwnership.lockOwnerFile(properties.scratchDir, ownerId)
        }
    }

    private fun open(
        executionId: UUID,
        reservation: ScratchReservation,
        initializer: TrustedStagingInitializer,
    ): DuckDbStaging {
        val files = DuckDbStagingFiles(properties.scratchDir, executionId)
        var root: DuckDBConnection? = null
        var claimedId = false
        var markerWritten = false
        var handedOut = false
        try {
            val failure: Exception =
                try {
                    DuckDbStagingFiles.prepareScratchDir(properties.scratchDir)
                    holdOwnerFile()
                    holdId(executionId)
                    claimedId = true
                    housekeeper.claim(files)
                    markerWritten = true
                    val opened = connect("$URL_PREFIX${files.database}", openProperties()).unwrap(DuckDBConnection::class.java)
                    root = opened
                    applyLockedConfiguration(opened, files, reservation.bytes, initializer, executionId)
                    val pool =
                        DuckDbLeasePool(executionId, properties.maxConnections, opened::duplicate) {
                            finalize(executionId, opened, files, reservation)
                        }
                    val bytes = files.diskBytes()
                    if (bytes > reservation.bytes) {
                        throw StagingScratchLimitException(executionId, reservation.bytes, bytes, engineReading = null)
                    }
                    return DuckDbStaging(executionId, pool, properties, files, reservation).also { handedOut = true }
                } catch (e: SQLException) {
                    e
                } catch (e: IOException) {
                    e
                } catch (e: IllegalArgumentException) {
                    e
                }
            throw creationFailed(executionId, failure)
        } finally {
            if (!handedOut) {
                if (markerWritten) abandon(executionId, root, files)
                if (claimedId) openHere.remove(executionId)
            }
        }
    }

    private fun openProperties(): Properties =
        Properties().apply {
            setProperty("allow_persistent_secrets", "false")
            // Streamed, not materialized: measured (lane #795 probe 17), a materialized read of a
            // table larger than memory_limit is held outside the buffer manager's reading.
            setProperty(DuckDBDriver.JDBC_STREAM_RESULTS, "true")
        }

    private fun applyLockedConfiguration(
        root: Connection,
        files: DuckDbStagingFiles,
        reservationBytes: Long,
        initializer: TrustedStagingInitializer,
        executionId: UUID,
    ) {
        val settings =
            listOf(
                "SET memory_limit = '${properties.memoryLimitBytes}B'",
                "SET temp_directory = ${literal(files.tempDir.toString())}",
                "SET max_temp_directory_size = '${reservationBytes}B'",
                "SET threads = ${properties.threads}",
                "SET TimeZone = 'UTC'",
                "SET autoinstall_known_extensions = false",
                "SET autoload_known_extensions = false",
                "SET allow_community_extensions = false",
            )
        root.createStatement().use { st -> settings.forEach { st.execute(it) } }
        initializer.initializeSafely(root, executionId, log)
        root.createStatement().use { st ->
            st.execute("SET enable_external_access = false")
            st.execute("SET lock_configuration = true")
        }
        val readBack = DuckDbConfigurationReadBack.sql(properties.memoryLimitBytes, reservationBytes)
        val failed = root.createStatement().use { st -> st.executeQuery(readBack).use(DuckDbConfigurationReadBack::failures) }
        // Server log only (creationFailed): the caller gets the fixed text.
        if (failed.isNotEmpty()) throw SQLException("the staging database's locked configuration did not take: ${failed.joinToString()}")
    }

    /** The finalizer the last lease out (or `close()`) runs exactly once; never throws an Exception. */
    private fun finalize(
        executionId: UUID,
        root: Connection,
        files: DuckDbStagingFiles,
        reservation: ScratchReservation,
    ) {
        try {
            try {
                root.close()
            } catch (e: SQLException) {
                log.warn(
                    "{}: DuckDB staging root close failed for execution {}: {}",
                    StagingErrorCodes.CLEANUP_FAILED,
                    executionId,
                    e.message,
                )
            }
            val failures = files.delete()
            failures.forEach { (path, e) ->
                log.warn(
                    "{}: DuckDB staging path {} of execution {} not deleted: {}",
                    StagingErrorCodes.CLEANUP_FAILED,
                    path,
                    executionId,
                    e.message,
                )
            }
            // The marker outlives a failed delete: what is left stays this owner's, removed by a
            // later same-id open here or by the first sweep after this process ends.
            if (failures.isEmpty()) deleteMarker(files)
        } finally {
            openHere.remove(executionId)
            reservation.release()
        }
    }

    private fun abandon(
        executionId: UUID,
        root: Connection?,
        files: DuckDbStagingFiles,
    ) {
        runCatching { root?.close() }
        // Only what this attempt created: its marker was written exclusively and no database path
        // existed before it (DuckDbScratchHousekeeper.claim), so the three paths are this attempt's.
        val failures = files.delete()
        failures.forEach { (path, e) ->
            log.warn("DuckDB staging path {} of failed open {} not deleted: {}", path, executionId, e.message)
        }
        if (failures.isEmpty()) deleteMarker(files)
    }

    private fun deleteMarker(files: DuckDbStagingFiles) {
        try {
            Files.deleteIfExists(files.marker)
        } catch (e: IOException) {
            log.warn("{}: DuckDB staging marker {} not deleted: {}", StagingErrorCodes.CLEANUP_FAILED, files.marker, e.message)
        }
    }

    /**
     * The catalogued `creation_failed`: a fixed text and the execution id, nothing from the failure.
     * A driver or file-system message names paths under the operator's scratch directory, and the
     * executor transports a setup failure's message AND its cause chain to the caller (057,
     * `error-detail: full`), so [failure] is not attached as the cause. It goes to the server log
     * at WARN instead, whole — the operator's own path, for the operator.
     */
    private fun creationFailed(
        executionId: UUID,
        failure: Exception,
    ): DatapipelinesException {
        log.warn(
            "{}: DuckDB staging database for execution {} could not be created",
            StagingErrorCodes.CREATION_FAILED,
            executionId,
            failure,
        )
        return DatapipelinesException(
            code = StagingErrorCodes.CREATION_FAILED,
            message = "Could not create the DuckDB staging database for execution $executionId; the cause is in the server log.",
            details = mapOf("execution_id" to executionId.toString()),
        )
    }

    private fun engineUnavailable(
        executionId: UUID,
        engine: StagingEngine,
    ): DatapipelinesException =
        DatapipelinesException(
            code = StagingErrorCodes.ENGINE_UNAVAILABLE,
            message = "The DuckDB staging factory cannot open a ${engine.wire} staging database (execution $executionId)",
            details = mapOf("execution_id" to executionId.toString(), "engine" to engine.wire),
        )

    private companion object {
        val log = LoggerFactory.getLogger(DuckDbStagingFactory::class.java)
        const val URL_PREFIX = "jdbc:duckdb:"

        /** A single-quoted SQL literal; the path is operator configuration plus a UUID, quoted anyway. */
        fun literal(value: String): String = "'${value.replace("'", "''")}'"
    }
}

/**
 * The configuration read-back of a new staging database (staging.md §8.5; #795 review F8): one
 * SELECT on the locked root, one column per check, each true when it holds.
 *
 * The engine reports `memory_limit` and `max_temp_directory_size` only as display text (`64.0 MiB`;
 * no byte form is readable), so each is compared, inside the engine, with the engine's own
 * `format_bytes` of the configured bytes. That is equality to display precision — one decimal of
 * the unit — and a skipped or ignored SET leaves the engine default (a share of the host's memory,
 * `90% of available disk space`), which never meets it (measured, lane #795 round two).
 */
internal object DuckDbConfigurationReadBack {
    private val CHECKS = listOf("lock_configuration", "enable_external_access off", "memory_limit", "max_temp_directory_size")

    fun sql(
        memoryLimitBytes: Long,
        reservationBytes: Long,
    ): String =
        "SELECT current_setting('lock_configuration'), NOT current_setting('enable_external_access'), " +
            "current_setting('memory_limit') = format_bytes($memoryLimitBytes), " +
            "current_setting('max_temp_directory_size') = format_bytes($reservationBytes)"

    /** The checks of [sql]'s row that do not hold — a NULL counts as not holding; all of them without a row. */
    fun failures(rs: ResultSet): List<String> = if (rs.next()) CHECKS.filterIndexed { i, _ -> !rs.getBoolean(i + 1) } else CHECKS
}
