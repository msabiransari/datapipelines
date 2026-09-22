package co.datapipelines.datasources

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.DriverManager
import java.util.UUID

/**
 * The #186 containment guard for the `tempdb` scratch engine ([SqlProbe.probeScratch]): the
 * scratch session is a **grantless non-admin** user (staging.md §9.5's two-phase shape, shared
 * through `H2RestrictedSession`), so the host-reaching functions the classifier's SELECT-only
 * gate cannot see — `FILE_READ`, `CSVREAD` are ordinary SELECT-shaped calls — are refused by H2
 * itself with SQLState `90040`.
 *
 * **This test must be able to fail.** Reverting `probeScratch` to a bare
 * `DriverManager.getConnection(url)` opens the scratch as `sa` — the first user of a fresh
 * in-memory H2 is its admin — and every refusal below becomes a successful file read: the
 * control test proves exactly that on an admin session, so a reversion turns the suite red.
 *
 * The file being read is a temp file this test writes, carrying a random token — never
 * `/proc/self/environ` or any real secret (186's brief: the exploit SQL lives in the tests, and
 * the target is a file the test owns).
 */
class SqlProbeScratchContainmentTest {
    private val probe = SqlProbe(mockk<DatasourceRegistry>())

    @Test
    fun `FILE_READ of a host file is refused as not-permitted, and the content never reaches the error`() {
        val token = "probe-token-${UUID.randomUUID()}"
        val secret = kotlin.io.path.createTempFile("probe-scratch", ".txt")
        try {
            secret.toFile().writeText(token)

            val thrown =
                shouldThrow<SqlProbeExecutionException> {
                    probe.probeScratch("SELECT FILE_READ('${secret.toAbsolutePath()}', 'UTF-8')")
                }

            assertAll(
                // The wire mapping keys on this predicate (SqlProbeTool.probing): 90040 → the
                // existing not-permitted answer (`datasource.table_forbidden`), never a 500.
                { (thrown.cause as? java.sql.SQLException)?.isPermissionDenied() shouldBe true },
                { (thrown.cause as? java.sql.SQLException)?.sqlState shouldBe "90040" },
                { thrown.driverMessage shouldNotContain token },
            )
        } finally {
            secret.toFile().delete()
        }
    }

    @Test
    fun `CSVREAD of a host file is refused the same way`() {
        val token = "probe-token-${UUID.randomUUID()}"
        val secret = kotlin.io.path.createTempFile("probe-scratch", ".csv")
        try {
            secret.toFile().writeText("col\n$token")

            val thrown =
                shouldThrow<SqlProbeExecutionException> {
                    probe.probeScratch("SELECT * FROM CSVREAD('${secret.toAbsolutePath()}')")
                }

            assertAll(
                { (thrown.cause as? java.sql.SQLException)?.isPermissionDenied() shouldBe true },
                { thrown.driverMessage shouldNotContain token },
            )
        } finally {
            secret.toFile().delete()
        }
    }

    @Test
    fun `CREATE ALIAS never reaches the engine - the classifier admits only SELECT and WITH`() {
        shouldThrow<SqlProbeRefusalException> {
            probe.probeScratch("CREATE ALIAS GET_PROP FOR 'java.lang.System.getProperty(java.lang.String)'")
        }
    }

    @Test
    fun `the grantless scratch user still runs what a probe exists for`() {
        // The other half of the trade: containment that broke the scratch check would be no fix.
        val outcome = probe.probeScratch("SELECT t.hr FROM (VALUES (0),(1),(2)) AS t(hr) ORDER BY t.hr")

        (outcome as ScratchProbeOutcome.Rows)
            .result.rows.rows.size shouldBe 3
    }

    @Test
    fun `the very same FILE_READ succeeds on an admin session - the control that makes the guard bite`() {
        // Guard the guard. Every refusal above would also "pass" if the SQL were merely
        // malformed, or if some later H2 refused these functions for everyone — neither would
        // mean the containment works. This runs the identical statement on an `sa` connection
        // and shows it SUCCEEDS: reading a server file the process can see is exactly the
        // capability the restricted scratch user takes away.
        val token = "probe-token-${UUID.randomUUID()}"
        val secret = kotlin.io.path.createTempFile("probe-scratch", ".txt")
        try {
            secret.toFile().writeText(token)
            readAsAdmin("SELECT FILE_READ('${secret.toAbsolutePath()}', 'UTF-8')") shouldContain token
        } finally {
            secret.toFile().delete()
        }
    }

    /** Runs [sql] on a throwaway `sa` session — the privilege level the scratch used to run as. */
    private fun readAsAdmin(sql: String): String =
        DriverManager.getConnection("jdbc:h2:mem:probe_control_${UUID.randomUUID()}", "sa", "").use { admin ->
            admin.createStatement().use { st ->
                st.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        }
}
