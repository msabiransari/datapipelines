package co.datapipelines.staging

import kotlinx.coroutines.runBlocking
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Collections
import java.util.UUID
import kotlin.io.path.name

/*
 * Shared free functions for the DuckDB staging tests (lane #795). Every test passes its own JUnit
 * temporary directory as the scratch directory, so "the files are gone" is asserted on a directory
 * nothing else writes to.
 */

internal const val MIB = 1L shl 20

/** Properties for a test database: small defaults so a test controls every bound it relies on. */
internal fun duckProps(
    scratchDir: Path,
    memoryLimitBytes: Long = 256 * MIB,
    expansionFactor: Int = 4,
    scratchCapacityBytes: Long = 8L * 1024 * MIB,
    maxConnections: Int = 4,
    insertBatchSize: Int = 1000,
): DuckDbStagingProperties =
    DuckDbStagingProperties(
        scratchDir = scratchDir,
        memoryLimitBytes = memoryLimitBytes,
        expansionFactor = expansionFactor,
        scratchCapacityBytes = scratchCapacityBytes,
        threads = 2,
        insertBatchSize = insertBatchSize,
        maxConnections = maxConnections,
    )

/** A factory over [duckProps]. */
internal fun duckFactory(
    scratchDir: Path,
    memoryLimitBytes: Long = 256 * MIB,
    scratchCapacityBytes: Long = 8L * 1024 * MIB,
    maxConnections: Int = 4,
): DuckDbStagingFactory =
    DuckDbStagingFactory(
        duckProps(
            scratchDir,
            memoryLimitBytes = memoryLimitBytes,
            scratchCapacityBytes = scratchCapacityBytes,
            maxConnections = maxConnections,
        ),
    )

/** Opens a DuckDB staging database for a fresh execution id, reserving [reservationBytes] of scratch. */
internal fun DuckDbStagingFactory.open(reservationBytes: Long = properties.defaultReservationBytes): DuckDbStaging =
    create(UUID.randomUUID(), reservationBytes) as DuckDbStaging

/** The names in [dir] that belong to [executionId] — what close() must leave empty. */
internal fun pathsOf(
    dir: Path,
    executionId: UUID,
): List<String> =
    Files.list(dir).use { s ->
        s
            .map { it.name }
            .filter { it.startsWith("exec_$executionId") }
            .sorted()
            .toList()
    }

/** Runs [sql] on an author lease and returns the first column of the first row as text. */
internal fun Staging.scalarText(sql: String): String? =
    runBlocking {
        withConnection { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
        }
    }

/** Executes [sql] on an author lease (statement level), blocking. */
internal fun Staging.authorExec(sql: String) {
    runBlocking { withConnection { c: Connection -> c.createStatement().use { it.execute(sql) } } }
}

/*
 * Leftover fixtures (#803). A crashed process's owner file is an owner file nobody holds a lock on —
 * exactly what the kernel leaves when the process dies (probe record A0, probe 4) — so planting one
 * unlocked is the on-disk state of a crash; DuckDbScratchCrossProcessTest produces the same state
 * with a real SIGKILL.
 */

/** Plants the owner file of a dead owner (no lock held on it) in [dir]; returns the owner id. */
internal fun plantDeadOwner(
    dir: Path,
    ownerId: UUID = UUID.randomUUID(),
): UUID {
    Files.createFile(dir.resolve(DuckDbScratchNames.owner(ownerId)))
    return ownerId
}

/**
 * Plants a database's footprint in [dir]: the file, the WAL and a spill file of the given sizes, and
 * a marker naming [ownerId] when it is not null (null = a leftover nobody can vouch for).
 */
internal fun plantDatabase(
    dir: Path,
    executionId: UUID = UUID.randomUUID(),
    ownerId: UUID?,
    databaseBytes: Int = 10,
    walBytes: Int = 5,
    spillBytes: Int = 7,
): DuckDbStagingFiles {
    val files = DuckDbStagingFiles(dir, executionId)
    if (ownerId != null) Files.writeString(files.marker, ownerId.toString())
    Files.write(files.database, ByteArray(databaseBytes))
    Files.write(files.wal, ByteArray(walBytes))
    Files.createDirectory(files.tempDir)
    Files.write(files.tempDir.resolve("duckdb_temp_storage_DEFAULT-0.tmp"), ByteArray(spillBytes))
    return files
}

/** Every entry name in [dir], sorted. */
internal fun namesIn(dir: Path): List<String> = Files.list(dir).use { s -> s.map { it.name }.sorted().toList() }

/** Records formatted SLF4J calls of every level, so a test can read the WARN a sweep logged. */
internal class RecordingLogger : LegacyAbstractLogger() {
    val lines: MutableList<Pair<Level, String>> = Collections.synchronizedList(mutableListOf())

    fun at(level: Level): List<String> = synchronized(lines) { lines.filter { it.first == level }.map { it.second } }

    override fun getName(): String = "recording"

    override fun isTraceEnabled(): Boolean = true

    override fun isDebugEnabled(): Boolean = true

    override fun isInfoEnabled(): Boolean = true

    override fun isWarnEnabled(): Boolean = true

    override fun isErrorEnabled(): Boolean = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        lines += level to MessageFormatter.basicArrayFormat(messagePattern, arguments)
    }
}
