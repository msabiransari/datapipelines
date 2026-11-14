package co.datapipelines.lake.maintenance.manifests

import ch.qos.logback.classic.Logger
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceRunHooks
import co.datapipelines.lake.maintenance.MaintenanceRunRow
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.MarkerPart
import co.datapipelines.lake.maintenance.PartResult
import co.datapipelines.lake.maintenance.manifests.ManifestRewriteHarness.Companion.DAYS
import co.datapipelines.lake.maintenance.plan.ProfileMaintenanceGate
import co.datapipelines.lake.profiles.LakePauseKind
import co.datapipelines.logcapture.capturingLogEvents
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * **W5–W7 of #963** (brief D1, D2, D5): the part in a run — second in the order, held by `pause.compaction_until` and
 * never by the window (959's gate, asked by 957's worker), `busy` and `deadline` as 957 words them, and never for an
 * `archived` table or a table with no snapshot.
 */
class ManifestRewriteRunIntegrationTest {
    private val h = ManifestRewriteHarness()
    private val gate = ProfileMaintenanceGate(h.m.named)

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    /** A table due for a fold: 12 small manifests over three days, threshold 10. */
    private fun dueTable(name: String = "orders"): MaintenanceTable {
        val table = h.table(name)
        h.threshold(table, THRESHOLD)
        h.manifests(table, count = 12, perManifest = 3, days = DAYS)
        return table
    }

    /** The real part, tracing when it runs. */
    private fun traced(trace: MutableList<String>): MaintenancePart =
        object : MaintenancePart by h.part {
            override fun run(run: MaintenanceRun): PartResult {
                trace += "run:${h.part.name}"
                return h.part.run(run)
            }
        }

    private fun around(trace: MutableList<String>): List<MaintenancePart> =
        listOf(MarkerPart("compaction", 3, trace = trace), traced(trace), MarkerPart("cleanup", 1, trace = trace))

    /** A window of one hour that starts two hours after the catalog's now: now is outside it. */
    private fun windowExcludingNow(): String {
        val now = LocalTime.ofInstant(h.m.dbNow(), ZoneOffset.UTC)
        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        return "${now.plusHours(2).format(hhmm)}-${now.plusHours(3).format(hhmm)}"
    }

    @Test
    fun `W5 - with cleanup and compaction around it, the part runs second`() {
        val table = dueTable()
        h.window(table, WHOLE_DAY)
        val trace = mutableListOf<String>()

        val row = h.run(table, parts = around(trace), gate = gate)

        trace.filter { it.startsWith("run:") } shouldContainExactly listOf("run:cleanup", "run:manifest_rewrite", "run:compaction")
        h.entry(row)["outcome"].asText() shouldBe "done"
        h.entry(row)["folded"].asBoolean() shouldBe true
    }

    @Test
    fun `W5 - with pause compaction_until set the part is recorded paused until that instant and the cleanup still runs`() {
        val table = dueTable()
        h.window(table, WHOLE_DAY)
        val until = h.m.dbNow().plus(Duration.ofHours(1))
        h.profiles.pauses.set(table.id, LakePauseKind.COMPACTION, until, h.profiles.actor)
        val location = h.currentLocation(table)
        val trace = mutableListOf<String>()

        val row = h.run(table, parts = around(trace), gate = gate)

        val entry = h.entry(row)
        entry["outcome"].asText() shouldBe "paused_until"
        entry["until"].asText() shouldBe until.toString()
        entry["reason"].asText() shouldBe "paused until $until"
        entry["attempted"].asBoolean() shouldBe false
        trace.filter { it.startsWith("run:") } shouldContainExactly listOf("run:cleanup")
        h.m.markers(table) shouldBe setOf("cleanup")
        withClue("only the cleanup's marker commit moved the table; no manifest was rewritten") {
            h.manifestList(table).size shouldBe 12
            h.currentLocation(table) shouldBe h.revisionLocations(table).last()
            (h.currentLocation(table) != location) shouldBe true
        }
    }

    @Test
    fun `W5 - outside the window the part still runs while compaction is held`() {
        val table = dueTable()
        h.window(table, windowExcludingNow())
        val trace = mutableListOf<String>()

        val row = h.run(table, parts = around(trace), gate = gate)

        h.entry(row)["outcome"].asText() shouldBe "done"
        h.entry(row)["folded"].asBoolean() shouldBe true
        h.entry(row, "compaction")["outcome"].asText() shouldBe "outside_window"
        trace.filter { it.startsWith("run:") } shouldContainExactly listOf("run:cleanup", "run:manifest_rewrite")
    }

    @Test
    fun `W6 - a lease held by an append makes the part busy, and nothing is committed`() {
        val table = dueTable()
        val location = h.currentLocation(table)
        val append = LakeTestDb.lease(LeaseKind.TABLE, table.leaseName)
        val row =
            try {
                h.run(table)
            } finally {
                LakeTestDb.leases.release(append.operationId)
            }

        val entry = h.entry(row)
        entry["outcome"].asText() shouldBe "busy"
        entry["attempted"].asBoolean() shouldBe true
        row.state shouldBe "finished"
        h.currentLocation(table) shouldBe location
        h.manifestList(table).size shouldBe 12
    }

    @Test
    fun `W6 - a write started after the run's deadline ends the part deadline, lake maintenance deadline, nothing committed`() {
        val table = dueTable()
        val location = h.currentLocation(table)
        // The lease is held (taken with the run's deadline); the catalog write starts only once the deadline passed.
        val hooks =
            object : MaintenanceRunHooks {
                override fun beforeWrite(run: MaintenanceRun) = h.m.awaitDbPast(run.deadline)
            }
        val runLog = LoggerFactory.getLogger(MaintenanceRun::class.java) as Logger
        lateinit var row: MaintenanceRunRow

        val events = capturingLogEvents(runLog) { row = h.run(table, hooks = hooks, jobTimeout = Duration.ofSeconds(DEADLINE_SECONDS)) }

        h.entry(row)["outcome"].asText() shouldBe "deadline"
        events.map { it.formattedMessage }.single { it.startsWith("event=lake.maintenance.deadline_refused") } shouldContain
            "code=${PipelineErrorCodes.Lake.MAINTENANCE_DEADLINE}"
        h.currentLocation(table) shouldBe location
        h.manifestList(table).size shouldBe 12
    }

    @Test
    fun `W7 - an archived table is never rewritten, and a table with no snapshot is nothing to do`() {
        val archived = h.status(dueTable("archived_orders"), "archived")
        val location = h.currentLocation(archived)
        val empty = h.table("empty_orders")
        h.threshold(empty, 2)

        h.part.declare(archived).expectsDue shouldBe false
        val archivedEntry = h.entry(h.run(archived))
        val emptyEntry = h.entry(h.run(empty))

        archivedEntry["outcome"].asText() shouldBe "nothing_to_do"
        archivedEntry["attempted"].asBoolean() shouldBe false
        h.currentLocation(archived) shouldBe location
        h.manifestList(archived).size shouldBe 12
        emptyEntry["outcome"].asText() shouldBe "nothing_to_do"
        emptyEntry["attempted"].asBoolean() shouldBe false
        h.loaded(empty).currentSnapshot() shouldBe null
    }

    @Test
    fun `W7 - a deprecated table is rewritten like an active one`() {
        val deprecated = h.status(dueTable(), "deprecated")

        h.part.declare(deprecated).expectsDue shouldBe true
        h.entry(h.run(deprecated))["folded"].asBoolean() shouldBe true
    }

    private companion object {
        const val THRESHOLD = 10
        const val WHOLE_DAY = "00:00-00:00"
        const val DEADLINE_SECONDS = 5L
    }
}
