package co.datapipelines.lake

import co.datapipelines.lake.append.ParquetWriterHarness
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.TimeUnit

/** Spill files are active runtime output, so another task must never snapshot them as repository content. */
class LakeTestDuckDbTest {
    @Test
    fun `an external sort spills only inside its owned build directory`() {
        val directory =
            LakeTestDuckDb.open().use { database ->
                val directory = database.directory
                directory.startsWith(Path.of("build").toAbsolutePath().normalize()) shouldBe true
                database.connection.createStatement().use { statement ->
                    val configured =
                        statement.executeQuery("SELECT current_setting('temp_directory')").use { rows ->
                            rows.next() shouldBe true
                            Path.of(rows.getString(1)).toAbsolutePath().normalize()
                        }
                    configured shouldBe directory
                    statement.execute("SET memory_limit = '32MB'")
                    statement.execute("SET threads = 1")
                    statement.queryTimeout = SORT_TIMEOUT_SECONDS
                    assertSpill(statement, directory)
                }
                directory
            }

        Files.exists(directory) shouldBe false
    }

    @Test
    fun `a statement failure closes the connection and removes its spill directory`() {
        val database = LakeTestDuckDb.open()

        shouldThrow<SQLException> {
            database.use { it.connection.createStatement().use { statement -> statement.executeQuery("SELECT missing_column") } }
        }

        database.connection.isClosed shouldBe true
        Files.exists(database.directory) shouldBe false
        database.close()
    }

    @Test
    fun `an initialization failure closes the connection and removes its owned directory`() {
        var connection: Connection? = null
        var directory: Path? = null

        shouldThrow<IllegalStateException> {
            LakeTestDuckDb.open { opened ->
                connection = opened
                directory =
                    opened.createStatement().use { statement ->
                        statement.executeQuery("SELECT current_setting('temp_directory')").use { rows ->
                            rows.next() shouldBe true
                            Path.of(rows.getString(1))
                        }
                    }
                error("fixture initialization failed")
            }
        }

        requireNotNull(connection).isClosed shouldBe true
        Files.exists(requireNotNull(directory)) shouldBe false
    }

    @Test
    fun `the append fixture configures the owned directory before disabling external access`() {
        var directory: Path? = null
        ParquetWriterHarness().withInput("SELECT current_setting('temp_directory') AS spill_path") { input ->
            input.scan(emptyList()) { rows ->
                rows.next() shouldBe true
                directory = Path.of(rows.getString(1)).toAbsolutePath().normalize()
                requireNotNull(directory).startsWith(Path.of("build").toAbsolutePath().normalize()) shouldBe true
                Files.isDirectory(requireNotNull(directory)) shouldBe true
            }
        }

        Files.exists(requireNotNull(directory)) shouldBe false
    }

    private fun assertSpill(
        statement: Statement,
        directory: Path,
    ) {
        FileSystems.getDefault().newWatchService().use { watcher ->
            directory.register(watcher, ENTRY_CREATE)
            statement.executeQuery("SELECT i FROM range(10000000) AS t(i) ORDER BY hash(i)").use { rows ->
                rows.next() shouldBe true
            }
            val key = requireNotNull(watcher.poll(WATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the sort did not spill" }
            key
                .pollEvents()
                .any { it.kind() == ENTRY_CREATE && it.context().toString().startsWith("duckdb_temp_storage_") }
                .shouldBe(true)
        }
    }

    private companion object {
        const val SORT_TIMEOUT_SECONDS = 30
        const val WATCH_TIMEOUT_SECONDS = 5L
    }
}
