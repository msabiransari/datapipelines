package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.lake.definitions.LakeDefinitionsHarness
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenancePartLoop
import co.datapipelines.lake.maintenance.MaintenanceScratch
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Companion.record
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Fixture
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/**
 * #969 round 2 C1 (the lander's R1): a positive declaration whose scratch reservation the shared budget refuses is
 * stored `capacity`, `attempted: false`, reason `scratch_refused` — never as no work — and the scratch database is never
 * opened. Through 957's REAL worker over the module's Postgres and MinIO, reading the stored `parts_json` entry back,
 * beside a control whose budget grants, and the controls where the part is honestly not due: no measurement, a stale
 * measurement (another profile pair), and a table no longer due since its measurement (granted or refused alike).
 */
class CompactionScratchRefusalIntegrationTest {
    private val h = CompactionHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `a due table whose reservation is refused is stored capacity - scratch_refused, nothing opened or rewritten`() {
        val table = dueTable("refused")
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        val port = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET)
        val part = h.part(port)
        part.declare(table).let { declared ->
            declared.expectsDue shouldBe true
            declared.scratchBytes shouldBeGreaterThan 0L
        }
        val requests = AtomicInteger()

        val stored = runWith(table, part, refusing(requests))

        withClue("the stored entry: $stored") {
            stored["outcome"].asText() shouldBe MaintenanceOutcome.CAPACITY.wire
            stored["attempted"].asBoolean() shouldBe false
            stored["reason"].asText() shouldBe MaintenancePartLoop.REASON_SCRATCH_REFUSED
        }
        requests.get() shouldBe 1
        port.opened.get() shouldBe 0
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot
        h.lastRun(table).let { row ->
            row.state shouldBe "finished"
            row.scratchReservedBytes shouldBe 0L
        }
    }

    @Test
    fun `control - the same due table with a budget that grants is rewritten through one opened database`() {
        val table = dueTable("granted")
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        val port = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET)
        val part = h.part(port)
        val declared = part.declare(table).scratchBytes

        val stored = h.run(table, listOf(part)).getValue(MaintenancePartNames.COMPACTION)

        withClue("the stored entry: $stored") {
            stored["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire
            stored["attempted"].asBoolean() shouldBe true
        }
        h.reservations.map { it.bytes } shouldBe listOf(declared)
        port.opened.get() shouldBe 1
        (h.load(table).currentSnapshot().snapshotId() == snapshot) shouldBe false
    }

    @Test
    fun `control - no measurement, then a stale one, declares nothing - nothing to do, nothing reserved, nothing opened`() {
        val table = h.table("unmeasured", KEYED)
        h.append(table, unsorted(table), unsorted(table))
        val port = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET)
        val requests = AtomicInteger()

        notDue(runWith(table, h.part(port), refusing(requests)))
        notDue(h.run(table, listOf(h.part(port))).getValue(MaintenancePartNames.COMPACTION))

        h.measure()
        h.part(port).declare(table).expectsDue shouldBe true
        // A profile edit after the measurement: the stored one is another profile pair's (959 D3a), so it declares zero.
        h.profile("maintenance.compact_min_small_files" to "2")
        h.part(port).declare(table).expectsDue shouldBe false
        notDue(runWith(table, h.part(port), refusing(requests)))
        notDue(h.run(table, listOf(h.part(port))).getValue(MaintenancePartNames.COMPACTION))

        requests.get() shouldBe 0
        h.reservations.size shouldBe 0
        port.opened.get() shouldBe 0
    }

    @Test
    fun `control - a table no longer due since its measurement is nothing to do, granted or refused`() {
        val table = dueTable("rewritten")
        h.run(table, listOf(h.part())).getValue(MaintenancePartNames.COMPACTION)["outcome"].asText() shouldBe
            MaintenanceOutcome.DONE.wire
        // Not measured again: the stored measurement still declares, the table read after admission has no candidate.
        val port = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET)
        h.part(port).declare(table).expectsDue shouldBe true
        val requests = AtomicInteger()

        notDue(h.run(table, listOf(h.part(port))).getValue(MaintenancePartNames.COMPACTION))
        notDue(runWith(table, h.part(port), refusing(requests)))

        requests.get() shouldBe 1
        port.opened.get() shouldBe 0
    }

    /** A table with two small unsorted files, measured: compaction declares scratch for it and it is due. */
    private fun dueTable(name: String): MaintenanceTable {
        val table = h.table(name, KEYED)
        h.append(table, unsorted(table), unsorted(table))
        h.measure()
        return table
    }

    /** One run through 957's real worker with [scratch] as its budget; answers the stored compaction entry. */
    private fun runWith(
        table: MaintenanceTable,
        part: CompactionPart,
        scratch: MaintenanceScratch,
    ): JsonNode {
        h.m.worker(parts = listOf(part), scratch = scratch).run(table.id.toString())
        return h.lastParts(table).getValue(MaintenancePartNames.COMPACTION)
    }

    /** A shared budget that refuses every reservation (the delivery path's shape: null), counting the requests. */
    private fun refusing(requests: AtomicInteger) =
        MaintenanceScratch {
            requests.incrementAndGet()
            null
        }

    private fun notDue(stored: JsonNode) =
        withClue("the stored entry: $stored") {
            stored["outcome"].asText() shouldBe MaintenanceOutcome.NOTHING_TO_DO.wire
            stored["attempted"].asBoolean() shouldBe false
            stored.hasNonNull("reason") shouldBe false
        }

    private fun unsorted(table: MaintenanceTable): Fixture {
        val schema = h.load(table).schema()
        val random = Random(h.files(table).size.toLong() * 31 + table.name.hashCode())
        return Fixture(List(ROWS) { record(schema, "u${random.nextInt()}", CompactionHarness.noise(random, PAD)) }, sorted = false)
    }

    private companion object {
        const val ROWS = 300
        const val PAD = 1024
        val KEYED: String = body(listOf(field(1, "k", "STRING"), field(2, "pad", "BINARY")))
    }
}
