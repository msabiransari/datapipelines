package co.datapipelines.lake

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Owns a fixture connection and its spill directory under ignored build output, never repository inputs. */
internal class LakeTestDuckDb private constructor(
    val connection: Connection,
    val directory: Path,
) : AutoCloseable {
    override fun close() {
        connection.close()
        remove(directory)
    }

    companion object {
        @Suppress("TooGenericExceptionCaught") // Initialization failures preserve their cause and close both owned resources.
        fun open(initialize: (Connection) -> Unit = {}): LakeTestDuckDb {
            val root = Files.createDirectories(Path.of("build", "duckdb-fixtures"))
            val directory = Files.createTempDirectory(root, "spill-").toAbsolutePath().normalize()
            var connection: Connection? = null
            try {
                connection = DriverManager.getConnection("jdbc:duckdb:")
                connection.createStatement().use { statement ->
                    val escaped = directory.toString().replace("'", "''")
                    statement.execute("SET temp_directory = '$escaped'")
                }
                initialize(connection)
                return LakeTestDuckDb(connection, directory)
            } catch (failure: Exception) {
                runCatching { connection?.close() }.onFailure(failure::addSuppressed)
                runCatching { remove(directory) }.onFailure(failure::addSuppressed)
                throw failure
            }
        }

        private fun remove(directory: Path) {
            if (!Files.exists(directory)) return
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
