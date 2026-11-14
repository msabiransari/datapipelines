package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.partition
import co.datapipelines.lake.definitions.LakeDefinitionsHarness
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceRunHooks
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.MarkerPart
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Companion.MIB
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Companion.multiset
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Companion.record
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Fixture
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames
import co.datapipelines.lake.maintenance.plan.ProfileMaintenanceGate
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.iceberg.Schema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Random

/**
 * #969 C, the part in a run over Postgres and MinIO: C11 (order and holds), C12 (capacity by the cap, real), C14a (left
 * for a fresh run, the mixed run, G5's smallest-merge cases stored), C14 (busy, a deadline while rows stream) and C15
 * (registered means due). Through 957's worker with 959's measurement before every run; the scratch port is the lake
 * tests' DuckDB ([DuckDbTestScratch]). Sizes in the time cases are the brief's ÷ [SCALE] (the profile minimum is a
 * 16 MiB target), with the rate and the job timeout set so a whole run and the time left are the brief's ÷ [SCALE] too.
 */
class CompactionRunIntegrationTest {
    private val h = CompactionHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `C11 - the part runs third, is held outside the window and by its pause while the others run`() {
        h.profile(WHOLE_DAY)
        val table = h.table("c11", KEYED)
        h.append(table, unsorted(table, SMALL_ROWS), unsorted(table, SMALL_ROWS))
        h.measure()
        val gate = ProfileMaintenanceGate(h.m.named)
        val parts = { listOf(h.part(), MarkerPart("cleanup", 1), MarkerPart("manifest_rewrite", 2)) }

        val inside = h.run(table, parts(), gate = gate)
        h.lastRun(table).partsJson.let { json -> NAMES.findAll(json).map { it.groupValues[1] }.toList() } shouldContainExactly
            listOf("cleanup", "manifest_rewrite", "compaction")
        inside.getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire

        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        val start = LocalTime.ofInstant(h.m.dbNow(), ZoneOffset.UTC).plusHours(2)
        h.profile("maintenance.window" to "\"${start.format(hhmm)}-${start.plusHours(1).format(hhmm)}\"")
        h.append(table, unsorted(table, SMALL_ROWS))
        h.measure()
        val outside = h.run(table, parts(), gate = gate)
        outside.getValue("compaction")["outcome"].asText() shouldBe "outside_window"
        outside.getValue("cleanup")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire
        outside.getValue("manifest_rewrite")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire

        h.profile(WHOLE_DAY)
        val until =
            h.m
                .dbNow()
                .plus(Duration.ofHours(1))
                .truncatedTo(ChronoUnit.SECONDS)
        LakeTestDb.jdbc.update(
            "UPDATE lake_tables_managed SET policy_json = " +
                "jsonb_build_object('pause', jsonb_build_object('compaction_until', ?)) WHERE id = ?",
            until.toString(),
            table.id,
        )
        h.measure()
        val paused = h.run(table, parts(), gate = gate)
        paused.getValue("compaction")["outcome"].asText() shouldBe "paused_until"
        paused.getValue("compaction")["reason"].asText() shouldBe "paused until $until"
        paused.getValue("cleanup")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire
    }

    @Test
    fun `C12 - a 64 MiB unsorted file over a 32 MiB cap is never split, blocked by cap beside a group, rewritten once raised`() {
        h.profile(TARGET_16, "maintenance.max_bytes_rewritten_per_run" to "${32 * MIB}")
        val table = h.table("c12", KEYED)
        val big = h.append(table, unsorted(table, ROWS_PER_MIB * 64)).single()
        h.measure()
        // Alone (record M4 (a)): left out of the candidate bytes, so the part declares nothing and is not due.
        h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.NOTHING_TO_DO.wire

        h.append(table, unsorted(table, SMALL_ROWS))
        h.measure()
        val beside = h.run(table, listOf(h.part())).getValue("compaction")
        beside["outcome"].asText() shouldBe MaintenanceOutcome.CAPACITY.wire
        beside["blocked"].asInt() shouldBe 1
        beside["largest_need_bytes"].asLong() shouldBe big.fileSizeInBytes()
        beside["limit_bytes"].asLong() shouldBe 32 * MIB
        beside["limit_source"].asText() shouldBe LimitSource.CAP.wire
        beside["committed"].asBoolean() shouldBe true
        beside["left_for_fresh_run"].asLong() shouldBe 0
        h.files(table).map { it.location() } shouldContain big.location()

        val rows = multiset(h.rows(table))
        h.profile(TARGET_16, "maintenance.max_bytes_rewritten_per_run" to "${128 * MIB}")
        h.measure()
        h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire
        h.files(table).map { it.location() } shouldNotContain big.location()
        multiset(h.rows(table)) shouldBe rows
    }

    @Test
    fun `C14a - a file that fits a whole run but not the time left - deadline, nothing started, rewritten by a fresh run`() {
        val table = h.table("c14a", KEYED)
        h.append(table, unsorted(table, SMALL_ROWS))
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        h.measure()
        val port = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET)
        // 1 KiB/s: a whole hour writes 3.6 MiB (the file fits), the minute left writes 60 KiB (it does not).
        val slow = CompactionHarness.SETTINGS.copy(bytesPerSecond = 1024)
        val spent = h.part(port, settings = slow, catalogNow = { h.m.dbNow().plus(Duration.ofMinutes(59)) })

        val stored = h.run(table, listOf(spent)).getValue("compaction")

        stored["outcome"].asText() shouldBe MaintenanceOutcome.DEADLINE.wire
        stored["committed"].asBoolean() shouldBe false
        stored["left_for_fresh_run"].asLong() shouldBe 1
        port.opened.get() shouldBe 0
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot
        h.measure()
        h.run(table, listOf(h.part(settings = slow))).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire
    }

    @Test
    fun `C14a - the mixed run - one blocked by cap, one left for a fresh run, nothing committed (959 N5's input)`() {
        // The brief's 256 MiB file under a 128 MiB cap beside a 64 MiB one, ÷ SCALE: 64 MiB, 32 MiB, 16 MiB.
        h.profile(TARGET_16, "maintenance.max_bytes_rewritten_per_run" to "${32 * MIB}")
        val table = h.table("mixed", KEYED)
        h.append(table, unsorted(table, ROWS_PER_MIB * 64), unsorted(table, ROWS_PER_MIB * 16))
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        h.measure()
        val port = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET)
        // 16 KiB/s: a whole hour writes 56 MiB (above the cap, so the cap is the whole-run limit); the minute left 960 KiB.
        val rate = CompactionHarness.SETTINGS.copy(bytesPerSecond = 16 * KIB)
        val part = h.part(port, settings = rate, catalogNow = { h.m.dbNow().plus(Duration.ofMinutes(59)) })

        val stored = h.run(table, listOf(part)).getValue("compaction")
        println("(mixed) stored compaction result: $stored")

        stored["outcome"].asText() shouldBe MaintenanceOutcome.CAPACITY.wire
        stored["limit_source"].asText() shouldBe LimitSource.CAP.wire
        stored["blocked"].asInt() shouldBe 1
        stored["left_for_fresh_run"].asLong() shouldBe 1
        stored["committed"].asBoolean() shouldBe false
        port.opened.get() shouldBe 0
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot
    }

    @Test
    fun `C14a - G5 (a) (b) (c1) stored - five in-order files, a whole run too short, then the time left too short, then one pair`() {
        h.profile(*FIVE_PROFILE)
        val table = h.table("five", KEYED)
        val files = h.append(table, *fiveInOrder(table))
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        val sizes = files.map { it.fileSizeInBytes() }.sorted()
        h.measure()

        // (a) a whole run writes 16 MiB (64 ÷ SCALE): every file's smallest merge (about 20 MiB) fits no run.
        val portA = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET)
        val a = h.run(table, listOf(h.part(portA, settings = five(16 * KIB))), jobTimeout = FIVE_TIMEOUT).getValue("compaction")
        a["outcome"].asText() shouldBe MaintenanceOutcome.CAPACITY.wire
        a["limit_source"].asText() shouldBe LimitSource.TIME.wire
        a["blocked"].asInt() shouldBe 5
        a["limit_bytes"].asLong() shouldBe 16 * MIB
        a["largest_need_bytes"].asLong() shouldBe sizes.last() + sizes.first()
        a["committed"].asBoolean() shouldBe false
        a["left_for_fresh_run"].asLong() shouldBe 0
        portA.opened.get() shouldBe 0
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot

        // (b) a whole run writes 24 MiB (96 ÷ SCALE) and 16 MiB (64 ÷ SCALE) fit the time left: all five left.
        h.measure()
        val portB = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET)
        val skew = FIVE_TIMEOUT.minusSeconds(16 * MIB / (24 * KIB))
        val b =
            h
                .run(
                    table,
                    listOf(h.part(portB, settings = five(24 * KIB), catalogNow = { h.m.dbNow().plus(skew) })),
                    jobTimeout = FIVE_TIMEOUT,
                ).getValue("compaction")
        b["outcome"].asText() shouldBe MaintenanceOutcome.DEADLINE.wire
        b["left_for_fresh_run"].asLong() shouldBe 5
        b["committed"].asBoolean() shouldBe false
        b["blocked"].asInt() shouldBe 0
        portB.opened.get() shouldBe 0
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot

        // (c1) a fresh run on the controlled clock: the first pair commits, then the clock leaves time for 4 MiB (16 ÷ SCALE).
        h.measure()
        val start = h.clock.instant()
        val c1 =
            h
                .run(
                    table,
                    listOf(h.part(settings = five(24 * KIB))),
                    hooks =
                        setClockAtCommit(
                            start.plus(FIVE_TIMEOUT).minusSeconds(4 * MIB / (24 * KIB)),
                        ),
                    jobTimeout = FIVE_TIMEOUT,
                ).getValue("compaction")
        c1["outcome"].asText() shouldBe MaintenanceOutcome.DEADLINE.wire
        c1["committed"].asBoolean() shouldBe true
        c1["left_for_fresh_run"].asLong() shouldBe 3
        c1["blocked"].asInt() shouldBe 0
        c1["groups"].size() shouldBe 1
        c1["groups"][0]["input_files"].asInt() shouldBe 2
        h.load(table).snapshots().count() shouldBe 2
    }

    @Test
    fun `C14a - G5 (c2) stored - the first pair commits, the second is not started for the cap, and compaction is no longer due`() {
        h.profile(*FIVE_PROFILE)
        val table = h.table("fivec2", KEYED)
        h.append(table, *fiveInOrder(table))
        val rows = multiset(h.rows(table))
        h.measure()

        val c2 = h.run(table, listOf(h.part(settings = five(24 * KIB))), jobTimeout = FIVE_TIMEOUT).getValue("compaction")

        c2["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire
        c2["committed"].asBoolean() shouldBe true
        c2["left_for_fresh_run"].asLong() shouldBe 0
        c2["blocked"].asInt() shouldBe 0
        c2["groups"].size() shouldBe 1
        c2["groups"][0]["input_files"].asInt() shouldBe 2
        h.load(table).snapshots().count() shouldBe 2
        multiset(h.rows(table)) shouldBe rows
        h.measure()
        val figures = h.plan.measurement(table)?.figures
        (figures?.smallFiles ?: Long.MAX_VALUE) shouldBeLessThan 5L
        figures?.unsortedFiles shouldBe 0L
        h.plan
            .decide()
            .getValue(table.id)
            .due shouldNotContain MaintenancePartNames.COMPACTION
    }

    @Test
    fun `C14 - a lease held by an append - busy, nothing committed`() {
        val table = h.table("busy", KEYED)
        h.append(table, unsorted(table, SMALL_ROWS), unsorted(table, SMALL_ROWS))
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        h.measure()
        var held: java.util.UUID? = null
        val port =
            DuckDbTestScratch(LakeDefinitionsHarness.BUCKET, before = {
                held =
                    LakeTestDb.lease(LeaseKind.TABLE, table.leaseName).operationId
            })
        try {
            val stored = h.run(table, listOf(h.part(port))).getValue("compaction")
            stored["outcome"].asText() shouldBe MaintenanceOutcome.BUSY.wire
            held shouldNotBe null
            h.load(table).currentSnapshot().snapshotId() shouldBe snapshot
        } finally {
            held?.let(LakeTestDb.leases::release)
        }
    }

    @Test
    fun `C14 - a deadline passing while rows stream abandons the group, and no group starts after it`() {
        val table = h.table("stream", TWO_REGIONS)
        val schema = h.load(table).schema()
        val random = Random(14)
        h.append(
            table,
            Fixture(List(STREAM_ROWS) { record(schema, "k$it", "eu", CompactionHarness.noise(random, PAD)) }, sorted = false),
            Fixture(List(STREAM_ROWS) { record(schema, "k$it", "us", CompactionHarness.noise(random, PAD)) }, sorted = false),
        )
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        h.measure()
        val port = DuckDbTestScratch(LakeDefinitionsHarness.BUCKET, rows = { rows -> passDeadlineOnFirstRow(rows) })

        val stored = h.run(table, listOf(h.part(port))).getValue("compaction")

        stored["outcome"].asText() shouldBe MaintenanceOutcome.DEADLINE.wire
        stored["committed"].asBoolean() shouldBe false
        stored["left_for_fresh_run"].asLong() shouldBe 2
        port.statements.size shouldBe 1
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot
    }

    @Test
    fun `C15 - with the part registered the plan enqueues rewrite work, and the measurement and the part agree on the candidates`() {
        h.profile(TARGET_16, WHOLE_DAY)
        val table = h.table("c15", KEYED)
        h.append(table, *Array(5) { ordered(table, SMALL_ROWS, "s$it") }, unsorted(table, SMALL_ROWS))

        h.plan.pass(h.plan.planner(registered = setOf(MaintenancePartNames.COMPACTION)))

        h.plan.queue.enqueues shouldContain table.id.toString()
        h.plan
            .decide(registered = setOf(MaintenancePartNames.COMPACTION))
            .getValue(table.id)
            .due shouldContain
            MaintenancePartNames.COMPACTION
        val measured =
            requireNotNull(
                h.plan
                    .measurement(table)
                    ?.figures
                    ?.candidateBytes,
            )
        val part = h.part()
        val row = requireNotNull(CompactionTables(h.m.named, h.m.defs.properties.name).row(table.id))
        val candidates =
            h.m.defs.catalogs.metadataFileIo().use { io ->
                part.candidates(requireNotNull(CompactionTables(h.m.named, h.m.defs.properties.name).pinned(table, io)), row.profile)
            }
        candidates.size shouldBe 6
        candidates.sumOf { it.sizeBytes } shouldBe measured
    }

    private fun unsorted(
        table: MaintenanceTable,
        rows: Int,
    ): Fixture {
        val schema = h.load(table).schema()
        val random = Random(rows.toLong() * 31 + h.files(table).size)
        return Fixture(List(rows) { record(schema, "u${random.nextInt()}", CompactionHarness.noise(random, PAD)) }, sorted = false)
    }

    private fun ordered(
        table: MaintenanceTable,
        rows: Int,
        prefix: String,
    ): Fixture {
        val schema: Schema = h.load(table).schema()
        val random = Random(prefix.hashCode().toLong())
        return Fixture(List(rows) { record(schema, "$prefix-%08d".format(it), CompactionHarness.noise(random, PAD)) }, sorted = true)
    }

    private fun fiveInOrder(table: MaintenanceTable) = Array(5) { ordered(table, ROWS_PER_MIB * 10, "f$it") }

    private fun five(bytesPerSecond: Long) = CompactionHarness.SETTINGS.copy(bytesPerSecond = bytesPerSecond, jobTimeout = FIVE_TIMEOUT)

    /** Moves the part's clock to [at] when its first commit takes the lease: the next plan sees that much time left. */
    private fun setClockAtCommit(at: java.time.Instant) =
        object : MaintenanceRunHooks {
            override fun beforeWrite(run: MaintenanceRun) = h.clock.set(at)
        }

    /** A cursor that moves the part's clock two hours on before handing over its first row. */
    private fun passDeadlineOnFirstRow(rows: java.sql.ResultSet): java.sql.ResultSet =
        java.lang.reflect.Proxy.newProxyInstance(
            java.sql.ResultSet::class.java.classLoader,
            arrayOf(java.sql.ResultSet::class.java),
        ) { _, method, args ->
            if (method.name == "next") h.clock.advance(Duration.ofHours(2))
            try {
                method.invoke(rows, *(args ?: emptyArray()))
            } catch (
                @Suppress("SwallowedException") e: java.lang.reflect.InvocationTargetException, // the cursor's own throw is its cause
            ) {
                throw requireNotNull(e.cause)
            }
        } as java.sql.ResultSet

    private companion object {
        const val SCALE = 4
        const val KIB = 1024L
        const val PAD = 1024
        const val ROWS_PER_MIB = 1024
        const val SMALL_ROWS = 300
        const val STREAM_ROWS = 1500
        val NAMES = Regex("\"name\"\\s*:\\s*\"([a-z_]+)\"")

        /** 1024 s: with 16 KiB/s a whole run writes 16 MiB, with 24 KiB/s 24 MiB (the brief's 64 and 96 MiB ÷ [SCALE]). */
        val FIVE_TIMEOUT: Duration = Duration.ofSeconds(1024)

        /** The built-in window is a few night hours (UTC); the hold tests set their own. */
        val WHOLE_DAY = "maintenance.window" to "\"00:00-00:00\""

        val TARGET_16 = "maintenance.target_file_bytes" to "${16 * MIB}"

        /** G4's profile ÷ [SCALE]: target 16 MiB, ratio 0.75, minimum 5, cap 32 MiB; five 10 MiB files in order. */
        val FIVE_PROFILE =
            arrayOf(
                TARGET_16,
                "maintenance.max_bytes_rewritten_per_run" to "${32 * MIB}",
                "maintenance.compact_min_small_files" to "5",
                "maintenance.compact_small_file_ratio" to "0.75",
            )

        val KEYED: String = body(listOf(field(1, "k", "STRING"), field(2, "pad", "BINARY")))

        val TWO_REGIONS: String =
            body(
                listOf(field(1, "k", "STRING"), field(2, "region", "STRING"), field(3, "pad", "BINARY")),
                partition = listOf(partition(2, "identity")),
            )
    }
}
