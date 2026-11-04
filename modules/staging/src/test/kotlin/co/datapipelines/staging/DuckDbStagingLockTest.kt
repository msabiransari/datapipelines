package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.duckdb.DuckDBConnection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException

/**
 * The author connection is locked BEFORE it is handed out (dp-lake design §15a, staging.md §8.5):
 * external access off and the configuration locked, so author SQL can neither reach a file nor undo
 * either setting. Every refusal is asserted on the ENGINE's reason, not merely "some error" — a
 * `read_parquet` of a non-Parquet file fails either way, and only the permission text proves the
 * file was never opened.
 *
 * Falsified at birth (handback): with the two lock statements and the read-back removed from the
 * factory, every refusal case here goes red; restored, green.
 */
class DuckDbStagingLockTest {
    @TempDir
    lateinit var scratch: Path

    private lateinit var staging: DuckDbStaging

    @BeforeEach
    fun open() {
        staging = duckFactory(scratch).open()
    }

    @AfterEach
    fun close() {
        staging.close()
    }

    private fun refusal(sql: String): SQLException = shouldThrow<SQLException> { staging.authorExec(sql) }

    @Test
    fun `duckdb_settings on an author connection reads the locked bounds`() {
        val settings =
            runBlocking {
                staging.withConnection { c ->
                    c.createStatement().use { st ->
                        st
                            .executeQuery(
                                "SELECT name, value FROM duckdb_settings() " +
                                    "WHERE name IN ('lock_configuration', 'enable_external_access', " +
                                    "'allow_persistent_secrets', 'autoinstall_known_extensions', 'autoload_known_extensions', " +
                                    "'allow_community_extensions', 'threads', 'TimeZone')",
                            ).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
                    }
                }
            }
        settings["lock_configuration"] shouldBe "true"
        settings["enable_external_access"] shouldBe "false"
        settings["allow_persistent_secrets"] shouldBe "false"
        settings["autoinstall_known_extensions"] shouldBe "false"
        settings["autoload_known_extensions"] shouldBe "false"
        settings["allow_community_extensions"] shouldBe "false"
        settings["threads"] shouldBe "2"
        settings["TimeZone"] shouldBe "UTC"
        staging.scalarText("SELECT current_setting('memory_limit')") shouldBe "256.0 MiB"
    }

    @Test
    fun `read_parquet of a host file is refused for want of file system access`() {
        refusal("SELECT * FROM read_parquet('/etc/hostname')").message shouldContain FILE_SYSTEM_DISABLED
    }

    @Test
    fun `ATTACH of a database file is refused`() {
        val target = scratch.resolve("attached.duckdb")
        refusal("ATTACH '$target' AS other").message shouldContain FILE_SYSTEM_DISABLED
        Files.exists(target) shouldBe false
    }

    @Test
    fun `INSTALL of an extension is refused`() {
        refusal("INSTALL json").message shouldContain FILE_SYSTEM_DISABLED
    }

    @Test
    fun `COPY TO a host path is refused and writes nothing`() {
        val target = scratch.resolve("exfil.csv")
        staging.authorExec("CREATE TABLE t AS SELECT 1 AS id")
        refusal("COPY t TO '$target'").message shouldContain FILE_SYSTEM_DISABLED
        Files.exists(target) shouldBe false
    }

    @Test
    fun `SET enable_external_access back on is refused by the lock`() {
        refusal("SET enable_external_access = true").message shouldContain locked("enable_external_access")
        staging.scalarText("SELECT current_setting('enable_external_access')") shouldBe "false"
    }

    @Test
    fun `SET memory_limit is refused by the lock`() {
        refusal("SET memory_limit = '64GB'").message shouldContain locked("memory_limit")
        staging.scalarText("SELECT current_setting('memory_limit')") shouldBe "256.0 MiB"
    }

    @Test
    fun `the lock cannot be lifted by SET or RESET, and no other setting moves`() {
        refusal("SET lock_configuration = false").message shouldContain locked("lock_configuration")
        refusal("RESET lock_configuration").message shouldContain locked("lock_configuration")
        refusal("SET max_temp_directory_size = '1TB'").message shouldContain locked("max_temp_directory_size")
        refusal("SET threads = 64").message shouldContain locked("threads")
        refusal("SET GLOBAL TimeZone = 'Asia/Karachi'").message shouldContain locked("TimeZone")
    }

    @Test
    fun `a refused SET keeps its own error, not a staging code`() {
        // Classifier guard: the locked SET names max_temp_directory_size, and must NOT read as a spill.
        val e = shouldThrow<SQLException> { runBlocking { staging.execute("SET max_temp_directory_size = '1TB'") } }
        e.message shouldContain locked("max_temp_directory_size")
    }

    @Test
    fun `the author connection exposes no driver connection, call or root`() {
        runBlocking {
            staging.withConnection { c ->
                shouldThrow<SQLException> { c.unwrap(DuckDBConnection::class.java) }
                c.isWrapperFor(DuckDBConnection::class.java) shouldBe false
                shouldThrow<SQLFeatureNotSupportedException> { c.prepareCall("CALL pragma_version()") }
                // Every route back to a connection returns the guarded one, never the driver's.
                c.createStatement().use { st -> (st.connection === c) shouldBe true }
                c.prepareStatement("SELECT 1").use { ps -> (ps.connection === c) shouldBe true }
                (c.metaData.connection === c) shouldBe true
            }
        }
    }

    private companion object {
        const val FILE_SYSTEM_DISABLED = "file system operations are disabled by configuration"

        fun locked(option: String) = "Cannot change configuration option \"$option\" - the configuration has been locked"
    }
}
