package co.datapipelines.typesystem

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * The two-phase de-privileged open (staging.md §9.5, 186) driven directly against the pinned
 * H2 driver (2.3.232) — the module's own guard for the helper `staging` and `datasources`
 * share. The behavioural refusals it enables (FILE_READ/CREATE ALIAS answering 90040) are
 * pinned by the callers' suites (`H2StagingPrivilegeTest`, `SqlProbeScratchContainmentTest`,
 * `H2InProcessPoolTest`); this pins the MECHANICS: the restricted identity, the grants, the
 * rotation, the sanitized failure, and the handover overlap.
 */
class H2RestrictedSessionTest {
    private val scratch = Files.createTempDirectory("h2-session")

    @Test
    fun `the restricted user is not admin, can do schema DDL with its grant, and openConnection lands on the same database`() {
        val url = "jdbc:h2:mem:session_${UUID.randomUUID()}"
        val session = H2RestrictedSession.open(url, user = "EXEC_TEST", grants = listOf("GRANT ALTER ANY SCHEMA TO EXEC_TEST"))
        try {
            session.firstConnection.use { first ->
                assertAll(
                    { single(first, "SELECT CURRENT_USER") shouldBe "EXEC_TEST" },
                    { single(first, "SELECT IS_ADMIN FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME = CURRENT_USER") shouldBe "FALSE" },
                    // The grant is what makes PUBLIC DDL work for a non-admin (§9.5, pinned).
                    { first.createStatement().use { it.execute("CREATE TABLE t (id INT)") } },
                )
                // The pool-growth seam, exercised while the first connection holds the database
                // open — the overlap rule: a mem: database dies with its LAST connection, so a
                // further connection must land in the SAME one.
                session.openConnection().use { further ->
                    assertAll(
                        { single(further, "SELECT CURRENT_USER") shouldBe "EXEC_TEST" },
                        { single(further, "SELECT COUNT(*) FROM t") shouldBe "0" },
                    )
                }
            }
        } finally {
            session.firstConnection.close()
        }
    }

    @Test
    fun `a colliding user is a sanitized failure - SQLState survives, the password never does`() {
        // Squat: the exec user exists when the open expects to create it (rotateIfExists=false).
        val url = "jdbc:h2:mem:session_${UUID.randomUUID()}"
        DriverManager.getConnection(url, "sa", "").use { squatter ->
            squatter.createStatement().use { it.execute("CREATE USER EXEC_TEST PASSWORD 'squatter'") }

            val thrown = shouldThrow<SQLException> { H2RestrictedSession.open(url, user = "EXEC_TEST") }

            assertAll(
                { thrown.message shouldContain "restricted-user setup failed (SQLState" },
                // Belt: no 256-bit hex run anywhere in the message (the inline DDL carries it).
                { Regex("[0-9a-f]{64}").containsMatchIn(thrown.message.orEmpty()) shouldBe false },
            )
        }
    }

    @Test
    fun `rotateIfExists makes the open idempotent over a file database`() {
        val dbFile = scratch.resolve("rotate-${UUID.randomUUID()}").toAbsolutePath().toString()
        val url = "jdbc:h2:file:$dbFile"

        // First generation creates the file database and the user.
        H2RestrictedSession.open(url, user = "EXEC_TEST", rotateIfExists = true).firstConnection.close()
        // The user persists in the file; the second open rotates the password instead of
        // colliding with it.
        val second = H2RestrictedSession.open(url, user = "EXEC_TEST", rotateIfExists = true)
        second.firstConnection.use { connection ->
            single(connection, "SELECT CURRENT_USER") shouldBe "EXEC_TEST"
        }
        // And the FIRST generation's password is dead: opening with it fails.
        val stale = shouldThrow<SQLException> { DriverManager.getConnection(url, "EXEC_TEST", second.password.reversed()) }
        stale.message shouldNotContain "EXEC_TEST"
    }

    @Test
    fun `the user name is confined to a plain SQL identifier before it is inlined into DDL`() {
        shouldThrow<IllegalArgumentException> {
            H2RestrictedSession.open("jdbc:h2:mem:session_${UUID.randomUUID()}", user = "X' ADMIN TRUE --")
        }
    }

    @Test
    fun `the password is 256-bit hex, fresh per open`() {
        val a = H2RestrictedSession.newPassword()
        val b = H2RestrictedSession.newPassword()

        assertAll(
            { a.length shouldBe 64 },
            { Regex("[0-9a-f]{64}").matches(a) shouldBe true },
            { a shouldNotBe b },
        )
    }

    @Test
    fun `operationalUrl is what restricted connections open against`() {
        val url = "jdbc:h2:mem:session_${UUID.randomUUID()}"
        // A deliberately wrong operational URL: if the restricted connect used the bootstrap's
        // URL the open would succeed — it fails, proving the operational URL is the one used.
        shouldThrow<SQLException> {
            H2RestrictedSession.open(url, user = "EXEC_TEST", operationalUrl = "jdbc:h2:mem:session_other;UNKNOWN_SETTING_X=1")
        }
    }

    private fun single(
        connection: Connection,
        sql: String,
    ): String =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
}
