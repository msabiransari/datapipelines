package co.datapipelines.executor.measure

import co.datapipelines.executor.BlockingDriver
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorHarness
import co.datapipelines.executor.FakeDatasourceRegistry
import co.datapipelines.executor.Fixtures
import co.datapipelines.executor.PipelineExecutionFailed
import co.datapipelines.executor.h2Datasource
import co.datapipelines.pipeline.NodeType
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
                    // POPULATED before the DELETE, and the reason is a row this measurement got
                    // wrong once: the INSERT above is CANCELLED by its own timeout, so a DELETE
                    // that followed it against an empty table returned in 1 ms and was reported as
                    // "the driver did NOT honour the timeout". It had nothing to delete. A row that
                    // measures an empty table is not a measurement of anything.
                    conn.createStatement().use {
                        it.execute("CREATE TABLE m_dml (n BIGINT)")
                        it.execute("""INSERT INTO m_dml SELECT "X" FROM SYSTEM_RANGE(1, 50000)""")
                    }
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

    /**
     * The two columns the driver table cannot answer: **does the T202 conversion fire**, and **how
     * long from the deadline to the node actually failing**.
     *
     * Both are properties of the executor, not of a driver, so they need a real node run rather
     * than a raw JDBC call. Three rows, one per bound, measured through `PipelineExecutor`:
     *
     *  - a statement that blows its own `queryTimeout` — expect `pipeline.node.query_timeout`,
     *    which is T202's conversion firing;
     *  - a node that blows its WALL-CLOCK deadline while its driver drops every cancel — expect
     *    `pipeline.node.timeout`, and an overshoot inside `cancel-grace-seconds`;
     *  - the same with a node-level `settings.timeout_seconds`, to show the override reaches the
     *    same machinery.
     *
     * The overshoot column is the one worth reading: it is the answer to "if a driver ignores us,
     * how late is the node?", and before this round the answer was unbounded.
     */
    @Test
    fun `the executor's own conversions and how late a node is when the driver will not stop`() {
        val rows = mutableListOf<Row>()

        rows += executorRow("statement blows queryTimeout (T202)", queryTimeoutSeconds = 1, nodeTimeoutSeconds = 300, dropCancel = false)
        rows +=
            executorRow(
                "node blows its deadline, driver drops cancel",
                queryTimeoutSeconds = 300,
                nodeTimeoutSeconds = 1,
                dropCancel = true,
            )
        rows +=
            executorRow(
                "same, via node.settings.timeout_seconds",
                queryTimeoutSeconds = 300,
                nodeTimeoutSeconds = 300,
                dropCancel = true,
                nodeOverride = 1,
            )

        println("### The executor's bounds: which code fires, and how late the node is")
        println("| case | code the node reported | elapsed from node start | overshoot past the budget |")
        println("|---|---|---|---|")
        rows.forEach { println("| ${it.kind} | ${it.behaviour} | ${it.elapsed} |") }
        println()
        println("`cancel-grace-seconds` is 1s in these runs, so an overshoot at or under ~1s is the")
        println("executor waiting out the grace and then abandoning the statement — by design.")
        println()
    }

    @Suppress("LongParameterList")
    private fun executorRow(
        label: String,
        queryTimeoutSeconds: Int,
        nodeTimeoutSeconds: Long,
        dropCancel: Boolean,
        nodeOverride: Int? = null,
    ): Row {
        val budgetMs = (nodeOverride?.toLong() ?: minOf(nodeTimeoutSeconds, queryTimeoutSeconds.toLong())) * 1_000
        val driver = if (dropCancel) BlockingDriver(prologueMs = DROPPED_CANCEL_PROLOGUE_MS) else null
        val source = h2Datasource("m_exec", listOf("CREATE TABLE m_exec (n INT)"))
        val sql = if (dropCancel) "DELETE FROM m_exec" else SLOW_SELECT
        val type = if (dropCancel) NodeType.DML else NodeType.DQL
        return ExecutorHarness(
            templateEngine = Fixtures.templateEngine(mapOf("n" to sql)),
            registry = FakeDatasourceRegistry(mapOf("m_exec" to source), blockingDriver = driver),
            config =
                ExecutorConfig(
                    nodeQueryTimeoutSeconds = queryTimeoutSeconds,
                    nodeTimeoutSeconds = nodeTimeoutSeconds,
                    cancelGraceSeconds = 1,
                    executionTimeoutSeconds = 300,
                    cancelPollIntervalSeconds = 300,
                ),
        ).use { h ->
            val node = Fixtures.node("n", type = type, source = "m_exec", timeoutSeconds = nodeOverride)
            var code = "(no failure)"
            val elapsed =
                kotlin.system.measureTimeMillis {
                    runBlocking {
                        runCatching { h.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(node)))) }
                            .onFailure { code = (it as? PipelineExecutionFailed)?.errorCode ?: it::class.simpleName.orEmpty() }
                    }
                }
            Row(label, "`$code`", "${elapsed}ms | ${elapsed - budgetMs}ms")
        }
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

        /** Longer than any budget below, so every cancel lands in the prologue and is dropped. */
        const val DROPPED_CANCEL_PROLOGUE_MS = 8_000L

        const val H2_VERSION_NOTE = "(the pinned driver — see gradle/libs.versions.toml)"
    }
}
