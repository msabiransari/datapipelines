package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * The §9.5 containment guard: author SQL runs **de-privileged**.
 *
 * `withQuery`/`execute` run a pipeline author's rendered template body. On an `sa` (admin)
 * session H2's admin surface reaches the host — `SELECT FILE_READ('/proc/self/environ')` returns
 * `DATAPIPELINES_DB_ENCRYPTION_KEY` and `DATAPIPELINES_JWT_SECRET`, and `CREATE ALIAS` loads any
 * JVM class — which escalates `author` to every datasource credential plus session forgery. The
 * factory therefore bootstraps with a transient `sa` connection and hands the module a
 * **non-admin** operational connection.
 *
 * **This test must be able to fail.** Every assertion below goes through the ordinary public
 * surface, so reverting the factory to `sa` — or granting the exec user admin, or a driver
 * upgrade that un-gates one of these functions for non-admin users — turns the refusals into
 * successes and fails the test rather than silently reopening the escalation. The SQLState
 * asserted (`90040`, "Admin rights are required") was read off the pinned driver (2.3.232), not
 * recalled.
 */
class H2StagingPrivilegeTest {
    private val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())

    @AfterEach
    fun tearDown() = staging.close()

    // ---------- the operational identity ----------

    @Test
    fun `the operational connection is not an admin session`() {
        val isAdmin =
            staging.readFromStaging { connection ->
                connection.createStatement().use { st ->
                    st
                        .executeQuery("SELECT IS_ADMIN FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME = CURRENT_USER")
                        .use { rows ->
                            rows.next()
                            rows.getBoolean(1)
                        }
                }
            }

        isAdmin shouldBe false
        // …and it is not the bootstrap identity, which is what would make every refusal below
        // evaporate. The bootstrap connection was closed before this instance was handed over.
        currentUser() shouldNotBe "SA"
    }

    // ---------- the host-reaching surface, through the author's own entry points ----------

    @Test
    fun `FILE_READ is refused, so author SQL cannot read the process environment`() {
        // The literal escalation from the threat model, run exactly the way an author would.
        refusedAsQuery("SELECT FILE_READ('/proc/self/environ', NULL)")
    }

    @Test
    fun `CSVWRITE is refused, so author SQL cannot write server files`() {
        refusedAsStatement("CALL CSVWRITE('/tmp/staging-privilege-probe.csv', 'SELECT 1')")
    }

    @Test
    fun `CREATE ALIAS is refused, so author SQL cannot load a JVM class`() {
        refusedAsStatement("CREATE ALIAS GET_PROPERTY FOR 'java.lang.System.getProperty(java.lang.String)'")
    }

    @Test
    fun `the remaining host-reaching functions are refused too`() {
        // The rest of the §9.5 surface, asserted as one set so a driver upgrade that un-gates any
        // single one of them cannot slip through on the strength of the three named above. Each
        // goes through the entry point its shape would really use (see the helpers).
        refusedAsQuery("SELECT FILE_WRITE(X'00', '/tmp/staging-privilege-probe.bin')")
        refusedAsQuery("SELECT * FROM CSVREAD('/etc/hosts')")
        refusedAsQuery("CALL LINK_SCHEMA('L', '', 'jdbc:h2:mem:other', 'sa', '', 'PUBLIC')")
        refusedAsStatement("RUNSCRIPT FROM '/tmp/staging-privilege-no-such.sql'")
    }

    @Test
    fun `the class-loading, server-property and connection-opening routes are refused too`() {
        // These three carry the weight of two acceptances: §9.5's class-loading claim, and the
        // decision to leave `sa` on an empty password — safe only while author SQL cannot open a
        // connection. All are admin-gated in 2.3.232; asserted so a driver upgrade that un-gates
        // one fails here instead of silently reopening the route.
        runBlocking { staging.execute("CREATE TABLE \"stg_trigger_host\" (\"id\" INTEGER)") }

        // CREATE TRIGGER … CALL loads a JVM class, exactly as CREATE ALIAS does.
        refusedAsStatement("CREATE TRIGGER \"trg\" BEFORE INSERT ON \"stg_trigger_host\" CALL 'com.example.NoSuch'")
        // A server-property SET — the route that could put DB_CLOSE_DELAY back and defeat §3.4.
        refusedAsStatement("SET DB_CLOSE_DELAY -1")
        // A connection-opening route distinct from LINK_SCHEMA: the one that would let author SQL
        // reconnect to its own database as `sa` and undo the containment from inside.
        refusedAsStatement(
            "CREATE LINKED TABLE \"lt\"('org.h2.Driver', 'jdbc:h2:mem:other', 'sa', '', 'PUBLIC', 'X')",
        )
    }

    @Test
    fun `the exec user cannot grant itself admin or mint another user`() {
        // If either of these worked the containment would be one statement deep.
        refusedAsStatement("ALTER USER STAGING_EXEC ADMIN TRUE")
        refusedAsStatement("CREATE USER ESCALATED PASSWORD 'x'")
    }

    // ---------- what the staging layer itself still needs, under the same user ----------

    @Test
    fun `everything the staging layer needs is available to the restricted user`() {
        // The other half of the trade: containment that also broke staging would be no fix. This
        // exercises the full round trip — DDL, insert, catalog read, accounting, cleanup — as the
        // non-admin user, so a grant that is too tight fails here rather than in production.
        val staged =
            SourceDb().use { src ->
                src.exec("CREATE TABLE t (id INTEGER, label VARCHAR(20))")
                src.exec("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
                runBlocking { staging.stage(src.query("SELECT id, label FROM t"), "stg_priv", Dialect.H2) }
            }
        staged.rowsStaged shouldBe 2L

        runBlocking { staging.execute("INSERT INTO \"stg_priv\" VALUES (3, 'c')") } shouldBe 1L
        runBlocking {
            staging.withQuery("SELECT COUNT(*) FROM \"stg_priv\"") { rows ->
                rows.next()
                rows.getLong(1)
            }
        } shouldBe 3L

        val stats = runBlocking { staging.stats() }
        stats.tableCount shouldBe 1
        stats.totalRows shouldBe 2L
        // The §8.2 reading still works without MEMORY_USED(), which this user cannot call.
        (stats.memoryUsedBytes > 0L) shouldBe true

        // And the §3.4 cleanup sweep — DROP TABLE per table, since DROP ALL OBJECTS is admin-only.
        staging.readFromStaging { connection ->
            connection.createStatement().use { it.execute("DROP TABLE \"stg_priv\" CASCADE") }
        }
        runBlocking { staging.stats() }.tableCount shouldBe 0
    }

    @Test
    fun `MEMORY_USED is confirmed admin-gated, which is why accounting reads the heap in-process`() {
        // Pins the premise of §8.2's in-process reading. If a future H2 un-gates MEMORY_USED for
        // non-admin users this fails, and the substitution can be revisited on evidence rather
        // than left as folklore.
        refusedAsQuery("SELECT MEMORY_USED()")
        refusedAsStatement("DROP ALL OBJECTS")
    }

    // ---------- the control: the same SQL succeeds on an admin session ----------

    @Test
    fun `the very same FILE_READ succeeds on an admin session, which is what makes this guard bite`() {
        // Guard the guard. Every refusal above would also "pass" if the SQL were merely malformed,
        // or if some later H2 rejected these functions for everyone — neither of which would mean
        // the containment works. This runs the identical statement on an `sa` connection to a
        // throwaway database and shows it SUCCEEDS: reading a server file the process can see is
        // exactly the capability §9.5 takes away, so the refusals above are about privilege and
        // nothing else. If the factory is ever reverted to `sa`, the tests above start behaving
        // like this one and fail.
        val secret = Files.createTempFile("staging-privilege", ".txt")
        val readItBack = "SELECT FILE_READ('${secret.absolutePathString()}', 'UTF-8')"
        try {
            secret.writeText(SECRET_MARKER)
            // An admin staging session reads host files. That is the escalation, demonstrated.
            asAdmin(readItBack) shouldBe SECRET_MARKER
        } finally {
            secret.deleteIfExists()
        }

        // …and the operational connection cannot read that same file.
        refusedAsQuery(readItBack)
    }

    /** Runs [sql] on a throwaway `sa` session — the privilege level §9.5 exists to give up. */
    private fun asAdmin(sql: String): String =
        DriverManager.getConnection("jdbc:h2:mem:privilege_control", "sa", "").use { admin ->
            admin.createStatement().use { st ->
                st.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        }

    // ---------- helpers ----------

    /*
     * Each probe runs through the entry point its statement shape would really use, because the
     * driver's checks are ordered: a result-set statement sent to `execute()` (JDBC
     * `executeUpdate`) is rejected with SQLState 90001 "not allowed for a query" BEFORE the
     * privilege check ever runs. Asserting refusal on the wrong entry point would therefore pass
     * for a reason that has nothing to do with privileges — and would keep passing if the user
     * were reverted to `sa`.
     */

    /** Refusal for author SQL that yields rows — the `withQuery` path (§3.3). */
    private fun refusedAsQuery(sql: String) {
        val thrown = shouldThrow<SQLException> { runBlocking { staging.withQuery(sql) { rows -> rows.next() } } }

        thrown.sqlState shouldBe ADMIN_RIGHTS_REQUIRED
    }

    /** Refusal for author SQL that is DDL or an update — the `execute` path (§10). */
    private fun refusedAsStatement(sql: String) {
        val thrown = shouldThrow<SQLException> { runBlocking { staging.execute(sql) } }

        thrown.sqlState shouldBe ADMIN_RIGHTS_REQUIRED
    }

    private fun currentUser(): String =
        staging.readFromStaging { connection ->
            connection.createStatement().use { st ->
                st.executeQuery("SELECT CURRENT_USER").use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        }

    private companion object {
        /** H2 2.3.232's SQLState for "Admin rights are required for this operation". */
        const val ADMIN_RIGHTS_REQUIRED = "90040"

        /** Stand-in for the real prize — the env file holding the encryption key and JWT secret. */
        const val SECRET_MARKER = "DATAPIPELINES_DB_ENCRYPTION_KEY=not-a-real-key"
    }
}
