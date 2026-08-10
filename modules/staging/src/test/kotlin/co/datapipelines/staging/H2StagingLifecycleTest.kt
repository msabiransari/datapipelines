package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * The destruction guarantee (§3.4, §3.5) — the regression test for `DB_CLOSE_DELAY=-1` ever
 * returning. Asserting `isClosed` alone is a false green: with the leaked flag the closed
 * handle would report closed while the in-memory database lived on until JVM exit. The real
 * proof is that a **fresh** connection to the same URL sees an empty database.
 *
 * That proof is only as good as "the same URL" (ST-TEST-4): the test rebuilds the URL from a
 * formula, so a factory that changed its URL would leave this test connecting to a database
 * that never existed — vacuously empty, vacuously green. The before-close probe below pins URL
 * equality first: the staged table must be VISIBLE through the rebuilt URL, and only then does
 * its later absence mean anything.
 */
class H2StagingLifecycleTest {
    private val props = H2StagingProperties()

    @Test
    fun `after close a fresh connection to the same URL sees an empty database`() {
        val executionId = UUID.randomUUID()
        val staging = H2StagingFactory(props).create(executionId)

        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER)")
            src.exec("INSERT INTO t VALUES (1), (2), (3)")
            runBlocking { staging.stage(src.query("SELECT id FROM t"), "stg_t", Dialect.H2) }
        }

        // The staged data really is there before close.
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_t\"") } shouldBe 3L

        // URL equality pinned: a SECOND connection to the rebuilt URL, opened while the instance
        // is alive, must see the staged table. If the factory's URL diverged from this formula
        // this assertion fails here rather than making the post-close emptiness meaningless.
        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { peer ->
            scalarLong(peer, "SELECT COUNT(*) FROM \"stg_t\"") shouldBe 3L
            // The signal this test keys on must be PRESENT before close, or its later absence
            // proves nothing.
            execUserCount(peer) shouldBe 1L
        }

        staging.close()

        staging.readFromStaging { it.isClosed } shouldBe true

        // The database did not survive its last connection closing — keyed on the STAGING_EXEC
        // user, NOT on emptiness (§12). Since §3.4 now drops the tables before closing, a
        // survived-but-emptied database is indistinguishable from a destroyed one by table count:
        // a DB_CLOSE_DELAY=-1 regression would sail through the old assertion. The user is created
        // by the bootstrap and disappears only when the database itself dies, so it is the one
        // observable that still separates the two.
        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { fresh ->
            execUserCount(fresh) shouldBe 0L
            tableCount(fresh) shouldBe 0
        }
    }

    @Test
    fun `close drops the staged tables before the connection goes, proving the belt ran`() {
        val executionId = UUID.randomUUID()
        val staging = H2StagingFactory(props).create(executionId)

        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER)")
            src.exec("INSERT INTO t VALUES (1), (2), (3)")
            runBlocking { staging.stage(src.query("SELECT id FROM t"), "stg_belt", Dialect.H2) }
        }

        // A peer connection held open across close() keeps the in-memory database alive past the
        // instance's own connection close — the only vantage point from which the §3.4 sweep is
        // observable at all. Without it, close() destroys the database and an absent table proves
        // nothing about whether dropStagedTables() ever ran (today it could be a no-op and all
        // other tests would stay green).
        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { peer ->
            scalarLong(peer, "SELECT COUNT(*) FROM \"stg_belt\"") shouldBe 3L

            staging.close()

            // The database really did survive — otherwise the emptiness below is vacuous.
            execUserCount(peer) shouldBe 1L
            // …and the staged table is gone, which only the enumerate-and-DROP sweep can explain.
            tableCount(peer) shouldBe 0
        }
    }

    @Test
    fun `a table parked outside PUBLIC is counted and dropped too`() {
        val executionId = UUID.randomUUID()
        val staging = H2StagingFactory(props).create(executionId)

        // The restricted user holds ALTER ANY SCHEMA, so author SQL can CREATE SCHEMA / SET SCHEMA
        // and land a table outside PUBLIC (verified against the pinned driver). A PUBLIC-only
        // catalog projection would neither count it (§10) nor release it (§3.4 belt).
        runBlocking { staging.execute("CREATE SCHEMA \"side\"") }
        runBlocking { staging.execute("CREATE TABLE \"side\".\"parked\" (\"id\" INTEGER)") }

        runBlocking { staging.stats() }.tableCount shouldBe 1

        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { peer ->
            staging.close()

            execUserCount(peer) shouldBe 1L
            scalarLong(peer, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'side'") shouldBe 0L
        }
    }

    /**
     * The count of the restricted user the factory creates (§9.5) — 1 while the database lives,
     * 0 once it has been destroyed. This is the falsifiable lifetime signal (§12).
     */
    private fun execUserCount(connection: Connection): Long =
        scalarLong(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME = 'STAGING_EXEC'")

    @Test
    fun `a non-default mode reaches the JDBC URL`() {
        val executionId = UUID.randomUUID()
        val regularProps = H2StagingProperties(mode = "REGULAR")
        val staging = H2StagingFactory(regularProps).create(executionId)

        // MODE is a URL parameter, so the only way a peer connection lands in the SAME database
        // is if the factory really used this mode string (a different MODE = a different URL).
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER)")
            src.exec("INSERT INTO t VALUES (5)")
            runBlocking { staging.stage(src.query("SELECT id FROM t"), "stg_mode", Dialect.H2) }
        }
        DriverManager.getConnection(stagingUrl(executionId, regularProps), "sa", "").use { peer ->
            scalarLong(peer, "SELECT COUNT(*) FROM \"stg_mode\"") shouldBe 1L
        }

        staging.close()
    }

    @Test
    fun `an unopenable database fails with the catalogued creation_failed code`() {
        // "NoSuchModeXyz" passes the SAFE_MODE shape check but H2 rejects it at connect time
        // (verified against the pinned driver: SQLState 90088, 'Unknown mode'). The factory must
        // wrap that as pipeline.staging.creation_failed, not leak a raw SQLException (§3.1).
        val executionId = UUID.randomUUID()
        val thrown =
            shouldThrow<DatapipelinesException> {
                H2StagingFactory(H2StagingProperties(mode = "NoSuchModeXyz")).create(executionId)
            }

        thrown.code shouldBe StagingErrorCodes.CREATION_FAILED
        thrown.details["execution_id"] shouldBe executionId.toString()
        thrown.message shouldContain executionId.toString()
    }

    @Test
    fun `a failure creating the restricted user never leaks its password`() {
        val executionId = UUID.randomUUID()

        // Force the one failure path whose driver message carries the cleartext credential: hold
        // the database open with STAGING_EXEC already present, so the factory's CREATE USER
        // collides and H2 appends the failing `CREATE USER … PASSWORD '<hex>'` to its message.
        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { squatter ->
            squatter.createStatement().use { it.execute("CREATE USER STAGING_EXEC PASSWORD 'squatter'") }

            val thrown = shouldThrow<DatapipelinesException> { H2StagingFactory(props).create(executionId) }

            thrown.code shouldBe StagingErrorCodes.CREATION_FAILED
            // Everything the caller and the logs can see: the message, the details, and the whole
            // cause chain — a scrubbed message would still leak through an unscrubbed cause.
            val visible =
                buildString {
                    append(thrown.message).append(thrown.details)
                    generateSequence(thrown.cause) { it.cause }.forEach { append(it.message) }
                }
            visible shouldNotContain "PASSWORD"
            // Belt: no 256-bit hex run anywhere, whatever wording H2 or we use around it.
            Regex("[0-9a-f]{64}").containsMatchIn(visible) shouldBe false
            // …and it is still diagnosable — the SQLState survives.
            visible shouldContain "SQLState"
        }
    }

    @Test
    fun `close on an already-broken connection does not throw`() {
        val staging = H2StagingFactory(props).create(UUID.randomUUID())
        // Break it out from under the instance, then close() from the executor's finally must
        // still not throw — it may only log (§3.4).
        staging.readFromStaging { it.close() }

        staging.close()
    }

    @Test
    fun `close is idempotent`() {
        val staging = H2StagingFactory(props).create(UUID.randomUUID())
        staging.close()
        staging.close()
    }
}
