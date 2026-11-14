package co.datapipelines.lake.maintenance

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.scheduler.SystemJobOutcome
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.PendingUpdate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * **R13 of #957** (D6, D7; the record's §11a call 34 "One task carries everything due for its table"): the part loop.
 * Parts run in their order; a part the gate holds is recorded with its reason and the others run; none due records
 * `nothing_to_do`; a throwing part ends the run `failed` and no later part starts; an unknown commit outcome ends it
 * `failed` with nothing concluded.
 */
class MaintenanceLoopIntegrationTest {
    private val h = MaintenanceHarness()
    private val json = PipelineJson.objectMapper()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `parts run in their order, each committing through the run`() {
        val table = h.table()
        val trace = mutableListOf<String>()
        val parts = listOf(MarkerPart("compaction", 3, trace = trace), MarkerPart("cleanup", 1, trace = trace), MarkerPart("rewrite", 2, trace = trace))

        h.worker(parts = parts).run(table.id.toString()) shouldBe SystemJobOutcome.Done

        trace.filter { it.startsWith("run:") } shouldContainExactly listOf("run:cleanup", "run:rewrite", "run:compaction")
        h.markers(table) shouldBe setOf("cleanup", "rewrite", "compaction")
        val row = h.rows(table).single()
        row.state shouldBe "finished"
        parts(row).map { it["name"].asText() to it["outcome"].asText() } shouldContainExactly
            listOf("cleanup" to "done", "rewrite" to "done", "compaction" to "done")
        h.audit.events.single().details["state"] shouldBe "finished"
    }

    @Test
    fun `a part the gate holds is recorded with its reason and the others still run`() {
        val table = h.table()
        val held = MarkerPart("rewrite", 2)
        val gate =
            MaintenanceGate { _, part ->
                if (part.name == "rewrite") GateAnswer.Held(PartResult(MaintenanceOutcome.OUTSIDE_WINDOW), "window_closed") else GateAnswer.Admit
            }

        h.worker(parts = listOf(MarkerPart("cleanup", 1), held, MarkerPart("compaction", 3)), gate = gate).run(table.id.toString())

        held.started.get() shouldBe false
        h.markers(table) shouldBe setOf("cleanup", "compaction")
        val rewrite = parts(h.rows(table).single()).single { it["name"].asText() == "rewrite" }
        rewrite["outcome"].asText() shouldBe "outside_window"
        rewrite["reason"].asText() shouldBe "window_closed"
        rewrite["attempted"].asBoolean() shouldBe false
    }

    @Test
    fun `none due records nothing to do and no part starts`() {
        val table = h.table()
        val parts = listOf(MarkerPart("cleanup", 1, due = false), MarkerPart("rewrite", 2, due = false))

        h.worker(parts = parts).run(table.id.toString()) shouldBe SystemJobOutcome.Done

        parts.forEach { it.started.get() shouldBe false }
        val row = h.rows(table).single()
        row.state shouldBe "finished"
        parts(row).map { it["outcome"].asText() } shouldContainExactly listOf("nothing_to_do", "nothing_to_do")
        h.markers(table) shouldBe emptySet()
    }

    @Test
    fun `with no part registered the run is admitted, records no part and finishes`() {
        val table = h.table()

        h.worker(parts = emptyList()).run(table.id.toString()) shouldBe SystemJobOutcome.Done

        val row = h.rows(table).single()
        row.state shouldBe "finished"
        parts(row).shouldBeEmpty()
    }

    @Test
    fun `a throwing part ends the run failed with its code and class only, and no later part starts`() {
        val table = h.table()
        val later = MarkerPart("compaction", 3)
        val throwing =
            MarkerPart("rewrite", 2, commits = false, before = { error("jdbc:postgresql://catalog-host:5432/secret?password=hunter2") })

        h.worker(parts = listOf(MarkerPart("cleanup", 1), throwing, later)).run(table.id.toString()) shouldBe SystemJobOutcome.Done

        later.started.get() shouldBe false
        val row = h.rows(table).single()
        row.state shouldBe "failed"
        row.errorCode shouldBe MaintenanceErrors.UNCLASSIFIED
        row.errorMessage shouldBe "IllegalStateException"
        row.partsJson shouldNotContain "jdbc:"
        parts(row).map { it["name"].asText() to it["outcome"].asText() } shouldContainExactly
            listOf("cleanup" to "done", "rewrite" to "failed")
        parts(row)[1]["error_code"].asText() shouldBe MaintenanceErrors.UNCLASSIFIED
        h.audit.events.single().details["state"] shouldBe "failed"
        h.audit.events.single().details.toString() shouldNotContain "hunter2"
        h.leaseRows() shouldBe 0
    }

    @Test
    fun `a commit whose outcome is unknown ends the run failed and concludes nothing`() {
        val table = h.table()
        val later = MarkerPart("compaction", 3)
        val unknown =
            object : MaintenancePart {
                override val name = "rewrite"
                override val order = 2

                override fun declare(table: MaintenanceTable) = MaintenanceDeclaration(expectsDue = true, scratchBytes = 0)

                override fun dueNow(run: MaintenanceRun) = true

                override fun run(run: MaintenanceRun): PartResult {
                    run.commit { DroppedConnection }
                    error("an unknown outcome is thrown, never answered")
                }
            }

        h.worker(parts = listOf(unknown, later)).run(table.id.toString()) shouldBe SystemJobOutcome.Done

        later.started.get() shouldBe false
        val row = h.rows(table).single()
        row.state shouldBe "failed"
        row.errorCode shouldBe PipelineErrorCodes.Lake.CATALOG_OUTCOME_UNKNOWN
        parts(row).single()["outcome"].asText() shouldBe "failed"
        h.leaseRows() shouldBe 0
    }

    private fun parts(row: MaintenanceRunRow): List<JsonNode> = json.readTree(row.partsJson).toList()

    /** A pending update whose commit loses the connection after it was sent (SQLSTATE class 08). */
    private object DroppedConnection : PendingUpdate<Unit> {
        override fun apply() = Unit

        override fun commit(): Unit = throw IllegalStateException("commit sent", SQLException("connection lost", "08006"))
    }
}
