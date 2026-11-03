package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException

/**
 * The DuckDB twin of `H2ConnectionPoolTest`'s "a dirtied session … nothing leaked" (staging.md §8.5
 * Leases, §9.2), on the real driver. H2 recycles a connection and sanitizes it; DuckDB has nothing
 * to sanitize because each lease is a fresh `duplicate()` closed on return, and a `RESET` would be
 * refused by the configuration lock anyway. What one lease leaves in its session — a variable, a
 * temporary table and macro, a prepared statement, the current schema, an open transaction — is
 * gone on the next lease, even at `max-connections = 1` where the next lease takes the same slot.
 * What the lease wrote to the DATABASE (a schema) stays, as a staged table does: cross-node state
 * is ordinary database objects plus `depends_on`, never a session.
 */
class DuckDbStagingSessionStateTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `session state left by one lease never reaches the next, and database objects stay`() {
        val staging = duckFactory(scratch, maxConnections = 1).open()
        staging.authorExec("CREATE TABLE kept (x INTEGER)")
        runBlocking {
            staging.withConnection { c ->
                c.createStatement().use { st ->
                    st.execute("SET VARIABLE leaked = 42")
                    st.execute("CREATE TEMP TABLE tmp_leak AS SELECT 1 AS x")
                    st.execute("CREATE TEMP MACRO leak_macro() AS 7")
                    st.execute("PREPARE leak_stmt AS SELECT 1")
                    st.execute("CREATE SCHEMA other")
                    st.execute("USE other")
                    st.execute("BEGIN TRANSACTION")
                    st.execute("INSERT INTO main.kept VALUES (1)")
                }
                // Non-vacuity: the dirt is really there inside the lease that made it.
                c.createStatement().use { st ->
                    st.executeQuery("SELECT getvariable('leaked'), current_schema(), (SELECT count(*) FROM main.kept)").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 42
                        rs.getString(2) shouldBe "other"
                        rs.getInt(3) shouldBe 1
                    }
                }
            }
        }

        staging.scalarText("SELECT getvariable('leaked')") shouldBe null
        staging.scalarText("SELECT current_schema()") shouldBe "main"
        staging.scalarText("SELECT count(*) FROM duckdb_tables() WHERE temporary") shouldBe "0"
        staging.scalarText("SELECT count(*) FROM duckdb_functions() WHERE function_name = 'leak_macro'") shouldBe "0"
        shouldThrow<SQLException> { staging.authorExec("EXECUTE leak_stmt") }.message shouldContain "leak_stmt"
        // The open transaction was discarded with its connection, never committed.
        staging.scalarText("SELECT count(*) FROM main.kept") shouldBe "0"
        // A schema is database state, not session state: it stays for the next node.
        val schemas = "SELECT count(*) FROM duckdb_schemas() WHERE schema_name = 'other' AND database_name = current_database()"
        staging.scalarText(schemas) shouldBe "1"
        staging.close()
    }
}
