package co.datapipelines.staging

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
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
