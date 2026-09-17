package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutionEventRecord
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 149: the execution detail page's operation summary is derived from the DURABLE
 * `node_progress` records — the last sample per node wins, a node with no sample is
 * absent, and an operation whose last sample is not terminal is reported as such (the
 * execution ended around it), never as committed.
 */
class NodeOperationHistoryTest {
    private val executionId = UUID.randomUUID()

    private fun record(
        id: Int,
        type: String,
        json: String,
    ) = ExecutionEventRecord(executionId, id, type, Instant.parse("2026-09-16T10:00:00Z").plusMillis(id * 100L), json)

    @Test
    fun `the last node_progress sample per node becomes that node's operation row`() {
        val rows =
            NodeOperationHistory.from(
                listOf(
                    record(1, "execution_started", """{"execution_id":"$executionId"}"""),
                    record(2, "node_started", """{"node_id":"a"}"""),
                    record(
                        3,
                        "node_progress",
                        """{"node_id":"a","sequence":1,"operation":"stage","destination":{"kind":"tempdb","table":"t"},
                           "state":"writing","elapsed_ms":300,"rows_fetched":100,"rows_written":50,"batches_written":1,
                           "timings_ms":{"executing":40,"fetching":200,"writing":60}}""",
                    ),
                    record(
                        4,
                        "node_progress",
                        """{"node_id":"a","sequence":2,"operation":"stage","destination":{"kind":"tempdb","table":"t"},
                           "state":"completed","elapsed_ms":900,"rows_fetched":350,"rows_written":350,
                           "batches_written":4,"committed":true,
                           "timings_ms":{"executing":40,"fetching":500,"waiting_output":5,"writing":300,"finalizing":9}}""",
                    ),
                    record(5, "node_completed", """{"node_id":"a","rows_out":350}"""),
                    record(
                        6,
                        "node_progress",
                        """{"node_id":"b","sequence":1,"operation":"writeback",
                           "destination":{"kind":"datasource","datasource":"pg","table":"out"},
                           "state":"writing","elapsed_ms":100,"rows_written":10,"batches_written":1,"timings_ms":{"writing":90}}""",
                    ),
                    record(7, "execution_aborted", """{"reason":"cancelled"}"""),
                ),
            )
        rows.map { it.nodeId } shouldBe listOf("a", "b")
        val a = rows[0]
        a.operation shouldBe "stage"
        a.destination shouldBe "tempdb.t"
        a.state shouldBe "completed"
        a.rowsFetched shouldBe 350
        a.rowsWritten shouldBe 350
        a.elapsedMs shouldBe 900
        a.committed shouldBe true
        a.terminal shouldBe true
        a.timings shouldBe "query 40 ms · fetch 500 ms · wait 5 ms · write 300 ms · finalize 9 ms"
        val b = rows[1]
        b.destination shouldBe "pg.out"
        b.state shouldBe "writing"
        b.terminal shouldBe false
        b.committed.shouldBeNull()
        b.rowsFetched.shouldBeNull()
        b.rowsWritten shouldBe 10
    }

    @Test
    fun `no node_progress records means no rows — the page says so rather than inventing`() {
        NodeOperationHistory
            .from(
                listOf(record(1, "execution_started", "{}"), record(2, "node_completed", """{"node_id":"a"}""")),
            ).shouldBeEmpty()
    }

    @Test
    fun `an unreadable payload is skipped, not fatal`() {
        val rows =
            NodeOperationHistory.from(
                listOf(
                    record(1, "node_progress", "not json"),
                    record(
                        2,
                        "node_progress",
                        """{"node_id":"x","sequence":1,"operation":"ctas","destination":{"kind":"none"},"state":"executing"}""",
                    ),
                ),
            )
        rows.map { it.nodeId } shouldBe listOf("x")
        rows[0].destination shouldBe "no output"
        rows[0].timings.shouldBeNull()
    }
}
