package co.datapipelines.executor.measure

import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * 108 §1 — **does the driver actually honour `Statement.queryTimeout`, per statement kind?**
 *
 * The round exists because T189 reported that a Postgres node was killed at 60 s as designed
 * while H2 tempdb nodes in the same pipeline ran 168, 191 and 330 s past the same budget. Two
 * explanations fit that: the executor never set a timeout on those statements, or it set one the
 * driver ignored. They call for completely different fixes, and neither can be chosen by reading
 * code — H2 checks cancellation at row boundaries of *certain operators*, and which operators is
 * not documented anywhere but the source.
 *
 * So each statement kind the runner issues gets a 1-second budget and a workload that would run
 * for minutes, and the elapsed time is the answer. A row that comes back at ~1 s means the driver
 * honoured it; a row that comes back at the workload's natural runtime means it did not, and that
 * node had NO bound before this round's wall-clock deadline.
 *
 * Reports, never asserts: gated on `DP_MEASURE=1` and skipped in an ordinary gate. A measurement
 * that fails a build is a measurement people start deleting.
 */
@EnabledIfEnvironmentVariable(named = "DP_MEASURE", matches = "1")
class DriverTimeoutMeasurement {
    @Test
    fun `each tempdb statement kind against a one-second queryTimeout`() {
        val rows = mutableListOf<Row>()
        val staging = H2StagingFactory(H2StagingProperties(queryTimeoutSeconds = BUDGET_SECONDS)).create(UUID.randomUUID())
        try {
            runBlocking {
                staging.withConnection { conn ->
                    rows +=
                        measure("tempdbCursor (SELECT)") {
                            conn.timed { st ->
                                st.executeQuery(SLOW_SELECT).use { rs ->
                                    var n = 0
                                    while (rs.next()) n++
                                }
                            }
                        }
                    rows += measure("tempdbCreateTableAs (CTAS)") { conn.timed { st -> st.execute("CREATE TABLE m_ctas AS $SLOW_SELECT") } }
                    conn.createStatement().use { it.execute("CREATE TABLE m_dml (n BIGINT)") }
                    rows +=
                        measure("tempdbDml (INSERT … SELECT)") { conn.timed { st -> st.executeUpdate("INSERT INTO m_dml $SLOW_SELECT") } }
                    rows +=
                        measure("tempdbDml (DELETE)") {
                            conn.timed { st ->
                                st.executeUpdate("DELETE FROM m_dml WHERE n IN ($SLOW_SELECT)")
                            }
                        }
                }
            }
        } finally {
            staging.close()
        }
        report("H2 ${H2_VERSION_NOTE}: statement kind vs a ${BUDGET_SECONDS}s queryTimeout", rows)
    }

    /**
     * The staging INSERT path — `H2Staging.stage`'s batched `executeBatch`, which sets NO
     * `queryTimeout` at all (staging §4.3; the executor's timeout is on the SOURCE statement).
     *
     * That is the measurement's point: this path is bounded by the node's wall-clock deadline and
     * by nothing else, which is precisely why the deadline had to exist.
     */
    @Test
    fun `the staging INSERT path carries no statement timeout`() {
        val staging = H2StagingFactory(H2StagingProperties(queryTimeoutSeconds = BUDGET_SECONDS)).create(UUID.randomUUID())
        val rows = mutableListOf<Row>()
        try {
            runBlocking {
                staging.withConnection { conn ->
                    conn.createStatement().use { it.execute("CREATE TABLE m_ins (n BIGINT)") }
                    val declared =
                        conn.prepareStatement("INSERT INTO m_ins VALUES (?)").use { it.queryTimeout }
                    rows += Row("H2Staging batch INSERT", "queryTimeout on the prepared statement", "${declared}s (0 = none)")
                }
            }
        } finally {
            staging.close()
        }
        report("The staging INSERT path", rows)
    }

    /** Runs [block], returning how long the driver took and what it raised. */
    private fun measure(
        kind: String,
        block: () -> Long,
    ): Row =
        try {
            val ms = block()
            Row(kind, "COMPLETED — the driver did NOT honour the timeout", "${ms}ms")
        } catch (e: SQLException) {
            Row(kind, "raised ${e.sqlState} (${e.javaClass.simpleName})", "${lastElapsedMs}ms")
        }

    private var lastElapsedMs = 0L

    private fun Connection.timed(body: (java.sql.Statement) -> Unit): Long {
        val started = System.nanoTime()
        try {
            createStatement().use { st ->
                st.queryTimeout = BUDGET_SECONDS
                body(st)
            }
        } finally {
            lastElapsedMs = (System.nanoTime() - started) / 1_000_000
        }
        return lastElapsedMs
    }

    private fun report(
        title: String,
        rows: List<Row>,
    ) {
        println("### $title")
        println("| statement kind | driver behaviour | elapsed |")
        println("|---|---|---|")
        rows.forEach { println("| ${it.kind} | ${it.behaviour} | ${it.elapsed} |") }
        println()
    }

    private data class Row(
        val kind: String,
        val behaviour: String,
        val elapsed: String,
    )

    private companion object {
        const val BUDGET_SECONDS = 1

        /** ~9·10⁸ row visits — minutes of work, so a 1 s budget that fires is unmistakable. */
        const val SLOW_SELECT =
            """SELECT COUNT(*) AS n FROM SYSTEM_RANGE(1, 30000) a, SYSTEM_RANGE(1, 30000) b WHERE MOD(a."X" + b."X", 7) = 0"""

        const val H2_VERSION_NOTE = "(the pinned driver — see gradle/libs.versions.toml)"
    }
}
