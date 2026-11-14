package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.LakeMinio
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lake tests' [CompactionScratchPort]: a plain in-memory DuckDB (the module's test runtime carries the driver)
 * that reads the module's MinIO through httpfs, set up as the web adapter's initializer does (`LakeStagingStorage`'s
 * secret, `s3_url_compatibility_mode`, UTC). The production adapter over a trusted scratch database is
 * `ManagedLakeCompactionE2eTest`'s. [rows] steers the cursor a test's consumer sees (C9's reader that drops a row);
 * [before] runs before every statement (a test's clock step or failure).
 *
 * Named without the `Test` suffix so `verifyTestsExecuted` counts only real test classes.
 */
internal class DuckDbTestScratch(
    private val bucket: String,
    private val rows: (ResultSet) -> ResultSet = { it },
    private val before: (List<String>) -> Unit = {},
) : CompactionScratchPort {
    val opened = AtomicInteger()
    val closed = AtomicInteger()
    val statements = CopyOnWriteArrayList<String>()
    val deadlines = CopyOnWriteArrayList<Instant>()

    override fun open(
        reservation: AutoCloseable,
        deadline: Instant,
    ): CompactionScratchSession {
        opened.incrementAndGet()
        deadlines += deadline
        val connection = DriverManager.getConnection("jdbc:duckdb:")
        val storage = LakeMinio.storage(bucket = bucket, create = false)
        connection.createStatement().use { st ->
            st.execute("SET TimeZone = 'UTC'")
            st.execute("INSTALL httpfs")
            st.execute("LOAD httpfs")
            // Test-only credentials of this JVM's MinIO; production binds them through LakeStagingStorage.
            st.execute(
                "CREATE SECRET lake (TYPE s3, KEY_ID '${quote(storage.accessKey)}', SECRET '${quote(storage.secretKey)}', " +
                    "REGION '${quote(storage.region)}', ENDPOINT '${quote(storage.endpoint.removePrefix("http://"))}', " +
                    "URL_STYLE 'path', USE_SSL false)",
            )
            st.execute("SET s3_url_compatibility_mode = true")
        }
        return Session(connection, reservation)
    }

    private inner class Session(
        private val connection: Connection,
        private val reservation: AutoCloseable,
    ) : CompactionScratchSession {
        override fun read(
            statement: CompactionStatement,
            files: List<String>,
            consume: (ResultSet) -> Unit,
        ) {
            before(files)
            statements += statement.sql
            connection.prepareStatement(statement.sql).use { ps ->
                ps.setArray(1, connection.createArrayOf("VARCHAR", files.toTypedArray()))
                ps.executeQuery().use { consume(rows(it)) }
            }
        }

        override fun diskBytes(): Long = 0

        override fun close() {
            closed.incrementAndGet()
            connection.close()
            reservation.close()
        }
    }

    private fun quote(text: String?): String = requireNotNull(text).replace("'", "''")
}

/** A reservation stand-in: its bytes and how often it was closed. */
internal class TestReservation(
    val bytes: Long,
) : AutoCloseable {
    val closes = AtomicInteger()

    override fun close() {
        closes.incrementAndGet()
    }
}
