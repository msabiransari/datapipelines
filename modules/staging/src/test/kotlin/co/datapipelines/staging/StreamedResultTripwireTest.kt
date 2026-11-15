package co.datapipelines.staging

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties

/**
 * #994 decision 3, the tripwire for the temporary guard: plain DuckDB on the pinned driver, streamed results on, no
 * staging code. An engine error raised after the first streamed chunk ENDS the cursor — `next()` returns false and
 * nothing is thrown (probed on 1.5.6.0, 2026-11-15; the report is the store's
 * notes/evidence/994-duckdb-streamed-short-result/upstream-report.md). This asserts today's defect on purpose: when a
 * driver bump turns it red, the driver throws mid-stream and the guard is no longer needed — remove
 * StreamedReadGuard.kt with its callers, the `pipeline.staging.result_incomplete` code, and the temporary refusal of
 * `jdbc_stream_results` (RefusedPropertyKeys, datasources.md §5.6), in that lane. Never delete or loosen it to make a
 * bump green.
 */
class StreamedResultTripwireTest {
    @Test
    fun `an engine error after the first streamed chunk still ends the cursor without an exception`() {
        val props = Properties().apply { setProperty("jdbc_stream_results", "true") }
        val (rows, thrown) = DriverManager.getConnection("jdbc:duckdb:", props).use { connection -> drain(connection) }
        withClue(MESSAGE + " (thrown after $rows rows: ${thrown?.message?.lineSequence()?.first()})") { thrown shouldBe null }
        withClue("non-vacuity: the read ended short of the rows the statement produces") {
            (rows in 1 until FAIL_AT + 1) shouldBe true
        }
    }

    /** Drains [SQL] on [connection]: the rows read, and what the driver threw (null when nothing). */
    private fun drain(connection: Connection): Pair<Long, SQLException?> {
        val rows = LongArray(1)
        return try {
            connection.createStatement().use { statement -> statement.executeQuery(SQL).use { rs -> countInto(rs, rows) } }
            rows[0] to null
        } catch (e: SQLException) {
            rows[0] to e
        }
    }

    /** Counts into [rows] as it goes, so the count survives a throw from `next()`. */
    private fun countInto(
        rs: ResultSet,
        rows: LongArray,
    ) {
        while (rs.next()) rows[0]++
    }

    private companion object {
        const val ROWS = 400_000L
        const val FAIL_AT = 300_000L
        const val SQL = "SELECT CASE WHEN range < $FAIL_AT THEN range ELSE error('boom') END AS v FROM range($ROWS)"
        const val MESSAGE =
            "the pinned DuckDB driver now throws when the engine fails mid-stream: remove #994's temporary guard " +
                "(StreamedReadGuard.kt and its callers, pipeline.staging.result_incomplete, the jdbc_stream_results " +
                "refusal) — see the store's notes/evidence/994-duckdb-streamed-short-result/upstream-report.md"
    }
}
